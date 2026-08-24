package dev.blamspot.jcode.design

import androidx.compose.runtime.compositionLocalOf

/**
 * The Trash preference, shared by the Settings screen and the Explorer through
 * [LocalTrashSettings] rather than threaded through JCodeShell as parameters (ART register limit —
 * see [ExplorerHiddenSetting], which is here for the same reason).
 *
 * [retentionDays] of 0 is "keep until I empty it".
 */
class TrashSettings(
    val enabled: Boolean = SettingsDefaults.TRASH_ENABLED,
    val retentionDays: Int = SettingsDefaults.TRASH_RETENTION_DAYS,
    val onSetEnabled: (Boolean) -> Unit = {},
    val onSetRetentionDays: (Int) -> Unit = {},
)

val LocalTrashSettings = compositionLocalOf { TrashSettings() }

/** The retention periods offered, in days. 0 is forever and is deliberately last. */
val TRASH_RETENTION_CHOICES: List<Int> = listOf(1, 7, 14, 30, 90, 0)

fun trashRetentionLabel(days: Int): String = when (days) {
    0 -> "Until I empty it"
    1 -> "1 day"
    else -> "$days days"
}
