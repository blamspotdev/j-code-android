package dev.jcode.design

import androidx.compose.runtime.compositionLocalOf

/** When the workbench's bottom status bar (branch / distro / caret info) is shown. */
enum class BottomBarVisibility {
    /** Never show the bottom status bar. */
    Hidden,

    /** Show it, except while the soft keyboard is up (reclaims a row for the editor/terminal). */
    HideOnKeyboard,

    /** Always show the bottom status bar. */
    AlwaysShow,
}

/** App setting: visibility of the workbench's bottom status bar. */
class BottomBarSetting(
    val visibility: BottomBarVisibility = SettingsDefaults.BOTTOM_STATUS_BAR,
    val onChange: (BottomBarVisibility) -> Unit = {},
)

val LocalBottomBarSetting = compositionLocalOf { BottomBarSetting() }
