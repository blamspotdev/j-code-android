package dev.blamspot.jcode.vdevice

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import dev.blamspot.jcode.OutputKind
import dev.blamspot.jcode.OutputLog
import dev.blamspot.jcode.ext.NativeExtensionLoader
import dev.blamspot.jcode.ext.api.JCodeVirtualDevice
import java.io.File
import dev.blamspot.jcode.ext.api.VirtualDeviceHost
import dev.blamspot.jcode.feature.marketplace.MarketplaceServiceLocator
import dev.blamspot.jcode.feature.marketplace.InstalledExtension
import dev.blamspot.jcode.feature.marketplace.nativeGuestModule

internal const val TAG = "VDEVICE"

/**
 * The app's entire view of the virtual device, and the only thing left in `:app` that knows one
 * exists.
 *
 * The device ships in the Android Dev Pack (see [JCodeVirtualDevice] for why four manifest stubs
 * stay behind). Every call here therefore has two answers: the pack's, or nothing. **Nothing is a
 * supported answer** — a JCode with no Android pack installed is not broken, it is a JCode that
 * cannot run APKs, exactly as one without the Kotlin pack cannot complete Kotlin. So no method here
 * throws for a missing pack and none of them reports one; the surfaces that offer the device are
 * hidden by [isAvailable] instead, which is a better answer than a button that explains itself only
 * after being pressed.
 *
 * **Resolution is lazy and cached.** Loading the pack means a `DexClassLoader` over a multi-megabyte
 * archive, and most sessions never open a device. Nothing here loads it until something actually
 * asks for the device — which is also why [attach] is where the pack empties the device rather than
 * app startup.
 */
internal object VirtualDeviceBridge : VirtualDeviceHost {

    /**
     * Bumped when the pack wants its tab shown. The workbench watches these rather than being called
     * directly: the pack can ask from any thread and from the `:guest` binder's, and a tab may only
     * be opened from the composition.
     */
    val revealDevice = mutableIntStateOf(0)
    val revealHardware = mutableIntStateOf(0)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var snackbarSink: ((String) -> Unit)? = null

    /** Set once the workbench composition exists; before that a message has nowhere to go but the log. */
    fun onSnackbar(sink: ((String) -> Unit)?) {
        snackbarSink = sink
    }

    /** Called once, early, so later calls need no [Context] of their own. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // --- resolution --------------------------------------------------------------------------

    private object Unresolved

    /** [Unresolved] until the first ask; then the pack's device, or null for "no pack installed". */
    @Volatile
    private var resolved: Any? = Unresolved

    @Volatile
    private var packCache: Any? = Unresolved

    /**
     * The installed pack that provides a device, if there is one.
     *
     * Found by what it *declares* rather than by id: `entry.native.guest` names the class the
     * `:guest` stub loads, and a pack with no such class has no device to offer whatever it is
     * called. That keeps "the Android pack owns the device" a fact about the Android pack rather
     * than a string compiled into the IDE.
     *
     * Cached because [isAvailable] is read from the composition — the Run panel hides its device row
     * on it — and the answer costs a directory listing and a YAML parse per installed extension.
     * [evict] is what makes it current again after an install or an uninstall.
     */
    fun pack(): InstalledExtension? {
        (packCache as? InstalledExtension)?.let { return it }
        if (packCache !== Unresolved) return null
        val context = appContext ?: return null
        val found = runCatching {
            MarketplaceServiceLocator.extensionInstaller(context).installed()
                .firstOrNull { it.nativeGuestModule() != null }
        }.getOrNull()
        packCache = found
        return found
    }

    /** True when a device could be opened. What the workbench hides its device surfaces on. */
    val isAvailable: Boolean get() = pack() != null

    /**
     * True while a device has actually been built, rather than merely being on offer.
     *
     * What the Task Manager lists the pack under: a device that has been opened is a `:guest`
     * process and, with the setting on, an adb daemon -- background work somebody may want to see
     * and stop, and which until now appeared there only as unnamed `sh` and `sleep` rows.
     */
    val isRunning: Boolean get() = resolved is JCodeVirtualDevice

    private fun device(): JCodeVirtualDevice? {
        (resolved as? JCodeVirtualDevice)?.let { return it }
        if (resolved !== Unresolved) return null
        synchronized(this) {
            (resolved as? JCodeVirtualDevice)?.let { return it }
            if (resolved !== Unresolved) return null
            val loaded = load()
            resolved = loaded
            return loaded
        }
    }

    private fun load(): JCodeVirtualDevice? {
        val context = appContext ?: return null
        val extension = pack() ?: return null
        val module = extension.nativeGuestModule() ?: return null
        val instance = runCatching { NativeExtensionLoader.resolve(context, extension, module).first }
            .onFailure { Log.w(TAG, "cannot load the virtual device from ${extension.id}", it) }
            .getOrNull()
        val device = instance as? JCodeVirtualDevice
        if (device == null) {
            Log.w(TAG, "${extension.id} declares a guest entry but is not a JCodeVirtualDevice")
            return null
        }
        // The pack's OWN context, not JCode's: the device's built-in apps are assets inside the
        // pack's archive, and JCode's AssetManager cannot see them — it answers an empty list, so the
        // device came up empty and said nothing about why.
        val deviceContext = runCatching { NativeExtensionLoader.assetContext(context, extension, module) }
            .onFailure { Log.w(TAG, "cannot reach the pack's own assets", it) }
            .getOrDefault(context)
        runCatching { device.attach(this, deviceContext) }
            .onFailure { Log.w(TAG, "the virtual device refused to attach", it) }
        return device
    }

    /** Drop the loaded device — on the pack being updated or uninstalled under a running JCode. */
    fun evict() {
        synchronized(this) {
            (resolved as? JCodeVirtualDevice)?.let { runCatching { it.shutdown() } }
            resolved = Unresolved
            packCache = Unresolved
        }
    }

    // --- what the workbench asks of the device -------------------------------------------------

    /**
     * The device's provider for [role], for the manifest stub that declares its authority.
     *
     * Null when no pack is installed, which the stubs answer emptily rather than reporting — see
     * [VirtualStorageProvider].
     */
    fun provider(role: String): android.content.ContentProvider? = device()?.let { device ->
        runCatching { device.provider(role) }
            .onFailure { Log.w(TAG, "the device has no $role provider", it) }
            .getOrNull()
    }

    fun requestOpen(apkPath: String?, activityClass: String? = null, run: Boolean = false) {
        device()?.requestOpen(apkPath, activityClass, run)
    }

    fun requestStop() {
        device()?.requestStop()
    }

    /**
     * Shut the device down, **without loading the pack to do it**.
     *
     * Called when the backend service stops and when the session is swiped away, neither of which is
     * a reason to load a multi-megabyte archive: a device that was never loaded is a device that is
     * already off.
     */
    fun shutdown() {
        (resolved as? JCodeVirtualDevice)?.let { runCatching { it.shutdown() } }
        // Forgotten as well as shut down, so [isRunning] answers for what is there rather than
        // for what was once loaded. The next ask reloads it; that is what `device()` is for.
        resolved = Unresolved
    }

    /**
     * Ask the device to start its own adb daemon, with its socket inside [rootfs].
     *
     * Returns the `adb connect` spec, or null when there is no pack to ask. That is the whole of
     * what the host does here: the daemon, the banner it answers with and everything it serves
     * are the pack's, and with no pack installed there is nothing listening rather than something
     * listening that says it cannot help.
     */
    suspend fun startAdb(rootfs: File): String? = device()?.startAdb(rootfs)

    /** Stop it. Does not load the pack to do it -- a device never loaded has no daemon running. */
    fun stopAdb() {
        (resolved as? JCodeVirtualDevice)?.let { runCatching { it.stopAdb() } }
    }

    // --- VirtualDeviceHost ---------------------------------------------------------------------

    /** What the device tab should read; the pack names it, the workbench applies it on the reveal. */
    @Volatile
    var deviceTabTitle: String? = null
        private set

    override fun openDeviceTab(title: String?) {
        title?.let { deviceTabTitle = it }
        revealDevice.intValue++
    }

    override fun openHardwareTab() {
        revealHardware.intValue++
    }

    override fun snackbar(message: String) {
        snackbarSink?.invoke(message) ?: OutputLog.append(message, OutputKind.Info)
    }

    override fun output(line: String, error: Boolean) {
        OutputLog.append(line, if (error) OutputKind.Error else OutputKind.Stdout)
    }

}
