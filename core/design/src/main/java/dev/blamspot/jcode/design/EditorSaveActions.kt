package dev.blamspot.jcode.design

import androidx.compose.runtime.compositionLocalOf

/**
 * Editor save-related actions, shared (via [LocalEditorSaveActions]) with the top bar's Save button so
 * its long-press menu can offer them without threading callbacks as params (JCodeShell is at the ART
 * verifier's register limit). Each defaults to a no-op.
 */
class EditorSaveActions(
    val onUndo: () -> Unit = {},
    val onRedo: () -> Unit = {},
    val onDiscard: () -> Unit = {},
    val onSaveAll: () -> Unit = {},
    val onFormat: () -> Unit = {},
    /** Save every dirty tab and suspend until the writes finish — used by the close-guard so a switch
     *  only proceeds once the buffers are safely on disk. Returns true only if every tab is now clean
     *  (false if any couldn't be saved, so the caller can avoid tearing down unsaved work). */
    val onSaveAllAwait: suspend () -> Boolean = { true },
)

val LocalEditorSaveActions = compositionLocalOf { EditorSaveActions() }
