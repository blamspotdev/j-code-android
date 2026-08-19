package dev.jcode.feature.editor.pane

import androidx.compose.runtime.compositionLocalOf
import dev.jcode.design.JCodeIcon

/** One extension-contributed editor context-menu item, already filtered for the active file. */
data class EditorMenuContribution(
    /** Stable key ("extensionId:actionId") identifying the contribution to the host. */
    val key: String,
    val label: String,
    val icon: JCodeIcon,
)

/**
 * Extra items the host injects into the editor's long-press context menu for the ACTIVE tab.
 * Carried by a CompositionLocal (not parameters) because the shell composables sit at the ART
 * verifier's register limit. [previewToggle] is non-null when the active tab supports a rendered
 * preview (e.g. a Markdown file); it flips the tab between source and preview. Contribution taps
 * report the pressed word alongside the item so extensions receive the tap context.
 */
data class EditorMenuExtras(
    val previewToggle: (() -> Unit)? = null,
    /** Opens the shared Go-to-line dialog for the active file tab. */
    val onGoToLine: (() -> Unit)? = null,
    /** Opens the Find-in-Files panel seeded with the pressed word (may be empty). */
    val onFindText: ((String) -> Unit)? = null,
    val contributions: List<EditorMenuContribution> = emptyList(),
    val onContribution: (EditorMenuContribution, String) -> Unit = { _, _ -> },
)

val LocalEditorMenuExtras = compositionLocalOf { EditorMenuExtras() }
