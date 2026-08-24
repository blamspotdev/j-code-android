package dev.blamspot.jcode.design

import androidx.compose.runtime.compositionLocalOf

/**
 * Performance / resource-management preferences, shared (via [LocalPerformanceSettings]) with both the
 * settings screen and JCodeShell without threading params through the latter (ART register limit).
 * [confirmCloseRunning] warns before closing a project/workspace that still has a running terminal
 * program, an active Build & Run, or a live debug session; [autoCloseIdleTerminals] auto-closes
 * terminals idle at the prompt past [idleTimeoutMinutes] to free their proot trees + memory.
 */
class PerformanceSettings(
    val hardwareAcceleration: Boolean = true,
    val confirmCloseRunning: Boolean = true,
    val autoCloseIdleTerminals: Boolean = false,
    val idleTimeoutMinutes: Int = 30,
    val maxTerminalSessions: Int = 12,
    val nestedShellTabs: Boolean = false,
    val installTimeoutMinutes: Int = 30,
    val exitOnSwipeAway: Boolean = true,
    val onSetHardwareAcceleration: (Boolean) -> Unit = {},
    val onSetConfirmCloseRunning: (Boolean) -> Unit = {},
    val onSetAutoCloseIdleTerminals: (Boolean) -> Unit = {},
    val onSetIdleTimeoutMinutes: (Int) -> Unit = {},
    val onSetMaxTerminalSessions: (Int) -> Unit = {},
    val onSetNestedShellTabs: (Boolean) -> Unit = {},
    val onSetInstallTimeoutMinutes: (Int) -> Unit = {},
    val onSetExitOnSwipeAway: (Boolean) -> Unit = {},
)

val LocalPerformanceSettings = compositionLocalOf { PerformanceSettings() }
