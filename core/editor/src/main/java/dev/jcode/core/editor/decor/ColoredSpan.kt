package dev.jcode.core.editor.decor

/**
 * A colored span representing syntax highlighting or other text coloring.
 * Produced by tree-sitter's HighlightSpanProducer and consumed by the renderer.
 */
data class ColoredSpan(
    /** Start byte offset in the buffer */
    val startByte: Int,
    /** End byte offset (exclusive) in the buffer */
    val endByte: Int,
    /** ARGB color value */
    val color: Int,
    /** Optional additional text style flags */
    val styleFlags: Int = 0,
) : Decoration {

    override fun zIndex(): Int = Layer.GLYPH_COLOR

    /** Whether this span is bold */
    val isBold: Boolean get() = (styleFlags and STYLE_BOLD) != 0

    /** Whether this span is italic */
    val isItalic: Boolean get() = (styleFlags and STYLE_ITALIC) != 0

    /** Whether this span is underlined */
    val isUnderline: Boolean get() = (styleFlags and STYLE_UNDERLINE) != 0

    /** Whether this span is strikethrough */
    val isStrikethrough: Boolean get() = (styleFlags and STYLE_STRIKETHROUGH) != 0

    companion object {
        const val STYLE_BOLD = 1 shl 0
        const val STYLE_ITALIC = 1 shl 1
        const val STYLE_UNDERLINE = 1 shl 2
        const val STYLE_STRIKETHROUGH = 1 shl 3

        /**
         * Find all colored spans that overlap a given byte range.
         */
        fun findOverlapping(spans: List<ColoredSpan>, startByte: Int, endByte: Int): List<ColoredSpan> {
            return spans.filter { it.startByte < endByte && it.endByte > startByte }
        }

        /**
         * Get the color at a specific byte offset, or null if no span covers it.
         * Returns the innermost (last added) span's color.
         */
        fun colorAt(spans: List<ColoredSpan>, byteOffset: Int): Int? {
            for (i in spans.indices.reversed()) {
                val span = spans[i]
                if (byteOffset >= span.startByte && byteOffset < span.endByte) {
                    return span.color
                }
            }
            return null
        }
    }
}
