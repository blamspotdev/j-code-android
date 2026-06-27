package dev.jcode.core.editor

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import dev.jcode.core.buffer.Snapshot
import dev.jcode.core.editor.decor.ColoredSpan
import dev.jcode.core.editor.decor.DecorationSet
import dev.jcode.core.resource.LruManagedCache
import dev.jcode.core.resource.ResourceManager
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
    // Display density (px per dp). fontSizeSp (sp) must be multiplied by this to get the px that
    // Paint.textSize expects; without it, text renders ~density× too small on high-DPI screens.
    private val density: Float = 1f,
    resourceManager: ResourceManager? = null,
) {
    private val lineShapeCache = LruManagedCache<LineShapeKey, ShapedLine>(
        name = "EditorLineShapeCache",
        maxSize = 5000,
    ).also { resourceManager?.registerCache(it) }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        isSubpixelText = true
        this.typeface = this@Renderer.typeface
    }

    private val lineNumberPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        isSubpixelText = true
        this.typeface = this@Renderer.typeface
        textAlign = Paint.Align.RIGHT
    }

    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2f
    }

    private val gutterBgPaint = Paint()
    private val gutterLinePaint = Paint().apply {
        strokeWidth = 1f
    }

    fun draw(canvas: Canvas, snapshot: Snapshot, viewport: Viewport, config: RenderConfig, carets: List<Caret>, decorations: DecorationSet = DecorationSet.EMPTY, theme: EditorTheme = EditorTheme.DARK) {
        // Configure paint for current config. fontSizeSp is in sp; Paint.textSize is in px, so convert
        // via the display density (a missing conversion renders text ~density× too small).
        val fontSizePx = config.fontSizeSp * density
        textPaint.textSize = fontSizePx
        textPaint.color = theme.foreground.toInt()
        lineNumberPaint.textSize = fontSizePx * 0.85f
        lineNumberPaint.color = theme.lineNumber.toInt()
        selectionPaint.color = theme.selection.toInt()
        cursorPaint.color = theme.cursor.toInt()
        gutterBgPaint.color = theme.gutterBackground.toInt()
        gutterLinePaint.color = theme.gutterBorder.toInt()
        val lineHeightPx = (fontSizePx * config.lineHeightMultiplier).toInt().coerceAtLeast(1)

        val visibleTop = viewport.visibleLineTop
        val visibleBottom = snapshot.lineCount.coerceAtMost(viewport.visibleLineBottom + 1)

        val gutterWidth = computeGutterWidth(snapshot, config)

        // Get colored spans for syntax highlighting
        val coloredSpans = decorations.atLayer(dev.jcode.core.editor.decor.Layer.GLYPH_COLOR)
            .filterIsInstance<ColoredSpan>()

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
                theme.lineNumberActive.toInt()
            } else {
                theme.lineNumber.toInt()
            }
            canvas.drawText(
                "${line + 1}",
                gutterWidth - 12f,
                y + lineHeightPx * 0.7f,
                lineNumberPaint,
            )

            // Draw line text with syntax highlighting
            if (coloredSpans.isNotEmpty()) {
                drawLineWithSpans(canvas, lineText, lineStart, lineEnd, coloredSpans, gutterWidth + 8f, y + lineHeightPx * 0.7f, config)
            } else {
                textPaint.color = theme.foreground.toInt()
                canvas.drawText(lineText, gutterWidth + 8f, y + lineHeightPx * 0.7f, textPaint)
            }
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

    private fun drawLineWithSpans(
        canvas: Canvas,
        lineText: String,
        lineStartByte: Int,
        lineEndByte: Int,
        spans: List<ColoredSpan>,
        x: Float,
        y: Float,
        config: RenderConfig,
    ) {
        var currentX = x
        var charIndex = 0

        while (charIndex < lineText.length) {
            val byteOffset = lineStartByte + charIndex

            // Find the color for this position
            val color = ColoredSpan.colorAt(spans, byteOffset) ?: 0xFFCDD6F4.toInt()

            // Find how far this color extends
            var endChar = charIndex + 1
            while (endChar < lineText.length) {
                val nextByteOffset = lineStartByte + endChar
                val nextColor = ColoredSpan.colorAt(spans, nextByteOffset) ?: 0xFFCDD6F4.toInt()
                if (nextColor != color) break
                endChar++
            }

            // Draw this segment
            val segment = lineText.substring(charIndex, endChar)
            textPaint.color = color
            canvas.drawText(segment, currentX, y, textPaint)
            currentX += measureTextWidth(segment, config)
            charIndex = endChar
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
