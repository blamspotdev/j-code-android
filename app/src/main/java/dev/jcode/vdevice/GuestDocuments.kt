package dev.jcode.vdevice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * What the device does when a guest asks for a document.
 *
 * `ACTION_OPEN_DOCUMENT` and its three siblings are the one kind of intent an app cannot be talked
 * out of: there is no library answer, the framework's own `ActivityResultContracts` produce exactly
 * these, and an app that cannot open a file is an app that cannot be used. Before this they went out
 * to the real system, and two separate things went wrong at once:
 *
 * 1. **The phone's picker opened over JCode**, offering a sandboxed app the user's own downloads,
 *    photos and cloud accounts. That is the leak the device exists to prevent, and it was the
 *    default path.
 * 2. **The answer went nowhere.** An embedded activity's token is one no `ActivityRecord` answers
 *    to, so `startActivityForResult` had its `resultTo` blanked on the way out — see
 *    [GuestActivityClient.detachEmbeddedTokens] — and there was no route back even in principle.
 *    Measured on WaveRepo: the picker listed the user's files, picking one did nothing, and the app
 *    waited for a callback that could never arrive.
 *
 * So the launch is taken off the wire, the device shows [VirtualFilePicker] over the guest, and the
 * answer is handed back through [deliver] as a `content://` URI into the device's own storage.
 *
 * The shape is deliberately the same as [GuestPermissions]: consume in the start-activity hook,
 * ask, write the answer back into the activity by hand. They are the two launches the system cannot
 * usefully answer on a guest's behalf.
 */
internal object GuestDocuments {

    /** `Activity.RESULT_CANCELED` / `RESULT_OK`, spelled out because this is not an Activity. */
    private const val RESULT_CANCELED = 0
    private const val RESULT_OK = -1

    private lateinit var host: Context

    /** How the container reaches the tab to put a picker on the screen; null with no tab bound. */
    @Volatile
    private var picker: ((PickerMode, String, String, (String?) -> Unit) -> Boolean)? = null

    fun install(context: Context) {
        host = context.applicationContext
    }

    /** Wires the device's picker to the container showing it, or unwires it when the tab goes away. */
    fun setPicker(picker: ((PickerMode, String, String, (String?) -> Unit) -> Boolean)?) {
        this.picker = picker
    }

    /**
     * Answers a guest's document request where it stands. True when the launch has been dealt with
     * and the binder call must not happen.
     *
     * The request code is read positionally, and this is the anchor: `IActivityTaskManager
     * .startActivity` takes `(caller, callingPackage, callingFeatureId, intent, resolvedType,
     * resultTo, resultWho, requestCode, flags, …)`, and there is no `int` anywhere before
     * `requestCode`. So "the first `int` after the intent" is it, on every overload, and a signature
     * that gains a parameter in front of the intent does not move it.
     */
    fun consume(args: Array<Any?>): Boolean {
        val intent = args.filterIsInstance<Intent>().firstOrNull() ?: return false
        val mode = modeOf(intent) ?: return false
        val activity = GuestRuntime.foregroundActivity() ?: return false
        val slot = args.indexOfFirst { it is Intent }
        val requestCode = args.drop(slot + 1).filterIsInstance<Int>().firstOrNull() ?: -1

        val ask = picker
        if (ask == null) {
            report(intent, "there is no device screen to show a picker on")
            answer(activity, requestCode, null, intent)
            return true
        }
        // Posted, and that is not incidental: a guest may ask for a document from **any** thread.
        // Measured on WaveRepo, whose GameActivity calls `startActivityForResult` from its game
        // thread — so adding the picker inline threw `CalledFromWrongThreadException` out of
        // `ViewGroup.addView` and the app was answered with a cancel it had done nothing to deserve.
        Handler(Looper.getMainLooper()).post {
            val shown = runCatching {
                ask(mode, titleFor(mode, intent), suggestedName(intent)) { chosen ->
                    answer(activity, requestCode, chosen, intent)
                }
            }.onFailure { Log.w(TAG, "cannot open the device's file picker", it) }.getOrDefault(false)
            if (!shown) {
                report(intent, "the device could not open its file picker")
                answer(activity, requestCode, null, intent)
            }
        }
        return true
    }

    /** Which of the four this is, or null when the intent is not a document request at all. */
    private fun modeOf(intent: Intent): PickerMode? = when (intent.action) {
        Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_GET_CONTENT -> PickerMode.OpenFile
        Intent.ACTION_CREATE_DOCUMENT -> PickerMode.CreateFile
        Intent.ACTION_OPEN_DOCUMENT_TREE -> PickerMode.OpenTree
        else -> null
    }

    private fun titleFor(mode: PickerMode, intent: Intent): String {
        val app = GuestRuntime.activeLabel() ?: "This app"
        return when (mode) {
            PickerMode.OpenFile -> "$app wants to open a file"
            PickerMode.CreateFile -> "$app wants to save a file"
            PickerMode.OpenTree -> "$app wants a folder"
        }.let { if (intent.type.isNullOrEmpty() || intent.type == "*/*") it else "$it (${intent.type})" }
    }

    private fun suggestedName(intent: Intent): String =
        intent.getStringExtra(Intent.EXTRA_TITLE).orEmpty()

    /**
     * Turns the person's choice into the result the app is waiting for.
     *
     * A cancel is a real answer — `RESULT_CANCELED` with no data is what a phone's picker returns
     * when Back is pressed, and every app already handles it. Which is why every failure path above
     * lands here too: an app told "cancelled" carries on, and an app told nothing hangs.
     */
    private fun answer(activity: Activity, requestCode: Int, devicePath: String?, request: Intent) {
        val data = devicePath?.let { path ->
            val uri = if (modeOf(request) == PickerMode.OpenTree) {
                VirtualStorageProvider.treeUri(host, path)
            } else {
                VirtualStorageProvider.documentUri(host, path)
            }
            grant(uri)
            Intent().setData(uri).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }
        val resultCode = if (data == null) RESULT_CANCELED else RESULT_OK
        VirtualDeviceLog.append(
            host,
            'I',
            TAG,
            "${GuestRuntime.activePackage()} ${request.action?.substringAfterLast('.')}: " +
                (devicePath ?: "cancelled"),
        )
        Handler(Looper.getMainLooper()).post {
            deliverResult(activity, requestCode, resultCode, data)
        }
    }

    /**
     * Makes the URI persistable before it is handed over.
     *
     * The guest could read it without this — a provider never permission-checks its own uid, and
     * `:guest` is JCode's — but `takePersistableUriPermission` is a *different* question, and it
     * throws for a URI that was never granted persistably. Apps call it the moment they get a
     * document, precisely so a recent-files entry survives a restart, and enough of them do it
     * outside a `try` that answering the question properly is cheaper than the crash.
     */
    private fun grant(uri: Uri) {
        runCatching {
            host.grantUriPermission(
                host.packageName,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }.onFailure { Log.w(TAG, "cannot make $uri persistable; the app may not be able to keep it", it) }
    }

    /**
     * Hands the result to the activity, through the door the framework itself uses where it is open.
     *
     * `dispatchActivityResult` is the right one: besides calling `onActivityResult` it notes the
     * fragment state as not-saved and clears the one-request-at-a-time flag. It is also **blocked**
     * at `targetSdk` 33 — absent from `Activity`'s declared members — so it is tried and not relied
     * on.
     *
     * The fallback is not a workaround so much as the same call one level in: `onActivityResult` is
     * `protected` SDK API, which no hidden-API policy applies to, and reflection dispatches it
     * virtually — so an app's own override runs, and AndroidX's `ComponentActivity` override
     * forwards it into `ActivityResultRegistry`, which is where a `registerForActivityResult`
     * launcher is waiting. Both the old callback and the modern contract are answered by it.
     *
     * Shared with [GuestCamera] rather than copied: there is one way back into an embedded activity
     * and it is subtle enough that a second copy would be a second thing to keep correct. Callers
     * post it to the main looper themselves.
     */
    fun deliverResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode < 0) return
        val dispatch = Activity::class.java.declaredMethods
            .firstOrNull { it.name == "dispatchActivityResult" && it.parameterTypes.size >= 4 }
        if (dispatch != null) {
            val trailing = arrayOfNulls<Any?>(dispatch.parameterTypes.size - 4)
            val sent = runCatching {
                dispatch.isAccessible = true
                dispatch.invoke(activity, null, requestCode, resultCode, data, *trailing)
                true
            }.onFailure { Log.w(TAG, "cannot dispatch the result the framework's way", it) }
                .getOrDefault(false)
            if (sent) return
        }
        runCatching {
            Activity::class.java
                .getDeclaredMethod(
                    "onActivityResult",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Intent::class.java,
                )
                .apply { isAccessible = true }
                .invoke(activity, requestCode, resultCode, data)
        }.onFailure {
            Log.e(TAG, "${activity.javaClass.name} never got its document back", it)
            VirtualDeviceLog.append(
                host,
                'E',
                TAG,
                "could not hand ${activity.javaClass.name} the result of request $requestCode",
            )
        }
    }

    /** Says in the device's own log why an app is about to be told "cancelled". */
    private fun report(intent: Intent, why: String) {
        if (!::host.isInitialized) return
        VirtualDeviceLog.append(
            host,
            'W',
            TAG,
            "${GuestRuntime.activePackage()} asked for ${intent.action}, but $why — " +
                "answering the app with a cancelled result",
        )
    }
}
