package dev.jcode.vdevice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/** What the container could learn about a guest APK without installing or running it. */
data class VirtualDeviceApp(
    val packageName: String,
    val label: String,
    val versionName: String?,
    /** Fully-qualified activity class names, in manifest order. */
    val activities: List<String>,
    /** The APK this was read out of — where the device's launcher and `am start` run it from. */
    val apkPath: String,
)

/**
 * Runs a built APK inside J Code — no install, no ADB, no root.
 *
 * The guest is loaded into a **separate process of this app** (`:guest`, declared on the stub
 * activities in `AndroidManifest.xml`) so it gets its own ART heap and its own framework hooks and
 * cannot corrupt the IDE. Inside that process the container loads the APK's dex, resources and
 * native libraries by hand and persuades `ActivityThread` to instantiate the guest's activities in
 * place of J Code's stubs, so the *system* still drives attach and the whole activity lifecycle.
 *
 * The guest therefore shares J Code's uid, its permissions and its process — this is a **sandboxed
 * preview, not a security boundary**.
 *
 * [launch] is the full-screen path: a real activity, in its own task, with everything a real window
 * brings. The device-sandbox editor tab is the other one — see [EmbeddedGuest] — and it exists because
 * the container instantiates the guest activity itself and so does not have to ask the system for a
 * window at all. Putting a *system-launched* activity on a display we own stays impossible for a
 * normal app: `ActivityOptions.setLaunchDisplayId` requires the signature|privileged
 * `ACTIVITY_EMBEDDING` permission.
 */
object VirtualDevice {

    /** Reads a guest APK's identity. Public-API only, so this is safe to call from the IDE process. */
    fun inspect(context: Context, apkPath: String): Result<VirtualDeviceApp> = runCatching {
        val pm = context.packageManager
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA
        val info = pm.getPackageArchiveInfo(apkPath, flags)
            ?: throw VirtualDeviceException("Not a readable APK: $apkPath")
        val appInfo = info.applicationInfo
            ?: throw VirtualDeviceException("APK has no <application>: $apkPath")

        // getApplicationLabel() can resolve a @string label out of an *archive* as long as sourceDir
        // points at it, which getPackageArchiveInfo already arranges.
        appInfo.sourceDir = apkPath
        appInfo.publicSourceDir = apkPath
        val label = runCatching { pm.getApplicationLabel(appInfo).toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: info.packageName

        VirtualDeviceApp(
            packageName = info.packageName,
            label = label,
            versionName = info.versionName,
            activities = info.activities.orEmpty().map { it.name },
            apkPath = apkPath,
        )
    }

    /**
     * The APK's own launcher icon, for the device's launcher.
     *
     * Same trick [inspect] uses for the label: an archive's drawables resolve as long as
     * `sourceDir` points back at the APK, so this stays public-API only and safe in the IDE process.
     */
    fun icon(context: Context, apkPath: String): android.graphics.drawable.Drawable? = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(apkPath, 0) ?: return null
        val appInfo = info.applicationInfo ?: return null
        appInfo.sourceDir = apkPath
        appInfo.publicSourceDir = apkPath
        pm.getApplicationIcon(appInfo)
    }.getOrNull()

}

class VirtualDeviceException(message: String, cause: Throwable? = null) : Exception(message, cause)
