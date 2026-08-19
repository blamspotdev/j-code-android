package dev.jcode.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf

/**
 * Markdown preview options. [wrapInPortrait] (default on) wraps prose to the viewport as usual;
 * when off, a portrait preview lays out at landscape width — the screen height, honoring the
 * "Respect device cutout" setting — and pans horizontally, so wide tables/code read unbroken.
 */
@Immutable
class MarkdownPreviewSetting(
    val wrapInPortrait: Boolean = SettingsDefaults.MARKDOWN_WRAP_PORTRAIT,
    val onSetWrapInPortrait: (Boolean) -> Unit = {},
)

val LocalMarkdownPreviewSetting = compositionLocalOf { MarkdownPreviewSetting() }
