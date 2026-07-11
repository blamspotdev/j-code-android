package dev.jcode.core.editor

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import dev.jcode.core.buffer.LineWindow
import dev.jcode.core.buffer.Snapshot
import dev.jcode.core.editor.decor.ColoredSpan
import dev.jcode.core.editor.decor.Decoration
import dev.jcode.core.editor.decor.DecorationSet
import dev.jcode.core.editor.decor.GutterMarkerDecoration
import dev.jcode.core.editor.decor.Layer
import dev.jcode.core.editor.decor.LineHighlightDecoration
import dev.jcode.core.editor.decor.SquiggleDecoration

/**
 * Renderer for the EditorView. Handles visible line computation, shaping,
 * and layered drawing (selection, glyphs, carets, gutter).
 *
 * Per-frame costs are kept flat in file size: the visible lines arrive as ONE batched native
 * read ([Snapshot.readLines]) instead of two JNI calls + an allocation per line, and syntax
 * coloring sweeps the byte-sorted span list with a cursor instead of scanning the whole file's
 * spans per character.
 */
class Renderer(
    private val typeface: Typeface,
    // Display density (px per dp). fontSizeSp (sp) must be multiplied by this to get the px that
    // Paint.textSize expects; without it, text renders ~density× too small on high-DPI screens.
    private val density: Float = 1f,
) {
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
    private val breakpointPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lineHighlightPaint = Paint()
    private val squigglePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    // The GLYPH_COLOR layer list is stable per DecorationSet instance, so the filterIsInstance
    // pass (O(spans) + a list copy) runs once per decoration change, not once per frame.
    private var cachedSpanSource: List<Decoration>? = null
    private var cachedSpans: List<ColoredSpan> = emptyList()

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
        val window = snapshot.readLines(visibleTop, (visibleBottom - visibleTop).coerceAtLeast(0))

        val gutterWidth = computeGutterWidth(snapshot, config)

        // Get colored spans for syntax highlighting
        val spanSource = decorations.atLayer(Layer.GLYPH_COLOR)
        if (spanSource !== cachedSpanSource) {
            cachedSpans = spanSource.filterIsInstance<ColoredSpan>()
            cachedSpanSource = spanSource
        }
        val coloredSpans = cachedSpans

        // Caret lines, computed once — the per-line loop below only does set lookups.
        val caretLines = carets.mapTo(HashSet()) { snapshot.offsetToLineColumn(it.head).first }

        // Draw gutter background
        canvas.drawRect(0f, 0f, gutterWidth.toFloat(), viewport.heightPx.toFloat(), gutterBgPaint)

        // Full-line background highlights (e.g. the current stopped line while debugging).
        val lineHighlights = decorations.atLayer(Layer.BACKGROUND)
            .filterIsInstance<LineHighlightDecoration>()
        for (hl in lineHighlights) {
            if (hl.line in visibleTop until visibleBottom) {
                val y = (hl.line - visibleTop) * lineHeightPx + viewport.scrollY % lineHeightPx
                lineHighlightPaint.color = hl.color
                canvas.drawRect(gutterWidth.toFloat(), y.toFloat(), canvas.width.toFloat(), (y + lineHeightPx).toFloat(), lineHighlightPaint)
            }
        }

        // Draw selection rects
        for (caret in carets) {
            if (caret.isSelection) {
                val startLine = snapshot.offsetToLineColumn(caret.start).first
                val endLine = snapshot.offsetToLineColumn(caret.end).first
                // Clamp the iteration to the visible slice so a huge selection costs O(visible), not
                // O(selection); the bounds cover exactly the lines that passed the in-body check before.
                for (line in maxOf(startLine, visibleTop) until minOf(endLine + 1, visibleBottom)) {
                    if (window.contains(line)) {
                        val lineStart = window.byteStart(line)
                        val lineEnd = window.byteEnd(line)
                        val selStart = if (line == startLine) (caret.start - lineStart).coerceAtLeast(0) else 0
                        val selEnd = if (line == endLine) (caret.end - lineStart).coerceAtMost(lineEnd - lineStart) else (lineEnd - lineStart)
                        if (selStart < selEnd) {
                            val lineText = window.text(line)
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
            if (!window.contains(line)) break

            val y = (line - visibleTop) * lineHeightPx + viewport.scrollY % lineHeightPx
            val lineText = window.text(line)

            // Draw line number
            lineNumberPaint.color = if (line in caretLines) {
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
                drawLineWithSpans(canvas, lineText, window.byteStart(line), coloredSpans, gutterWidth + 8f, y + lineHeightPx * 0.7f, config)
            } else {
                textPaint.color = theme.foreground.toInt()
                canvas.drawText(lineText, gutterWidth + 8f, y + lineHeightPx * 0.7f, textPaint)
            }
        }

        // Squiggly diagnostic underlines, drawn just below each affected line's text baseline.
        val squiggles = decorations.atLayer(Layer.SQUIGGLY)
            .filterIsInstance<SquiggleDecoration>()
        for (sq in squiggles) {
            val sqStart = sq.startByte.coerceAtLeast(0)
            val sqEnd = sq.endByte.coerceAtLeast(sqStart)
            val startLine = snapshot.offsetToLineColumn(sqStart).first
            val endLine = snapshot.offsetToLineColumn(sqEnd).first
            // Clamp to the visible slice so a file-spanning diagnostic costs O(visible), not O(range);
            // the bounds cover exactly the lines that passed the in-body visibility check before.
            for (line in maxOf(startLine, visibleTop) until minOf(endLine + 1, visibleBottom)) {
                if (!window.contains(line)) continue
                val lineStart = window.byteStart(line)
                val lineEnd = window.byteEnd(line)
                val lineText = window.text(line)
                val c0 = (if (line == startLine) sqStart - lineStart else 0)
                    .coerceIn(0, lineText.length)
                val c1 = (if (line == endLine) sqEnd - lineStart else lineEnd - lineStart)
                    .coerceIn(c0, lineText.length)
                val xStart = gutterWidth + 8f + measureTextWidth(lineText.substring(0, c0), config)
                var xEnd = gutterWidth + 8f + measureTextWidth(lineText.substring(0, c1), config)
                if (xEnd < xStart + 12f) xEnd = xStart + 12f // keep zero-width diagnostics visible
                val y = (line - visibleTop) * lineHeightPx + viewport.scrollY % lineHeightPx
                squigglePaint.color = sq.severity.color
                SquiggleDecoration.drawSquiggle(canvas, squigglePaint, xStart, xEnd, y + lineHeightPx * 0.7f + 5f)
            }
        }

        // Gutter markers: breakpoint dots + the current-execution marker, drawn in the gutter's left inset.
        val gutterMarkers = decorations.atLayer(Layer.GUTTER)
            .filterIsInstance<GutterMarkerDecoration>()
        for (marker in gutterMarkers) {
            if (marker.line in visibleTop until visibleBottom) {
                val y = (marker.line - visibleTop) * lineHeightPx + viewport.scrollY % lineHeightPx
                val radius = lineHeightPx * 0.24f
                val cx = radius + 6f
                val cy = y + lineHeightPx / 2f
                breakpointPaint.color = marker.color
                if (marker.kind == GutterMarkerDecoration.Kind.CurrentLine) {
                    // A right-pointing arrow for the current execution line.
                    val p = android.graphics.Path().apply {
                        moveTo(cx - radius, cy - radius); lineTo(cx + radius, cy); lineTo(cx - radius, cy + radius); close()
                    }
                    canvas.drawPath(p, breakpointPaint)
                } else {
                    canvas.drawCircle(cx, cy, radius, breakpointPaint)
                }
            }
        }

        // Draw cursors
        for (caret in carets) {
            val (line, col) = snapshot.offsetToLineColumn(caret.head)
            if (line in visibleTop until visibleBottom && window.contains(line)) {
                val lineText = window.text(line)
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
        spans: List<ColoredSpan>,
        x: Float,
        y: Float,
        config: RenderConfig,
    ) {
        // Spans are byte-sorted and non-overlapping (one tokenizer produces the layer), so a
        // single forward cursor resolves every character's color: seed with a binary search,
        // then advance while walking the line.
        var spanIndex = ColoredSpan.firstSpanIndexFor(spans, lineStartByte)
        val defaultColor = 0xFFCDD6F4.toInt()

        fun colorFor(byteOffset: Int): Int {
            while (spanIndex < spans.size && spans[spanIndex].endByte <= byteOffset) spanIndex++
            val span = spans.getOrNull(spanIndex) ?: return defaultColor
            return if (byteOffset >= span.startByte) span.color else defaultColor
        }

        var currentX = x
        var charIndex = 0
        while (charIndex < lineText.length) {
            val color = colorFor(lineStartByte + charIndex)

            // Find how far this color extends
            var endChar = charIndex + 1
            while (endChar < lineText.length) {
                val savedIndex = spanIndex
                if (colorFor(lineStartByte + endChar) != color) {
                    spanIndex = savedIndex
                    break
                }
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

    // Monospace advance for the current text size; measureText("M") is exact for every ASCII glyph
    // in a monospace font (JetBrains Mono), so an all-ASCII run's width is just count × advance —
    // no minikin call per span run per line per frame, which is the scroll hot path on slow SoCs.
    private var cachedAdvanceSize = -1f
    private var cachedAdvance = 0f

    private fun asciiAdvance(): Float {
        if (textPaint.textSize != cachedAdvanceSize) {
            cachedAdvance = textPaint.measureText("M")
            cachedAdvanceSize = textPaint.textSize
        }
        return cachedAdvance
    }

    private fun measureTextWidth(text: String, config: RenderConfig): Float {
        if (text.isEmpty()) return 0f
        // Fast path: a run of printable ASCII advances by a constant amount in a monospace font.
        // Tabs (0x09) and non-ASCII fall through to exact measurement.
        var allAscii = true
        for (i in text.indices) {
            if (text[i].code !in 0x20..0x7E) { allAscii = false; break }
        }
        if (allAscii) return text.length * asciiAdvance()
        return textPaint.measureText(text)
    }
}
