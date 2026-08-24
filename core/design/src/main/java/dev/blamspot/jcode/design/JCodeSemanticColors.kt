package dev.blamspot.jcode.design

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Extra semantic colors that Material's [ColorScheme] does not model (it only carries `error`).
 * Provided per theme bundle so success/warning/info stay consistent and themeable instead of being
 * hardcoded at call sites.
 */
@Immutable
data class JCodeSemanticColors(
    val success: Color,
    val onSuccess: Color,
    val warning: Color,
    val onWarning: Color,
    val info: Color,
    val onInfo: Color,
)

val LocalSemanticColors = staticCompositionLocalOf {
    ThemeBundleRegistry.default.darkSemantic
}
