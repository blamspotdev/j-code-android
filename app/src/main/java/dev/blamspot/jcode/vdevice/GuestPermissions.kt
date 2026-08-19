package dev.blamspot.jcode.vdevice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/**
 * How a guest's permissions are answered, now that the device has an opinion about them.
 *
 * A guest holds JCode's permissions and no others, which used to make every permission question a
 * question about the *IDE* — and the container answered `checkPermission` with a flat
 * `PERMISSION_GRANTED` so that libraries expecting a straight answer got one. That was fine while
 * there was nothing to decide. It is not fine now that a person can hand one app the microphone and
 * refuse it to the next.
 *
 * So the device answers the way a phone does, out of two settings that are deliberately separate:
 *
 *  - **What the app may do**, per permission it declares in its own manifest: allow, deny, or ask.
 *    Managed per app in Manage permissions.
 *  - **What the device has**, per piece of hardware: off, simulated, or the phone's. Managed once
 *    for the whole device on the hardware bench.
 *
 * They are *both* required. An app cannot be given a camera the device does not have, and a device
 * with a camera does not hand it to an app that was refused one. Everything a phone would answer
 * without asking anybody — a permission the app never declared — is denied, which is the same answer
 * the platform gives and the one every caller is written to handle.
 *
 * ### The request nobody could answer
 *
 * `requestPermissions` was broken outright before any of this. An app that called it built an intent
 * for the permission controller, which went out to the real system, which was being asked to grant a
 * permission to **JCode** — a package that does not declare most of them — and the result came back
 * addressed to an activity token no `ActivityRecord` answers to. So the dialog never appeared, the
 * callback never arrived, and an app that waits for one before doing anything simply stopped there.
 *
 * [consume] takes that launch off the wire. What is already decided is answered from the policy;
 * what is still [PermissionRule.Ask] is put to the person at the keyboard, through the device's own
 * prompt, and their answer is written down so the same question is not asked twice.
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
    private const val EXTRA_RESULTS = "android.content.pm.extra.REQUEST_PERMISSIONS_RESULTS"

    private lateinit var host: Context

    /**
     * Set while this object is deciding, because deciding asks questions of its own.
     *
     * "Is Real available for the microphone" is answered by checking whether **JCode** holds
     * `RECORD_AUDIO` — a `checkSelfPermission` that arrives back here through the very hook that
     * asked it, and would go round for ever. On re-entry the device has no opinion, which sends that
     * inner question to the real system, which is the one that can answer it.
     */
    private val deciding = ThreadLocal.withInitial { false }

    /** Requests waiting on the person at the keyboard, by the id the answer will come back under. */
    private val pending = HashMap<Int, Pending>()
    private val nextRequest = AtomicInteger(1)

    /** How the container reaches the IDE to put a question on the screen; null with no tab bound. */
    @Volatile
    private var prompt: ((Int, Array<String>) -> Unit)? = null

    private class Pending(
        val activity: Activity,
        val packageName: String,
        val requestCode: Int,
        val permissions: Array<String>,
        val results: IntArray,
        /** Which slots the person is being asked about; the rest are already decided. */
        val asked: List<Int>,
    )

    fun install(context: Context) {
        host = context.applicationContext
        disableCaches()
    }

    /** Wires the device's prompt to the tab showing it, or unwires it when the tab goes away. */
    fun setPrompt(prompt: ((Int, Array<String>) -> Unit)?) {
        this.prompt = prompt
        if (prompt == null) synchronized(pending) { pending.clear() }
    }

    /**
     * What this device says about [permission] for the app currently on its screen, or null when it
     * has no opinion and the caller should carry on as it did before.
     */
    fun answer(permission: String): Int? = allowed(permission)?.let {
        if (it) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
    }

    /**
     * The same answer, about a named app rather than whichever one is on the screen.
     *
     * For anything an app can still hold while it is *not* the app on the screen — a location
     * registration belonging to a service that was allowed to keep running. Asking the active-guest
     * question there would answer one app with another app's permissions, which is the wrong answer
     * however the two happen to be set.
     */
    fun answerFor(packageName: String, permission: String): Int? {
        val guest = GuestLoader.forPackage(packageName) ?: return null
        return allowed(guest, permission)?.let {
            if (it) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
    }

    /** Whether the device declares [feature] as hardware it has, or null when it does not govern it. */
    fun feature(feature: String): Boolean? {
        val hardware = VirtualHardware.byFeature(feature) ?: return null
        return mode(hardware)?.let { it != HardwareMode.Off }
    }

    /**
     * Whether the active guest may use [permission] — or null when this is not a question about a
     * guest at all, which is the only case the container stays out of.
     */
    private fun allowed(permission: String): Boolean? {
        val guest = GuestRuntime.activeGuest() ?: return null
        return allowed(guest, permission)
    }

    private fun allowed(guest: LoadedGuest, permission: String): Boolean? {
        if (!::host.isInitialized || deciding.get() == true) return null
        // Undeclared is denied, exactly as the platform would answer: a permission an app did not
        // ask for in its manifest is one it does not have, however the device feels about it.
        if (!guest.requestedPermissions.contains(permission)) return false
        deciding.set(true)
        return try {
            if (VirtualDevicePolicy.rule(host, guest.packageName, permission) != PermissionRule.Allow) {
                false
            } else {
                // Allowed by the app's rule, and still subject to the device having the thing: a
                // camera that is switched off is a camera the device does not have.
                VirtualHardware.byPermission(permission)
                    ?.let { VirtualDevicePolicy.mode(host, it) != HardwareMode.Off }
                    ?: true
            }
        } finally {
            deciding.set(false)
        }
    }

    private fun mode(hardware: VirtualHardware): HardwareMode? {
        if (!::host.isInitialized || deciding.get() == true) return null
        GuestRuntime.activePackage() ?: return null
        deciding.set(true)
        return try {
            VirtualDevicePolicy.mode(host, hardware)
        } finally {
            deciding.set(false)
        }
    }

    /**
     * Answers a guest's `requestPermissions` where it stands, rather than letting it go to a system
     * that would refuse it on JCode's behalf. True when the launch has been dealt with and the
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
        val guest = GuestRuntime.activePackage() ?: return false

        val results = IntArray(permissions.size)
        val asked = ArrayList<Int>()
        permissions.forEachIndexed { index, permission ->
            val undecided = ::host.isInitialized &&
                VirtualDevicePolicy.rule(host, guest, permission) == PermissionRule.Ask &&
                GuestRuntime.activeGuest()?.requestedPermissions?.contains(permission) == true
            results[index] = if (allowed(permission) == true) {
                PackageManager.PERMISSION_GRANTED
            } else {
                PackageManager.PERMISSION_DENIED
            }
            if (undecided) asked += index
        }

        val ask = prompt
        if (asked.isEmpty() || ask == null) {
            if (asked.isNotEmpty()) {
                Log.w(TAG, "no device tab to ask about ${asked.size} permission(s); denying them")
            }
            Log.i(TAG, "answered $guest's request: ${describe(permissions, results)}")
            Handler(Looper.getMainLooper()).post { deliver(activity, requestCode, permissions, results) }
            return true
        }

        val id = nextRequest.getAndIncrement()
        synchronized(pending) {
            pending[id] = Pending(activity, guest, requestCode, permissions, results, asked)
        }
        val question = asked.map { permissions[it] }.toTypedArray()
        Log.i(TAG, "asking about ${question.joinToString()} for $guest")
        runCatching { ask(id, question) }.onFailure {
            Log.w(TAG, "cannot put the question on the screen; denying it", it)
            answered(id, BooleanArray(question.size))
        }
        return true
    }

    /**
     * The person's answer, coming back from the tab.
     *
     * Written down as well as delivered: an app that is refused the camera asks again the next time
     * it is opened, and a device that asked once and remembered is the difference between a policy
     * and a nag.
     */
    fun answered(requestId: Int, granted: BooleanArray) {
        val waiting = synchronized(pending) { pending.remove(requestId) } ?: return
        waiting.asked.forEachIndexed { slot, index ->
            val allow = slot < granted.size && granted[slot]
            if (::host.isInitialized) {
                VirtualDevicePolicy.setRule(
                    host,
                    waiting.packageName,
                    waiting.permissions[index],
                    if (allow) PermissionRule.Allow else PermissionRule.Deny,
                )
            }
            // Re-asked rather than trusted: a granted camera is still no camera if the device has
            // none, and the answer the app is given has to be the one it would get from a check.
            waiting.results[index] = if (allowed(waiting.permissions[index]) == true) {
                PackageManager.PERMISSION_GRANTED
            } else {
                PackageManager.PERMISSION_DENIED
            }
        }
        Log.i(
            TAG,
            "answered ${waiting.packageName}'s request: " +
                describe(waiting.permissions, waiting.results),
        )
        Handler(Looper.getMainLooper()).post {
            deliver(waiting.activity, waiting.requestCode, waiting.permissions, waiting.results)
        }
    }

    private fun describe(permissions: Array<String>, results: IntArray): String =
        permissions.mapIndexed { index, permission ->
            val granted = results.getOrNull(index) == PackageManager.PERMISSION_GRANTED
            "${permission.substringAfterLast('.')}=${if (granted) "granted" else "denied"}"
        }.joinToString()

    /**
     * Hands the answer to the activity, through the door the framework itself uses.
     *
     * `Activity.requestPermissions` sets `mHasCurrentPermissionsRequest` and only the framework's own
     * result dispatch clears it — and while it is set, the *next* request the app makes is cancelled
     * outright with "Can request only one set of permissions at a time". An app that asks for the
     * camera and then the microphone would be answered once and refused thereafter, which is not a
     * failure anybody would think to look for.
     *
     * Every route to clearing it is closed at `targetSdk` 33, measured on Android 13: the field
     * itself, `dispatchRequestPermissionsResult` (which is the method that clears it) and
     * `dispatchActivityResult` (which calls that one) are all **blocked** — absent from `Activity`'s
     * declared members entirely. The real path in is `ActivityThread.sendActivityResult`, which
     * looks the activity up in a record map an embedded activity was never in.
     *
     * So the callback is delivered directly, and the consequence is written down rather than hidden:
     * **one runtime permission request per activity instance.** The first is answered properly; a
     * second is cancelled by the platform, with two empty arrays, before the container ever sees it
     * — which is a documented outcome apps are written to handle, and which reopening the app
     * clears, because the flag belongs to the instance.
     */
    private fun deliver(
        activity: Activity,
        requestCode: Int,
        permissions: Array<String>,
        results: IntArray,
    ) {
        if (dispatch(activity, requestCode, permissions, results)) return
        runCatching { activity.onRequestPermissionsResult(requestCode, permissions, results) }
            .onFailure { Log.w(TAG, "${activity.javaClass.name} threw on its permission result", it) }
    }

    private fun dispatch(
        activity: Activity,
        requestCode: Int,
        permissions: Array<String>,
        results: IntArray,
    ): Boolean {
        val data = Intent()
            .putExtra(EXTRA_NAMES, permissions)
            .putExtra(EXTRA_RESULTS, results)
        // The inner one first: it is the method that clears the flag, and it takes exactly this.
        // Then the outer one, found by name rather than by signature — its trailing `reason`
        // argument was added part-way through the platform's history and both shapes take the same
        // first four.
        val direct = Activity::class.java.declaredMethods
            .firstOrNull { it.name == "dispatchRequestPermissionsResult" }
        val outer = Activity::class.java.declaredMethods
            .firstOrNull { it.name == "dispatchActivityResult" && it.parameterTypes.size >= 4 }
        val method: Method
        val arguments: Array<Any?>
        when {
            direct != null -> {
                method = direct
                arguments = arrayOf(requestCode, data)
            }
            outer != null -> {
                method = outer
                val trailing = arrayOfNulls<Any?>(outer.parameterTypes.size - 4)
                arguments = arrayOf(WHO_PREFIX, requestCode, Activity.RESULT_OK, data, *trailing)
            }
            else -> {
                reportOnce(
                    "the platform's own dispatch is out of reach, so this app gets one request " +
                        "per activity — a second is cancelled before the container sees it",
                )
                return false
            }
        }
        return runCatching {
            method.isAccessible = true
            method.invoke(activity, *arguments)
            reportOnce("results delivered through ${method.name}")
            true
        }.onFailure {
            Log.w(TAG, "cannot dispatch the permission result the framework's way", it)
        }.getOrDefault(false)
    }

    /**
     * Says once which route the results take.
     *
     * Worth a line in the log because the platform decides it and the consequence is invisible:
     * only the framework's own dispatch clears `mHasCurrentPermissionsRequest`, and without it the
     * *second* request an activity makes is cancelled by the platform before the container ever
     * sees it — the app is answered with two empty arrays and nothing anywhere says why.
     */
    private var reported: String? = null

    private fun reportOnce(what: String) {
        if (reported == what) return
        reported = what
        Log.w(TAG, "permission results: $what")
    }

    /**
     * Turns off the platform's process-wide permission caches.
     *
     * `PermissionManager` memoises `checkPermission` behind a `PropertyInvalidatedCache` whose nonce
     * only the system bumps — so the first answer this device gave for a permission would be the
     * answer it kept giving, and revoking the camera would not be visible to the app until something
     * outside JCode invalidated the cache. Both caches are off by request here, in `:guest` only,
     * where the whole process exists to run one guest at a time.
     */
    private fun disableCaches() {
        val manager = HiddenApi.classOrNull("android.permission.PermissionManager") ?: return
        listOf("disablePermissionCache", "disablePackageNamePermissionCache").forEach { name ->
            HiddenApi.method(manager, name)?.let { runCatching { it.invoke(null) } }
        }
    }
}
