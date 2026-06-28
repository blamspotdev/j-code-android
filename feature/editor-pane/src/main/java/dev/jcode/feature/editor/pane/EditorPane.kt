package dev.jcode.feature.editor.pane

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.jcode.core.editor.EditorContextRequest
import dev.jcode.core.editor.EditorLanguageAction
import dev.jcode.core.editor.EditorView
import dev.jcode.design.EditorKeyboardSettings
import dev.jcode.design.InAppKeyboard
import dev.jcode.design.KeyAction

/**
 * Editor pane composable that hosts a tab strip and the active EditorView.
 */
@Composable
fun EditorPane(
    group: EditorGroup,
    modifier: Modifier = Modifier,
    onTabSelected: (String) -> Unit = {},
    onTabClosed: (String) -> Unit = {},
    onOpenFile: () -> Unit = {},
    languageActionsEnabled: Boolean = false,
    onLanguageAction: (EditorLanguageAction, String) -> Unit = { _, _ -> },
    editorKeyboard: EditorKeyboardSettings = EditorKeyboardSettings(),
    pageContent: @Composable (EditorTab) -> Unit = {},
) {
    // The active editor view, lifted so the sibling in-app keyboard can drive it.
    var activeEditorView by remember { mutableStateOf<EditorView?>(null) }
    // The in-app keyboard has no system IME insets, so it tracks its own visibility: shown when the
    // editor is tapped, hidden via its ⌄ key or when the active editor goes away.
    var inAppKbVisible by remember { mutableStateOf(false) }

    Column(modifier = modifier.clipToBounds()) {
        // Tab strip — explicit fixed height so it's never compressed
        TabStrip(
            group = group,
            onTabSelected = onTabSelected,
            onTabClosed = onTabClosed,
            onOpenFile = onOpenFile,
        )

        // Active tab body: a file tab hosts the editor view; a page tab renders host content.
        val activeTab = group.activeTab
        if (activeTab != null) {
            Box(
                modifier = Modifier
                    .weight(1f, fill = true)
                    .clipToBounds(),
            ) {
                val editorState = activeTab.editorState
                if (editorState != null) {
                    EditorViewHost(
                        editorState = editorState,
                        languageActionsEnabled = languageActionsEnabled,
                        onLanguageAction = onLanguageAction,
                        useInAppKeyboard = editorKeyboard.useInAppKeyboard,
                        onEditorViewChanged = {
                            activeEditorView = it
                            if (it == null) inAppKbVisible = false
                        },
                        onKeyboardRequested = { inAppKbVisible = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    pageContent(activeTab)
                }
            }
        } else {
            // Empty state
            Box(
                modifier = Modifier.weight(1f, fill = true),
                contentAlignment = Alignment.Center,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "No file open",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Open a file to start editing",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // In-app keyboard: shown when enabled, the editor has been tapped, and a file is active.
        val showKeyboard = editorKeyboard.useInAppKeyboard &&
            inAppKbVisible &&
            activeTab?.editorState != null &&
            activeEditorView != null
        if (showKeyboard) {
            InAppKeyboard(
                codeKeys = editorKeyboard.codeKeys,
                onAction = { action ->
                    val v = activeEditorView ?: return@InAppKeyboard
                    when (action) {
                        is KeyAction.Text -> v.insertTextAtCaret(action.value)
                        KeyAction.Backspace -> v.backspace()
                        KeyAction.Enter -> v.insertTextAtCaret("\n")
                        KeyAction.Space -> v.insertTextAtCaret(" ")
                        KeyAction.Tab -> v.insertIndent()
                        KeyAction.Left -> v.moveCaretBy(-1)
                        KeyAction.Right -> v.moveCaretBy(1)
                        KeyAction.Up -> v.moveCaretLineBy(-1)
                        KeyAction.Down -> v.moveCaretLineBy(1)
                        KeyAction.Hide -> inAppKbVisible = false
                    }
                },
            )
        }
    }
}

/**
 * Horizontal tab strip for editor tabs.
 */
@Composable
private fun TabStrip(
    group: EditorGroup,
    onTabSelected: (String) -> Unit,
    onTabClosed: (String) -> Unit,
    onOpenFile: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .height(36.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            group.tabs.forEach { tab ->
                TabItem(
                    tab = tab,
                    isActive = tab.id == group.activeTabId,
                    onSelected = { onTabSelected(tab.id) },
                    onClosed = { onTabClosed(tab.id) },
                )
            }

            // Open file button
            IconButton(
                onClick = onOpenFile,
                modifier = Modifier
                    .width(36.dp)
                    .height(36.dp),
            ) {
                Text("+", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * Individual tab item.
 */
@Composable
private fun TabItem(
    tab: EditorTab,
    isActive: Boolean,
    onSelected: () -> Unit,
    onClosed: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onSelected)
            .background(
                if (isActive) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface
            )
            .height(36.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Dirty indicator
        if (tab.isDirty) {
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .height(8.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }

        Text(
            text = tab.title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )

        IconButton(
            onClick = onClosed,
            modifier = Modifier
                .width(20.dp)
                .height(20.dp),
        ) {
            Text(
                text = "×",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Hosts the custom EditorView inside Compose via AndroidView.
 */
@Composable
fun EditorViewHost(
    editorState: dev.jcode.core.editor.EditorState,
    modifier: Modifier = Modifier,
    languageActionsEnabled: Boolean = false,
    onLanguageAction: (EditorLanguageAction, String) -> Unit = { _, _ -> },
    useInAppKeyboard: Boolean = false,
    onEditorViewChanged: (EditorView?) -> Unit = {},
    onKeyboardRequested: () -> Unit = {},
) {
    val density = LocalDensity.current
    var view by remember { mutableStateOf<EditorView?>(null) }
    var menu by remember { mutableStateOf<EditorContextRequest?>(null) }

    Box(modifier = modifier.clipToBounds()) {
        AndroidView(
            factory = { context ->
                EditorView(context).apply {
                    attach(editorState)
                    onContextRequest = { menu = it }
                    this.useInAppKeyboard = useInAppKeyboard
                    onInAppKeyboardRequested = onKeyboardRequested
                    view = this
                    onEditorViewChanged(this)
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { v ->
                v.attach(editorState)
                v.onContextRequest = { menu = it }
                v.useInAppKeyboard = useInAppKeyboard
                v.onInAppKeyboardRequested = onKeyboardRequested
                view = v
                onEditorViewChanged(v)
            },
            onRelease = {
                onEditorViewChanged(null)
                it.onInAppKeyboardRequested = null
                it.detach()
            },
        )

        menu?.let { req ->
            val offset = with(density) { DpOffset(req.xPx.toDp(), req.yPx.toDp()) }
            DropdownMenu(
                expanded = true,
                onDismissRequest = { menu = null },
                offset = offset,
            ) {
                DropdownMenuItem(text = { Text("Copy") }, onClick = { view?.copySelection(); menu = null })
                DropdownMenuItem(text = { Text("Cut") }, onClick = { view?.cutSelection(); menu = null })
                DropdownMenuItem(text = { Text("Paste") }, onClick = { view?.pasteClipboard(); menu = null })
                DropdownMenuItem(text = { Text("Select all") }, onClick = { view?.selectAll(); menu = null })
                if (languageActionsEnabled) {
                    HorizontalDivider()
                    EditorLanguageAction.entries.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(action.label) },
                            onClick = { onLanguageAction(action, req.word); menu = null },
                        )
                    }
                }
            }
        }
    }

    DisposableEffect(editorState) {
        onDispose { /* EditorState lifecycle managed by EditorTab */ }
    }
}
