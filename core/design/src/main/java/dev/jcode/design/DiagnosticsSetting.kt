package dev.jcode.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import dev.jcode.core.diag.DiagLevel

/**
 * Settings → Diagnostics. Every capture is **opt-in and off by default**: nothing is recorded until
 * the user asks for it, and turning the master switch off stops recording immediately while leaving
 * whatever was captured available to export.
 */
@Immutable
class DiagnosticsSetting(
    val enabled: Boolean = SettingsDefaults.DIAGNOSTIC_LOGGING,
    val level: DiagLevel = SettingsDefaults.DIAGNOSTIC_LEVEL,
    val captureSystemLog: Boolean = SettingsDefaults.DIAGNOSTIC_SYSTEM_LOG,
    val captureCrashes: Boolean = SettingsDefaults.DIAGNOSTIC_CRASHES,
    /** Where the files are, shown so the user can find them outside the app. Empty until started. */
    val location: String = "",
    /** Total bytes on disk, refreshed while the card is open. */
    val sizeBytes: Long = 0L,
    val onSetEnabled: (Boolean) -> Unit = {},
    val onSetLevel: (DiagLevel) -> Unit = {},
    val onSetCaptureSystemLog: (Boolean) -> Unit = {},
    val onSetCaptureCrashes: (Boolean) -> Unit = {},
    /** The tail of the current session, for the in-app viewer. */
    val recentLines: () -> List<String> = { emptyList() },
    val onExport: () -> Unit = {},
    val onClear: () -> Unit = {},
    val onRefresh: () -> Unit = {},
)

val LocalDiagnosticsSetting = compositionLocalOf { DiagnosticsSetting() }
