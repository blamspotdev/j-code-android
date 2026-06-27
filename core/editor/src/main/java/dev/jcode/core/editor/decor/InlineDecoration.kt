package dev.jcode.core.editor.decor

/**
 * An inline decoration that occupies horizontal space within a line of text.
 * Used for ghost text (AI completions), inline hints, parameter hints, etc.
 */
data class InlineDecoration(
    override val id: String,
    /** Byte offset in the buffer where this inlay should appear */
    val offset: Int,
    /** Width in pixels that this inlay occupies */
    val widthPx: Float,
    /** Height in pixels (defaults to line height if 0) */
    val heightPx: Float = 0f,
) : Decoration {

    override fun zIndex(): Int = Layer.INLAY

    companion object {
        /**
         * Find all inline decorations that fall within a given line's byte range.
         */
        fun findInLine(inlays: List<InlineDecoration>, lineStartByte: Int, lineEndByte: Int): List<InlineDecoration> {
            return inlays.filter { it.offset in lineStartByte until lineEndByte }
        }

        /**
         * Calculate total inlay width before a given byte offset within a line.
         */
        fun totalWidthBefore(inlays: List<InlineDecoration>, lineStartByte: Int, beforeByte: Int): Float {
            return inlays
                .filter { it.offset in lineStartByte until beforeByte }
                .sumOf { it.widthPx.toDouble() }
                .toFloat()
        }
    }
}

/**
 * Ghost text decoration - a special inline decoration for AI completion previews.
 * Rendered with reduced opacity to distinguish from actual text.
 */
data class GhostTextDecoration(
    val offset: Int,
    val text: String,
    val color: Int,
    val alpha: Float = 0.5f,
) : Decoration {
    override val id: String = "ghost-$offset-${text.hashCode()}"

    override fun zIndex(): Int = Layer.GHOST_TEXT
}
