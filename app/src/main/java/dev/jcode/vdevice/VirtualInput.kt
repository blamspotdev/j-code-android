package dev.jcode.vdevice

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.roundToLong
import kotlinx.coroutines.delay

/**
 * `adb shell input` for JCode's virtual device.
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

    /**
     * Back is not a key the guest is handed; it is a decision about the device.
     *
     * On a phone the key never reaches the app either — the window manager reads it and calls
     * `onBackPressed`. Here the equivalent knowledge lives in [EmbeddedGuest.back], which closes the
     * shade first, then any open dialog, then pops the activity stack. Dispatching KEYCODE_BACK to a
     * view instead does *nothing*: `View.dispatchKeyEvent` on a decor view never reaches
     * `Activity.onBackPressed`, so `adb shell input keyevent 4` was silently a no-op and there was no
     * way to leave a second screen from a terminal. [AppSandboxSurfaceView.forward] already refuses
     * to relay this key for the same reason; the tab's own Back button has always called
     * [AppSandboxSession.back].
     */
    fun key(session: AppSandboxSession, keyCode: Int) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            session.back()
            return
        }
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
     * The same gesture, measured from the **device's** screen instead of the phone's.
     *
     * A `MotionEvent` carries two positions: `getX`/`getY`, which every `ViewGroup` shifts on the way
     * down the tree, and `getRawX`/`getRawY`, which nothing shifts because they are where the finger
     * landed on the *display*. Relaying an event into the tab leaves the second one alone — so a guest
     * that reads it is told the tab's offset down JCode's window, and the device's screen appears to
     * start a couple of hundred pixels above the top of itself.
     *
     * That is not a corner case. It is what GameActivity hands native code
     * (`GameActivityPointerAxes.rawX`), what SDL reads, and what any app doing its own hit-testing
     * against a full-bleed surface uses — all of them correct on a phone, where a full-screen window
     * starts at the origin and the two positions are the same number. Measured on WaveRepo: every tap
     * arrived 258 px below the control it was aimed at, so the app rendered perfectly and answered
     * nothing. Nothing in the log said so, because from the app's side nothing went wrong.
     *
     * So the event is rebuilt from its **local** coordinates, which makes them its raw ones too: the
     * device's screen is the guest's window, exactly as a phone's screen is a full-screen app's. The
     * batched samples come with it — a velocity tracker with one sample per event fits no curve, and
     * flings would die.
     *
     * Returns the event itself when the two already agree, which is every event
     * [VirtualInput] synthesises and every one a full-screen guest gets.
     */
    fun inDeviceSpace(event: MotionEvent): MotionEvent {
        if (event.rawX == event.x && event.rawY == event.y) return event
        val count = event.pointerCount
        val properties = Array(count) { index ->
            MotionEvent.PointerProperties().also { event.getPointerProperties(index, it) }
        }
        val history = event.historySize
        val rebuilt = MotionEvent.obtain(
            event.downTime,
            if (history > 0) event.getHistoricalEventTime(0) else event.eventTime,
            event.action,
            count,
            properties,
            coordsOf(event, if (history > 0) 0 else CURRENT, count),
            event.metaState,
            event.buttonState,
            event.xPrecision,
            event.yPrecision,
            event.deviceId,
            event.edgeFlags,
            event.source,
            event.flags,
        )
        for (sample in 1 until history) {
            rebuilt.addBatch(
                event.getHistoricalEventTime(sample),
                coordsOf(event, sample, count),
                event.metaState,
            )
        }
        if (history > 0) rebuilt.addBatch(event.eventTime, coordsOf(event, CURRENT, count), event.metaState)
        return rebuilt
    }

    /**
     * One sample's coordinates for every pointer, already in the event's *local* space —
     * `getPointerCoords` reports what `getX`/`getY` do, which is the whole point of reading them
     * here. [sample] is [CURRENT] for the event's own position, or an index into its history.
     */
    private fun coordsOf(event: MotionEvent, sample: Int, count: Int): Array<MotionEvent.PointerCoords> =
        Array(count) { index ->
            MotionEvent.PointerCoords().also {
                if (sample == CURRENT) {
                    event.getPointerCoords(index, it)
                } else {
                    event.getHistoricalPointerCoords(index, sample, it)
                }
            }
        }

    private const val CURRENT = -1

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
