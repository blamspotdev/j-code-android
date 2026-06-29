package dev.jcode.core.editor.decor

/**
 * Z-order layers for decorations (bottom to top).
 */
object Layer {
    const val BACKGROUND = 0
    const val SELECTION = 100
    const val SQUIGGLY = 200
    const val GLYPH_COLOR = 300
    const val GLYPH = 400
    const val COMPOSING = 500
    const val GHOST_TEXT = 600
    const val INLAY = 700
    const val CARET = 800
    const val GUTTER = 900
    const val MINIMAP = 1000
    const val POPUP = 1100
}

/**
 * Base interface for all editor decorations.
 * Each decoration has a z-index for layer ordering.
 */
interface Decoration {
    /** Z-order layer (use Layer constants). Lower values draw first. */
    fun zIndex(): Int

    /** Unique identifier for this decoration instance. */
    val id: String get() = hashCode().toString()
}
