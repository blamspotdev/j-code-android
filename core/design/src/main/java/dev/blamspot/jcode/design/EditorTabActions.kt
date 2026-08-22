package dev.blamspot.jcode.design

import androidx.compose.runtime.compositionLocalOf

/**
 * Per-tab actions for the editor tab strip's long-press menu, shared (via [LocalEditorTabActions])
 * so the pin / close-others / close-to-the-right handlers reach the tab UI without threading params
 * through JCodeShell (which is at the ART verifier's register limit). Each takes the tab's id.
 */
class EditorTabActions(
    val onTogglePin: (String) -> Unit = {},
    val onCloseOthers: (String) -> Unit = {},
    val onCloseToRight: (String) -> Unit = {},
    /** Set (or, with a null hex, clear) the manual color of a file tab. Persists to the project .jcode. */
    val onSetTabColor: (String, String?) -> Unit = { _, _ -> },
)

val LocalEditorTabActions = compositionLocalOf { EditorTabActions() }
