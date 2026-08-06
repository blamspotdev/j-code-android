package dev.jcode.vdevice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/** What the container could learn about a guest APK without installing or running it. */
data class VirtualDeviceApp(
    val apkPath: String,
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    /** Fully-qualified activity class names, in manifest order. */
    val activities: List<String>,
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
            apkPath = apkPath,
            packageName = info.packageName,
            label = label,
            versionName = info.versionName,
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong(),
            activities = info.activities.orEmpty().map { it.name },
        )
    }

    /**
     * Starts [apkPath] in the guest process and returns the guest's identity.
     *
     * [activityClassName] picks a specific activity; when null the container resolves the
     * MAIN/LAUNCHER one from the APK's manifest, falling back to the first declared activity.
     *
     * Returns as soon as the launch intent is dispatched — the guest process starts asynchronously,
     * and failures past this point surface in logcat under the `VDEVICE` tag.
     */
    fun launch(
        context: Context,
        apkPath: String,
        activityClassName: String? = null,
    ): Result<VirtualDeviceApp> = inspect(context, apkPath).mapCatching { app ->
        if (app.activities.isEmpty()) {
            throw VirtualDeviceException("${app.packageName} declares no activities")
        }
        val intent = Intent(context, GuestBootstrapActivity::class.java)
            .putExtra(GuestRuntime.EXTRA_APK, apkPath)
            .putExtra(GuestRuntime.EXTRA_ACTIVITY, activityClassName)
            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        Log.i(TAG, "launch ${app.packageName} (${activityClassName ?: "launcher"}) from $apkPath")
        app
    }
}

class VirtualDeviceException(message: String, cause: Throwable? = null) : Exception(message, cause)
