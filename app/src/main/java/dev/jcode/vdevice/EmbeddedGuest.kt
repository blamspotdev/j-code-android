package dev.jcode.vdevice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.view.Display
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceControlViewHost
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * One guest app rendered into an editor tab, living in the `:guest` process.
 *
 * [SurfaceControlViewHost] is what makes this permission-free: it exists to let one process's views
 * be composited inside another's, the IDE and `:guest` share a uid, and — unlike an activity on a
 * virtual display — it asks nothing of the activity task manager. The guest's activity is built
 * window-less by [GuestRuntime.embed] and only its decor view is handed over.
 *
 * `hostToken` is the IDE `SurfaceView`'s own input token, and it is not optional:
 * `WindowlessWindowManager` asks the window manager to grant the embedded hierarchy an input
 * channel parented to it, and on Android 13 that call fails outright without one — taking the whole
 * host down with it on the next traversal.
 *
 * Having the channel is still not the same as being fed by it. Measured on Android 13: touches over
 * the tab are dispatched to J Code's window, not to the embedded one, so every event is relayed
 * over Binder from the IDE and dispatched straight into [container]. That is safe rather than
 * doubled — the IDE only ever sees an event the dispatcher did *not* give to the guest — but it does
 * cost the soft keyboard, which is why text arrives here as synthesised key events.
 */
internal class EmbeddedGuest(
    private val context: Context,
    private val onFinished: (String) -> Unit,
) {

    private var host: SurfaceControlViewHost? = null
    private var container: FrameLayout? = null

    /** Embedded back stack, bottom first. Only the top activity's decor is visible. */
    private val stack = ArrayList<Activity>()

    /** False once a lifecycle step had to fall back to the public `Instrumentation` calls. */
    var fullLifecycle = true
        private set

    val packageName: String?
        get() = stack.lastOrNull()?.packageName

    fun start(
        apkPath: String,
        activityClass: String?,
        width: Int,
        height: Int,
        hostToken: IBinder?,
    ): SurfaceControlViewHost.SurfacePackage {
        stop()
        if (hostToken == null) {
            throw VirtualDeviceException("this window has no input token to host a guest under")
        }
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: throw VirtualDeviceException("no default display")
        val activity = GuestRuntime.embed(apkPath, activityClass)
        try {
            val container = FrameLayout(context)
            container.addView(activity.window.decorView, matchParent())
            // The cast picks the long-standing IBinder overload over the InputTransferToken one.
            val host = SurfaceControlViewHost(context, display, hostToken as IBinder?)
            // Assigned before setView: a host that fails half-way still has a pending traversal, and
            // only stop() can release it before that traversal crashes the process.
            this.host = host
            this.container = container
            setView(host, container, width, height)

            stack += activity
            fullLifecycle = GuestRuntime.resumeEmbedded(activity)
            GuestRuntime.setEmbeddedLauncher(::push)

            return host.surfacePackage
                ?: throw VirtualDeviceException("the view host produced no surface package")
        } catch (t: Throwable) {
            runCatching { GuestRuntime.destroyEmbedded(activity) }
            stop()
            throw t
        }
    }

    fun surface(): SurfaceControlViewHost.SurfacePackage =
        host?.surfacePackage ?: throw VirtualDeviceException("no guest is running")

    fun resize(width: Int, height: Int) {
        host?.relayout(width, height)
    }

    fun touch(event: MotionEvent) {
        container?.dispatchTouchEvent(event)
        reapFinished()
    }

    fun key(event: KeyEvent) {
        container?.dispatchKeyEvent(event)
        reapFinished()
    }

    /** Types [text] as key events: with no window, the guest's fields cannot bind an IME. */
    fun text(text: String) {
        val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        map.getEvents(text.toCharArray())?.forEach { key(it) }
    }

    fun back() {
        if (stack.size > 1) {
            pop()
            return
        }
        @Suppress("DEPRECATION")
        stack.lastOrNull()?.onBackPressed()
        reapFinished()
    }

    fun stop() {
        GuestRuntime.setEmbeddedLauncher(null)
        stack.asReversed().forEach { activity ->
            (activity.window.decorView.parent as? ViewGroup)?.removeView(activity.window.decorView)
            runCatching { GuestRuntime.destroyEmbedded(activity) }
        }
        stack.clear()
        container = null
        host?.release()
        host = null
    }

    /** [GuestRuntime.setEmbeddedLauncher] callback: a guest activity started another one. */
    private fun push(stub: Intent): Boolean {
        val container = container ?: return false
        val activity = GuestRuntime.embed(stub)
        stack.lastOrNull()?.window?.decorView?.visibility = View.GONE
        container.addView(activity.window.decorView, matchParent())
        stack += activity
        if (!GuestRuntime.resumeEmbedded(activity)) fullLifecycle = false
        return true
    }

    private fun pop() {
        val activity = stack.removeLastOrNull() ?: return
        (activity.window.decorView.parent as? ViewGroup)?.removeView(activity.window.decorView)
        runCatching { GuestRuntime.destroyEmbedded(activity) }
        val below = stack.lastOrNull()
        if (below == null) {
            onFinished("The app closed its last screen.")
            return
        }
        below.window.decorView.visibility = View.VISIBLE
        GuestRuntime.resumeEmbedded(below)
    }

    /**
     * `Activity.finish()` reaches a task manager that has never heard of this activity, so it does
     * nothing but set `isFinishing`; the container is the only thing that can act on it.
     */
    private fun reapFinished() {
        while (stack.lastOrNull()?.isFinishing == true) pop()
    }

    /**
     * The public `setView(View, int, int)` builds its layout params with no flags, which leaves the
     * embedded hierarchy on the software renderer; the hidden overload would let
     * `FLAG_HARDWARE_ACCELERATED` through. Measured on Android 13: the overload is filtered out of
     * `SurfaceControlViewHost`'s declared methods, so a guest in a tab is CPU-rendered and there is
     * nothing to reflect at. It draws correctly, just without the GPU.
     */
    private fun setView(host: SurfaceControlViewHost, view: View, width: Int, height: Int) {
        val withParams = HiddenApi.method(
            SurfaceControlViewHost::class.java,
            "setView",
            View::class.java,
            WindowManager.LayoutParams::class.java,
        )
        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSPARENT,
        )
        if (withParams != null && runCatching { withParams.invoke(host, view, params) }.isSuccess) return
        host.setView(view, width, height)
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )
}
