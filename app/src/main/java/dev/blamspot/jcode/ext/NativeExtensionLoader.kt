package dev.blamspot.jcode.ext

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Resources
import android.util.Log
import dalvik.system.DexClassLoader
import dev.blamspot.jcode.ext.api.JCODE_EXT_ABI
import dev.blamspot.jcode.ext.api.JCodeNativeExtension
import dev.blamspot.jcode.ext.api.JCodeVirtualDeviceGuest
import dev.blamspot.jcode.feature.marketplace.InstalledExtension
import dev.blamspot.jcode.feature.marketplace.NativeModule
import dev.blamspot.jcode.feature.marketplace.nativeGuestModule
import java.io.File
import dev.blamspot.jcode.core.resource.ManagedCache
import dev.blamspot.jcode.core.resource.ResourceManagerLocator
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * Loads an extension's **native** UI — an APK it ships — into JCode's own process, on first use.
 *
 * The technique is the virtual device's guest loader's: a [DexClassLoader] over the archive and an
 * [AssetManager] with the archive's path added, so the plugin gets both its classes and its
 * resources. One decision is deliberately the **opposite** of that loader's, and it is the
 * whole reason this is a separate file:
 *
 * > **The parent is JCode's own class loader, not the boot loader.**
 *
 * A guest app is another app and must not see JCode's libraries; a native extension *is* JCode's UI
 * and must see exactly one Compose runtime — the composition it returns is spliced into JCode's own.
 * Delegating to the boot loader would give it a second copy of Compose and the two would not
 * interoperate.
 *
 * That inversion is safe here only because of a rule the packaging enforces: **a plugin bundles no
 * AndroidX**. The hazard the pack's own guest loader documents at length — a library present in both
 * loaders, each
 * with its own generated `R`, resolving ids against the wrong resource table — needs the library to
 * be present twice. With Compose and AndroidX resolved parent-first from JCode and nothing shared
 * bundled in the plugin, there is no second copy for an id to come from. The plugin's *own* `R` is
 * unique to its dex and indexes its own table, which is what `addAssetPath` supplies.
 *
 * Not a security boundary: a plugin runs with JCode's permissions because it is JCode's process.
 * What keeps that honest is upstream — [resolve] refuses an extension that was not officially
 * signed, so "install an extension" cannot quietly mean "run this code".
 */
internal object NativeExtensionLoader {

    private const val TAG = "NativeExtLoader"

    /** Why a plugin could not be used, in words meant for the Issues pane rather than a log. */
    class LoadFailure(message: String) : Exception(message)

    private class Loaded(val instance: JCodeNativeExtension, val context: Context)

    private val cache = ConcurrentHashMap<String, Loaded>()

    /**
     * Lets the OS reclaim loaded plugins under memory pressure.
     *
     * By far the heaviest thing this app caches: every entry is a whole APK resident in-process —
     * a `DexClassLoader`, an `AssetManager` holding the archive open, and a `Context`. Dropping an
     * entry is safe rather than clever: a page already on screen holds its instance directly, so
     * eviction costs a reload on the *next* open and nothing else. Kept out of the map means the
     * loader and its resources can actually be collected once no page refers to them.
     */
    private object LoadedPlugins : ManagedCache {
        override val name = "NativeExtensionLoader"
        override val size: Int get() = cache.size

        /** Nominal: one entry per installed native extension, so this is a ceiling, not a target. */
        override val maxSize = 8

        override fun trim(ratio: Float) {
            if (ratio <= 0f) return
            val drop = ceil(cache.size * ratio).toInt().coerceAtMost(cache.size)
            if (drop <= 0) return
            cache.keys.take(drop).forEach { cache.remove(it) }
        }

        override fun clear() = cache.clear()
    }

    /**
     * Whether an unsigned, sideloaded extension may load code into JCode's process.
     *
     * A plain field rather than a read of the preference, because [resolve] is called from the
     * composition and a DataStore read is suspending — blocking on one to draw a frame is worse than
     * the staleness of a flag the workbench pushes. `MainViewModel` keeps it current.
     */
    @Volatile
    var allowUnsigned: Boolean = false

    /**
     * Where [allowUnsigned] is written down so the `:guest` process can read it.
     *
     * A Kotlin `object` exists once per PROCESS, and the virtual device's guest half runs in
     * `:guest` — so the flag the workbench pushes into this field in the main process is simply
     * absent over there, and the guest refused every unsigned pack with developer options plainly
     * on. The device opened and then reported "Could not start the guest process."
     *
     * A file rather than the preference itself: DataStore is explicitly single-process, and
     * multi-process SharedPreferences has been unreliable for a decade. Its presence is the whole
     * value, so there is nothing to parse and nothing to corrupt.
     */
    private const val ALLOW_UNSIGNED_MARKER = "allow-unsigned-extensions"

    /** Sets the flag for this process and records it for the others. Main process only. */
    fun setAllowUnsigned(context: Context, allowed: Boolean) {
        allowUnsigned = allowed
        val marker = File(context.filesDir, ALLOW_UNSIGNED_MARKER)
        runCatching { if (allowed) marker.createNewFile() else marker.delete() }
            .onFailure { Log.w(TAG, "could not record the unsigned-extension setting", it) }
    }

    /** Adopts what the main process recorded. For a process that has no workbench to push it. */
    fun adoptAllowUnsigned(context: Context) {
        allowUnsigned = File(context.filesDir, ALLOW_UNSIGNED_MARKER).exists()
    }

    @Volatile
    private var registered = false

    private fun registerForTrimming(context: Context) {
        if (registered) return
        synchronized(this) {
            if (registered) return
            runCatching { ResourceManagerLocator.resourceManager(context).registerCache(LoadedPlugins) }
            registered = true
        }
    }

    /** Drop an extension's plugins — on uninstall or update, so the next open picks up the new archive. */
    fun evict(extensionId: String) {
        cache.keys.removeAll { it.substringBefore('/') == extensionId }
    }

    /** What a module's loaded plugin is filed under. One archive, one entry. */
    private fun key(extension: InstalledExtension, module: NativeModule) = "${extension.id}/${module.id}"

    /**
     * The plugin for one of [extension]'s native modules, loading it if this is the first ask.
     *
     * Per module rather than per extension: a pack ships several archives and the caller has already
     * decided which one answers — loading the others to reach this one is what splitting them was
     * for.
     *
     * Throws [LoadFailure] with something worth showing the user; every refusal below is a case
     * where the alternative is a crash inside the IDE's own UI that reads as JCode being broken.
     */
    @Throws(LoadFailure::class)
    fun resolve(
        host: Context,
        extension: InstalledExtension,
        module: NativeModule,
    ): Pair<JCodeNativeExtension, Context> {
        registerForTrimming(host)
        cache[key(extension, module)]?.let { return it.instance to it.context }

        val entry = module.payload

        // Signed-only, and checked here rather than at install time as well as: an extension can be
        // sideloaded unsigned for development (that is what `dev` means), and that is fine for a web
        // frontend running in a WebView. Code that loads into this process is a different question.
        //
        // [allowUnsigned] is the one way past it, and it is not a convenience: the virtual device now
        // ships in an extension, so without it the device cannot be worked on at all — every test
        // round would need the pack signed first. It is off by default and turned on only by
        // Settings → Developer options, the same switch that already permits unsigned sideloading.
        if (extension.dev && !allowUnsigned) {
            throw LoadFailure(
                "${extension.name} is an unsigned development build. Extensions that ship native " +
                    "code run inside JCode itself, so only officially signed packages are loaded. " +
                    "Turn on Settings → Developer options to load unsigned ones while working on them.",
            )
        }

        // Per module, because each archive is built separately and can be built against a different
        // API than its neighbour in the same pack. Named only when there IS a neighbour: a pack with
        // one module has an id nobody chose -- "Source Control (native-0)" tells a reader nothing
        // and reads like part of the fault.
        if (module.abi != JCODE_EXT_ABI) {
            val which = if (extension.nativeModules.size > 1) "${extension.name} (${module.id})" else extension.name
            throw LoadFailure(
                "$which was built for JCode extension API ${module.abi}; " +
                    "this JCode implements $JCODE_EXT_ABI. Update the extension.",
            )
        }

        val apk = File(extension.dir, entry)
        if (!apk.isFile) throw LoadFailure("${extension.name}'s native payload ($entry) is missing.")

        val loaded = runCatching { load(host, extension, module, apk) }
            .getOrElse { throw asFailure(extension, it) }
        cache[key(extension, module)] = loaded
        return loaded.instance to loaded.context
    }

    /**
     * The pack's `:guest`-side half, for the manifest stub that owns that process.
     *
     * Separate from [resolve] rather than another cache entry, because this runs in a **different
     * process**: `:guest` has its own copy of this object, its own class loader and its own instance
     * of the pack, and the two never see each other. What they share is the technique — a
     * [DexClassLoader] over the same archive, parented on JCode's own loader.
     *
     * Note the parent is JCode's loader here too, not the boot loader. The boot-parented one belongs
     * to the *guest app* the container loads; this is the container itself, which is JCode's code
     * running in a process of JCode's, and it must see exactly one copy of everything JCode ships.
     *
     * No resources are attached: the container draws its status bar and its permission prompt from
     * the pack's own archive, so the caller supplies a context built the same way [resolve] does.
     */
    @Throws(LoadFailure::class)
    fun resolveGuest(host: Context, extension: InstalledExtension): Pair<JCodeVirtualDeviceGuest, Context> {
        // Only the module that declares a guest — which is the whole point of loading per module
        // here: this process wants the container and nothing else the pack happens to ship.
        val module = extension.nativeGuestModule()
            ?: throw LoadFailure("${extension.name} declares no guest entry class (entry.native.guest).")
        val entry = module.payload
        val guestClass = module.guestClass
            ?: throw LoadFailure("${extension.name} declares no guest entry class (entry.native.guest).")
        if (extension.dev && !allowUnsigned) {
            throw LoadFailure("${extension.name} is an unsigned development build.")
        }
        if (module.abi != JCODE_EXT_ABI) {
            val which = if (extension.nativeModules.size > 1) "${extension.name} (${module.id})" else extension.name
            throw LoadFailure(
                "$which was built for JCode extension API ${module.abi}; " +
                    "this JCode implements $JCODE_EXT_ABI. Update the extension.",
            )
        }
        val apk = File(extension.dir, entry)
        if (!apk.isFile) throw LoadFailure("${extension.name}'s native payload ($entry) is missing.")

        return runCatching {
            // A dex output directory of its own: `:guest` and the IDE would otherwise write optimised
            // dex for the same archive to one path from two processes.
            val dexDir = File(host.codeCacheDir, "native-ext-guest/${extension.id}/${module.id}").apply { mkdirs() }
            val loader = DexClassLoader(
                apk.absolutePath,
                dexDir.absolutePath,
                File(extension.dir, "lib").takeIf { it.isDirectory }?.absolutePath,
                NativeExtensionLoader::class.java.classLoader,
            )
            val instance = loader.loadClass(guestClass)
                .getDeclaredConstructor()
                .newInstance() as? JCodeVirtualDeviceGuest
                ?: throw LoadFailure("$guestClass does not implement JCodeVirtualDeviceGuest.")
            instance to pluginContext(host, apk, loader)
        }.getOrElse { throw asFailure(extension, it) }
    }

    /**
     * A [Context] whose assets are [extension]'s own, over the host context given.
     *
     * Not cached, and that is the point: [resolve]'s cache is keyed on the extension and its base
     * context is whichever caller got there first — usually a page, whose context is the Activity.
     * A singleton that held one of those would hold the Activity, so the virtual device asks for its
     * own over the application context instead.
     *
     * The device needs this at all because its six built-in apps ship as assets **inside the pack**.
     * Read through JCode's own AssetManager they are simply not there, and `assets.list` answers an
     * empty array rather than failing — so the device came up with nothing installed and nothing
     * logged.
     */
    @Throws(LoadFailure::class)
    fun assetContext(host: Context, extension: InstalledExtension, module: NativeModule): Context {
        val apk = File(extension.dir, module.payload)
        if (!apk.isFile) throw LoadFailure("${extension.name}'s native payload (${module.payload}) is missing.")
        return pluginContext(host, apk, NativeExtensionLoader::class.java.classLoader!!)
    }

    private fun load(host: Context, extension: InstalledExtension, module: NativeModule, apk: File): Loaded {
        // Per-module optimised-dex directory, so two plugins never race over one output and an
        // update is not read back out of the previous version's cache.
        val dexDir = File(host.codeCacheDir, "native-ext/${extension.id}/${module.id}").apply { mkdirs() }

        val loader = DexClassLoader(
            apk.absolutePath,
            dexDir.absolutePath,
            File(extension.dir, "lib").takeIf { it.isDirectory }?.absolutePath,
            NativeExtensionLoader::class.java.classLoader,
        )

        val entryClass = module.entryClass
        val instance = loader.loadClass(entryClass)
            .getDeclaredConstructor()
            .newInstance() as? JCodeNativeExtension
            ?: throw LoadFailure("$entryClass does not implement JCodeNativeExtension.")

        return Loaded(instance, pluginContext(host, apk, loader))
    }

    /**
     * A [Context] whose resources are the plugin's own and whose class loader is the plugin's.
     *
     * Wrapping JCode's context rather than building one from nothing keeps everything the plugin
     * does not override — files, preferences, system services — pointed at JCode, which is correct:
     * the plugin has no identity of its own on the device.
     */
    private fun pluginContext(host: Context, apk: File, loader: ClassLoader): Context {
        val assets = AssetManager::class.java.getDeclaredConstructor()
            .apply { isAccessible = true }
            .newInstance()
        val added = runCatching {
            AssetManager::class.java
                .getDeclaredMethod("addAssetPath", String::class.java)
                .apply { isAccessible = true }
                .invoke(assets, apk.absolutePath) as? Int
        }.getOrNull()
        // A bare .dex has no resource table by definition, so failing to attach one is the expected
        // outcome rather than a problem to report. For an archive it IS a problem: the plugin still
        // runs, but its own @drawable/@string break as "resource not found" deep inside its UI.
        if ((added == null || added == 0) && !apk.name.endsWith(".dex", ignoreCase = true)) {
            Log.w(TAG, "addAssetPath failed for ${apk.name}; the plugin's own resources will not resolve")
        }
        @Suppress("DEPRECATION")
        val resources = Resources(assets, host.resources.displayMetrics, host.resources.configuration)
        return object : ContextWrapper(host) {
            override fun getAssets(): AssetManager = assets
            override fun getResources(): Resources = resources
            override fun getClassLoader(): ClassLoader = loader
        }
    }

    /**
     * Turn a load failure into something legible.
     *
     * `newInstance` wraps anything the plugin's constructor threw, and a linkage error's own message
     * is a class name with no hint of what to do about it — which is exactly the case that means
     * "this plugin was built against a different JCode".
     */
    private fun asFailure(extension: InstalledExtension, cause: Throwable): LoadFailure {
        val real = (cause as? java.lang.reflect.InvocationTargetException)?.targetException ?: cause
        Log.w(TAG, "cannot load native extension ${extension.id}", real)
        return when (real) {
            is LoadFailure -> real
            is ClassNotFoundException, is NoClassDefFoundError, is NoSuchMethodError, is LinkageError ->
                LoadFailure(
                    "${extension.name} could not be loaded — it was built against a different " +
                        "version of JCode (${real.javaClass.simpleName}: ${real.message}). " +
                        "Updating the extension usually fixes it.",
                )
            else -> LoadFailure("${extension.name} could not be loaded: ${real.message ?: real.javaClass.simpleName}")
        }
    }
}
