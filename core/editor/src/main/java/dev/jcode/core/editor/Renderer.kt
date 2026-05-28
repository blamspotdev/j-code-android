package dev.jcode.core.editor

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.util.LruCache
import dev.jcode.core.buffer.Snapshot
import java.nio.charset.StandardCharsets

/**
 * Key for the line shape cache.
 */
private data class LineShapeKey(
    val lineContent: String,
    val fontSize: Float,
    val tabWidth: Int,
)

/**
 * Pre-shaped line data for rendering.
 */
private data class ShapedLine(
    val text: String,
    val widthPx: Float,
    val tabOffsets: List<Float> = emptyList(),
)

/**
 * Renderer for the EditorView. Handles visible line computation, shaping,
 * and layered drawing (selection, glyphs, carets, gutter).
 */
class Renderer(
    private val typeface: Typeface,
) {
    private val lineShapeCache = LruCache<LineShapeKey, ShapedLine>(5000)

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        isSubpixelText = true
        this.typeface = this@Renderer.typeface
        color = 0xFFCDD6F4.toInt()
    }

    private val lineNumberPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        isSubpixelText = true
        this.typeface = this@Renderer.typeface
        textSize = textPaint.textSize * 0.85f
        textAlign = Paint.Align.RIGHT
        color = 0xFF6C7086.toInt()
    }

    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40585B76.toInt()
    }

    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF5E0DC.toInt()
        strokeWidth = 2f
    }

    private val gutterBgPaint = Paint().apply {
        color = 0xFF181825.toInt()
    }

    private val gutterLinePaint = Paint().apply {
        color = 0xFF313244.toInt()
        strokeWidth = 1f
    }

    fun draw(canvas: Canvas, snapshot: Snapshot, viewport: Viewport, config: RenderConfig, carets: List<Caret>) {
        // Configure paint for current config
        textPaint.textSize = config.fontSizeSp
        lineNumberPaint.textSize = config.fontSizeSp * 0.85f
        val lineHeightPx = (config.fontSizeSp * config.lineHeightMultiplier).toInt().coerceAtLeast(1)

        val visibleTop = viewport.visibleLineTop
        val visibleBottom = snapshot.lineCount.coerceAtMost(viewport.visibleLineBottom + 1)

        val gutterWidth = computeGutterWidth(snapshot, config)

        // Draw gutter background
        canvas.drawRect(0f, 0f, gutterWidth.toFloat(), viewport.heightPx.toFloat(), gutterBgPaint)

        // Draw selection rects
        for (caret in carets) {
            if (caret.isSelection) {
                val startLine = snapshot.offsetToLineColumn(caret.start).first
                val endLine = snapshot.offsetToLineColumn(caret.end).first
                for (line in startLine..endLine) {
                    if (line in visibleTop until visibleBottom) {
                        val (lineStart, lineEnd) = snapshot.lineAt(line)
                        val selStart = if (line == startLine) (caret.start - lineStart).coerceAtLeast(0) else 0
                        val selEnd = if (line == endLine) (caret.end - lineStart).coerceAtMost(lineEnd - lineStart) else (lineEnd - lineStart)
                        if (selStart < selEnd) {
                            val lineText = snapshot.readRangeAsUtf16(lineStart, lineEnd)
                            val xStart = gutterWidth + measureTextWidth(lineText.substring(0, selEnd), config)
                            val xEnd = gutterWidth + measureTextWidth(lineText.substring(0, selStart), config)
                            val y = (line - visibleTop) * lineHeightPx + viewport.scrollY % lineHeightPx
                            canvas.drawRect(xEnd.toFloat(), y.toFloat(), xStart.toFloat(), (y + lineHeightPx).toFloat(), selectionPaint)
                        }
                    }
                }
            }
        }

        // Draw lines
        for (line in visibleTop until visibleBottom) {
            if (line >= snapshot.lineCount) break

            val y = (line - visibleTop) * lineHeightPx + viewport.scrollY % lineHeightPx
            val (lineStart, lineEnd) = snapshot.lineAt(line)
            val lineText = snapshot.readRangeAsUtf16(lineStart, lineEnd)

            // Draw line number
            lineNumberPaint.color = if (carets.any { snapshot.offsetToLineColumn(it.head).first == line }) {
                0xFFCDD6F4.toInt()
            } else {
                0xFF6C7086.toInt()
            }
            canvas.drawText(
                "${line + 1}",
                gutterWidth - 12f,
                y + lineHeightPx * 0.7f,
                lineNumberPaint,
            )

            // Draw line text
            val shaped = getShapedLine(lineText, config)
            textPaint.color = 0xFFCDD6F4.toInt()
            canvas.drawText(
                shaped.text,
                gutterWidth + 8f,
                y + lineHeightPx * 0.7f,
                textPaint,
            )
        }

        // Draw cursors
        for (caret in carets) {
            val (line, col) = snapshot.offsetToLineColumn(caret.head)
            if (line in visibleTop until visibleBottom) {
                val (lineStart, lineEnd) = snapshot.lineAt(line)
                val lineText = snapshot.readRangeAsUtf16(lineStart, lineEnd)
                val visibleText = lineText.substring(0, col.coerceAtMost(lineText.length))
                val x = gutterWidth + 8f + measureTextWidth(visibleText, config)
                val y = (line - visibleTop) * lineHeightPx + viewport.scrollY % lineHeightPx
                canvas.drawLine(x, y.toFloat(), x, (y + lineHeightPx).toFloat(), cursorPaint)
            }
        }
    }

    private fun computeGutterWidth(snapshot: Snapshot, config: RenderConfig): Int {
        val lineCount = snapshot.lineCount
        val digits = lineCount.toString().length.coerceAtLeast(3)
        val sample = "9".repeat(digits)
        return (measureTextWidth(sample, config) + 24).toInt()
    }

    private fun getShapedLine(text: String, config: RenderConfig): ShapedLine {
        val key = LineShapeKey(text, config.fontSizeSp, config.tabWidth)
        return lineShapeCache.get(key) ?: run {
            val width = measureTextWidth(text, config)
            ShapedLine(text, width).also { lineShapeCache.put(key, it) }
        }
    }

    private fun measureTextWidth(text: String, config: RenderConfig): Float {
        if (text.isEmpty()) return 0f
        return textPaint.measureText(text)
    }
}
