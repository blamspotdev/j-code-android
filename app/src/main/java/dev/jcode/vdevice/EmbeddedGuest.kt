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
import android.view.WindowInsets
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
    private var container: DeviceRoot? = null
    private var windows: EmbeddedWindows? = null

    /** The device's own status bar, over whatever activity is on the screen — see [VirtualStatusBar]. */
    private var statusBar: VirtualStatusBar? = null

    /** The device's keyboard, over the app being typed into — see [VirtualKeyboard]. */
    private var keyboard: VirtualKeyboard? = null

    /** The device's permission prompt while an app is blocked on it — see [VirtualPermissionDialog]. */
    private var permission: VirtualPermissionDialog? = null

    /** Whether the current stroke belongs to the device's own UI — see [deviceUiUnder]. */
    private var strokeIsDeviceUi = false

    /** Layout listener that catches `SurfaceView`s a guest adds after it has started. */
    private var surfaceWatcher: ViewTreeObserver.OnGlobalLayoutListener? = null

    /** Embedded back stack, bottom first. Only the top activity's decor is visible. */
    private val stack = ArrayList<Activity>()

    /** Whether the device's screen is being looked at — see [setVisible]. */
    private var shown = true

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
        val container = DeviceRoot(context)
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
            // Built before the device's layers go up, because it is one of them. Loading the
            // keyboard's APK is not part of this: that waits until something is focused to type
            // into, so a device nobody types on never pays for one.
            keyboard = VirtualKeyboard(context, container, ::divide, ::keyFromKeyboard)
            raiseDeviceUi(container)
            // Explicitly, rather than leaving it to the first change [followForegroundApp] notices:
            // an app whose window happens to match the container's starting assumption is a change
            // of nothing, and would have been the one app never told what its screen looks like.
            divide()
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
        divide()
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
        GuestHierarchy.write(xml, roots())
    }

    /**
     * Everything on the device's screen and where it sits: the container, then each window the guest
     * has open, at the frame [EmbeddedWindows] placed it at.
     *
     * One list, because "what is on the screen" has one answer — [dump] walks it and
     * [VirtualKeyboard] searches it for the focused field, and a keyboard that disagreed with the
     * dump about which field that is would be a keyboard an agent cannot drive.
     */
    private fun roots(): List<Pair<View, Rect>> =
        listOfNotNull(container?.let { it to Rect() }) +
            windows?.children().orEmpty().map { it.view to it.frame }

    fun touch(event: MotionEvent) {
        // Re-anchored first, so the guest's raw coordinates are the device's screen rather than the
        // phone's — see VirtualInput.inDeviceSpace, without which a native app hit-tests against an
        // origin that is wherever the tab happens to sit in JCode's window.
        val anchored = VirtualInput.inDeviceSpace(event)
        try {
            if (anchored.actionMasked == MotionEvent.ACTION_DOWN) {
                // Decided once and kept for the whole stroke. Re-hit-testing a MOVE hands the rest
                // of a drag to whatever is underneath the moment the finger leaves the key it
                // started on — and a keyboard is nothing but strokes that begin on one thing.
                strokeIsDeviceUi = deviceUiUnder(anchored.x, anchored.y)
                keyboard?.press(anchored.x, anchored.y)
            }
            val child = if (strokeIsDeviceUi) null else topWindow()
            if (child == null) {
                container?.dispatchTouchEvent(anchored)
            } else {
                // The tab's coordinates are the host's; a child window's are its own. The raw ones
                // stay the device's, which is what a dialog on a phone also sees.
                anchored.offsetLocation(-child.frame.left.toFloat(), -child.frame.top.toFloat())
                child.view.dispatchTouchEvent(anchored)
            }
        } finally {
            when (anchored.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> strokeIsDeviceUi = false
            }
            if (anchored !== event) anchored.recycle()
        }
        followFocus()
        reapFinished()
    }

    fun key(event: KeyEvent) {
        // A modal takes the keys as well as the touches. The app underneath keeps its focus while
        // the prompt is up, so without this anything typed over the question would land in the
        // field behind it — see [deviceUiUnder] for the same decision about the other hand.
        if (permission != null) return
        val child = topWindow()
        if (child == null) container?.dispatchKeyEvent(event) else child.view.dispatchKeyEvent(event)
        followFocus()
        reapFinished()
    }

    /**
     * Whether the device's own UI owns [x], [y], which is a question the ordinary path cannot answer.
     *
     * A guest's dialog is a *separate window* rather than a child of the container, so [topWindow]
     * would take every touch the moment one is open. Being in front of the container is not enough
     * when the thing in front of *that* is a different window — and an app with a dialog up is
     * exactly when the device's own chrome needs to be reachable.
     *
     * All three of them, and the status bar is the one that had been left out. A shade you can only
     * pull down on some screens is not the device's shade, it is a decoration on the ones that
     * happen to have no dialog open; the same argument that put the keyboard and the prompt here
     * applies to it unchanged. [VirtualStatusBar.ownsTouchAt] keeps the claim narrow — the grab
     * strip, or anywhere at all once the shade is open — so a guest keeps every touch that is
     * genuinely its own.
     */
    private fun deviceUiUnder(x: Float, y: Float): Boolean =
        permission != null ||
            keyboard?.contains(x, y) == true ||
            statusBar?.ownsTouchAt(x, y) == true

    /** The dialog, popup or drop-down the guest currently has open, if any. */
    private fun topWindow(): EmbeddedWindow? = windows?.children()?.lastOrNull()

    /**
     * Tells the device whether anybody is looking at it.
     *
     * False when its tab is not on screen or JCode is in the background; true when it comes back.
     * Without this a guest ran at full tilt behind whatever the person was actually doing — see
     * [GuestRuntime.pauseEmbedded] for what a pause is worth.
     */
    fun setVisible(visible: Boolean) {
        val activity = stack.lastOrNull() ?: return
        if (visible == shown) return
        shown = visible
        // Nobody is looking at the device, so nothing on it has the focus — and a shade found still
        // open on the way back is a panel from a session that ended, hiding the app it was pulled
        // over. A phone's goes away with the screen for the same reason.
        if (!visible) statusBar?.collapse()
        if (visible) GuestRuntime.resumeEmbedded(activity) else GuestRuntime.pauseEmbedded(activity)
    }

    /**
     * Types [text] into whatever the device is focused on.
     *
     * Down the field's own `InputConnection` when there is one, which is the whole reason the device
     * has a keyboard: `KeyCharacterMap` has no key for `é` and none at all for an emoji, and dropped
     * both without saying so. Key events remain the answer when nothing on the screen takes text —
     * a game reading raw keys, a launcher, a guest that has focused nothing yet.
     */
    fun text(text: String) {
        followFocus()
        if (keyboard?.type(text) == true) return
        val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        map.getEvents(text.toCharArray())?.forEach { key(it) }
    }

    fun back() {
        // Modal, and the app is blocked on the answer: Back is dropped rather than allowed to leave
        // a callback nobody is going to make. The prompt has no dismiss for the same reason.
        if (permission != null) return
        // The device's own shade is above everything else, so it takes Back first — the same order a
        // phone answers in, and the guest never sees a key that was not meant for it.
        statusBar?.takeIf { it.isOpen }?.let {
            it.collapse()
            return
        }
        // Then the keyboard, which is what Back means to anybody who has one open. The field keeps
        // its focus, as on a phone — see [VirtualKeyboard.dismiss] for why that has to be remembered.
        keyboard?.takeIf { it.isShowing }?.let {
            it.dismiss()
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

    // --- the device's keyboard ----------------------------------------------------------------

    /**
     * `adb shell ime <show|hide|toggle|status|list>`, and the tab's keyboard button behind `toggle`.
     *
     * The override for the one bound [followFocus] has: a guest that moves the focus with no input
     * at all is only noticed on the *next* event, so there has to be a way to say "now".
     */
    fun ime(command: String): String {
        val keyboard = keyboard ?: return "ime: no device is running\n"
        return when (command) {
            "show" -> if (keyboard.show(roots())) "" else "ime: nothing on this screen takes text\n"
            "hide" -> {
                keyboard.dismiss()
                ""
            }

            "toggle" -> {
                if (keyboard.isShowing) keyboard.dismiss() else keyboard.show(roots())
                ""
            }

            "status" -> keyboard.status()
            // One keyboard, and it is the device's. Shaped like the real command's `-s` output,
            // which is the id an `ime set` would take if this device ever had a second one.
            "list" -> "${KeyboardApp.PACKAGE}/.KeyboardHost\n"
            else -> "ime: expected show, hide, toggle, status or list\n"
        }
    }

    /**
     * Re-reads which field has the focus and opens or closes the keyboard to match.
     *
     * Called after every touch and every key, which are the moments focus can move. **Known bound:**
     * a guest that moves it on its own — a form that jumps to the next field on a timer — is caught
     * only when the next event arrives; `ime show` is the way out of that.
     *
     * Suspended entirely while the prompt is up. The field underneath keeps its focus, so without
     * this the keyboard would come straight back up *under* a modal the person cannot get past.
     */
    private fun followFocus() {
        if (permission != null) return
        keyboard?.refresh(roots())
    }

    /**
     * The one thing the keyboard cannot say through an `InputConnection` — see [KeyboardApp.MSG_KEY].
     *
     * Enter on a single-line field that asked for no editor action is delivered as a key on a phone,
     * because that is the only way an app that watches for it hears about it at all.
     */
    private fun keyFromKeyboard(code: Int) {
        val now = SystemClock.uptimeMillis()
        key(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        key(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }

    // --- the device's permission prompt -------------------------------------------------------

    /**
     * Puts the device's own permission prompt on the screen, and answers [onAnswer] with what the
     * person said — see [VirtualPermissionDialog] for why it is drawn here rather than by the IDE.
     *
     * One at a time, which costs nothing: `Activity.requestPermissions` is one at a time too, so a
     * second question can only exist if something has gone wrong, and denying it is the answer that
     * leaves an app running.
     */
    fun askPermission(packageName: String, permissions: List<String>, onAnswer: (Boolean) -> Unit) {
        val container = container
        if (container == null || permission != null) {
            onAnswer(false)
            return
        }
        // Before the prompt goes up, not after: a keyboard left over a modal is a row of keys
        // still taking touches for a field nobody can see, and an open shade over one is a panel
        // the person can still pull about while the app underneath is blocked on an answer.
        keyboard?.dismiss()
        statusBar?.collapse()
        val prompt = VirtualPermissionDialog(context, packageName, permissions) { allow ->
            answerPermission(allow)
        }
        permission = prompt
        answer = onAnswer
        container.addView(prompt, matchParent())
    }

    /** What the guest is blocked on, held only while [permission] is on the screen. */
    private var answer: ((Boolean) -> Unit)? = null

    private fun answerPermission(allow: Boolean) {
        val prompt = permission ?: return
        (prompt.parent as? ViewGroup)?.removeView(prompt)
        permission = null
        val waiting = answer
        answer = null
        waiting?.invoke(allow)
        // The field under the prompt still has the focus it had before, so the keyboard the prompt
        // displaced comes back the way it went — which is what the person was doing when the app
        // interrupted them.
        followFocus()
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
        GuestResults.clear()
        keyboard?.release()
        keyboard = null
        // Answered rather than abandoned: a guest is blocked inside requestPermissions, and the
        // thread it is blocked on belongs to a process that is not going away.
        answerPermission(false)
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
     * The device's own layers, kept as the container's last children: the status bar, the keyboard,
     * and the permission prompt over both.
     *
     * Re-added rather than moved whenever an activity goes in below them, because `addView` appends
     * and a new decor view would otherwise be drawn over the lot — and take their touches with it.
     *
     * The order is a phone's. The prompt is last because it is modal over everything, including the
     * device's own chrome. The bar and the keyboard never overlap, so which of the two is in front
     * is a question that does not arise.
     */
    private fun raiseDeviceUi(container: FrameLayout) {
        statusBar?.let(container::removeView)
        val bar = statusBar ?: VirtualStatusBar(context)
            .also { statusBar = it }
        // Full height, not as tall as the strip — see [VirtualStatusBar] for why a shade has to
        // cover the screen it can be dismissed by tapping.
        container.addView(bar, matchParent())
        keyboard?.raise()
        permission?.let { prompt ->
            container.removeView(prompt)
            container.addView(prompt, matchParent())
        }
    }

    /** [GuestRuntime.setEmbeddedLauncher] callback: a guest activity started another one. */
    private fun push(stub: Intent): Boolean {
        val container = container ?: return false
        val activity = GuestRuntime.embed(stub, windows?.token)
        // The one going behind is paused, not just hidden. A hidden activity that was never paused
        // keeps its sensors registered and its animations running, which is what a phone's
        // lifecycle exists to stop — and what the device's own Camera relies on to switch its
        // viewfinder off when something opens over it.
        stack.lastOrNull()?.let {
            it.window.decorView.visibility = View.GONE
            GuestRuntime.pauseEmbedded(it)
        }
        // Whatever the shade was showing belonged to the screen that is going away. A phone closes
        // it when something starts on top — opening an app from a notification is the ordinary way
        // to see that — and an open shade left hanging over a screen nobody chose it from is the
        // clearest case of a panel that has lost the focus it was pulled with.
        statusBar?.collapse()
        container.addView(activity.window.decorView, contentParams())
        raiseDeviceUi(container)
        stack += activity
        // Whoever started this one is owed an answer when it finishes — see GuestResults.
        GuestResults.attach(activity)
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
        // Before it is destroyed: the answer is read off the activity itself, and onDestroy is
        // entitled to clear it.
        GuestResults.harvest(activity)
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

    /** The guest's window as it goes in: whatever [divide] currently leaves it. */
    private fun contentParams() = matchParent().apply {
        topMargin = contentTop()
        bottomMargin = keyboard?.height ?: 0
    }

    /** Where the guest's window starts: below the bar, or at the top when the bar is not taking room. */
    private fun contentTop(): Int = if (style.hidden || style.overlay) 0 else statusBarHeight()

    /**
     * What the guest should be told is around it — see [GuestInsets].
     *
     * The inset is the other half of [contentTop]: between them they always add up to the bar's
     * height, because the space has to be accounted for exactly once. A window that starts below the
     * bar is covered by none of it; one that starts at the top is covered by all of it.
     *
     * `wouldCover` keys off `contentTop() == 0`, which is precisely "this window is full height" —
     * the only case in which a bar, were it shown, would be over any of it.
     */
    private fun deviceInsets(): WindowInsets = GuestInsets.of(
        covered = if (style.overlay) statusBarHeight() else 0,
        wouldCover = if (contentTop() == 0) statusBarHeight() else 0,
        shown = !style.hidden,
    )

    /**
     * Hands out the container's height to the three things that share it — the device's status bar
     * at the top, its keyboard at the bottom, and the guest's window in between — and tells the
     * guest what is left.
     *
     * One function for both ends on purpose. They were two, and two can disagree: the guest's
     * `Configuration` is what its resource qualifiers and its measurement come from, and a window
     * whose margins said one thing while its configuration said another is an app laid out for a
     * screen it does not have.
     *
     * The bottom margin is what `adjustResize` means. Shortening the window is what every app copes
     * with however old it is, which is why the keyboard is *not* also reported as an inset — see
     * [GuestInsets].
     */
    private fun divide() {
        val top = contentTop()
        val bottom = keyboard?.height ?: 0
        // The guest's own configuration first: a relayout is what asks it to measure again, so it
        // has to already know the size it is measuring for.
        GuestRuntime.sizeEmbeddedWindow(width, (height - top - bottom).coerceAtLeast(1))
        stack.forEach { hosted ->
            val decor = hosted.window.decorView
            (decor.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                if (params.topMargin == top && params.bottomMargin == bottom) return@let
                params.topMargin = top
                params.bottomMargin = bottom
                decor.layoutParams = params
            }
        }
        // And what the app should make of it, for the apps that ask — see [DeviceRoot].
        container?.dispatchApplyWindowInsets(deviceInsets())
    }

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
        // Not `GONE` for a full-screen app any more, and that is the whole of "the shade can be
        // pulled anywhere": a view with no height receives no touches, so taking the bar away took
        // the shade with it. [VirtualStatusBar.apply] stops *drawing* the strip instead and keeps
        // the edge to pull from.
        bar.apply(next)
        // The guest's own window grows into the space the bar gives up, and shrinks when it takes it
        // back.
        divide()
        Log.i(TAG, "status bar over ${GuestRuntime.activePackage()}: $next")
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    /**
     * The device's screen, and the one thing on it that is not a view: what the apps on it are told
     * about the chrome around them.
     *
     * A windowless hierarchy is still given insets by its `ViewRootImpl`, and they describe
     * **JCode's** window — the phone's status bar, the phone's gesture strip. An app that trusts
     * them is padding itself around furniture on a device it is not running on, which is the same
     * category of leak as a guest reading the phone's sensors. Substituting them here rather than on
     * each guest's decor view is what keeps an app's own `setOnApplyWindowInsetsListener` working:
     * the app still gets to be the last word on its own window, it is just answering a question
     * about the right device. See [GuestInsets] for what the answers are.
     */
    private inner class DeviceRoot(context: Context) : FrameLayout(context) {
        override fun dispatchApplyWindowInsets(insets: WindowInsets): WindowInsets =
            super.dispatchApplyWindowInsets(deviceInsets())
    }

    private fun statusBarHeight(): Int =
        (VirtualStatusBar.BAR_DP * context.resources.displayMetrics.density).toInt()
}
