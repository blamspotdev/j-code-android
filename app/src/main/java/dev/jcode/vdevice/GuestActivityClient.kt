package dev.jcode.vdevice

import android.content.pm.ActivityInfo
import android.os.IBinder
import android.util.Log
import android.view.Display
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap

/**
 * Answers the activity manager's token-keyed questions for an *embedded* guest activity.
 *
 * An embedded activity is built by hand, so its token is a bare [android.os.Binder] rather than
 * something the window manager minted. Everything the framework routes through `ActivityClient`
 * carries that token, and the server rejects it before doing anything:
 *
 * ```
 * Bad activity token: android.os.BinderProxy@5fb1255
 * java.lang.ClassCastException: android.os.BinderProxy cannot be cast to
 *     com.android.server.wm.ActivityRecord$Token
 *     at com.android.server.wm.ActivityRecord.getTaskForActivityLocked
 * ```
 *
 * Measured on CPU-Z, whose Mobile Ads SDK asks for its task from inside a WebView and took the whole
 * guest down with a native `SIGTRAP` when the answer never came.
 *
 * `IActivityClientController` is an **interface**, which is what makes this tractable: one [Proxy]
 * sits underneath every entry point. Calls carrying a token this container minted are answered here;
 * every other call — including a full-screen guest's, whose token *is* real — goes straight through
 * untouched.
 *
 * ### Where the proxy gets in
 *
 * Not by replacing `ActivityClient`'s own singleton: `ActivityClient.INTERFACE_SINGLETON` is
 * **blocked** at `targetSdk` 33, measured on this device —
 * `Accessing hidden field …ActivityClient;->INTERFACE_SINGLETON (blocked, reflection, denied)`.
 *
 * It gets in one level up instead. That singleton builds its controller by calling
 * `IActivityTaskManager.getActivityClientController()`, and [GuestHooks.installStartActivityHook]
 * already proxies *that* binder through a member which is greylisted and allowed. So the container
 * answers the question with [wrap] and the singleton caches the wrapper as though the server had
 * handed it over — no additional hidden member, and the one it depends on is already load-bearing.
 *
 * This is why the hook has to be installed before anything in `:guest` touches an activity: the
 * singleton asks once and keeps the answer.
 *
 * ### Why an unknown method is swallowed rather than forwarded
 *
 * The alternative to a made-up answer is not a correct answer, it is the exception above. So a
 * method this does not model returns a type-appropriate nothing: `false`, `0`, or null. That turns
 * a class of hard failures into no-ops, which is the difference between an app that runs with one
 * feature inert and an app that does not run.
 */
internal object GuestActivityClient {

    /**
     * A task id no real task will answer to. Callers overwhelmingly use it as an opaque handle or a
     * "same task?" comparison, so it only has to be stable and not collide.
     */
    private const val EMBEDDED_TASK_ID = 0x7C0DE

    /**
     * Weak, because an activity's token is reachable for exactly as long as the activity is: the
     * `Activity` holds `mToken`, so letting go of the activity lets go of the entry.
     */
    private val tokens: MutableSet<IBinder> =
        Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap()))

    /** The `IActivityTaskManager` method that hands the controller out — see the class docs. */
    const val CONTROLLER_GETTER = "getActivityClientController"


    fun register(token: IBinder) {
        tokens += token
    }

    fun forget(token: IBinder) {
        tokens -= token
    }

    private fun isEmbedded(token: IBinder): Boolean = tokens.contains(token)

    /**
     * Blanks any embedded token in an outgoing `startActivity`'s arguments.
     *
     * `resultTo` is the caller's activity token, and the server rejects an embedded one before it
     * does anything — `Bad activity token … at ActivityStarter.execute`. Null is both accepted and
     * accurate: it means "no activity is waiting for a result", and an embedded activity could not
     * have been delivered one through the server regardless.
     */
    fun detachEmbeddedTokens(args: Array<Any?>) {
        for (index in args.indices) {
            val token = args[index] as? IBinder ?: continue
            if (isEmbedded(token)) args[index] = null
        }
    }

    /**
     * Wraps the real `IActivityClientController` on its way back from the activity task manager, so
     * the singleton that asked for it caches this instead. Returns [controller] unchanged when the
     * interface cannot be reached, which leaves embedded guests exactly as they were.
     */
    fun wrap(controller: Any): Any = runCatching {
        if (Proxy.isProxyClass(controller.javaClass)) return controller
        val iface = HiddenApi.classOrNull("android.app.IActivityClientController") ?: return controller
        Proxy.newProxyInstance(
            GuestActivityClient::class.java.classLoader,
            arrayOf(iface),
            Handler(controller),
        ).also { Log.i(TAG, "activity client hook installed") }
    }.getOrElse {
        Log.w(TAG, "cannot wrap the activity client; embedded guests keep a bad token", it)
        controller
    }

    private class Handler(private val real: Any) : InvocationHandler {

        override fun invoke(proxy: Any?, method: Method, args: Array<Any?>?): Any? {
            val token = args?.firstNotNullOfOrNull { it as? IBinder }
            if (token != null && isEmbedded(token)) return answer(method, args)
            return try {
                method.invoke(real, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }

        private fun answer(method: Method, args: Array<Any?>?): Any? = when (method.name) {
            "getTaskForActivity" -> EMBEDDED_TASK_ID
            "getDisplayId" -> Display.DEFAULT_DISPLAY
            /*
             * Not the generic int default, which is 0 — and 0 is SCREEN_ORIENTATION_LANDSCAPE.
             *
             * Every embedded guest was therefore answering "I asked for landscape" while sitting in
             * a portrait tab, and a framework that compares the two disagreed with the screen it had
             * been given. SDL refuses to start its render thread on exactly that mismatch:
             *
             *   V SDL: Window size: 1080x1510
             *   V SDL: Skip .. Surface is not ready.
             *
             * — so ES-DE initialised, created its surface, and drew nothing. UNSPECIFIED is also the
             * honest answer: the tab has one shape, the guest does not get to choose it, and
             * GuestWindow.makeResizable already strips the same declaration from the manifest.
             */
            "getRequestedOrientation" -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            // The tab shows one activity at a time and it is the one being asked about.
            "isTopOfTask", "willActivityBeVisible" -> true
            /*
             * Since Android 12 an activity does not act on Back itself: `Activity.onBackPressed`
             * hands the decision to the system, because only the system knows whether this is the
             * last activity in the task — in which case the task goes to the back rather than the
             * app closing. The server answers by calling `requestFinish()` on a callback it is
             * passed.
             *
             * Swallowing it meant Back did nothing on any screen an app had pushed. NewPipe's
             * settings could be opened and never left: the first Back popped a preference fragment,
             * which AppCompat does itself before delegating, and the second arrived here and
             * stopped.
             *
             * The container answers instead of the callback. Reflecting `requestFinish` out of the
             * arguments was tried first and found nothing — measured, `carried no finish callback` —
             * so rather than guess at a shape, this tells the tab, which holds the activity itself
             * and finishes it down the same path a guest's own `finish()` takes. A guest's tab is
             * its own task and the container decides when the device is done with an app, so
             * "finish" is always the answer here.
             */
            "onBackPressed" -> null.also { GuestRuntime.onEmbeddedBackPressed() }
            // Activity.finish() only sets mFinished when this returns true, and the container reaps
            // a finished activity by asking isFinishing() — so answering false would leave a guest
            // that called finish() on screen for good.
            //
            // Saying true is necessary but not sufficient: something has to *look* afterwards. This
            // is the only point in the process that knows a finish happened at all, so it says so
            // rather than leaving the container to notice on the next touch — which it could not,
            // since a click handler runs a message later than the touch that produced it.
            "finishActivity", "finishActivityAffinity" -> true.also { GuestRuntime.onEmbeddedFinish() }
            // Named, because a swallowed call is invisible by construction and that is the whole
            // cost of the policy above: the feature that quietly did nothing looks identical to one
            // that was never used. recreate() cost a build cycle to find for exactly that reason.
            else -> {
                Log.d(TAG, "activity client call not modelled: ${method.name}")
                empty(method.returnType)
            }
        }

        private fun empty(type: Class<*>): Any? = when (type) {
            Void.TYPE -> null
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            else -> null
        }
    }
}
