package dev.blamspot.jcode.design

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp

/**
 * Footprints for interactive controls: the heights and paddings that make a button read as this
 * app's button rather than Material's default, which is half again as tall.
 */
object ControlSize {
    /** Minimum height of a compact button — the app's standard action size. */
    val compactHeight = 32.dp

    /** Inside a compact button, around its label. */
    val compactPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)

    /** Icon-only button in a dense strip (tab bars, list rows). */
    val iconButtonSm = 28.dp

    /** Icon-only button in a panel header. */
    val iconButton = 36.dp

    /** Material's minimum comfortable touch target. */
    val touchTarget = 40.dp
}
