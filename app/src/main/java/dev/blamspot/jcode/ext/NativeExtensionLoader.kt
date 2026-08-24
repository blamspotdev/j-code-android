package dev.blamspot.jcode.ext

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Resources
import android.util.Log
import dalvik.system.DexClassLoader
import dev.blamspot.jcode.ext.api.JCODE_EXT_ABI
import dev.blamspot.jcode.ext.api.JCodeNativeExtension
import dev.blamspot.jcode.feature.marketplace.InstalledExtension
import dev.blamspot.jcode.vdevice.HiddenApi
import java.io.File
import dev.blamspot.jcode.core.resource.ManagedCache
import dev.blamspot.jcode.core.resource.ResourceManagerLocator
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * Loads an extension's **native** UI — an APK it ships — into JCode's own process, on first use.
 *
 * The technique is [dev.blamspot.jcode.vdevice.GuestLoader]'s: a [DexClassLoader] over the archive and an
 * [AssetManager] with the archive's path added, so the plugin gets both its classes and its
 * resources. One decision is deliberately the **opposite** of the guest loader's, and it is the
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
 * AndroidX**. The hazard `GuestLoader` documents at length — a library present in both loaders, each
 * with its own generated `R`, resolving ids against the wrong resource table — needs the library to
 * be present twice. With Compose and AndroidX resolved parent-first from JCode and nothing shared
 * bundled in the plugin, there is no second copy for an id to come from. The plugin's *own* `R` is
 * unique to its dex and indexes its own table, which is what [addAssetPath] supplies.
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

    /** Drop a plugin — on uninstall or update, so the next open picks up the new archive. */
    fun evict(extensionId: String) {
        cache.remove(extensionId)
    }

    fun evictAll() {
        cache.clear()
    }

    /**
     * The plugin for [extension], loading it if this is the first ask.
     *
     * Throws [LoadFailure] with something worth showing the user; every refusal below is a case
     * where the alternative is a crash inside the IDE's own UI that reads as JCode being broken.
     */
    @Throws(LoadFailure::class)
    fun resolve(host: Context, extension: InstalledExtension): Pair<JCodeNativeExtension, Context> {
        registerForTrimming(host)
        cache[extension.id]?.let { return it.instance to it.context }

        val entry = extension.nativeEntry
            ?: throw LoadFailure("${extension.name} declares no native entry point.")

        // Signed-only, and checked here rather than at install time as well as: an extension can be
        // sideloaded unsigned for development (that is what `dev` means), and that is fine for a web
        // frontend running in a WebView. Code that loads into this process is a different question.
        if (extension.dev) {
            throw LoadFailure(
                "${extension.name} is an unsigned development build. Extensions that ship native " +
                    "code run inside JCode itself, so only officially signed packages are loaded.",
            )
        }

        if (extension.nativeAbi != JCODE_EXT_ABI) {
            throw LoadFailure(
                "${extension.name} was built for JCode extension API ${extension.nativeAbi}; " +
                    "this JCode implements $JCODE_EXT_ABI. Update the extension.",
            )
        }

        val apk = File(extension.dir, entry)
        if (!apk.isFile) throw LoadFailure("${extension.name}'s native entry ($entry) is missing.")

        val loaded = runCatching { load(host, extension, apk) }
            .getOrElse { throw asFailure(extension, it) }
        cache[extension.id] = loaded
        return loaded.instance to loaded.context
    }

    private fun load(host: Context, extension: InstalledExtension, apk: File): Loaded {
        // Per-extension optimised-dex directory, so two plugins never race over one output and an
        // update is not read back out of the previous version's cache.
        val dexDir = File(host.codeCacheDir, "native-ext/${extension.id}").apply { mkdirs() }

        val loader = DexClassLoader(
            apk.absolutePath,
            dexDir.absolutePath,
            File(extension.dir, "lib").takeIf { it.isDirectory }?.absolutePath,
            NativeExtensionLoader::class.java.classLoader,
        )

        val entryClass = extension.nativeClass
            ?: throw LoadFailure("${extension.name} declares no entry class (entry.class).")
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
        val added = HiddenApi.method(AssetManager::class.java, "addAssetPath", String::class.java)
            ?.invoke(assets, apk.absolutePath) as? Int
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
