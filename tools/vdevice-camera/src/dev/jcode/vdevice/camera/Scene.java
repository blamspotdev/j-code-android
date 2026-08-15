package dev.jcode.vdevice.camera;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.util.Locale;

/**
 * What the virtual device's camera sees.
 *
 * <p>Drawn, and drawn to look drawn: colour bars an app can check it decoded, a horizon that rolls
 * and pitches with the device's attitude, a compass rose on its heading, and a frame counter. Two
 * properties come out of that and both are the point — nothing here could be mistaken for a
 * photograph of a room, which is what a camera quietly handing over <em>something</em> would invite;
 * and it agrees with the rest of the device, because the attitude it is drawn from is read from the
 * same sensors any other app reads. Turn the device on the hardware bench and the picture turns.
 *
 * <p>The whole scene is a function of its arguments, so the viewfinder and the still captured from
 * it are one renderer evaluated at two moments and two sizes rather than two renderers that have to
 * be kept in step.
 */
final class Scene {

    private static final int[] BARS = {
        0xFFC0C0C0, 0xFFC0C000, 0xFF00C0C0, 0xFF00C000,
        0xFFC000C0, 0xFFC00000, 0xFF0000C0, 0xFF101010,
    };

    /** The pitch, in degrees, that moves the horizon a full half-frame. */
    private static final float MAX_PITCH = 90f;

    /** Nominal frame period for the counter drawn on the picture — 30 fps. */
    private static final long FRAME_MS = 33L;

    /** How wide one monospace character is as a fraction of its size, plus a margin's worth. */
    private static final float MONOSPACE_ADVANCE = 0.65f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    Scene() {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(Color.WHITE);
        text.setColor(Color.WHITE);
        text.setTypeface(Typeface.MONOSPACE);
    }

    /**
     * Draws one frame at {@code width}×{@code height}.
     *
     * @param azimuth heading in degrees, 0 at north
     * @param pitch   nose-up positive, in degrees
     * @param roll    right-side-down positive, in degrees
     * @param frameAt the clock reading to stamp the frame with
     */
    void draw(Canvas canvas, int width, int height, float azimuth, float pitch, float roll, long frameAt) {
        float unit = height / 24f;
        bars(canvas, width, height);
        horizon(canvas, width, height, pitch, roll);
        compass(canvas, width * 0.5f, height - unit * 4.6f, unit * 1.6f, azimuth);

        String status = String.format(
            Locale.US,
            "frame %06d   hdg %05.1f   pitch %+05.1f   roll %+05.1f",
            frameAt / FRAME_MS, azimuth, pitch, roll);

        text.setTextSize(unit);
        canvas.drawText("JCODE VIRTUAL CAMERA", unit, unit * 1.6f, text);
        // Sized against the *width*, not the height. A viewfinder is the shape of the window and a
        // still is the shape of a sensor, so a size derived from the height alone runs a
        // fixed-width line off the right edge of the taller one.
        text.setTextSize(Math.min(unit, width / (status.length() * MONOSPACE_ADVANCE)));
        canvas.drawText(status, unit, height - unit, text);
    }

    private void bars(Canvas canvas, int width, int height) {
        float barWidth = (float) width / BARS.length;
        for (int i = 0; i < BARS.length; i++) {
            fill.setColor(BARS[i]);
            canvas.drawRect(i * barWidth, 0f, (i + 1) * barWidth, height, fill);
        }
    }

    /**
     * A horizon that rolls and pitches with the device.
     *
     * <p>This is the part that makes the camera and the motion sensors one device rather than two:
     * tilt the bench and the picture tilts, which is what an app doing horizon detection or AR would
     * expect and what nothing on this device could previously show.
     */
    private void horizon(Canvas canvas, int width, int height, float pitch, float roll) {
        double radians = Math.toRadians(roll);
        float centre = height / 2f + pitch / MAX_PITCH * height / 2f;
        float dx = (float) Math.cos(radians) * width;
        float dy = (float) Math.sin(radians) * width;
        stroke.setStrokeWidth(height * 0.006f);
        canvas.drawLine(width / 2f - dx, centre - dy, width / 2f + dx, centre + dy, stroke);
    }

    /** A rose pointing the way the device's compass says it is facing. */
    private void compass(Canvas canvas, float cx, float cy, float radius, float azimuth) {
        stroke.setStrokeWidth(radius * 0.08f);
        canvas.drawCircle(cx, cy, radius, stroke);
        double angle = Math.toRadians(azimuth - 90.0);
        canvas.drawLine(cx, cy,
            cx + (float) Math.cos(angle) * radius,
            cy + (float) Math.sin(angle) * radius, stroke);
        text.setTextSize(radius * 0.7f);
        canvas.drawText("N", cx - radius * 0.25f, cy - radius * 1.2f, text);
    }
}
