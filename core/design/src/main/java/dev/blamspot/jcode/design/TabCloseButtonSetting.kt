package dev.blamspot.jcode.design

import androidx.compose.runtime.compositionLocalOf

/**
 * Tab close-button preference, shared (via [LocalTabCloseButtonSetting]) with the deep tab UIs and the
 * settings screen so it doesn't have to be threaded as params. [hidden] hides the "×" on editor and
 * terminal tabs to avoid accidental closes (close via the tab's long-press menu); [onChange] toggles it.
 */
class TabCloseButtonSetting(
    val hidden: Boolean = false,
    val onChange: (Boolean) -> Unit = {},
)

val LocalTabCloseButtonSetting = compositionLocalOf { TabCloseButtonSetting() }
