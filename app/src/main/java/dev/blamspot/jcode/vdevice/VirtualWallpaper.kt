package dev.blamspot.jcode.vdevice

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.withTranslation
import kotlin.math.min

/**
 * What JCode's virtual device shows when nothing is running on it.
 *
 * A device with its screen on and no app on it should look like a device, not like a failure, so the
 * idle screen is a wallpaper rather than the black a dead surface gives back. Three outlined
 * shapes — a square, a triangle and a circle — over dark grey: unmistakably a placeholder, quiet
 * enough to put a launcher on top of, and drawn thin so the app icons stay the brightest thing on
 * the screen.
 *
 * It is drawn rather than shipped as a drawable because the same picture has to reach two places
 * that cannot share one: the tab paints it onto the `SurfaceView`'s own surface (nothing composed
 * *behind* a SurfaceView is ever visible — it punches a hole in the window), and [VirtualScreen]
 * paints it into a bitmap so `adb shell screencap` answers with the screen the user is looking at.
 *
 * The composition is deterministic and laid out in fractions of the screen, so it scales to any tab
 * size and two captures of an idle device compare equal.
 */
internal object VirtualWallpaper {

    /**
     * The device's screen colour with nothing on it.
     *
     * [VirtualPalette.SURFACE] rather than [VirtualPalette.BACKGROUND], and the step up is the
     * point: the status bar *is* the ground colour at 80%, so a wallpaper painted the ground too
     * would have the bar disappear into the home screen behind it. The wallpaper is the thing the
     * bar sits on, so it is one tone above it.
     */
    const val BACKGROUND = VirtualPalette.SURFACE

    private const val LINE = 0xFFC3CBD8.toInt()

    /** Centre x, centre y (fractions of the screen), size (fraction of the shorter side), rotation, alpha. */
    private class Figure(
        val x: Float,
        val y: Float,
        val size: Float,
        val turn: Float,
        val alpha: Int,
        val draw: (Path, Float) -> Unit,
    )

    // Kept off the middle band on purpose: the launcher's grid sits there, and wallpaper that runs
    // under text is just noise. Each large shape has a small echo of a different kind near it.
    private val FIGURES = listOf(
        Figure(0.82f, 0.17f, 0.52f, 0f, 40, ::circle),
        Figure(0.20f, 0.80f, 0.38f, -12f, 36, ::square),
        Figure(0.74f, 0.83f, 0.34f, 8f, 30, ::triangle),
        Figure(0.15f, 0.20f, 0.15f, 18f, 26, ::square),
        Figure(0.90f, 0.55f, 0.12f, 0f, 22, ::circle),
        Figure(0.08f, 0.55f, 0.13f, -20f, 22, ::triangle),
    )

    fun draw(canvas: Canvas, width: Int, height: Int) {
        canvas.drawColor(BACKGROUND)
        if (width <= 0 || height <= 0) return

        val unit = min(width, height).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (unit * 0.005f).coerceAtLeast(1.5f)
            strokeJoin = Paint.Join.ROUND
            color = LINE
        }
        val path = Path()

        FIGURES.forEach { figure ->
            path.rewind()
            figure.draw(path, unit * figure.size / 2f)
            paint.alpha = figure.alpha
            canvas.withTranslation(figure.x * width, figure.y * height) {
                rotate(figure.turn)
                drawPath(path, paint)
            }
        }
    }

    private fun circle(path: Path, radius: Float) {
        path.addCircle(0f, 0f, radius, Path.Direction.CW)
    }

    private fun square(path: Path, half: Float) {
        path.addRect(-half, -half, half, half, Path.Direction.CW)
    }

    /** Equilateral, point up, centred on its centroid so rotation looks intended. */
    private fun triangle(path: Path, half: Float) {
        val height = half * 1.732f
        path.moveTo(0f, -height * 2f / 3f)
        path.lineTo(half, height / 3f)
        path.lineTo(-half, height / 3f)
        path.close()
    }
}
