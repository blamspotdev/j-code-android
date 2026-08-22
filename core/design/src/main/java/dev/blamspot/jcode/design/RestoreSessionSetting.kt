package dev.blamspot.jcode.design

import androidx.compose.runtime.compositionLocalOf

/**
 * "Restore last session" preference, shared (via [LocalRestoreSession]) with the settings screen without
 * threading a param through JCodeShell (which is at the ART verifier's register limit). When [enabled]
 * (the default), the last open workspace/project and editor tabs (incl. unsaved changes) are reopened on
 * launch; [onChange] toggles it.
 */
class RestoreSessionSetting(
    val enabled: Boolean = true,
    val onChange: (Boolean) -> Unit = {},
)

val LocalRestoreSession = compositionLocalOf { RestoreSessionSetting() }
