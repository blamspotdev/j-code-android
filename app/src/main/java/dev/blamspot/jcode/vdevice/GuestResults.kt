package dev.blamspot.jcode.vdevice

import android.app.Activity
import android.content.Intent
import android.util.Log
import java.lang.reflect.Field

/**
 * How an answer gets back from one screen of the virtual device to the screen that asked for it.
 *
 * `startActivityForResult` is half of a contract, and until now the device only had the half that
 * goes out. An embedded activity could start another one — that has worked since the container grew
 * a back stack — but nothing ever came back, because the platform's return path runs through an
 * `ActivityRecord` and these activities have none. Every result the device produced was therefore
 * produced by the *container*, for the two intents it answered itself.
 *
 * That is what stopped the device having a camera app rather than a camera *screen*. An app asks for
 * a photo by starting somebody else's activity and waiting; if no answer can come back, the only way
 * to answer at all is for the container to impersonate the app that should have. With this, the
 * device's own Camera and Files are ordinary apps: they are started like any other, they call
 * `setResult` like any other, and the requester's `onActivityResult` runs.
 *
 * ### Two reflective steps, and only the second one is delicate
 *
 * **Reading** the finished activity's answer is `mResultCode`/`mResultData` — private fields of
 * `Activity` with no SDK equivalent, since `setResult` has no getter. **Delivering** it is
 * `onActivityResult`, which is `protected` SDK API that no hidden-API policy applies to, and
 * reflection dispatches virtually — so an app's own override runs, and AndroidX's `ComponentActivity`
 * override forwards into `ActivityResultRegistry`, where a `registerForActivityResult` launcher is
 * waiting. Both the old callback and the modern contract are answered by it.
 */
internal object GuestResults {

    private const val TAG = "VDEVICE"
    private const val RESULT_CANCELED = 0

    /**
     * Who is waiting, under what code, and what they asked for.
     *
     * The request is kept because the answer needs it: the device's Files app returns the path it
     * chose, and whether that becomes a document URI or a tree URI depends on which action was sent.
     */
    class Pending(val requester: Activity, val requestCode: Int, val request: Intent?)

    /**
     * Set by the start-activity hook just before the launch, taken by the container when it pushes.
     *
     * A field rather than an argument threaded through `startForGuest` because the launch crosses
     * `GuestRuntime.decide` → `embeddedLauncher` → `EmbeddedGuest.push`, all of which take an intent
     * and nothing else, and every one of them would have to grow a parameter it does not otherwise
     * use. The window between the two calls is one synchronous hop on the same thread.
     */
    @Volatile
    private var expecting: Pending? = null

    /** Started activity → who is waiting for its answer. */
    private val waiting = HashMap<Activity, Pending>()

    private val resultCodeField: Field? by lazy { field("mResultCode") }
    private val resultDataField: Field? by lazy { field("mResultData") }

    /**
     * Notes that the activity about to start owes an answer to [requester].
     *
     * A negative request code is the platform's "not for result" — `startActivity` is
     * `startActivityForResult(intent, -1)` underneath — so it records nothing.
     */
    fun expect(requester: Activity?, requestCode: Int, request: Intent?) {
        expecting = if (requester != null && requestCode >= 0) {
            Pending(requester, requestCode, request)
        } else {
            null
        }
    }

    /** Takes what [expect] noted, if the launch actually became an activity. */
    fun attach(started: Activity) {
        expecting?.let { waiting[started] = it }
        expecting = null
    }

    /** Drops a launch that never became an activity, so the next one cannot inherit it. */
    fun forget() {
        expecting = null
    }

    /**
     * Takes what [expect] noted so a launch that has to finish on another thread can carry it there.
     *
     * The field is cleared by the taking, because between here and [resume] the launch is in flight
     * on the main looper and there is nothing for a second launch to inherit.
     */
    fun pending(): Pending? = expecting.also { expecting = null }

    /** Puts a [pending] back, on the thread that is about to host the launch. */
    fun resume(pending: Pending?) {
        expecting = pending
    }

    /**
     * Tells [pending]'s requester that the launch never happened.
     *
     * A cancelled result rather than silence, for the same reason [harvest] answers a Back with one:
     * an app that called `startActivityForResult` is waiting, and an app that is told nothing waits
     * for ever. Must be called on the main thread, like every other delivery.
     */
    fun cancel(pending: Pending?) {
        val waiting = pending ?: return
        deliver(waiting.requester, waiting.requestCode, RESULT_CANCELED, null)
    }

    /**
     * Delivers [finished]'s answer to whoever was waiting for it, as it is popped off the stack.
     *
     * A screen that finishes without ever calling `setResult` answers `RESULT_CANCELED` with no
     * data, which is exactly what the platform does and exactly what a caller is written to handle —
     * so a Back out of the camera reaches the app as a cancelled capture rather than as silence.
     */
    fun harvest(finished: Activity) {
        val pending = waiting.remove(finished) ?: return
        val code = resultCodeField?.getInt(finished) ?: RESULT_CANCELED
        val data = resultDataField?.get(finished) as? Intent
        deliver(
            pending.requester,
            pending.requestCode,
            code,
            GuestDocuments.addressed(pending.request, data),
        )
    }

    /** Forgets a whole stack's worth, so a torn-down session leaves no activities held. */
    fun clear() {
        waiting.clear()
        expecting = null
    }

    /**
     * Calls [activity]'s `onActivityResult` directly.
     *
     * Shared with the container's own answers — see [GuestDocuments] and [GuestCamera] — because
     * there is one way back into an embedded activity and it is subtle enough that a second copy
     * would be a second thing to keep correct. Callers post it to the main looper themselves.
     */
    fun deliver(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
        runCatching {
            val method = Activity::class.java.getDeclaredMethod(
                "onActivityResult",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Intent::class.java,
            )
            method.isAccessible = true
            method.invoke(activity, requestCode, resultCode, data)
        }.onFailure { Log.w(TAG, "cannot deliver result $requestCode to ${activity.javaClass.name}", it) }
    }

    /**
     * `Activity`'s private result fields, or null with a line saying so.
     *
     * Null is survivable rather than fatal: every answer then reads as `RESULT_CANCELED`, which is a
     * real result an app handles, instead of the device hanging. It has not happened at `targetSdk`
     * 33 — these are plain private fields rather than `@hide` API — but the members this container
     * depends on have been withdrawn before, and a device that answers "cancelled" is a device that
     * still works.
     */
    private fun field(name: String): Field? = runCatching {
        Activity::class.java.getDeclaredField(name).apply { isAccessible = true }
    }.onFailure { Log.w(TAG, "Activity.$name is not reachable; results will read as cancelled", it) }
        .getOrNull()
}
