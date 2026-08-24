package dev.blamspot.jcode.vdevice

import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import java.util.WeakHashMap

/**
 * Makes a guest's own `SurfaceView` visible inside the tab.
 *
 * A `SurfaceView` does not paint. Its pixels live in a separate `SurfaceControl` which, by default,
 * sits **below** the window, and what makes it visible on a phone is the window punching a
 * transparent hole where the view is. A windowless `SurfaceControlViewHost` does not honour that
 * hole, so the guest's own opaque window background is drawn straight over the surface and the app
 * renders solid black while behaving perfectly correctly in every other respect.
 *
 * Measured on ES-DE: `org.libsdl.app.SDLSurface` present, focused, correctly sized at
 * `[0,50][1080,1560]`, SDL initialised and reading the device's identity — and nothing on screen.
 * That is the whole SDL / Unity / emulator-frontend family, which is a large share of what anyone
 * would want to try on a device like this.
 *
 * `setZOrderOnTop` moves the surface above the window instead, where nothing can paint over it.
 *
 * ### Why only the full-bleed ones
 *
 * Raising *every* `SurfaceView` would be wrong. A video player puts one behind its controls on
 * purpose, and lifting it above the window would hide the controls — trading a black screen for an
 * unusable one. So only a surface that already covers essentially the whole guest is raised, which
 * is exactly the case where there is nothing meant to be drawn over it, and exactly the case that is
 * black today.
 *
 * The cost, stated plainly: a raised surface is above *everything* in the host's own layer, so it
 * covers the device's status bar too — the same thing a full-screen game does to a phone's.
 */
internal object GuestSurfaces {

    /** A surface must cover this much of the guest before it counts as the guest's whole screen. */
    private const val FULL_BLEED = 0.9f

    private val raised = WeakHashMap<SurfaceView, Boolean>()

    /**
     * True while the device is showing something of its own over the guest, so the raised surfaces
     * belong back under the window — see [setCovered].
     */
    private var covered = false

    /**
     * Raises any full-bleed `SurfaceView` under [root]. Idempotent, and cheap enough to call on
     * every layout pass: a view is looked at once and then remembered.
     */
    fun raiseFullBleed(root: View) {
        val area = root.width.toLong() * root.height
        if (area <= 0L) return
        forEachSurface(root) { surface ->
            if (raised.containsKey(surface)) return@forEachSurface
            val painted = surface.width.toLong() * surface.height
            if (painted < area * FULL_BLEED) return@forEachSurface
            raised[surface] = true
            runCatching {
                surface.setZOrderOnTop(!covered)
                Log.i(TAG, "raised ${surface.javaClass.name} above the window so it can be seen")
            }.onFailure { Log.w(TAG, "cannot raise ${surface.javaClass.name}", it) }
        }
    }

    /**
     * Puts the raised surfaces back **below** the window while the device has something of its own
     * to show over the guest, and above it again afterwards.
     *
     * This is the cost of raising them, arriving: a surface above the window is above *everything*
     * in the host's layer, so the device's own file picker was added, laid out, and drew a complete
     * screen underneath the game — which still had the touches, since Z-order decides what is seen
     * and the view tree decides what is touched. Measured on WaveRepo: the picker was there and
     * invisible, which is the worst of the three possible outcomes.
     *
     * Lowering rather than trying to out-rank it is the honest fix. The guest's own window
     * background then covers the surface, which is what a phone shows too when a picker opens over
     * a game: the game is not on the screen any more.
     */
    fun setCovered(value: Boolean) {
        if (covered == value) return
        covered = value
        raised.keys.toList().forEach { surface ->
            runCatching { surface.setZOrderOnTop(!value) }
                .onFailure { Log.w(TAG, "cannot re-order ${surface.javaClass.name}", it) }
        }
    }

    private fun forEachSurface(view: View, action: (SurfaceView) -> Unit) {
        if (view is SurfaceView) {
            action(view)
            return
        }
        if (view !is ViewGroup) return
        for (index in 0 until view.childCount) {
            forEachSurface(view.getChildAt(index), action)
        }
    }
}
