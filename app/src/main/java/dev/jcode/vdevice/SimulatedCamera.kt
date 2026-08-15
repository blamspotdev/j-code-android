package dev.jcode.vdevice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * What the virtual device's camera sees.
 *
 * The device has never had one that produced anything: `Simulated` gave a guest the camera *feature*
 * and the `CAMERA` permission and then no frame ever arrived, which is enough for an app to decide it
 * has a camera and not enough for it to do anything with one. This is the frame.
 *
 * ### It is drawn, and it is drawn to look drawn
 *
 * A scene rendered from the device's own simulated state, not a photograph and not pretending to be
 * one: colour bars an app can check it decoded, a horizon that rolls and pitches with the attitude on
 * the hardware bench, a compass rose on the heading the sensors are reporting, and the frame's own
 * clock. Two properties fall out of that, and both are the point:
 *
 *  - **An app can tell it is a test image.** Nothing here could be mistaken for a picture of a room,
 *    which is what a camera that quietly hands over *something* would invite.
 *  - **It agrees with the other hardware.** The heading in the corner is
 *    [SimulatedHardware.sample]'s, so an app reading the compass and the camera together is being
 *    told one consistent story — turn the device on the bench and the horizon turns.
 *
 * ### A function of time, like everything else on the bench
 *
 * [draw] takes the clock reading it should render, so the viewfinder and a still captured from it are
 * the same function evaluated at two moments rather than two renderers that have to be kept in step.
 * `SystemClock.elapsedRealtime` counts from the same boot in every process, so this holds across
 * `:guest` and the IDE for free — the reasoning is [SimulatedHardware]'s and this follows it.
 */
internal object SimulatedCamera {

    /** What a still comes out at. A 4:3 sensor, because that is what a phone's back camera is. */
    const val STILL_WIDTH = 1440
    const val STILL_HEIGHT = 1080

    /** Where a captured photo lands, so `adb pull` can fetch it and a gallery app can find it. */
    private const val PICTURES = "DCIM/Camera"

    private val bars = intArrayOf(
        0xFFC0C0C0.toInt(), 0xFFC0C000.toInt(), 0xFF00C0C0.toInt(), 0xFF00C000.toInt(),
        0xFFC000C0.toInt(), 0xFFC00000.toInt(), 0xFF0000C0.toInt(), 0xFF101010.toInt(),
    )

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = android.graphics.Typeface.MONOSPACE
    }

    /**
     * Draws one frame onto [canvas] at [width]×[height].
     *
     * [sample] is the device's current simulated state; passing it in rather than reading it here is
     * what lets a caller draw the frame it *captured* rather than the frame that is current by the
     * time the JPEG is written.
     */
    @Synchronized
    fun draw(canvas: Canvas, width: Int, height: Int, sample: HardwareSample, nowElapsed: Long) {
        val unit = height / 24f
        // `orientation[0]`, not `bearing`. The two are different readings and only one of them is a
        // compass: `bearing` is the direction a *route* is travelling, which is 0 for a device that
        // is standing still, so a camera drawn from it reported north however the device was turned.
        val heading = sample.orientation.getOrElse(0) { 0f }
        drawBars(canvas, width, height)
        drawHorizon(canvas, width, height, sample)
        drawCompass(canvas, width * 0.5f, height - unit * 4.6f, unit * 1.6f, heading)

        // A frame counter rather than a wall clock: a wall clock in a test image is a date that
        // will read as stale, and what an app needs to know is that the frame changed.
        val status = String.format(
            Locale.US,
            "frame %06d   hdg %05.1f   pitch %+05.1f   roll %+05.1f",
            nowElapsed / FRAME_MS,
            heading,
            sample.orientation.getOrElse(1) { 0f },
            sample.orientation.getOrElse(2) { 0f },
        )
        text.textSize = unit
        canvas.drawText("JCODE VIRTUAL CAMERA", unit, unit * 1.6f, text)
        // Sized against the *width*, not the height. The frame's aspect ratio is whatever window it
        // is drawn into — the viewfinder is the shape of the tab and a still is the shape of a
        // sensor — so a size derived from the height alone runs a fixed-width line off the right
        // edge of the taller one, which is where "roll" lost its last digit.
        text.textSize = minOf(unit, width / (status.length * MONOSPACE_ADVANCE))
        canvas.drawText(status, unit, height - unit, text)
    }

    /** The one frame both the viewfinder and a capture come from, as a bitmap. */
    fun frame(context: Context, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val now = SystemClock.elapsedRealtime()
        draw(Canvas(bitmap), width, height, SimulatedHardware.sample(context), now)
        return bitmap
    }

    /**
     * Takes a still and stores it on the device, answering where it went.
     *
     * It goes to `DCIM/Camera` whether or not the app asked for a copy of its own, for the reason a
     * phone does the same: the picture the person just took should be somewhere they can find it,
     * and on this device "somewhere" is a path `adb pull` takes.
     */
    fun capture(context: Context): File {
        val bitmap = frame(context, STILL_WIDTH, STILL_HEIGHT)
        val directory = File(VirtualStorage.root(context), PICTURES).apply { mkdirs() }
        val file = File(directory, "IMG_%d.jpg".format(SystemClock.elapsedRealtime()))
        try {
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    private fun drawBars(canvas: Canvas, width: Int, height: Int) {
        val barWidth = width.toFloat() / bars.size
        bars.forEachIndexed { index, colour ->
            fill.color = colour
            canvas.drawRect(index * barWidth, 0f, (index + 1) * barWidth, height.toFloat(), fill)
        }
    }

    /**
     * A horizon line that rolls and pitches with the device's attitude.
     *
     * This is the part that makes the camera and the motion sensors one device rather than two: tilt
     * the bench and the picture tilts, which is what an app doing horizon detection or AR would
     * expect and what nothing on this device could previously show.
     */
    private fun drawHorizon(canvas: Canvas, width: Int, height: Int, sample: HardwareSample) {
        val roll = Math.toRadians(sample.orientation.getOrElse(2) { 0f }.toDouble())
        val pitch = sample.orientation.getOrElse(1) { 0f }
        val centre = height / 2f + pitch / MAX_PITCH * height / 2f
        val reach = width.toFloat()
        val dx = cos(roll).toFloat() * reach
        val dy = sin(roll).toFloat() * reach

        canvas.save()
        stroke.strokeWidth = height * 0.006f
        stroke.color = Color.WHITE
        canvas.drawLine(
            width / 2f - dx,
            centre - dy,
            width / 2f + dx,
            centre + dy,
            stroke,
        )
        canvas.restore()
    }

    /** A rose pointing the way the device's compass says it is facing. */
    private fun drawCompass(canvas: Canvas, cx: Float, cy: Float, radius: Float, bearing: Float) {
        stroke.color = Color.WHITE
        stroke.strokeWidth = radius * 0.08f
        canvas.drawCircle(cx, cy, radius, stroke)
        val angle = Math.toRadians(bearing.toDouble() - 90.0)
        fill.color = Color.WHITE
        canvas.drawLine(
            cx,
            cy,
            cx + cos(angle).toFloat() * radius,
            cy + sin(angle).toFloat() * radius,
            stroke,
        )
        text.textSize = radius * 0.7f
        canvas.drawText("N", cx - radius * 0.25f, cy - radius * 1.2f, text)
    }

    /** The pitch, in degrees, that moves the horizon a full half-frame. */
    private const val MAX_PITCH = 90f

    /** Nominal frame period for the counter drawn on the picture — 30 fps. */
    private const val FRAME_MS = 33L

    /** How wide one monospace character is as a fraction of its size, plus a margin's worth. */
    private const val MONOSPACE_ADVANCE = 0.65f

    private const val JPEG_QUALITY = 90
}
