package dev.jcode.vdevice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceControlViewHost
import androidx.compose.runtime.mutableStateOf
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** How a guest app can be shown, given what this window supports. */
internal enum class AppSandboxTier {
    /** Composited into the editor tab out of the `:guest` process. */
    Embedded,

    /** No embedding possible; the app can still take over the screen as its own task. */
    FullScreen,
}

internal sealed interface SandboxStatus {
    data object Idle : SandboxStatus
    data object Starting : SandboxStatus

    /** [warning] is set when the guest is up but something about it is degraded. */
    data class Running(val warning: String?) : SandboxStatus
    data class Stopped(val reason: String) : SandboxStatus
    data class Failed(val message: String) : SandboxStatus
}

/**
 * Shared state for J Code's single device-sandbox tab: the run flow only asks for one, the shell
 * opens the tab, and the page reads the APK back out.
 *
 * The device outlives every app that runs on it — its screen is blank rather than absent when
 * nothing is running, which is what `screencap` answers with and what stopping an app returns to.
 */
internal object AppSandbox {

    /** Bumped on each open request so the shell can surface the tab. */
    val revealSignal = mutableStateOf(0)

    // The tab's own state lives here rather than in the composition: the editor pane tears a page
    // down when another tab is selected, and a guest must survive that without being restarted or
    // losing the APK the user typed.

    /** APK the sandbox runs, set by a finished virtual-device build or typed into the page. */
    val apkPath = mutableStateOf("")

    /** Activity in [apkPath] to start; null runs the APK's launcher activity. */
    val activityClass = mutableStateOf<String?>(null)

    /** True while an app should be on the device's screen. False is a live, blank device. */
    val running = mutableStateOf(false)

    /**
     * "Always run in full screen" — the escape hatch for an app the embedded path cannot host.
     *
     * Embedding buys the IDE around the app and a screen an agent can read, but it costs the guest a
     * real window: its activity token is one no `ActivityRecord` answers to, so anything reaching
     * the activity manager about itself is answered by the container rather than the system. Some
     * apps want more than that — a real task, a real token, `PendingIntent`s the system will act on
     * — and for those a full-screen guest is not a downgrade, it is the only thing that works.
     *
     * Kept here rather than read from the DataStore at each call site because both the tab and the
     * adb daemon's `am start` have to agree on it, and the daemon is not a composable.
     */
    val alwaysFullScreen = mutableStateOf(false)

    private val main = Handler(Looper.getMainLooper())

    private var session: AppSandboxSession? = null

    /**
     * Asks the shell for the sandbox tab, optionally switching it to [apkPath] and — when [run] —
     * starting it rather than leaving the tab on its setup screen.
     *
     * Everything here is Compose snapshot state and one of the callers is the adb daemon answering
     * `am start` on an IO dispatcher, so the write is marshalled the way `TerminalSessionHost`
     * marshals its OSC callbacks rather than trusting the calling thread.
     */
    fun requestOpen(apkPath: String?, activityClass: String? = null, run: Boolean = false) {
        val open = Runnable {
            // The activity only means anything next to the APK it belongs to, so the two move
            // together — a bare reveal leaves whatever the tab was already showing alone.
            apkPath?.trim()?.takeIf { it.isNotEmpty() }?.let {
                // Asking for a different app is asking for a different guest; the page only starts
                // one when the session it holds is not already running something.
                if (it != this.apkPath.value || activityClass != this.activityClass.value) {
                    session?.close()
                }
                this.apkPath.value = it
                this.activityClass.value = activityClass
            }
            if (run) running.value = true
            revealSignal.value += 1
        }
        if (Looper.myLooper() == main.looper) open.run() else main.post(open)
    }

    /** One session per process: the container owns a single `:guest` process, so a second tab would
     *  only ever be a second view of the same guest. */
    @Synchronized
    fun session(context: Context): AppSandboxSession =
        session ?: AppSandboxSession(context).also { session = it }

    /** The live session, if the tab has ever opened one. Null is a device with no guest bound. */
    @Synchronized
    fun sessionOrNull(): AppSandboxSession? = session

    /**
     * Takes whatever is running off the device and leaves the screen on — `am force-stop`, and the
     * same place the tab's Stop button lands.
     *
     * Marshalled for the same reason [requestOpen] is: the caller is usually the adb daemon on an IO
     * dispatcher, and [running] is Compose snapshot state.
     */
    fun requestStop() {
        val stop = Runnable {
            session?.close()
            running.value = false
        }
        if (Looper.myLooper() == main.looper) stop.run() else main.post(stop)
    }

    @Synchronized
    fun close() {
        session?.close()
        session = null
        running.value = false
    }

    /** Turns the device off, process and all — see [AppSandboxSession.shutdown]. */
    @Synchronized
    fun shutdown() {
        session?.shutdown()
        session = null
        running.value = false
    }
}

/**
 * The IDE's half of an embedded guest: binds [GuestSessionService], holds the resulting
 * `SurfacePackage`, and forwards input.
 *
 * Unbinding takes the *guest* down, and for a tab switch or a Stop that is the whole teardown. It
 * does **not** take the process: Android keeps an emptied `:guest` around and rebinds into it, so the
 * loaded dex, the swapped `Instrumentation` and the faked `Build` all survive a close. [shutdown] is
 * what ends the process, and closing the tab is the one thing that means it.
 */
internal class AppSandboxSession(context: Context) {

    private val appContext = context.applicationContext
    private val _status = MutableStateFlow<SandboxStatus>(SandboxStatus.Idle)
    val status: StateFlow<SandboxStatus> = _status.asStateFlow()

    /** The package the tab's [android.view.SurfaceView] should adopt. Re-published, not reused, when
     *  the view is recreated: a SurfaceView releases its child package as it detaches. */
    private val _surface = MutableStateFlow<SurfaceControlViewHost.SurfacePackage?>(null)
    val surface: StateFlow<SurfaceControlViewHost.SurfacePackage?> = _surface.asStateFlow()

    // Startup outlives the composition that asked for it. Driving it from a LaunchedEffect instead
    // would abandon a half-started guest — with the service bound and no way back — every time the
    // tab is relaid out, which is exactly what a surface's first size change does.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var startup: Job? = null

    @Volatile
    private var service: IGuestSession? = null
    private var bound = false
    private val connected = MutableStateFlow(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IGuestSession.Stub.asInterface(binder)
            connected.value = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            connected.value = false
            // The process is gone, so it wrote no trace of its own — ask the system why instead.
            VirtualDeviceLog.appendExitReason(appContext)
            _status.value = SandboxStatus.Failed("The guest process stopped unexpectedly.")
        }
    }

    private val callback = object : IGuestSessionCallback.Stub() {
        override fun onGuestFinished(reason: String?) {
            _status.value = SandboxStatus.Stopped(reason ?: "The app closed.")
        }
    }

    /**
     * Starts the guest, or — when it is already running — adapts it to the tab's current size and
     * re-publishes its surface. Safe to call on every layout pass; it does nothing while a start is
     * already in flight.
     */
    fun ensureStarted(
        apkPath: String,
        activityClass: String?,
        width: Int,
        height: Int,
        hostToken: IBinder?,
    ) {
        if (startup?.isActive == true || width <= 0 || height <= 0) return
        if (_status.value is SandboxStatus.Running) {
            resize(width, height)
            startup = scope.launch { _surface.value = readSurface() }
            return
        }
        startup = scope.launch { start(apkPath, activityClass, width, height, hostToken) }
    }

    fun restart(
        apkPath: String,
        activityClass: String?,
        width: Int,
        height: Int,
        hostToken: IBinder?,
    ) {
        close()
        ensureStarted(apkPath, activityClass, width, height, hostToken)
    }

    private suspend fun start(
        apkPath: String,
        activityClass: String?,
        width: Int,
        height: Int,
        hostToken: IBinder?,
    ) {
        _status.value = SandboxStatus.Starting
        val guest = withTimeoutOrNull(BIND_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                if (!bind()) return@withContext null
                connected.first { it }
                service
            }
        } ?: return fail("Could not start the guest process.")

        val result = withContext(Dispatchers.IO) {
            runCatching { guest.start(apkPath, activityClass, width, height, hostToken, callback) }
        }.getOrElse { return fail(it.message ?: "The guest process refused the app.") }

        result.getString(GuestSessionService.KEY_ERROR)?.let { return fail(it) }
        val surface = result.getParcelable(
            GuestSessionService.KEY_SURFACE,
            SurfaceControlViewHost.SurfacePackage::class.java,
        ) ?: return fail("The guest produced no surface.")

        _surface.value = surface
        _status.value = SandboxStatus.Running(
            warning = if (result.getBoolean(GuestSessionService.KEY_FULL_LIFECYCLE, true)) null else
                "Android 13 blocks Activity.mActivityLifecycleCallbacks, so callbacks this app " +
                    "registered on the activity itself were not sent onActivityPostStarted or " +
                    "onActivityPostResumed. Its own Lifecycle — and so Compose — is driven directly.",
        )
    }

    /** Writes the running guest's screen into [png]; false when there is nothing to capture. */
    suspend fun capture(png: File): Boolean {
        if (_status.value !is SandboxStatus.Running) return false
        val guest = service ?: return false
        png.delete()
        val result = withContext(Dispatchers.IO) { runCatching { guest.capture(png.absolutePath) } }
        result.getOrNull()?.getString(GuestSessionService.KEY_ERROR)
            ?.let { Log.w(TAG, "guest screen capture: $it") }
        result.exceptionOrNull()?.let { Log.w(TAG, "guest screen capture failed", it) }
        return png.isFile && png.length() > 0
    }

    /** Writes the running guest's view tree into [xml]; false when there is nothing to dump. */
    suspend fun dump(xml: File): Boolean {
        if (_status.value !is SandboxStatus.Running) return false
        val guest = service ?: return false
        xml.delete()
        val result = withContext(Dispatchers.IO) { runCatching { guest.dump(xml.absolutePath) } }
        result.getOrNull()?.getString(GuestSessionService.KEY_ERROR)
            ?.let { Log.w(TAG, "guest view dump: $it") }
        result.exceptionOrNull()?.let { Log.w(TAG, "guest view dump failed", it) }
        return xml.isFile && xml.length() > 0
    }

    /** True while an app is actually up — what input injection needs before it means anything. */
    val isRunning: Boolean get() = _status.value is SandboxStatus.Running

    private suspend fun readSurface(): SurfaceControlViewHost.SurfacePackage? {
        val guest = service ?: return null
        return withContext(Dispatchers.IO) { runCatching { guest.surface() } }.getOrNull()
            ?.getParcelable(
                GuestSessionService.KEY_SURFACE,
                SurfaceControlViewHost.SurfacePackage::class.java,
            )
    }

    fun resize(width: Int, height: Int) = ignoringDeath { it.resize(width, height) }

    fun touch(event: MotionEvent) = ignoringDeath { it.touch(event) }

    fun key(event: KeyEvent) = ignoringDeath { it.key(event) }

    fun text(text: String) = ignoringDeath { it.text(text) }

    fun back() = ignoringDeath { it.back() }

    fun close() {
        startup?.cancel()
        startup = null
        if (bound) runCatching { appContext.unbindService(connection) }
        bound = false
        service = null
        connected.value = false
        _surface.value = null
        _status.value = SandboxStatus.Idle
    }

    /**
     * Turns the device off, rather than putting its screen away.
     *
     * [close] unbinds, which is what a tab switch or a Stop wants: the guest goes, the device stays,
     * and the launcher is drawn by the IDE without needing `:guest` at all. Closing the *tab* is a
     * different statement — there is no device any more — and unbinding does not make it true, since
     * Android keeps the emptied process and rebinds into it with everything the container had
     * accumulated still in place.
     */
    fun shutdown() {
        runCatching { service?.shutdown() }
        close()
    }

    private fun bind(): Boolean {
        if (bound) return true
        bound = appContext.bindService(
            Intent(appContext, GuestSessionService::class.java),
            connection,
            Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
        )
        if (!bound) Log.e(TAG, "cannot bind ${GuestSessionService::class.java.name}")
        return bound
    }

    private fun fail(message: String) {
        _surface.value = null
        VirtualDeviceLog.append(appContext, 'E', TAG, message)
        _status.value = SandboxStatus.Failed(message)
    }

    private inline fun ignoringDeath(block: (IGuestSession) -> Unit) {
        val guest = service ?: return
        runCatching { block(guest) }.onFailure { Log.w(TAG, "guest call failed", it) }
    }

    private companion object {
        const val BIND_TIMEOUT_MS = 15_000L
    }
}
