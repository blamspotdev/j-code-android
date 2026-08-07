package dev.jcode.vdevice

import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.os.Build
import android.os.Process
import android.util.Log
import dalvik.system.DexClassLoader
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipFile

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
private val NATIVE_LIB = Regex("""lib/([^/]+)/[^/]+\.so""")

/**
 * A guest APK loaded into the current process: its code, its resources, and the private storage tree
 * its [GuestContext] hands out in place of J Code's.
 */
internal class LoadedGuest(
    val apkPath: String,
    val packageName: String,
    val applicationInfo: ApplicationInfo,
    val activities: Map<String, ActivityInfo>,
    val launchActivity: String,
    val classLoader: ClassLoader,
    val resources: Resources,
    val dataDir: File,
) {
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

    fun themeOf(activityClass: String): Int {
        val activity = activities[activityClass]
        return if (activity != null && activity.theme != 0) activity.theme else applicationInfo.theme
    }

    /**
     * Resolved here rather than left to `PackageItemInfo.loadLabel`, which would look a `labelRes` up
     * through the host `PackageManager` under J Code's package name and hand back J Code's label.
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

    private val loaded = HashMap<String, LoadedGuest>()

    @Synchronized
    fun load(context: Context, apkPath: String): LoadedGuest {
        loaded[apkPath]?.let { return it }

        val host = context.applicationContext
        val apk = File(apkPath)
        if (!apk.canRead()) throw VirtualDeviceException("Cannot read APK: $apkPath")

        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA
        val info = host.packageManager.getPackageArchiveInfo(apkPath, flags)
            ?: throw VirtualDeviceException("Not a readable APK: $apkPath")
        val appInfo = info.applicationInfo
            ?: throw VirtualDeviceException("${info.packageName} has no <application>")

        val packageName = info.packageName
        val guestDataDir = File(host.filesDir, "vdevice/$packageName").apply { mkdirs() }
        val nativeLibDir = extractNativeLibraries(apk, File(guestDataDir, "lib"))

        appInfo.sourceDir = apkPath
        appInfo.publicSourceDir = apkPath
        appInfo.dataDir = guestDataDir.absolutePath
        appInfo.deviceProtectedDataDir = guestDataDir.absolutePath
        appInfo.nativeLibraryDir = nativeLibDir?.absolutePath
        // Same uid as the IDE by construction: the guest is code running inside J Code's own app.
        appInfo.uid = Process.myUid()
        appInfo.processName = "${host.packageName}:guest"

        val classLoader = DexClassLoader(
            apkPath,
            File(guestDataDir, "dex").apply { mkdirs() }.absolutePath,
            nativeLibDir?.absolutePath,
            host.classLoader,
        )

        val assets = newAssetManager()
        val cookie = addAssetPath(assets, apkPath)
        val hostResources = host.resources
        @Suppress("DEPRECATION")
        val resources = Resources(assets, hostResources.displayMetrics, hostResources.configuration)

        val activities = info.activities.orEmpty().associateBy { it.name }
        val launchActivity = cookie
            ?.let { findLauncherActivity(assets, it, packageName) }
            ?.takeIf { activities.containsKey(it) }
            ?: activities.keys.firstOrNull()
            ?: throw VirtualDeviceException("$packageName declares no activities")

        val guest = LoadedGuest(
            apkPath = apkPath,
            packageName = packageName,
            applicationInfo = appInfo,
            activities = activities,
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

        loaded[apkPath] = guest
        Log.i(
            TAG,
            "loaded $packageName launch=$launchActivity activities=${activities.size} " +
                "data=$guestDataDir libs=${nativeLibDir ?: "none"}",
        )
        return guest
    }

    /**
     * `AssetManager`'s no-arg constructor is hidden but yields an asset manager that already carries
     * the framework's own assets, so `addAssetPath` on top of it gives the guest working resources
     * without disturbing J Code's.
     */
    private fun newAssetManager(): AssetManager =
        AssetManager::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

    private fun addAssetPath(assets: AssetManager, apkPath: String): Int? {
        val add = HiddenApi.method(AssetManager::class.java, "addAssetPath", String::class.java)
            ?: return null
        return (add.invoke(assets, apkPath) as? Int)?.takeIf { it != 0 }
    }

    /**
     * `getPackageArchiveInfo` returns the activity list but drops intent filters, so MAIN/LAUNCHER has
     * to come from the binary manifest — which the [AssetManager] built above can parse directly.
     */
    private fun findLauncherActivity(assets: AssetManager, cookie: Int, packageName: String): String? =
        runCatching {
            val parser = assets.openXmlResourceParser(cookie, "AndroidManifest.xml")
            try {
                scanForLauncher(parser, packageName)
            } finally {
                parser.close()
            }
        }.getOrElse {
            Log.w(TAG, "cannot parse manifest of $packageName", it)
            null
        }

    private fun scanForLauncher(parser: XmlResourceParser, packageName: String): String? {
        var current: String? = null
        var isMain = false
        var isLauncher = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "activity", "activity-alias" -> {
                    val name = parser.getAttributeValue(ANDROID_NS, "targetActivity")
                        ?: parser.getAttributeValue(ANDROID_NS, "name")
                    current = name?.let { qualify(it, packageName) }
                    isMain = false
                    isLauncher = false
                }

                "action" ->
                    isMain = isMain ||
                        parser.getAttributeValue(ANDROID_NS, "name") == "android.intent.action.MAIN"

                "category" ->
                    isLauncher = isLauncher ||
                        parser.getAttributeValue(ANDROID_NS, "name") == "android.intent.category.LAUNCHER"
            }
            if (isMain && isLauncher) current?.let { return it }
        }
        return null
    }

    private fun qualify(name: String, packageName: String): String = when {
        name.startsWith(".") -> packageName + name
        !name.contains('.') -> "$packageName.$name"
        else -> name
    }

    /**
     * Copies the shared objects under `lib/<abi>/` out of the APK so `System.loadLibrary` can find
     * them. Uncompressed, page-aligned libraries could be mapped straight out of the APK, but only
     * through hidden linker paths; a plain extraction works for every APK however it was packaged.
     */
    private fun extractNativeLibraries(apk: File, target: File): File? = ZipFile(apk).use { zip ->
        val entries = zip.entries().asSequence()
            .mapNotNull { entry ->
                NATIVE_LIB.matchEntire(entry.name)?.groupValues?.get(1)?.let { it to entry }
            }
            .groupBy({ it.first }, { it.second })
        val abi = Build.SUPPORTED_ABIS.firstOrNull { entries.containsKey(it) } ?: return null

        val abiDir = File(target, abi).apply { mkdirs() }
        entries.getValue(abi).forEach { entry ->
            val out = File(abiDir, File(entry.name).name)
            if (out.exists() && out.length() == entry.size) return@forEach
            zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
            out.setExecutable(true, false)
        }
        abiDir
    }
}
