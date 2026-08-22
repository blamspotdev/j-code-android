package dev.blamspot.jcode.design

import androidx.compose.runtime.compositionLocalOf

/**
 * Editor drag-gesture preference, shared (via [LocalEditorDragMovesCursor]) with the editor view host and
 * the settings screen. When [enabled], a one-finger drag on the editor moves the text cursor (the view
 * scrolls to follow) instead of scrolling the content; long-press text selection is unaffected.
 */
class EditorDragSetting(
    val enabled: Boolean = false,
    val onChange: (Boolean) -> Unit = {},
    /** Drag-to-cursor sensitivity, 1 (slow/precise) … 5 (fast), independent per axis. */
    val verticalLevel: Int = 2,
    val horizontalLevel: Int = 2,
    val onVerticalLevelChange: (Int) -> Unit = {},
    val onHorizontalLevelChange: (Int) -> Unit = {},
)

val LocalEditorDragMovesCursor = compositionLocalOf { EditorDragSetting() }
