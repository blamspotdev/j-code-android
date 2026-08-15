package dev.jcode.vdevice

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.IBinder
import android.util.Log
import android.view.ContextThemeWrapper
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

private const val CALLBACKS_FIELD = "mActivityLifecycleCallbacks"
private const val PRE_PREFIX = "onActivityPre"

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
 * The framework hooks the container installs in the guest process, and the framework calls it makes
 * by hand — the policy that decides what to do with either lives in [GuestRuntime].
 *
 * All of this is reflection against `ActivityThread` and `Activity` internals and is only ever used
 * in `:guest`, never in the IDE process. Every member it reaches for is one [HiddenApi] documents as
 * measured-allowed, and each step is guarded so a platform that withdraws one degrades rather than
 * crashes.
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
     * Replaces the process-wide `IActivityTaskManager` binder proxy so intents the guest starts can
     * be redirected onto a stub, or answered outright, before they leave the process.
     *
     * Hooking the binder rather than `Instrumentation.execStartActivity` covers every entry point —
     * `Activity.startActivity`, `Context.startActivity`, `startActivityForResult` — with one hook,
     * and `execStartActivity` is not in the SDK, so it cannot be overridden anyway.
     *
     * [decide] returns what should happen: proceed untouched, launch a different intent, or —
     * for the device-sandbox tab — nothing at all. [StartAction.Consumed] exists because an embedded
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
                    // The one place an embedded activity's controller can be substituted: this call
                    // is how ActivityClient's singleton obtains it, and the singleton's own field is
                    // blocked at targetSdk 33. See GuestActivityClient.
                    if (method.name == GuestActivityClient.CONTROLLER_GETTER && args.isNullOrEmpty()) {
                        val controller = try {
                            method.invoke(real)
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                        return controller?.let(GuestActivityClient::wrap)
                    }
                    if (args != null && method.name.startsWith("startActivity")) {
                        GuestActivityClient.detachEmbeddedTokens(args)
                        // A runtime permission request is a launch like any other from here, and the
                        // one launch the system cannot usefully answer — the device answers it
                        // itself. See GuestPermissions.
                        if (GuestPermissions.consume(args)) return consumed(method.returnType)
                        // A document request is the other one, and for the same two reasons: the
                        // system would answer it with the *phone's* picker over the user's own
                        // files, and the result could not be delivered back to a token no
                        // ActivityRecord answers to. See GuestDocuments.
                        if (GuestDocuments.consume(args)) return consumed(method.returnType)
                        // Logged because "the app opened, but the phone's copy of it" is otherwise
                        // indistinguishable from "the container hosted it", and the difference is
                        // which binder call carried the intent.
                        args.filterIsInstance<Intent>().firstOrNull()?.let { outgoing ->
                            Log.i(TAG, "outgoing ${method.name}: ${outgoing.component}")
                        }
                        val slot = args.indexOfFirst { it is Intent }
                        if (slot >= 0) {
                            when (val action = decide(args[slot] as Intent)) {
                                is StartAction.Proceed -> Unit
                                is StartAction.Redirect -> args[slot] = action.intent
                                // Consumed means the tab has already hosted this activity, so the
                                // binder call must not happen at all — whatever it returns.
                                //
                                // Guarding on an int return let every other overload fall through
                                // and go out **with the original intent**, and where the phone has
                                // its own copy of the package installed the system then resolved the
                                // component against that copy: the app opened twice, once in the
                                // device and once outside it, and the one the user saw was the
                                // wrong one. Measured on ES-DE, whose ConfiguratorActivity was
                                // hosted correctly and still launched the installed app over JCode.
                                is StartAction.Consumed -> return consumed(method.returnType)
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
     * What a launch the container answered itself reports back — a success, whatever shape the
     * overload declares. Every `startActivity*` on this interface returns an int, a boolean or
     * nothing, and a caller reading any of them must not conclude the launch failed.
     */
    private fun consumed(returnType: Class<*>): Any? = when (returnType) {
        Int::class.javaPrimitiveType -> START_SUCCESS
        Boolean::class.javaPrimitiveType -> true
        else -> null
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
     * Points an embedded activity's child windows at the host that is showing it.
     *
     * `Dialog` carries no token of its own: it adds its decor through the *activity's*
     * `WindowManager`, and `Window.adjustLayoutParamsForSubWindow` fills the token in from the
     * activity window's app token — which for an embedded activity is a bare `Binder` no window
     * manager has heard of, hence `BadTokenException`. Handing that slot the
     * `SurfaceControlViewHost`'s window token instead is what makes `WindowManagerGlobal` give the
     * dialog the host's windowless session; see [EmbeddedWindows]. `PopupWindow`, `Spinner`
     * drop-downs and menu panels already resolve their token off the anchor or decor view, which is
     * inside the host's hierarchy, so they need nothing here.
     *
     * `setWindowManager` is public SDK — only the token being someone else's window is new.
     */
    fun hostWindowIn(activity: Activity, windowToken: IBinder?) {
        if (windowToken == null) return
        runCatching {
            activity.window.setWindowManager(
                activity.windowManager,
                windowToken,
                activity.javaClass.name,
                true,
            )
        }.onFailure { Log.w(TAG, "${activity.javaClass.name} cannot open dialogs in the tab", it) }
    }

    /**
     * Dispatches one of the `Application.ActivityLifecycleCallbacks` steps that only
     * `Activity.perform*` reaches.
     *
     * `Instrumentation.callActivityOnStart/onResume/onStop` are public and run the activity's own
     * `onStart()`/`onResume()`/`onStop()`, which dispatch the plain `onActivityStarted`-style
     * callbacks themselves. What they skip is the `Pre`/`Post` pair `Activity.performStart` wraps
     * them in — and `performStart`/`performResume` are `max-target-p`, so the container cannot call
     * them at `targetSdk` 33.
     *
     * That pair is not a detail. On API 29+ AndroidX's `ReportFragment` drives a
     * `ComponentActivity`'s `LifecycleRegistry` from `onActivityPostCreated`/`PostStarted`/
     * `PostResumed`/`PreStopped` alone, registered through `Activity.registerActivityLifecycleCallbacks`
     * — so without them a Compose guest never leaves CREATED, its frame clock is never resumed, and
     * the surface stays blank. `onCreate` and `onPause`/`onDestroy` need nothing here: their public
     * `Instrumentation` entry points go through `performCreate`/`performPause`/`performDestroy`,
     * which dispatch their own.
     *
     * The methods invoked are public interface members; the two lists they are read off are private
     * fields, and only one of them is within reach. Measured on Android 13 at `targetSdk` 33:
     * `Application.mActivityLifecycleCallbacks` is greylisted and readable, while
     * `Activity.mActivityLifecycleCallbacks` is *blocked* outright. So an app's Application-scoped
     * listeners get the whole sequence and its activity-scoped ones do not — which is why
     * [GuestRuntime.resumeEmbedded] also drives the guest's own `LifecycleRegistry`, doing by hand
     * what the `ReportFragment` callbacks it cannot reach would have done. This returns false in
     * exactly that case, and the tab says so rather than hiding it.
     */
    fun dispatchLifecycleCallback(activity: Activity, name: String): Boolean {
        val method = runCatching {
            Application.ActivityLifecycleCallbacks::class.java.getMethod(name, Activity::class.java)
        }.getOrElse {
            Log.w(TAG, "no ActivityLifecycleCallbacks#$name", it)
            return false
        }
        val own = registered(activityCallbacks, activity)
        val application = registered(applicationCallbacks, activity.application)
        if (own == null && application == null) return false
        // Activity.dispatchActivityPre*() runs the application's listeners first and the activity's
        // own second; dispatchActivityPost*() is the other way round.
        val ordered = if (name.startsWith(PRE_PREFIX)) {
            application.orEmpty() + own.orEmpty()
        } else {
            own.orEmpty() + application.orEmpty()
        }
        ordered.forEach { callback ->
            runCatching { method.invoke(callback, activity) }.onFailure {
                Log.w(TAG, "$name on ${callback.javaClass.name}", (it as? InvocationTargetException)?.targetException ?: it)
            }
        }
        return own != null
    }

    private val activityCallbacks by lazy { HiddenApi.field(Activity::class.java, CALLBACKS_FIELD) }

    private val applicationCallbacks by lazy { HiddenApi.field(Application::class.java, CALLBACKS_FIELD) }

    /** A snapshot of one callback list, or null when the field itself is out of reach. */
    private fun registered(field: Field?, owner: Any?): List<Any>? {
        if (field == null || owner == null) return null
        val list = runCatching { field.get(owner) }.getOrNull() as? List<*> ?: return null
        return synchronized(list) { list.filterNotNull() }
    }

    /**
     * Points an already-attached guest activity at its own [GuestContext].
     *
     * `ActivityThread` attaches every activity to a `ContextImpl` belonging to JCode, and
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
