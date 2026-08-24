package dev.blamspot.jcode.design

import androidx.compose.runtime.compositionLocalOf

/**
 * Environment (Linux rootfs) backup/restore actions, shared (via [LocalEnvironmentBackup]) with the
 * settings screen. [onBackup] packs the active environment to a `.tar.gz` file; [onRestore] extracts
 * a picked `.tar.gz` back over it. [onUpdatePackages] runs the opt-in `apt-get update && upgrade`
 * ([updatingPackages] is true while it runs).
 */
class EnvironmentBackupActions(
    val onBackup: () -> Unit = {},
    val onRestore: () -> Unit = {},
    val onUpdatePackages: () -> Unit = {},
    val updatingPackages: Boolean = false,
    /**
     * Migration between two differently-packaged installs: [onExportMigration] writes this install's
     * environment, projects, extensions and settings to shared storage, and [onImportMigration]
     * takes over a bundle another install left there. [migrationSummary] describes that bundle when
     * one is waiting, and is null when there is nothing to import.
     */
    val onExportMigration: () -> Unit = {},
    val onImportMigration: () -> Unit = {},
    val migrationSummary: String? = null,
)

val LocalEnvironmentBackup = compositionLocalOf { EnvironmentBackupActions() }
