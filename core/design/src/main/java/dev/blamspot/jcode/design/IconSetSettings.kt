package dev.blamspot.jcode.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf

/**
 * The two icon-set choices, shared with the settings screen through [LocalIconSetSettings].
 *
 * A composition local rather than JCodeShell parameters, for the same reason [FontSettings] is one:
 * the shell already threads more arguments than the ART register limit is comfortable with, and this
 * would have added four more. The active sets themselves reach call sites through [LocalUiIconSet]
 * and [LocalFileIconSet]; this is only what the settings screen needs to offer a choice.
 *
 * [uiSets] is the built-ins plus whatever installed extensions provide. [fileSets] is extensions
 * only — JCode ships no file icon set, so the Settings card for it stays hidden until one is
 * installed, and "None" (an empty id) is always a valid choice.
 */
@Immutable
class IconSetSettings(
    val uiSets: List<UiIconSet> = emptyList(),
    val uiSetId: String = "",
    val onSelectUiSet: (String) -> Unit = {},
    val fileSets: List<FileIconSet> = emptyList(),
    val fileSetId: String = "",
    val onSelectFileSet: (String) -> Unit = {},
) {
    /** The chosen UI set, falling back to the default when the id names nothing installed. */
    val activeUiSetId: String
        get() = uiSets.firstOrNull { it.id == uiSetId }?.id ?: UiIconSetRegistry.default.id

    /** The chosen file set's id, or [FileIconSetRegistry.NONE_ID] when none applies. */
    val activeFileSetId: String
        get() = fileSets.firstOrNull { it.id == fileSetId }?.id ?: FileIconSetRegistry.NONE_ID
}

val LocalIconSetSettings = compositionLocalOf { IconSetSettings() }
