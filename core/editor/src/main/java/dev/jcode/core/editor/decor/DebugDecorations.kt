package dev.jcode.core.editor.decor

/**
 * A gutter marker anchored to a 0-based line (breakpoint dot, or the current-execution marker).
 * Unlike the byte-range decorations, these are line-based because the gutter is drawn per line.
 */
data class GutterMarkerDecoration(
    override val id: String,
    /** 0-based line the marker sits on. */
    val line: Int,
    /** ARGB fill color. */
    val color: Int,
    val kind: Kind = Kind.Breakpoint,
) : Decoration {
    override fun zIndex(): Int = Layer.GUTTER

    enum class Kind { Breakpoint, CurrentLine }
}

/**
 * A full-line background highlight anchored to a 0-based line (e.g. the current stopped line while
 * debugging). Drawn at the BACKGROUND layer, beneath text and selection.
 */
data class LineHighlightDecoration(
    override val id: String,
    /** 0-based line to highlight. */
    val line: Int,
    /** ARGB background color. */
    val color: Int,
) : Decoration {
    override fun zIndex(): Int = Layer.BACKGROUND
}
