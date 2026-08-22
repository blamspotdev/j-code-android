package dev.blamspot.jcode.vdevice

import android.annotation.SuppressLint
import android.content.Context
import android.os.IBinder
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceControlViewHost
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * The tab's window onto the guest.
 *
 * A SurfaceView, never a TextureView: the guest's views are composited by SurfaceFlinger from a
 * `SurfaceControl` the `:guest` process owns, and only a SurfaceView can adopt one
 * ([SurfaceView.setChildSurfacePackage]). It also has to be hardware accelerated, which JCode's
 * window is not by default — the page checks that before offering to run anything in the tab.
 *
 * It lives as long as the tab does, with or without a guest: it is what gives the device its
 * resolution and the host token a guest is embedded under.
 *
 * Input is forwarded by hand. The embedded hierarchy is registered with no host input token, so the
 * system delivers it nothing; the events that arrive here are already in this view's coordinates,
 * which are the guest's, so they can be relayed unchanged.
 */
@SuppressLint("ClickableViewAccessibility", "ViewConstructor")
internal class AppSandboxSurfaceView(
    context: Context,
    private val session: AppSandboxSession,
    private val onSized: (Int, Int) -> Unit,
) : SurfaceView(context) {

    /**
     * The home screen this surface is showing, or null while a guest owns the screen.
     *
     * Non-null is what makes the device's own launcher live: it is drawn onto this surface (so
     * `screencap` sees it) and it is what a touch is resolved against (so `input tap` reaches it).
     */
    private var home: List<LauncherApp>? = null

    /** Set by the tab each composition, so a tap does not call back into a stale lambda. */
    var onLaunchApp: (VirtualDeviceApp) -> Unit = {}
    var onAppMenu: (VirtualDeviceApp, Float, Float) -> Unit = { _, _, _ -> }

    private var pressed: VirtualDeviceApp? = null
    private var pressedAt = 0f to 0f
    private val longPress = Runnable {
        pressed?.let { app ->
            pressed = null
            onAppMenu(app, pressedAt.first, pressedAt.second)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        // The home screen has to be painted onto the surface itself: a SurfaceView punches a hole in
        // the window, so nothing composed behind it is ever seen. A guest's own surface package is
        // reparented above this one, which is why starting an app needs no matching erase.
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = paintHome()

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                paintHome()

            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        })
        // A raw listener, not a Compose gesture: the guest expects real pointer ids and pressures,
        // and Compose's pointer input reports neither.
        setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                // Otherwise the navigation drawer's swipe-to-open steals any drag starting here.
                parent?.requestDisallowInterceptTouchEvent(true)
                requestFocus()
            }
            if (home != null) touchHome(event) else session.touch(event)
            true
        }
    }

    /**
     * Puts the device's launcher on the screen, or takes it off for [apps] of null — which is what a
     * guest starting looks like from here.
     */
    fun showHome(apps: List<LauncherApp>?) {
        home = apps
        cancelPress()
        if (apps == null) return
        paintHome()
        // A surface that does not exist yet cannot be painted on, and stopping an app is exactly
        // when it does not exist: the tab rebuilds this view as the guest's screen goes (see its
        // `generation`), so the launcher is handed to a view that was created moments ago.
        //
        // That is normally nothing to worry about — [SurfaceHolder.Callback.surfaceCreated] paints
        // again, from the list [home] is already holding. But a `SurfaceView` creates its surface
        // from an `OnPreDrawListener`, so it needs the view tree to *draw* once after it is
        // attached, and the moment an app stops is the moment the screen goes still: nothing
        // animates, nothing invalidates, no traversal runs, and the callback that would have
        // rescued this never comes. Measured — the device sat on its wallpaper colour with no
        // wallpaper, no status bar and no icons until the tab was closed and reopened, while taps
        // still landed on the icons nobody could see.
        //
        // So the draw is asked for rather than waited for. One traversal is all it takes.
        if (!holder.surface.isValid) invalidate()
    }

    /**
     * Taps and long-presses on the launcher, resolved against the very rectangles [VirtualLauncher]
     * drew — so an agent that reads an icon's position out of `screencap` can tap it.
     */
    private fun touchHome(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hit(event.x, event.y)
                pressedAt = event.x to event.y
                if (pressed != null) postDelayed(longPress, LONG_PRESS_MS)
            }

            MotionEvent.ACTION_MOVE ->
                // A drag off the icon is a scroll gesture that missed, not a launch.
                if (pressed != null && hit(event.x, event.y) != pressed) cancelPress()

            MotionEvent.ACTION_UP -> {
                val app = pressed
                cancelPress()
                app?.takeIf { hit(event.x, event.y) == it }?.let(onLaunchApp)
            }

            MotionEvent.ACTION_CANCEL -> cancelPress()
        }
    }

    private fun hit(x: Float, y: Float): VirtualDeviceApp? = home?.let { apps ->
        VirtualLauncher.hit(width, height, resources.displayMetrics.density, apps, x, y)
    }

    private fun cancelPress() {
        pressed = null
        removeCallbacks(longPress)
    }

    fun adopt(surface: SurfaceControlViewHost.SurfacePackage) {
        setChildSurfacePackage(surface)
    }

    /**
     * Redraws the idle screen — wallpaper, the device's name, and whatever is installed. Called when
     * the surface appears, when the installed set changes, and whenever an app leaves the device, so
     * stopping one lands back on a live home screen rather than on whatever it drew last.
     */
    fun paintHome() {
        if (!holder.surface.isValid) return
        val canvas = runCatching { holder.lockCanvas() }.getOrNull() ?: return
        try {
            VirtualLauncher.draw(
                canvas = canvas,
                width = canvas.width,
                height = canvas.height,
                density = resources.displayMetrics.density,
                apps = home.orEmpty(),
            )
        } finally {
            runCatching { holder.unlockCanvasAndPost(canvas) }
        }
    }

    /**
     * The token the window manager parents the guest's input channel to.
     *
     * Non-SDK, and there is no public equivalent before `getInputTransferToken` in API 35. Without
     * it the guest cannot be embedded at all, and there is no other way to run one — a null here is
     * the device reporting that it cannot start the app.
     */
    fun hostToken(): IBinder? = runCatching {
        SurfaceView::class.java.getDeclaredMethod("getHostToken")
            .apply { isAccessible = true }
            .invoke(this) as? IBinder
    }.getOrNull()

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width > 0 && height > 0) onSized(width, height)
    }

    // No `onCheckIsTextEditor`, and that is the point of the device having a keyboard of its own.
    //
    // This view used to answer yes, so the *phone's* IME opened over the tab and typed into a
    // connection that replayed each character into the guest. It worked and it was in the wrong
    // window: the phone's keyboard is JCode's chrome, so `screencap` of the device did not show it,
    // `uiautomator dump` did not list it, and `input tap` could not reach a key — an agent could see
    // a text field and had no way to answer it. See [VirtualKeyboard] for what replaced it.
    //
    // A physical keyboard is unaffected: its keys arrive as key events, which is what the two
    // overrides below forward.

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        forward(keyCode, event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        forward(keyCode, event) || super.onKeyUp(keyCode, event)

    /** BACK deliberately stays with JCode — the toolbar sends the guest its own Back. */
    private fun forward(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return false
        session.key(event)
        return true
    }

    private companion object {
        /** The platform's own long-press threshold, so the launcher feels like every other one. */
        val LONG_PRESS_MS = android.view.ViewConfiguration.getLongPressTimeout().toLong()
    }
}
