package dev.jcode.vdevice

import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.ServiceInfo
import android.content.res.AssetManager
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.os.Build
import android.os.Process
import android.util.Log
import dalvik.system.DexClassLoader
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
private val NATIVE_LIB = Regex("""lib/([^/]+)/[^/]+\.so""")

/** The suffix of the directory holding an installed package's split APKs — see [GuestLoader.splitsOf]. */
internal const val SPLITS_SUFFIX = ".splits"

/**
 * The theme the platform falls back to for an app that declares none, chosen by `targetSdkVersion`
 * exactly as the hidden `Resources.selectSystemTheme` does.
 *
 * An app is not obliged to declare a theme, and one that does not is *not* asking for an empty one:
 * `ContextThemeWrapper` runs the declared id through this first, so what it actually gets is the
 * platform default for the SDK it was built against. Skipping that step leaves a guest with a theme
 * carrying no styles at all — `theme={InheritanceMap=[], Themes=[]}` — and the first framework layout
 * that resolves a `?attr/…` against it dies. Measured: RetroArch (`targetSdk` 28, no `android:theme`
 * anywhere in its manifest) failing to inflate `android:layout/screen_title`.
 *
 * The four ids are public SDK constants, so this needs no hidden member of its own.
 */
internal fun selectDefaultTheme(declared: Int, targetSdkVersion: Int): Int = when {
    declared != 0 -> declared
    targetSdkVersion < Build.VERSION_CODES.HONEYCOMB -> android.R.style.Theme
    targetSdkVersion < Build.VERSION_CODES.ICE_CREAM_SANDWICH -> android.R.style.Theme_Holo
    targetSdkVersion < Build.VERSION_CODES.N -> android.R.style.Theme_DeviceDefault
    else -> android.R.style.Theme_DeviceDefault_Light_DarkActionBar
}

/**
 * A guest APK loaded into the current process: its code, its resources, and the private storage tree
 * its [GuestContext] hands out in place of JCode's.
 */
internal class LoadedGuest(
    val apkPath: String,
    val splitPaths: List<String>,
    val packageName: String,
    val packageInfo: PackageInfo,
    val applicationInfo: ApplicationInfo,
    val activities: Map<String, ActivityInfo>,
    val providers: List<ProviderInfo>,
    val services: Map<String, ServiceInfo>,
    val receivers: Map<String, ActivityInfo>,
    /** Declared `<service>` actions by class — `getPackageArchiveInfo` drops every intent filter. */
    val serviceActions: Map<String, Set<String>>,
    /** Declared `<receiver>` actions by class, for the same reason. */
    val receiverActions: Map<String, Set<String>>,
    val launchActivity: String,
    val classLoader: ClassLoader,
    val resources: Resources,
    val dataDir: File,
) {
    /**
     * What the APK's manifest asks for, which is the outer bound on what the device can give it.
     * A permission not in here is one the platform itself would refuse — see [GuestPermissions].
     */
    val requestedPermissions: Set<String> = packageInfo.requestedPermissions?.toSet().orEmpty()

    val filesDir = File(dataDir, "files")
    val cacheDir = File(dataDir, "cache")
    val codeCacheDir = File(dataDir, "code_cache")
    val noBackupDir = File(dataDir, "no_backup")
    val databasesDir = File(dataDir, "databases")
    val sharedPrefsDir = File(dataDir, "shared_prefs")

    /** The guest's own [Application], created on the first activity launch. */
    var application: Application? = null

    /** Base context handed to the guest [Application] and returned as its application context. */
    lateinit var appContext: GuestContext

    /**
     * The `SensorManager` this guest is handed — see [GuestSensors.forGuest]. Held per guest rather
     * than per context so that one app has one set of registrations, however many of its contexts
     * ask for the service.
     */
    var sensors: android.hardware.SensorManager? = null

    /** The guest's declared components, so [GuestComponents] can host them. */
    val components = GuestComponents(this)

    /** Never 0: an undeclared application theme resolves to the platform default for its SDK. */
    val applicationTheme: Int
        get() = selectDefaultTheme(applicationInfo.theme, applicationInfo.targetSdkVersion)

    fun themeOf(activityClass: String): Int =
        activities[activityClass]?.theme?.takeIf { it != 0 } ?: applicationTheme

    /**
     * A theme built directly out of the guest's own resource table, layered the way
     * `ContextThemeWrapper.initializeTheme` layers one: the application's theme is the base an
     * activity's own theme is applied over.
     *
     * A style *id* alone is not enough to theme a guest — see [GuestRuntime.bind]. The id only means
     * anything next to the resource table it was compiled against, and the object is the only way to
     * say which one that is. The base is always applied, because [applicationTheme] resolves an
     * undeclared theme to the platform's default rather than to nothing.
     */
    fun newTheme(activityClass: String): Resources.Theme = resources.newTheme().apply {
        applyStyle(applicationTheme, true)
        activities[activityClass]?.theme?.takeIf { it != 0 }?.let { applyStyle(it, true) }
    }

    /**
     * Resolved here rather than left to `PackageItemInfo.loadLabel`, which would look a `labelRes` up
     * through the host `PackageManager` under JCode's package name and hand back JCode's label.
     */
    fun labelOf(activityClass: String): CharSequence =
        activities[activityClass]?.let { text(it.nonLocalizedLabel, it.labelRes) }
            ?: text(applicationInfo.nonLocalizedLabel, applicationInfo.labelRes)
            ?: packageName

    private fun text(nonLocalized: CharSequence?, res: Int): CharSequence? =
        nonLocalized?.takeIf { it.isNotBlank() }
            ?: res.takeIf { it != 0 }?.let { runCatching { resources.getText(it) }.getOrNull() }
}

/** Loads guest APKs into the guest process. One [LoadedGuest] per APK path, cached for the process. */
internal object GuestLoader {

    /** A loaded guest, plus what the APKs behind it looked like when it was loaded. */
    private class Cached(val guest: LoadedGuest, val fingerprint: List<Long>)

    private val loaded = HashMap<String, Cached>()

    /**
     * Every guest loaded in this process, by package name — what [GuestPackageHook] answers the
     * framework's own `PackageManager` queries out of.
     */
    private val byPackage = HashMap<String, LoadedGuest>()

    @Synchronized
    fun forPackage(packageName: String): LoadedGuest? = byPackage[packageName]

    /**
     * Drops a guest from both caches, so the next launch loads it again from its APK.
     *
     * The class loader and everything it holds are not unloaded — ART has no way to — so this is not
     * reclaiming memory, it is making sure a force-stopped app comes back as a *start* rather than
     * as the heap the user just asked to be rid of.
     */
    @Synchronized
    fun forget(packageName: String) {
        byPackage.remove(packageName) ?: return
        loaded.entries.removeAll { it.value.guest.packageName == packageName }
    }

    /** The declared `<provider>` behind [authority], across every guest loaded in this process. */
    @Synchronized
    fun providerFor(authority: String): ProviderInfo? = byPackage.values
        .firstNotNullOfOrNull { guest ->
            guest.providers.firstOrNull { info ->
                info.authority?.split(';')?.contains(authority) == true
            }
        }

    /**
     * The split APKs installed beside [base], by the convention [VirtualDeviceApps] stages them
     * under: `<package>.apk` next to `<package>.splits/`.
     *
     * Discovering them from the store rather than passing them in is what keeps the split case off
     * the wire — `IGuestSession.start` still takes one path, and every caller that names an APK
     * (a finished build, `am start`, the launcher) keeps working untouched.
     */
    fun splitsOf(base: File): List<File> =
        File(base.parentFile, base.name.removeSuffix(".apk") + SPLITS_SUFFIX)
            .listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".apk") }
            .sortedBy(File::getName)

    @Synchronized
    fun load(context: Context, apkPath: String): LoadedGuest {
        val host = context.applicationContext
        val apk = File(apkPath)
        if (!apk.canRead()) throw VirtualDeviceException("Cannot read APK: $apkPath")
        val splits = splitsOf(apk)
        val allApks = listOf(apk) + splits

        // Keyed on the APKs' identity, not just the base's path. Unbinding the service is *supposed*
        // to take `:guest` with it, but Android keeps an emptied process around and rebinds into it —
        // so a rebuilt APK at the same path was being answered out of this cache, and the device
        // quietly ran the previous build. That is the one thing a device you iterate against must
        // never do. Measured: the pid survived `am force-stop` + `am start`, and the guest that came
        // back was the old code. Splits are fingerprinted too, so replacing one reloads the guest.
        val fingerprint = allApks.flatMap { listOf(it.lastModified(), it.length()) }
        loaded[apkPath]?.let { cached ->
            if (cached.fingerprint == fingerprint) return cached.guest
            Log.i(TAG, "$apkPath changed on disk; reloading it")
        }

        // Signing certificates are asked for because an app is entitled to ask who signed it, and a
        // null answer is not one any of them are written to survive. NewPipe reads
        // `PackageInfoCompat.hasSignatures` in `MainActivity.onCreate` to decide whether it is an
        // official release build, and threw straight out of onCreate on the null. Collecting them
        // costs one pass over the APK signing block per load, and the honest answer — signed, but
        // not by whoever built the original — is what a sideloaded copy would report anyway.
        // Permissions are asked for because the device answers out of what the app *declared*, the
        // way the platform does — see GuestPermissions. Without them every permission a guest holds
        // would read as one it never asked for, which is denied.
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA or
            PackageManager.GET_PROVIDERS or PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or PackageManager.GET_SIGNING_CERTIFICATES or
            PackageManager.GET_PERMISSIONS or
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val info = host.packageManager.getPackageArchiveInfo(apkPath, flags)
            ?: throw VirtualDeviceException("Not a readable APK: $apkPath")
        val appInfo = info.applicationInfo
            ?: throw VirtualDeviceException("${info.packageName} has no <application>")

        val packageName = info.packageName
        val guestDataDir = File(host.filesDir, "vdevice/$packageName").apply { mkdirs() }
        val nativeLibDir = extractNativeLibraries(allApks, File(guestDataDir, "lib"))

        appInfo.sourceDir = apkPath
        appInfo.publicSourceDir = apkPath
        appInfo.splitSourceDirs = splits.map { it.absolutePath }.toTypedArray().takeIf { it.isNotEmpty() }
        appInfo.splitPublicSourceDirs = appInfo.splitSourceDirs
        appInfo.dataDir = guestDataDir.absolutePath
        appInfo.deviceProtectedDataDir = guestDataDir.absolutePath
        appInfo.nativeLibraryDir = nativeLibDir?.absolutePath
        // Same uid as the IDE by construction: the guest is code running inside JCode's own app.
        appInfo.uid = Process.myUid()
        appInfo.processName = "${host.packageName}:guest"

        // The parent is the *boot* class loader, not JCode's, and that is load-bearing.
        //
        // Delegating to JCode's would be parent-first, so every library the IDE also ships —
        // AndroidX, Kotlin, Compose — would be answered out of the IDE's dex instead of the guest's.
        // The classes would run, which is what makes this so quiet, but each library's generated `R`
        // would carry *JCode's* resource ids while the guest's resource table only knows the
        // guest's. That is exactly how an AppCompat guest carrying a perfectly good
        // Theme.AppCompat theme was told to "use a Theme.AppCompat theme (or descendant)":
        // AppCompatDelegate looked up JCode's `windowActionBar` id and the guest's table, quite
        // correctly, had never heard of it.
        //
        // Isolating the parent gives the guest its own copy of everything it ships, which is what a
        // real app process has. Nothing crosses between the two loaders but framework types.
        val classLoader = DexClassLoader(
            allApks.joinToString(File.pathSeparator) { it.absolutePath },
            File(guestDataDir, "dex").apply { mkdirs() }.absolutePath,
            nativeLibDir?.absolutePath,
            Context::class.java.classLoader,
        )

        val assets = newAssetManager()
        // The base first, then every split, so a config split's resources overlay the base's the way
        // the platform stacks them. A density or language split whose path is missing here does not
        // fail loudly; it just resolves to the base entry, which is the hardest kind of gap to see.
        val cookie = addAssetPath(assets, apkPath)
        splits.forEach { addAssetPath(assets, it.absolutePath) }
        val hostResources = host.resources
        @Suppress("DEPRECATION")
        val resources = Resources(assets, hostResources.displayMetrics, hostResources.configuration)

        val activities = info.activities.orEmpty().associateBy { it.name }
        val manifest = cookie?.let { scanManifest(assets, it, packageName) } ?: ManifestScan()
        val launchActivity = manifest.launchActivity
            ?.takeIf { activities.containsKey(it) }
            ?: activities.keys.firstOrNull()
            ?: throw VirtualDeviceException("$packageName declares no activities")

        // Every component the guest declares carries the archive's own ApplicationInfo, which names
        // the APK's original data directory and no native library path. They have to be pointed at
        // the patched one, or a hosted provider or service would build a Context describing an app
        // that is not installed on this device.
        val providers = info.providers.orEmpty().onEach { it.applicationInfo = appInfo }.toList()
        val services = info.services.orEmpty().onEach { it.applicationInfo = appInfo }.associateBy { it.name }
        val receivers = info.receivers.orEmpty().onEach { it.applicationInfo = appInfo }.associateBy { it.name }

        val guest = LoadedGuest(
            apkPath = apkPath,
            splitPaths = splits.map { it.absolutePath },
            packageName = packageName,
            packageInfo = info,
            applicationInfo = appInfo,
            activities = activities,
            providers = providers,
            services = services,
            receivers = receivers,
            serviceActions = manifest.serviceActions,
            receiverActions = manifest.receiverActions,
            launchActivity = launchActivity,
            classLoader = classLoader,
            resources = resources,
            dataDir = guestDataDir,
        )
        guest.appContext = GuestContext(host, guest)
        listOf(
            guest.filesDir, guest.cacheDir, guest.codeCacheDir,
            guest.noBackupDir, guest.databasesDir, guest.sharedPrefsDir,
        ).forEach { it.mkdirs() }

        loaded[apkPath] = Cached(guest, fingerprint)
        byPackage[packageName] = guest
        Log.i(
            TAG,
            "loaded $packageName launch=$launchActivity activities=${activities.size} " +
                "providers=${providers.size} services=${services.size} receivers=${receivers.size} " +
                "splits=${splits.size} data=$guestDataDir libs=${nativeLibDir ?: "none"}",
        )
        VirtualDeviceLog.append(
            host,
            'I',
            TAG,
            "loaded $packageName from $apkPath" +
                if (splits.isEmpty()) "" else " with ${splits.size} split(s)",
        )
        return guest
    }

    /**
     * `AssetManager`'s no-arg constructor is hidden but yields an asset manager that already carries
     * the framework's own assets, so `addAssetPath` on top of it gives the guest working resources
     * without disturbing JCode's.
     */
    private fun newAssetManager(): AssetManager =
        AssetManager::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

    private fun addAssetPath(assets: AssetManager, apkPath: String): Int? {
        val add = HiddenApi.method(AssetManager::class.java, "addAssetPath", String::class.java)
            ?: return null
        return (add.invoke(assets, apkPath) as? Int)?.takeIf { it != 0 }
    }

    /** What one pass over the binary manifest recovers that `getPackageArchiveInfo` does not. */
    private class ManifestScan(
        val launchActivity: String? = null,
        val serviceActions: Map<String, Set<String>> = emptyMap(),
        val receiverActions: Map<String, Set<String>> = emptyMap(),
    )

    /**
     * `getPackageArchiveInfo` returns the component lists but drops every intent filter, so
     * MAIN/LAUNCHER — and the actions a service or receiver answers to — have to come from the
     * binary manifest, which the [AssetManager] built above can parse directly.
     */
    private fun scanManifest(assets: AssetManager, cookie: Int, packageName: String): ManifestScan =
        runCatching {
            val parser = assets.openXmlResourceParser(cookie, "AndroidManifest.xml")
            try {
                scanComponents(parser, packageName)
            } finally {
                parser.close()
            }
        }.getOrElse {
            Log.w(TAG, "cannot parse manifest of $packageName", it)
            ManifestScan()
        }

    private fun scanComponents(parser: XmlResourceParser, packageName: String): ManifestScan {
        val serviceActions = HashMap<String, MutableSet<String>>()
        val receiverActions = HashMap<String, MutableSet<String>>()
        var launchActivity: String? = null

        var tag: String? = null
        var current: String? = null
        var isMain = false
        var isLauncher = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "activity", "activity-alias", "service", "receiver" -> {
                    tag = parser.name
                    val name = parser.getAttributeValue(ANDROID_NS, "targetActivity")
                        ?: parser.getAttributeValue(ANDROID_NS, "name")
                    current = name?.let { qualify(it, packageName) }
                    isMain = false
                    isLauncher = false
                }

                "action" -> {
                    val action = parser.getAttributeValue(ANDROID_NS, "name")
                    val owner = current
                    if (action != null && owner != null) {
                        when (tag) {
                            "service" -> serviceActions.getOrPut(owner) { mutableSetOf() } += action
                            "receiver" -> receiverActions.getOrPut(owner) { mutableSetOf() } += action
                            else -> isMain = isMain || action == "android.intent.action.MAIN"
                        }
                    }
                }

                "category" ->
                    isLauncher = isLauncher ||
                        parser.getAttributeValue(ANDROID_NS, "name") == "android.intent.category.LAUNCHER"
            }
            if (launchActivity == null && isMain && isLauncher) launchActivity = current
        }
        return ManifestScan(launchActivity, serviceActions, receiverActions)
    }

    private fun qualify(name: String, packageName: String): String = when {
        name.startsWith(".") -> packageName + name
        !name.contains('.') -> "$packageName.$name"
        else -> name
    }

    /**
     * Copies the shared objects under `lib/<abi>/` out of the APKs so `System.loadLibrary` can find
     * them. Uncompressed, page-aligned libraries could be mapped straight out of an APK, but only
     * through hidden linker paths; a plain extraction works for every APK however it was packaged.
     *
     * All of [apks] are searched together, because an app bundle keeps no libraries in its base at
     * all — they are the whole content of `split_config.<abi>.apk`, and a base-only search finds an
     * app with no native code rather than an app whose native code is elsewhere.
     */
    private fun extractNativeLibraries(apks: List<File>, target: File): File? {
        val byAbi = HashMap<String, MutableList<Pair<File, ZipEntry>>>()
        apks.forEach { apk ->
            runCatching {
                ZipFile(apk).use { zip ->
                    zip.entries().asSequence().forEach { entry ->
                        val abi = NATIVE_LIB.matchEntire(entry.name)?.groupValues?.get(1) ?: return@forEach
                        byAbi.getOrPut(abi) { mutableListOf() } += apk to entry
                    }
                }
            }.onFailure { Log.w(TAG, "cannot read $apk for native libraries", it) }
        }
        val abi = Build.SUPPORTED_ABIS.firstOrNull { byAbi.containsKey(it) } ?: return null

        val abiDir = File(target, abi).apply { mkdirs() }
        byAbi.getValue(abi).forEach { (apk, entry) ->
            val out = File(abiDir, File(entry.name).name)
            // Same size is not the same file once an APK has been rebuilt, so the extraction is
            // only skipped for a copy that is also newer than the APK it came from.
            if (out.exists() && out.length() == entry.size && out.lastModified() >= apk.lastModified()) {
                return@forEach
            }
            runCatching {
                ZipFile(apk).use { zip ->
                    zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                }
                out.setExecutable(true, false)
            }.onFailure { Log.w(TAG, "cannot extract ${entry.name} from $apk", it) }
        }
        return abiDir
    }
}
