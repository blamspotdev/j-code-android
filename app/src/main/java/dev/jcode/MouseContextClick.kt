package dev.jcode

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration

/**
 * Desktop-style right-click for the whole workbench.
 *
 * Android turns a secondary mouse button that nothing consumes into a Back key press, so with a mouse
 * attached every right-click navigated backwards instead of opening a menu. This swallows the whole
 * secondary-button gesture and replays it as a long-press at the same point: every surface that
 * already has a long-press menu — editor, terminal, tabs, explorer rows, extension chips — gets the
 * desktop behaviour without opting in, and a surface with no menu simply does nothing.
 *
 * Owned by the Activity, which is the only place that sees pointer events regardless of which pane
 * holds focus.
 */
internal class MouseContextClick(private val dispatch: (MotionEvent) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())

    /** Uptime of the last secondary-button press, used to drop a Back key the system emits anyway. */
    private var pressedAt = 0L

    /** Set while the real gesture that began on the secondary button is being swallowed. */
    private var swallowing = false

    /** The not-yet-sent release of the synthetic long-press, so a second right-click can flush it. */
    private var pendingRelease: Runnable? = null

    /** Pointer events. Returns true when the event belongs to a right-click and must not go further. */
    fun onTouchEvent(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Only a press that STARTS on the secondary button alone; right-clicking mid-drag with
                // the left button held stays an ordinary drag.
                if (event.buttonState != MotionEvent.BUTTON_SECONDARY) return false
                swallowing = true
                pressedAt = SystemClock.uptimeMillis()
                beginLongPress(event.x, event.y)
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val consumed = swallowing
                swallowing = false
                consumed
            }
            else -> swallowing
        }
    }

    /**
     * Button press/release, which arrive on the generic-motion path. Consuming them is what stops the
     * system from synthesizing Back in the first place.
     */
    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action != MotionEvent.ACTION_BUTTON_PRESS && action != MotionEvent.ACTION_BUTTON_RELEASE) {
            return false
        }
        if (event.actionButton != MotionEvent.BUTTON_SECONDARY) return false
        if (action == MotionEvent.ACTION_BUTTON_PRESS) pressedAt = SystemClock.uptimeMillis()
        return true
    }

    /**
     * Backstop for devices that emit the Back key anyway: drop only the one that lands right after a
     * right-click. The real Back button and the system back gesture are untouched.
     */
    fun shouldSwallowBack(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.KEYCODE_BACK &&
            pressedAt != 0L &&
            SystemClock.uptimeMillis() - pressedAt <= BACK_SUPPRESS_MS

    /** Press now, release just past the long-press threshold, so gesture detectors report a long press. */
    private fun beginLongPress(x: Float, y: Float) {
        pendingRelease?.let { handler.removeCallbacks(it); it.run() }
        val downTime = SystemClock.uptimeMillis()
        send(downTime, downTime, MotionEvent.ACTION_DOWN, x, y)
        val release = Runnable {
            pendingRelease = null
            send(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)
        }
        pendingRelease = release
        handler.postDelayed(release, ViewConfiguration.getLongPressTimeout().toLong() + LONG_PRESS_MARGIN_MS)
    }

    private fun send(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        // Replayed as a touch: a mouse-sourced replay would just re-enter the branch above, and touch
        // is what the long-press paths across the app are written against.
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        dispatch(event)
        event.recycle()
    }

    private companion object {
        const val BACK_SUPPRESS_MS = 400L
        const val LONG_PRESS_MARGIN_MS = 120L
    }
}
