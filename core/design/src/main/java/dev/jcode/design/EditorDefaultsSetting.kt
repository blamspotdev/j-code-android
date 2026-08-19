package dev.jcode.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf

/**
 * App-level (Global settings) editor defaults. These feed the editor when no workspace/project
 * `.jcode` override exists — font size is the base of the effective font-size merge, word wrap
 * drives soft-wrapping for every open editor tab.
 */
@Immutable
class EditorFontSizeSetting(
    val value: Float = SettingsDefaults.EDITOR_FONT_SIZE,
    val onChange: (Float) -> Unit = {},
)

val LocalEditorFontSizeSetting = compositionLocalOf { EditorFontSizeSetting() }

@Immutable
class EditorWordWrapSetting(
    val enabled: Boolean = SettingsDefaults.EDITOR_WORD_WRAP,
    val onChange: (Boolean) -> Unit = {},
)

val LocalEditorWordWrapSetting = compositionLocalOf { EditorWordWrapSetting() }
