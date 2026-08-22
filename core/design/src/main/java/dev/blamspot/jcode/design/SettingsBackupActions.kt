package dev.blamspot.jcode.design

import androidx.compose.runtime.compositionLocalOf

/**
 * Settings backup/restore actions, shared (via [LocalSettingsBackup]) with the settings screen
 * without threading params through JCodeShell. [onExport] launches a file picker to save the app
 * preferences to a JSON document; [onImport] picks a document and restores them.
 */
class SettingsBackupActions(
    val onExport: () -> Unit = {},
    val onImport: () -> Unit = {},
)

val LocalSettingsBackup = compositionLocalOf { SettingsBackupActions() }
