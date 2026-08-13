package dev.jcode.vdevice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * How a guest's runtime permissions are answered, now that the device has an opinion about them.
 *
 * A guest holds J Code's permissions and no others, which used to make every permission question a
 * question about the *IDE* — and the container answered `checkPermission` with a flat
 * `PERMISSION_GRANTED` so that libraries expecting a straight answer got one. That was fine while
 * there was nothing to decide. It is not fine now that the user can hand one app the microphone and
 * refuse it to the next: the answer has to be the one they gave.
 *
 * So for the permissions in [VirtualHardware] the device answers from its own policy, and for
 * everything else nothing changes — the question goes where it always went. A guest asking for
 * `READ_CONTACTS` is still told what the container has always told it; inventing a stricter answer
 * there would break apps for a promise this device does not make.
 *
 * ### The request nobody could answer
 *
 * `requestPermissions` was broken outright before this. An app that called it built an intent for
 * the permission controller, which went out to the real system, which was being asked to grant a
 * permission to **J Code** — a package that does not declare most of them — and the result came back
 * addressed to an activity token no `ActivityRecord` answers to. So the dialog never appeared, the
 * callback never arrived, and an app that waits for one before doing anything simply stopped there.
 *
 * [consume] takes that launch off the wire and answers it from the policy instead. There is no
 * prompt: the user has already said what this app may have, in Manage permissions, and asking them
 * again the moment the app starts would be asking the same question twice.
 */
internal object GuestPermissions {

    /**
     * `Activity.REQUEST_PERMISSIONS_WHO_PREFIX`, `PackageManager.ACTION_REQUEST_PERMISSIONS` and
     * `EXTRA_REQUEST_PERMISSIONS_NAMES` — none of them SDK constants, all of them fixed strings the
     * platform matches on by value, which is why they can be written down rather than reflected at.
     */
    private const val WHO_PREFIX = "@android:requestPermissions:"
    private const val ACTION_REQUEST = "android.content.pm.action.REQUEST_PERMISSIONS"
    private const val EXTRA_NAMES = "android.content.pm.extra.REQUEST_PERMISSIONS_NAMES"

    private lateinit var host: Context

    /**
     * Set while this object is deciding, because deciding asks questions of its own.
     *
     * "Is Real available for the microphone" is answered by checking whether **J Code** holds
     * `RECORD_AUDIO` — a `checkSelfPermission` that arrives back here through the very hook that
     * asked it, and would go round for ever. On re-entry the device has no opinion, which sends that
     * inner question to the real system, which is the one that can answer it.
     */
    private val deciding = ThreadLocal.withInitial { false }

    fun install(context: Context) {
        host = context.applicationContext
        disableCaches()
    }

    /**
     * What this device says about [permission] for the app currently on its screen, or null when it
     * has no opinion and the caller should carry on as it did before.
     */
    fun answer(permission: String): Int? = allowed(permission)?.let {
        if (it) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
    }

    /** Whether the device declares [feature] as hardware it has, or null when it does not govern it. */
    fun feature(feature: String): Boolean? {
        val hardware = VirtualHardware.byFeature(feature) ?: return null
        return mode(hardware)?.let { it != HardwareMode.Off }
    }

    private fun allowed(permission: String): Boolean? {
        val hardware = VirtualHardware.byPermission(permission) ?: return null
        return mode(hardware)?.let { it != HardwareMode.Off }
    }

    private fun mode(hardware: VirtualHardware): HardwareMode? {
        if (!::host.isInitialized || deciding.get() == true) return null
        val guest = GuestRuntime.activePackage() ?: return null
        deciding.set(true)
        return try {
            VirtualDevicePolicy.mode(host, guest, hardware)
        } finally {
            deciding.set(false)
        }
    }

    /**
     * Answers a guest's `requestPermissions` where it stands, rather than letting it go to a system
     * that would refuse it on J Code's behalf. True when the launch has been dealt with and the
     * binder call must not happen.
     *
     * The request code is the one thing here that has to be read positionally. It is the first `int`
     * *after* the `resultWho` string — the `@android:requestPermissions:` marker the platform itself
     * matches on — so it is anchored to a value rather than to an argument index, and a signature
     * that gains a parameter somewhere else does not move it.
     */
    fun consume(args: Array<Any?>): Boolean {
        val intent = args.filterIsInstance<Intent>().firstOrNull() ?: return false
        if (intent.action != ACTION_REQUEST) return false
        val permissions = intent.getStringArrayExtra(EXTRA_NAMES)?.takeIf { it.isNotEmpty() }
            ?: return false
        val marker = args.indexOfFirst { it is String && it.startsWith(WHO_PREFIX) }
        if (marker < 0) return false
        val requestCode = args.drop(marker + 1).filterIsInstance<Int>().firstOrNull() ?: return false
        val activity = GuestRuntime.foregroundActivity() ?: return false

        val results = permissions.map {
            // Anything outside the device's own hardware keeps the answer the container has always
            // given, so a library that asks for something unrelated is no worse off than before.
            if (allowed(it) != false) PackageManager.PERMISSION_GRANTED
            else PackageManager.PERMISSION_DENIED
        }.toIntArray()

        Log.i(
            TAG,
            "answered ${GuestRuntime.activePackage()}'s request for " +
                permissions.zip(results.toTypedArray()).joinToString {
                    "${it.first.substringAfterLast('.')}=${if (it.second == 0) "granted" else "denied"}"
                },
        )
        Handler(Looper.getMainLooper()).post { deliver(activity, requestCode, permissions, results) }
        return true
    }

    private fun deliver(activity: Activity, requestCode: Int, permissions: Array<String>, results: IntArray) {
        runCatching { activity.onRequestPermissionsResult(requestCode, permissions, results) }
            .onFailure { Log.w(TAG, "${activity.javaClass.name} threw on its permission result", it) }
        // `Activity.requestPermissions` sets this and only the framework's own result dispatch
        // clears it — and while it is set, the *next* request the app makes is cancelled outright
        // with "Can request only one set of permissions at a time". An app that asks for the camera
        // and then the microphone would have been answered once and refused thereafter.
        HiddenApi.field(Activity::class.java, "mHasCurrentPermissionsRequest")
            ?.let { field -> runCatching { field.setBoolean(activity, false) } }
    }

    /**
     * Turns off the platform's process-wide permission caches.
     *
     * `PermissionManager` memoises `checkPermission` behind a `PropertyInvalidatedCache` whose nonce
     * only the system bumps — so the first answer this device gave for a permission would be the
     * answer it kept giving, and revoking the camera would not be visible to the app until something
     * outside J Code invalidated the cache. Both caches are off by request here, in `:guest` only,
     * where the whole process exists to run one guest at a time.
     */
    private fun disableCaches() {
        val manager = HiddenApi.classOrNull("android.permission.PermissionManager") ?: return
        listOf("disablePermissionCache", "disablePackageNamePermissionCache").forEach { name ->
            HiddenApi.method(manager, name)?.let { runCatching { it.invoke(null) } }
        }
    }
}
