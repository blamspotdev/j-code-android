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
         * Index of the first span in the startByte-sorted, non-overlapping [spans] list that could
         * cover or follow [byteOffset] (i.e. the first with endByte > byteOffset). The renderer
         * seeds a per-line sweep with this instead of scanning the whole file's span list per
         * character.
         */
        fun firstSpanIndexFor(spans: List<ColoredSpan>, byteOffset: Int): Int {
            var lo = 0
            var hi = spans.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (spans[mid].endByte <= byteOffset) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }
}
