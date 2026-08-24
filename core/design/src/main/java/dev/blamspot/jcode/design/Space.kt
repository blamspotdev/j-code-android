package dev.blamspot.jcode.design

import androidx.compose.ui.unit.dp

/**
 * The app's spacing scale: every padding and gap comes from here rather than a literal, so density
 * is one decision instead of several hundred.
 *
 * The steps are the ones JCode actually designs in. [s] (6) and [ms] (10) look like odd steps for a
 * 4-based scale and are kept deliberately: together they carry a third of the app's spacing, and
 * folding them into their neighbours would have restyled the workbench rather than tokenised it.
 * New code should still reach for [xs]/[sm]/[md]/[lg] first.
 */
object Space {
    /** No gap — an explicit zero, so it reads as a decision rather than an omission. */
    val none = 0.dp

    /** Separators and 1px rules only. */
    val hairline = 1.dp

    val xxs = 2.dp
    val xs = 4.dp
    val s = 6.dp
    val sm = 8.dp
    val ms = 10.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
}
