package dev.jcode.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf

/**
 * App-level (Global settings) terminal defaults. [value] is in sp — the terminal view takes raw
 * pixels, so the workbench scales it by the display density before applying it, which also makes
 * the terminal render at the same physical size across devices.
 */
@Immutable
class TerminalFontSizeSetting(
    val value: Float = SettingsDefaults.TERMINAL_FONT_SIZE,
    val onChange: (Float) -> Unit = {},
)

val LocalTerminalFontSizeSetting = compositionLocalOf { TerminalFontSizeSetting() }
