package dev.jcode.vdevice

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.roundToLong
import kotlinx.coroutines.delay

/**
 * `adb shell input` for J Code's virtual device.
 *
 * The tab already relays real `MotionEvent`s and `KeyEvent`s to the guest, so driving the device
 * from a terminal is a matter of *making* the right events rather than finding a way in: these are
 * built exactly as a touchscreen's would be — real down/move/up streams sharing one down time, a
 * `SOURCE_TOUCHSCREEN` device — and handed to the same [AppSandboxSession] calls a finger goes
 * through. An app cannot tell the two apart, which is the point: what an agent verifies this way is
 * what a person would see.
 *
 * Coordinates are the device's screen, the same ones `screencap` and `uiautomator dump` report.
 */
internal object VirtualInput {

    /** A drag with no duration still has to move over time, or a view sees a teleport and no fling. */
    private const val DEFAULT_SWIPE_MS = 300L

    /** ~60 Hz. Fewer samples and a fling's velocity tracker has nothing to fit a curve to. */
    private const val SWIPE_STEP_MS = 16L

    suspend fun tap(session: AppSandboxSession, x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        session.send(down, down, MotionEvent.ACTION_DOWN, x, y)
        // A real tap is not instantaneous, and a view that measures the gap treats a zero-length one
        // as a stray event rather than a press.
        delay(SWIPE_STEP_MS)
        session.send(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)
    }

    suspend fun swipe(
        session: AppSandboxSession,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long? = null,
    ) {
        val duration = (durationMs ?: DEFAULT_SWIPE_MS).coerceAtLeast(SWIPE_STEP_MS)
        val steps = (duration / SWIPE_STEP_MS).toInt().coerceAtLeast(1)
        val down = SystemClock.uptimeMillis()
        session.send(down, down, MotionEvent.ACTION_DOWN, fromX, fromY)
        for (step in 1..steps) {
            delay(SWIPE_STEP_MS)
            val at = step.toFloat() / steps
            session.send(
                downTime = down,
                eventTime = down + (duration * at).roundToLong(),
                action = MotionEvent.ACTION_MOVE,
                x = fromX + (toX - fromX) * at,
                y = fromY + (toY - fromY) * at,
            )
        }
        session.send(down, down + duration, MotionEvent.ACTION_UP, toX, toY)
    }

    fun key(session: AppSandboxSession, keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        session.key(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        session.key(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    /**
     * What `input keyevent` accepts: a bare number, a name, or a name with the `KEYCODE_` prefix
     * already on it. Null for anything the platform does not know.
     */
    fun keyCode(name: String): Int? {
        name.toIntOrNull()?.let { return it.takeIf { code -> code > 0 } }
        val qualified = if (name.startsWith("KEYCODE_")) name else "KEYCODE_${name.uppercase()}"
        return KeyEvent.keyCodeFromString(qualified).takeIf { it != KeyEvent.KEYCODE_UNKNOWN }
    }

    /**
     * `touch` is `oneway` but writes its parcel inside the call, so the event can be returned to the
     * pool as soon as it returns — which a swipe, at one event per frame, needs it to be.
     */
    private fun AppSandboxSession.send(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            touch(event)
        } finally {
            event.recycle()
        }
    }
}
