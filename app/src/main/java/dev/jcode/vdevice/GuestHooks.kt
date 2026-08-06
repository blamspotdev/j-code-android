package dev.jcode.vdevice

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Message
import android.util.Log
import android.view.ContextThemeWrapper
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

private const val CLIENT_TRANSACTION = "android.app.servertransaction.ClientTransaction"
private const val LAUNCH_ACTIVITY_ITEM = "android.app.servertransaction.LaunchActivityItem"
private const val EMBEDDED_REASON = "JCode app sandbox"

/** `ActivityManager.START_SUCCESS`, which is not in the SDK. */
private const val START_SUCCESS = 0

/** What [GuestHooks.installStartActivityHook] should do with an outgoing launch. */
internal sealed interface StartAction {
    /** Not the guest's; let it go out as it is. */
    data object Proceed : StartAction

    /** Launch [intent] — a stub carrying the guest activity's identity — instead. */
    data class Redirect(val intent: Intent) : StartAction

    /** Already handled inside the process; the binder call must not happen. */
    data object Consumed : StartAction
}

/**
 * The three framework hooks the container installs in the guest process, and nothing else — the
 * policy that decides what to do with them lives in [GuestRuntime].
 *
 * All of this is reflection against `ActivityThread` internals and is only ever installed in
 * `:guest`, never in the IDE process. It assumes [HiddenApi.unseal] has already run.
 */
internal object GuestHooks {

    fun currentActivityThread(): Any? {
        val cls = HiddenApi.classOrNull("android.app.ActivityThread") ?: return null
        return HiddenApi.method(cls, "currentActivityThread")
            ?.let { runCatching { it.invoke(null) }.getOrNull() }
    }

    /**
     * Swaps `ActivityThread.mInstrumentation`. This is what lets the container answer `newActivity`
     * with a class loaded out of the guest APK while the system still performs `attach` and drives
     * the whole activity lifecycle itself.
     */
    fun installInstrumentation(activityThread: Any): GuestInstrumentation? {
        val field = HiddenApi.field(activityThread.javaClass, "mInstrumentation") ?: return null
        val current = runCatching { field.get(activityThread) }.getOrNull() as? Instrumentation ?: return null
        if (current is GuestInstrumentation) return current
        val replacement = GuestInstrumentation(current)
        return runCatching { field.set(activityThread, replacement); replacement }
            .onFailure { Log.e(TAG, "cannot install instrumentation", it) }
            .getOrNull()
    }

    /**
     * Intercepts activity launches on `ActivityThread.mH` before the system acts on them.
     *
     * A `Handler` consults its `mCallback` first, so installing one there gives a look at every
     * `ClientTransaction` on its way in — including the `LaunchActivityItem` carrying the stub's
     * `Intent` and `ActivityInfo`, which [onLaunch] rewrites into the guest's. The callback always
     * reports "unhandled" so `ActivityThread.H.handleMessage` still runs as usual.
     */
    fun installLaunchHook(activityThread: Any, onLaunch: (Intent, ActivityInfo?) -> Unit): Boolean {
        val handler = HiddenApi.field(activityThread.javaClass, "mH")
            ?.let { runCatching { it.get(activityThread) }.getOrNull() } as? Handler ?: return false
        val callbackField = HiddenApi.field(Handler::class.java, "mCallback") ?: return false
        val previous = runCatching { callbackField.get(handler) }.getOrNull() as? Handler.Callback
        if (previous is LaunchCallback) return true
        return runCatching {
            callbackField.set(handler, LaunchCallback(previous, onLaunch))
            true
        }.onFailure { Log.e(TAG, "cannot install launch hook", it) }.getOrDefault(false)
    }

    private class LaunchCallback(
        private val previous: Handler.Callback?,
        private val onLaunch: (Intent, ActivityInfo?) -> Unit,
    ) : Handler.Callback {
        override fun handleMessage(msg: Message): Boolean {
            runCatching { inspect(msg) }.onFailure { Log.w(TAG, "launch hook", it) }
            return previous?.handleMessage(msg) ?: false
        }

        private fun inspect(msg: Message) {
            val transaction = msg.obj ?: return
            if (transaction.javaClass.name != CLIENT_TRANSACTION) return
            val items = HiddenApi.method(transaction.javaClass, "getCallbacks")
                ?.let { runCatching { it.invoke(transaction) }.getOrNull() } as? List<*> ?: return
            for (item in items) {
                if (item == null || item.javaClass.name != LAUNCH_ACTIVITY_ITEM) continue
                val intent = HiddenApi.field(item.javaClass, "mIntent")?.get(item) as? Intent ?: continue
                onLaunch(intent, HiddenApi.field(item.javaClass, "mInfo")?.get(item) as? ActivityInfo)
            }
        }
    }

    /**
     * Replaces the process-wide `IActivityTaskManager` binder proxy so intents the guest starts can
     * be redirected onto a stub, or answered outright, before they leave the process.
     *
     * Hooking the binder rather than `Instrumentation.execStartActivity` covers every entry point —
     * `Activity.startActivity`, `Context.startActivity`, `startActivityForResult` — with one hook,
     * and `execStartActivity` is not in the SDK, so it cannot be overridden anyway.
     *
     * [decide] returns what should happen: proceed untouched, launch a different intent, or —
     * for the app-sandbox tab — nothing at all. [StartAction.Consumed] exists because an embedded
     * activity holds a token no `ActivityRecord` answers to, so letting a guest's own
     * `startActivity` reach the task manager would at best push a second, full-screen copy of the
     * app over the IDE. The call is answered with `START_SUCCESS` instead, and the tab hosts the new
     * activity itself.
     */
    fun installStartActivityHook(decide: (Intent) -> StartAction): Boolean {
        try {
            val holder = HiddenApi.classOrNull("android.app.ActivityTaskManager") ?: return false
            val singleton = HiddenApi.field(holder, "IActivityTaskManagerSingleton")?.get(null) ?: return false
            val singletonClass = HiddenApi.classOrNull("android.util.Singleton") ?: return false
            // Let the singleton create the real proxy before taking it out, or create() would
            // overwrite our replacement on first use.
            HiddenApi.method(singletonClass, "get")?.invoke(singleton)
            val instanceField = HiddenApi.field(singletonClass, "mInstance") ?: return false
            val real = instanceField.get(singleton) ?: return false
            val iface = HiddenApi.classOrNull("android.app.IActivityTaskManager") ?: return false

            val handler = object : InvocationHandler {
                override fun invoke(proxy: Any?, method: Method, args: Array<Any?>?): Any? {
                    if (args != null && method.name.startsWith("startActivity")) {
                        val slot = args.indexOfFirst { it is Intent }
                        if (slot >= 0) {
                            when (val action = decide(args[slot] as Intent)) {
                                is StartAction.Proceed -> Unit
                                is StartAction.Redirect -> args[slot] = action.intent
                                is StartAction.Consumed ->
                                    if (method.returnType == Int::class.javaPrimitiveType) return START_SUCCESS
                            }
                        }
                    }
                    return try {
                        method.invoke(real, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                }
            }
            val proxy = Proxy.newProxyInstance(GuestHooks::class.java.classLoader, arrayOf(iface), handler)
            instanceField.set(singleton, proxy)
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "cannot install start-activity hook", t)
            return false
        }
    }

    /**
     * Gives an activity built out of band the `ActivityThread` a launched one would have had.
     *
     * `Instrumentation.newActivity` attaches with a null thread, and `Activity.startActivity` reads
     * `mMainThread.getApplicationThread()` before anything the container hooks — so without this, an
     * embedded guest cannot navigate at all. Best effort: the field is greylisted today, and a guest
     * that never starts a second activity does not need it.
     */
    fun adoptActivityThread(activity: Activity, activityThread: Any?): Boolean {
        if (activityThread == null) return false
        val field = HiddenApi.field(Activity::class.java, "mMainThread") ?: return false
        return runCatching { field.set(activity, activityThread); true }
            .onFailure { Log.w(TAG, "embedded activity has no ActivityThread; it cannot navigate", it) }
            .getOrDefault(false)
    }

    /**
     * Runs one of `Activity`'s `perform*` lifecycle steps.
     *
     * The public `Instrumentation.callActivityOn*` calls are not equivalent: only `performStart` and
     * `performResume` dispatch the `ActivityLifecycleCallbacks` that AndroidX's `ReportFragment`
     * registers, and without those a Compose guest never advances past CREATED and its frame clock
     * stays paused — a composed but permanently blank surface. Returns false when the member is not
     * reachable, so the caller can fall back to the public calls and say so.
     */
    fun performLifecycle(activity: Activity, name: String): Boolean {
        val method = Activity::class.java.declaredMethods
            .firstOrNull { it.name == name }
            ?.apply { isAccessible = true }
            ?: return false
        val args: List<Any?> = method.parameterTypes.map { type ->
            when (type) {
                String::class.java -> EMBEDDED_REASON
                Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }
        return runCatching { method.invoke(activity, *args.toTypedArray()); true }
            .onFailure { Log.w(TAG, "Activity#$name failed", (it as? InvocationTargetException)?.targetException ?: it) }
            .getOrDefault(false)
    }

    /**
     * Points an already-attached guest activity at its own [GuestContext].
     *
     * `ActivityThread` attaches every activity to a `ContextImpl` belonging to J Code, and
     * `ContextThemeWrapper` caches resources and inflater off it during `attach()`. Swapping `mBase`
     * and dropping those caches — after `attach`, before `onCreate` — is what makes
     * `getPackageName()`, `getFilesDir()`, `getResources()` and layout inflation report the guest.
     *
     * `mTheme` is not cleared here because it cannot be: it is `max-target-p` and denied to this app.
     * [GuestRuntime.onLaunchActivity] keeps it from ever being created instead.
     */
    fun rebase(activity: Activity, guest: LoadedGuest): Boolean {
        val baseField = HiddenApi.field(ContextWrapper::class.java, "mBase") ?: return false
        val current = runCatching { baseField.get(activity) }.getOrNull() as? Context ?: return false
        if (current is GuestContext) return true

        return runCatching {
            baseField.set(activity, GuestContext(current, guest))
            HiddenApi.field(ContextThemeWrapper::class.java, "mResources")?.set(activity, null)
            HiddenApi.field(ContextThemeWrapper::class.java, "mInflater")?.set(activity, null)
            guest.application?.let { HiddenApi.field(Activity::class.java, "mApplication")?.set(activity, it) }
            true
        }.onFailure { Log.e(TAG, "cannot rebase ${activity.javaClass.name}", it) }.getOrDefault(false)
    }
}
