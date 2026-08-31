package dev.blamspot.jcode.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf

/**
 * "Developer options" state. When [enabled], the app reveals extension-authoring tools — a
 * right-drawer "Extension Dev" tab, where an unsigned `.jext` or `.vsix` can be inspected and
 * debugged. Off by default; normal users never see any of it. Importing one does not depend on this:
 * the Extensions panel takes both package formats either way and flags what arrives unsigned.
 */
@Immutable
class DeveloperSetting(
    val enabled: Boolean = SettingsDefaults.DEVELOPER_OPTIONS,
    val onSetEnabled: (Boolean) -> Unit = {},
)

val LocalDeveloperSetting = compositionLocalOf { DeveloperSetting() }
