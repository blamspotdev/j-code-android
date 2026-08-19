package dev.jcode.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf

/**
 * "Developer options" state. When [enabled], the app reveals extension-authoring tools — a
 * right-drawer "Extension Dev" tab and the ability to sideload an unsigned `.jext`. Off by default;
 * normal users never see any of it. [onLoadExtension] opens the sideload picker (with a trust warning).
 */
@Immutable
class DeveloperSetting(
    val enabled: Boolean = SettingsDefaults.DEVELOPER_OPTIONS,
    val onSetEnabled: (Boolean) -> Unit = {},
    val onLoadExtension: () -> Unit = {},
)

val LocalDeveloperSetting = compositionLocalOf { DeveloperSetting() }
