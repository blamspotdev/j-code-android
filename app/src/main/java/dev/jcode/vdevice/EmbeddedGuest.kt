package dev.jcode.vdevice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceControlViewHost
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
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
 * the tab are dispatched to JCode's window, not to the embedded one, so every event is relayed
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

    /** The device's own status bar, over whatever activity is on the screen — see [VirtualStatusBar]. */
    private var statusBar: VirtualStatusBar? = null

    /** Layout listener that catches `SurfaceView`s a guest adds after it has started. */
    private var surfaceWatcher: ViewTreeObserver.OnGlobalLayoutListener? = null

    /** Embedded back stack, bottom first. Only the top activity's decor is visible. */
    private val stack = ArrayList<Activity>()

    /** The tab's size, kept because the bar appearing or going away re-divides it — see [followForegroundApp]. */
    private var width = 0
    private var height = 0

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
        this.width = width
        this.height = height
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

            // Before the activity exists, so its very first measure is against the window it is
            // actually going into rather than against the whole phone — see GuestWindow.
            // The size the guest is told it has is the size it is actually given — the container
            // minus the status bar — or it lays out for a screen taller than its window.
            GuestRuntime.sizeEmbeddedWindow(apkPath, width, height - statusBarHeight())
            val guest = GuestRuntime.embed(apkPath, activityClass, windows?.token)
            activity = guest
            container.addView(guest.window.decorView, contentParams())
            stack += guest
            fullLifecycle = GuestRuntime.resumeEmbedded(guest)
            GuestRuntime.setEmbeddedLauncher(::push)
            GuestRuntime.setEmbeddedFinisher(::reapFinished)
            GuestRuntime.setEmbeddedBackHandler(::finishTop)
            // Added last, so it is the topmost child: the device's own status bar has to sit over
            // the app the way a phone's does, and a FrameLayout hands the front child the touch
            // first — which is what lets the shade be pulled down over a guest that is drawing
            // full-bleed underneath it.
            addStatusBar(container)
            followForegroundApp()
            watchForSurfaces(container)

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
        this.width = width
        this.height = height
        // The guest's own configuration first: relayout is what asks it to measure again, so it has
        // to already know the size it is measuring for.
        GuestRuntime.sizeEmbeddedWindow(width, height - contentTop())
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
            // The device's own screen is what a guest is drawn on top of, so it is what shows
            // through anything translucent — the same picture an idle capture answers with.
            VirtualWallpaper.draw(canvas, bitmap.width, bitmap.height)
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

    /**
     * Writes the guest's view tree to [xml] for `uiautomator dump`.
     *
     * Dialogs and popups are separate windows rather than children of [container], so they are
     * walked as their own roots — each offset by the frame [EmbeddedWindows] placed it at, which is
     * what keeps every `bounds` in the coordinates `input tap` takes.
     */
    fun dump(xml: File) {
        val container = container ?: throw VirtualDeviceException("no guest is running")
        val roots = listOf<Pair<View, Rect>>(container to Rect()) +
            windows?.children().orEmpty().map { it.view to it.frame }
        GuestHierarchy.write(xml, roots)
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
        // The device's own shade is above everything, so it takes Back first — the same order a
        // phone answers in, and the guest never sees a key that was not meant for it.
        statusBar?.takeIf { it.isOpen }?.let {
            it.collapse()
            return
        }
        // A dialog or popup closes itself on Back, so it is sent the key rather than being reached
        // around — dismissing it from here would skip the guest's own cancel handling.
        topWindow()?.let { child ->
            val now = SystemClock.uptimeMillis()
            child.view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
            child.view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))
            return
        }
        // The activity decides first, exactly as it does on a phone — the window manager never pops
        // a task itself, it calls onBackPressed and lets the activity answer.
        //
        // Popping the stack directly whenever it held more than one activity skipped that answer.
        // NewPipe's Appearance screen is a *fragment* inside the settings activity, so Back left the
        // sub-screen, the settings list and the settings activity all at once and landed back on the
        // main screen. Every other back stack an activity keeps — an open drawer, a WebView's
        // history, a multi-step form — was being skipped the same way.
        //
        // An activity with nothing of its own to pop finishes itself, and that is what [reapFinished]
        // acts on, so "the activity consumed it" and "leave this screen" stay one decision.
        @Suppress("DEPRECATION")
        stack.lastOrNull()?.onBackPressed()
        reapFinished()
    }

    fun stop() {
        // Whether the app is allowed to outlive its screen is the one question here, and the answer
        // decides both halves: an app kept in the background keeps its services *and* the
        // notifications that are usually the only way to reach them, and one that is not keeps
        // neither. Leaving notifications behind for an app that has actually gone means the next
        // app's status bar counts somebody else's — measured as CPU-Z reporting the fixture's two.
        if (GuestRuntime.activePackage()?.let { GuestRuntime.mayRunInBackground(it) } != true) {
            VirtualNotifications.clearAll()
            GuestRuntime.releaseComponents()
        }
        GuestRuntime.setEmbeddedLauncher(null)
        GuestRuntime.setEmbeddedFinisher(null)
        GuestRuntime.setEmbeddedBackHandler(null)
        stack.asReversed().forEach { activity ->
            (activity.window.decorView.parent as? ViewGroup)?.removeView(activity.window.decorView)
            runCatching { GuestRuntime.destroyEmbedded(activity) }
        }
        stack.clear()
        statusBar = null
        surfaceWatcher?.let { watcher ->
            runCatching { container?.viewTreeObserver?.removeOnGlobalLayoutListener(watcher) }
        }
        surfaceWatcher = null
        container = null
        windows?.release()
        windows = null
        host?.release()
        host = null
    }

    /**
     * Watches for `SurfaceView`s the guest creates, which it may do at any point rather than only
     * while its activity is being built — SDL and every engine like it add theirs from native code
     * once it has started. A layout listener catches all of them for the cost of one early-out per
     * pass; see [GuestSurfaces] for what is done with them and why only some.
     */
    private fun watchForSurfaces(container: FrameLayout) {
        if (surfaceWatcher != null) return
        // The same pass also re-reads the foreground app's status bar style, because an app changes
        // its mind about that at runtime — full-screen for a video, back afterwards — and a layout
        // is the one moment the container is told something happened.
        val watcher = ViewTreeObserver.OnGlobalLayoutListener {
            GuestSurfaces.raiseFullBleed(container)
            followForegroundApp()
        }
        surfaceWatcher = watcher
        container.viewTreeObserver.addOnGlobalLayoutListener(watcher)
    }

    /**
     * The device's status bar, kept as the container's last child.
     *
     * Re-added rather than moved whenever an activity goes in below it, because `addView` appends
     * and a new decor view would otherwise be drawn over the bar — and take its touches with it.
     */
    private fun addStatusBar(container: FrameLayout) {
        statusBar?.let(container::removeView)
        val bar = statusBar ?: VirtualStatusBar(context)
            .also { statusBar = it }
        container.addView(
            bar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    /** [GuestRuntime.setEmbeddedLauncher] callback: a guest activity started another one. */
    private fun push(stub: Intent): Boolean {
        val container = container ?: return false
        val activity = GuestRuntime.embed(stub, windows?.token)
        stack.lastOrNull()?.window?.decorView?.visibility = View.GONE
        container.addView(activity.window.decorView, contentParams())
        addStatusBar(container)
        stack += activity
        if (!GuestRuntime.resumeEmbedded(activity)) fullLifecycle = false
        followForegroundApp()
        return true
    }

    /**
     * [GuestRuntime.setEmbeddedBackHandler] callback: the platform asked the server to answer a Back.
     *
     * `finish()` rather than `pop()`, so the activity learns it is going away and runs its own
     * teardown — the same path it takes when a guest closes a screen itself, and the one
     * [reapFinished] is already waiting on.
     */
    private fun finishTop() {
        stack.lastOrNull()?.finish()
        reapFinished()
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
        followForegroundApp()
    }

    /**
     * `Activity.finish()` reaches a task manager that has never heard of this activity, so it does
     * nothing but set `isFinishing`; the container is the only thing that can act on it.
     */
    private fun reapFinished() {
        while (stack.lastOrNull()?.isFinishing == true) pop()
    }

    /**
     * The guest's window: the whole container **below the device's status bar**.
     *
     * The bar is drawn over the container's top strip, and a guest laid out to the full height drew
     * underneath it — NewPipe's toolbar came out with its title half-hidden behind the device's own
     * name. A phone does not ask an app to avoid the status bar, it gives the app a window that does
     * not include it, and that is what the top margin is.
     *
     * Doing it by margin rather than by dispatching insets is deliberate: insets only help an app
     * that reads them, and one that does not would still draw underneath. A window that stops where
     * the bar starts is true for every guest, however it lays itself out.
     */
    private fun contentParams() = matchParent().apply { topMargin = contentTop() }

    /** Where the guest's window starts: below the bar, or at the top when the bar is not taking room. */
    private fun contentTop(): Int = if (style.hidden || style.overlay) 0 else statusBarHeight()

    /**
     * What the bar currently looks like. Held rather than recomputed on every layout pass so that
     * [followForegroundApp] can tell a real change from the hundred times a frame it is asked.
     */
    private var style = GuestWindow.StatusBarStyle()

    /**
     * Re-reads the foreground activity's window and reshapes the bar around it.
     *
     * Called after anything that changes which activity is in front, and again on every layout pass,
     * because an app does not only decide this at startup: a video player goes full-screen when a
     * video starts and comes back when it ends, and the bar has to follow it both ways. Nothing
     * happens unless the answer actually changed, which is what keeps a layout listener from
     * requesting layout from inside a layout.
     */
    private fun followForegroundApp() {
        val activity = stack.lastOrNull() ?: return
        val next = GuestWindow.statusBarStyleOf(activity)
        if (next == style) return
        style = next
        val bar = statusBar ?: return
        bar.visibility = if (next.hidden) View.GONE else View.VISIBLE
        bar.apply(next)
        // The guest's own window grows into the space the bar gives up, and shrinks when it takes it
        // back. Its configuration has to be told first — relayout is what makes it measure again.
        GuestRuntime.sizeEmbeddedWindow(width, height - contentTop())
        stack.forEach { hosted ->
            val decor = hosted.window.decorView
            (decor.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                if (params.topMargin != contentTop()) {
                    params.topMargin = contentTop()
                    decor.layoutParams = params
                }
            }
        }
        Log.i(TAG, "status bar over ${GuestRuntime.activePackage()}: $next")
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    private fun statusBarHeight(): Int =
        (VirtualStatusBar.BAR_DP * context.resources.displayMetrics.density).toInt()
}
