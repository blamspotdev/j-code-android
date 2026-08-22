package dev.blamspot.jcode.design

import androidx.compose.ui.unit.dp

/**
 * Border and divider widths.
 *
 * Named `StrokeWidth` rather than `Stroke` because Compose's draw scope already owns that name, and
 * a file that both draws on a canvas and borders a surface should not have to disambiguate.
 */
object StrokeWidth {
    /** Sub-pixel on most screens: dividers that should register without drawing a line. */
    val hairline = 0.5.dp

    val thin = 1.dp
    val thick = 2.dp

    /** The accent bar that marks a coloured or selected tab. */
    val accent = 3.dp
}
