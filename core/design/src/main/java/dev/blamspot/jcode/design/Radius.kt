package dev.blamspot.jcode.design

import androidx.compose.ui.unit.dp

/** Corner radii for surfaces, cards, chips and pills. */
object Radius {
    /** Square — an explicit zero for a surface that deliberately has no rounding. */
    val none = 0.dp

    val xs = 2.dp
    val sm = 4.dp
    val md = 6.dp
    val lg = 8.dp
    val xl = 10.dp
    val xxl = 12.dp
    val xxxl = 16.dp
    val sheet = 20.dp

    /** Fully round: larger than any control this app draws, so the corners meet in the middle. */
    val pill = 999.dp
}
