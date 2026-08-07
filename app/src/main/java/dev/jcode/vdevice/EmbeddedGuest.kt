package dev.jcode.vdevice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.os.SystemClock
import android.view.Display
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceControlViewHost
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import java.io.File

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
 *
 * Relaying is also why input has to pick its own target: a dialog or a popup is a *separate* window
 * with its own view root, not a child of [container], so [EmbeddedWindows] is asked which window is
 * on top and the event is translated into it.
 */
internal class EmbeddedGuest(
    private val context: Context,
    private val onFinished: (String) -> Unit,
) {

    private var host: SurfaceControlViewHost? = null
    private var container: FrameLayout? = null
    private var windows: EmbeddedWindows? = null

    /** Embedded back stack, bottom first. Only the top activity's decor is visible. */
    private val stack = ArrayList<Activity>()

    /** False once an activity's own `ActivityLifecycleCallbacks` proved out of reach — see
     *  [GuestHooks.dispatchLifecycleCallback]. */
    var fullLifecycle = true
        private set

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
        val container = FrameLayout(context)
        // The cast picks the long-standing IBinder overload over the InputTransferToken one.
        val host = SurfaceControlViewHost(context, display, hostToken as IBinder?)
        // Assigned before setView: a host that fails half-way still has a pending traversal, and
        // only stop() can release it before that traversal crashes the process.
        this.host = host
        this.container = container
        var activity: Activity? = null
        try {
            // The host is given its view before the guest exists so the guest can be built already
            // knowing the window its dialogs belong to — that token only exists once a view root is
            // attached to the host, and `onCreate` is too late to learn it.
            host.setView(container, width, height)
            windows = EmbeddedWindows.install(host, container, width, height)

            val guest = GuestRuntime.embed(apkPath, activityClass, windows?.token)
            activity = guest
            container.addView(guest.window.decorView, matchParent())
            stack += guest
            fullLifecycle = GuestRuntime.resumeEmbedded(guest)
            GuestRuntime.setEmbeddedLauncher(::push)

            return host.surfacePackage
                ?: throw VirtualDeviceException("the view host produced no surface package")
        } catch (t: Throwable) {
            activity?.let { runCatching { GuestRuntime.destroyEmbedded(it) } }
            stop()
            throw t
        }
    }

    fun surface(): SurfaceControlViewHost.SurfacePackage =
        host?.surfacePackage ?: throw VirtualDeviceException("no guest is running")

    fun resize(width: Int, height: Int) {
        windows?.resize(width, height)
        host?.relayout(width, height)
    }

    /**
     * Draws the guest's screen into [png], for `adb shell screencap` — see [VirtualScreen] for why
     * re-drawing is what is left once the composited layer turns out to be unreachable.
     *
     * The guest's dialogs and popups are separate windows, so they are drawn over the container at
     * the frames [EmbeddedWindows] places them at rather than being missed.
     */
    fun capture(png: File) {
        val container = container ?: throw VirtualDeviceException("no guest is running")
        val bitmap = Bitmap.createBitmap(
            container.width.coerceAtLeast(1),
            container.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.BLACK)
            container.draw(canvas)
            windows?.children()?.forEach { child ->
                canvas.save()
                canvas.translate(child.frame.left.toFloat(), child.frame.top.toFloat())
                child.view.draw(canvas)
                canvas.restore()
            }
            png.parentFile?.mkdirs()
            png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }

    fun touch(event: MotionEvent) {
        val child = topWindow()
        if (child == null) {
            container?.dispatchTouchEvent(event)
        } else {
            // The tab's coordinates are the host's; a child window's are its own.
            event.offsetLocation(-child.frame.left.toFloat(), -child.frame.top.toFloat())
            child.view.dispatchTouchEvent(event)
        }
        reapFinished()
    }

    fun key(event: KeyEvent) {
        val child = topWindow()
        if (child == null) container?.dispatchKeyEvent(event) else child.view.dispatchKeyEvent(event)
        reapFinished()
    }

    /** The dialog, popup or drop-down the guest currently has open, if any. */
    private fun topWindow(): EmbeddedWindow? = windows?.children()?.lastOrNull()

    /** Types [text] as key events: with no window, the guest's fields cannot bind an IME. */
    fun text(text: String) {
        val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        map.getEvents(text.toCharArray())?.forEach { key(it) }
    }

    fun back() {
        // A dialog or popup closes itself on Back, so it is sent the key rather than being reached
        // around — dismissing it from here would skip the guest's own cancel handling.
        topWindow()?.let { child ->
            val now = SystemClock.uptimeMillis()
            child.view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
            child.view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))
            return
        }
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
        windows?.release()
        windows = null
        host?.release()
        host = null
    }

    /** [GuestRuntime.setEmbeddedLauncher] callback: a guest activity started another one. */
    private fun push(stub: Intent): Boolean {
        val container = container ?: return false
        val activity = GuestRuntime.embed(stub, windows?.token)
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

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )
}
