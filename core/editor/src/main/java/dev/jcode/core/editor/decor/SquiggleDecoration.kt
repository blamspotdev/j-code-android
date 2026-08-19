package dev.jcode.core.editor.decor

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/**
 * Diagnostic severity levels for squiggly decorations.
 */
enum class DiagnosticSeverity(val color: Int) {
    ERROR(0xFFFF5555.toInt()),
    WARNING(0xFFE6C35C.toInt()),
    INFO(0xFF56B6C2.toInt()),
    HINT(0xFF98C379.toInt()),
}

/**
 * A squiggly underline decoration for diagnostics (errors, warnings, etc.).
 * Drawn beneath the text at the SQUIGGLY layer.
 */
data class SquiggleDecoration(
    override val id: String,
    /** Start byte offset */
    val startByte: Int,
    /** End byte offset (exclusive) */
    val endByte: Int,
    /** Severity level (determines color) */
    val severity: DiagnosticSeverity,
    /** Optional diagnostic message */
    val message: String? = null,
    /** Optional diagnostic code */
    val code: String? = null,
    /** Optional source (e.g., "lsp", "tree-sitter") */
    val source: String? = null,
) : Decoration {

    override fun zIndex(): Int = Layer.SQUIGGLY

    companion object {
        /**
         * Draw a squiggly line from startX to endX at the given Y position.
         */
        fun drawSquiggle(canvas: Canvas, paint: Paint, startX: Float, endX: Float, y: Float) {
            val path = Path()
            val amplitude = 2f
            val wavelength = 6f

            path.moveTo(startX, y)
            var x = startX
            var up = true
            while (x < endX) {
                val nextX = (x + wavelength).coerceAtMost(endX)
                val midX = (x + nextX) / 2
                val midY = if (up) y - amplitude else y + amplitude
                path.quadTo(midX, midY, nextX, y)
                x = nextX
                up = !up
            }

            canvas.drawPath(path, paint)
        }
    }
}

/**
 * A background highlight decoration (e.g., for the current line, search results).
 */
data class BackgroundDecoration(
    override val id: String,
    /** Start byte offset */
    val startByte: Int,
    /** End byte offset (exclusive) */
    val endByte: Int,
    /** Background color (ARGB) */
    val color: Int,
    /** Optional border radius */
    val cornerRadius: Float = 0f,
) : Decoration {

    override fun zIndex(): Int = Layer.BACKGROUND
}
