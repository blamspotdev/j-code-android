package dev.blamspot.jcode.design

import androidx.compose.runtime.compositionLocalOf

/**
 * In-app update state, shared (via [LocalAppUpdate]) with the settings screen without threading
 * params through JCodeShell (ART register limit). Populated from a GitHub-release check on startup:
 * [updateAvailable] flags a newer [latestVersion] than [currentVersion]; [onCheck] re-runs the check;
 * [onOpenRelease] opens the release page in a browser.
 */
class AppUpdateSetting(
    val currentVersion: String = "",
    val latestVersion: String? = null,
    val updateAvailable: Boolean = false,
    val checking: Boolean = false,
    val onCheck: () -> Unit = {},
    val onOpenRelease: () -> Unit = {},
    /** Download + install the update in-app; falls back to [onOpenRelease] when the release has no APK. */
    val onInstallUpdate: () -> Unit = {},
    /** True while the in-app updater is downloading/installing; [installProgress] is the download %. */
    val installing: Boolean = false,
    val installProgress: Int = 0,
)

val LocalAppUpdate = compositionLocalOf { AppUpdateSetting() }
