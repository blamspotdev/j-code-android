package dev.blamspot.jcode.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * The design tokens that a theme bundle varies, alongside [MaterialTheme].
 *
 * The geometry scales — [Space], [Radius], [StrokeWidth], [IconSize], [ControlSize] — are plain
 * objects rather than accessors here: they are the same in every bundle, so routing them through a
 * CompositionLocal only added an indirection and a `@Composable` requirement at every call site.
 */
object JCodeTheme {
    val semanticColors: JCodeSemanticColors
        @Composable get() = LocalSemanticColors.current
}
