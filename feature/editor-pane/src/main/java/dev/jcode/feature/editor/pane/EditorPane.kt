package dev.jcode.feature.editor.pane

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.jcode.core.editor.CompletionAnchor
import dev.jcode.core.editor.EditorContextRequest
import dev.jcode.core.editor.EditorLanguageAction
import dev.jcode.core.editor.EditorView
import dev.jcode.core.editor.completion.CompletionContext
import dev.jcode.core.editor.completion.CompletionWindow
import dev.jcode.core.editor.completion.EditorCompletionModule
import dev.jcode.core.editor.completion.LocalCompletionSource
import dev.jcode.design.CompactContextMenu
import dev.jcode.design.ContextAction
import dev.jcode.design.LocalEditorDragMovesCursor
import dev.jcode.design.JCodeIcon
import dev.jcode.design.JcTooltip
import dev.jcode.design.LocalTabCloseButtonSetting
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
    onSave: () -> Unit = {},
    languageActionsEnabled: Boolean = false,
    onLanguageAction: (EditorLanguageAction, String) -> Unit = { _, _ -> },
    breakpointLinesFor: (EditorTab) -> Set<Int> = { emptySet() },
    stoppedLineFor: (EditorTab) -> Int? = { null },
    onToggleBreakpoint: (EditorTab, Int) -> Unit = { _, _ -> },
    evaluateInDebugFrame: ((String, (String?) -> Unit) -> Unit)? = null,
    pageContent: @Composable (EditorTab) -> Unit = {},
) {
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
                        onSave = onSave,
                        languageActionsEnabled = languageActionsEnabled,
                        onLanguageAction = onLanguageAction,
                        breakpointLines = breakpointLinesFor(activeTab),
                        stoppedLine = stoppedLineFor(activeTab),
                        onToggleBreakpoint = { line -> onToggleBreakpoint(activeTab, line) },
                        evaluateInDebugFrame = evaluateInDebugFrame,
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
            JcTooltip("Open file") {
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
}

/**
 * Individual tab item.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabItem(
    tab: EditorTab,
    isActive: Boolean,
    onSelected: () -> Unit,
    onClosed: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                // Long-press always offers Close, so the tab stays closeable even when the "×" is
                // hidden via the avoid-accidental-close setting.
                .combinedClickable(onClick = onSelected, onLongClick = { menuOpen = true })
                .background(
                    if (isActive) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.surface
                )
                .height(36.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = tab.title,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )

            // Trailing slot: a round dot marks unsaved changes (taking the "×" spot, editor-style);
            // a clean, closeable tab shows the "×". Closing is done from the tab's long-press menu.
            when {
                tab.isDirty -> {
                    JcTooltip("Unsaved changes") {
                        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                            ModifiedDot()
                        }
                    }
                }
                !LocalTabCloseButtonSetting.current.hidden -> {
                    JcTooltip("Close tab") {
                        // Plain clickable Box (not IconButton) so the touch target stays a tight 20dp;
                        // an IconButton's enforced 48dp minimum spills over the title and closes the
                        // tab on a title tap.
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .clickable(onClick = onClosed),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "×",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        CompactContextMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            listActions = listOf(
                ContextAction(JCodeIcon.Close, "Close") { onClosed() },
            ),
        )
    }
}

/** Small round dot marking a tab with unsaved changes. */
@Composable
private fun ModifiedDot() {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
    )
}

/**
 * Hosts the custom EditorView inside Compose via AndroidView.
 */
@Composable
fun EditorViewHost(
    editorState: dev.jcode.core.editor.EditorState,
    modifier: Modifier = Modifier,
    onSave: () -> Unit = {},
    languageActionsEnabled: Boolean = false,
    onLanguageAction: (EditorLanguageAction, String) -> Unit = { _, _ -> },
    breakpointLines: Set<Int> = emptySet(),
    stoppedLine: Int? = null,
    onToggleBreakpoint: (Int) -> Unit = {},
    evaluateInDebugFrame: ((String, (String?) -> Unit) -> Unit)? = null,
) {
    val density = LocalDensity.current
    var view by remember { mutableStateOf<EditorView?>(null) }
    var menu by remember { mutableStateOf<EditorContextRequest?>(null) }
    var completionAnchor by remember { mutableStateOf<CompletionAnchor?>(null) }
    var inspection by remember { mutableStateOf<VariableInspection?>(null) }
    // Long-press variable inspection is active only while a debug session is stopped (the host passes
    // a non-null evaluator). Consuming the press suppresses selection + the context menu.
    val wordLongPressHandler: ((String, Float, Float) -> Boolean)? = evaluateInDebugFrame?.let { eval ->
        { word, x, y ->
            eval(word) { value ->
                if (value != null) inspection = VariableInspection(word, value, x, y)
            }
            true
        }
    }
    val completionSource = LocalCompletionSource.current
    val dragSetting = LocalEditorDragMovesCursor.current
    val dragCursorEnabled = dragSetting.enabled
    val dragCursorVLevel = dragSetting.verticalLevel
    val dragCursorHLevel = dragSetting.horizontalLevel

    // A completion popup belongs to its file; clear it when the active editor (tab) changes.
    LaunchedEffect(editorState) { completionAnchor = null }

    // An inspection popup belongs to the stopped debug frame; clear it on tab switch or resume.
    LaunchedEffect(editorState, evaluateInDebugFrame == null) { inspection = null }

    // Apply breakpoint dots (GUTTER) + the current-stopped line marker/highlight (BACKGROUND). These
    // layers are independent of syntax (GLYPH_COLOR), so replacing them never clobbers highlighting.
    LaunchedEffect(editorState, breakpointLines, stoppedLine) {
        val markers = buildList<dev.jcode.core.editor.decor.Decoration> {
            breakpointLines.forEach { line ->
                add(
                    dev.jcode.core.editor.decor.GutterMarkerDecoration(
                        id = "bp:$line", line = line, color = 0xFFE5484D.toInt(),
                        kind = dev.jcode.core.editor.decor.GutterMarkerDecoration.Kind.Breakpoint,
                    ),
                )
            }
            stoppedLine?.let { l ->
                add(
                    dev.jcode.core.editor.decor.GutterMarkerDecoration(
                        id = "cur", line = l, color = 0xFFF2C94C.toInt(),
                        kind = dev.jcode.core.editor.decor.GutterMarkerDecoration.Kind.CurrentLine,
                    ),
                )
            }
        }
        val highlights = buildList<dev.jcode.core.editor.decor.Decoration> {
            stoppedLine?.let { l ->
                add(dev.jcode.core.editor.decor.LineHighlightDecoration(id = "curline", line = l, color = 0x33F2C94C))
            }
        }
        editorState.updateDecorations {
            it.replaceLayer(dev.jcode.core.editor.decor.Layer.GUTTER, markers)
                .replaceLayer(dev.jcode.core.editor.decor.Layer.BACKGROUND, highlights)
        }
    }

    Box(modifier = modifier.clipToBounds()) {
        AndroidView(
            factory = { context ->
                EditorView(context).apply {
                    attach(editorState)
                    onContextRequest = { menu = it }
                    onSaveRequest = { onSave() }
                    onCompletionAnchorChanged = { completionAnchor = it }
                    onGutterTap = { onToggleBreakpoint(it) }
                    onWordLongPress = wordLongPressHandler
                    dragMovesCursor = dragCursorEnabled
                    cursorDragVerticalLevel = dragCursorVLevel
                    cursorDragHorizontalLevel = dragCursorHLevel
                    view = this
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { v ->
                v.attach(editorState)
                v.onContextRequest = { menu = it }
                v.onSaveRequest = { onSave() }
                v.onCompletionAnchorChanged = { completionAnchor = it }
                v.onGutterTap = { onToggleBreakpoint(it) }
                v.onWordLongPress = wordLongPressHandler
                v.dragMovesCursor = dragCursorEnabled
                v.cursorDragVerticalLevel = dragCursorVLevel
                v.cursorDragHorizontalLevel = dragCursorHLevel
                view = v
            },
            onRelease = { it.detach() },
        )

        val anchor = completionAnchor
        val completionItems = remember(anchor?.prefix, completionSource) {
            anchor?.let { completionSource(it.prefix) } ?: emptyList()
        }
        if (anchor != null && completionItems.isNotEmpty()) {
            CompletionWindow(
                context = CompletionContext(completionItems, anchor.replaceStart, null),
                anchorX = anchor.xPx,
                anchorY = anchor.yPx,
                onDismiss = { completionAnchor = null },
                onSelect = { item ->
                    view?.let { v ->
                        val snippet = item.snippetText
                        if (snippet != null) {
                            val applied = EditorCompletionModule.snippetEngine.apply(snippet, anchor.replaceStart)
                            // Caret goes to the first real tab stop ($1…), else the final stop ($0), else end.
                            val firstStop = applied.tabStops.filter { it.number > 0 }.minByOrNull { it.number }
                            val zeroStop = applied.tabStops.firstOrNull { it.number == 0 }
                            val target = firstStop?.offset ?: zeroStop?.offset ?: applied.finalOffset
                            v.replaceRange(anchor.replaceStart, anchor.caret, applied.text, target)
                        } else {
                            val insert = item.insertText ?: item.label
                            val caretAfter = anchor.replaceStart + insert.toByteArray(Charsets.UTF_8).size
                            v.replaceRange(anchor.replaceStart, anchor.caret, insert, caretAfter)
                        }
                    }
                    completionAnchor = null
                },
            )
        }

        inspection?.let { insp ->
            VariableInspectPopup(inspection = insp, onDismiss = { inspection = null })
        }

        menu?.let { req ->
            val offset = with(density) { DpOffset(req.xPx.toDp(), req.yPx.toDp()) }
            CompactContextMenu(
                expanded = true,
                onDismissRequest = { menu = null },
                offset = offset,
                quickActions = listOf(
                    ContextAction(JCodeIcon.Copy, "Copy") { view?.copySelection() },
                    ContextAction(JCodeIcon.Cut, "Cut") { view?.cutSelection() },
                    ContextAction(JCodeIcon.Paste, "Paste") { view?.pasteClipboard() },
                ),
                listActions = buildList {
                    add(ContextAction(JCodeIcon.SelectAll, "Select all") { view?.selectAll() })
                    if (languageActionsEnabled) {
                        EditorLanguageAction.entries.forEach { action ->
                            add(ContextAction(action.menuIcon(), action.label) { onLanguageAction(action, req.word) })
                        }
                    }
                },
            )
        }
    }

    DisposableEffect(editorState) {
        onDispose { /* EditorState lifecycle managed by EditorTab */ }
    }
}

/** A resolved long-press variable inspection: the word, its evaluated value, and the press position. */
private data class VariableInspection(val word: String, val value: String, val xPx: Float, val yPx: Float)

/**
 * Small floating "name = value" card shown when a variable is long-pressed while the debugger is
 * stopped. Anchored at the press position (view-relative px), flipping above when out of room —
 * the same positioning scheme as the completion window.
 */
@Composable
private fun VariableInspectPopup(inspection: VariableInspection, onDismiss: () -> Unit) {
    val positionProvider = remember(inspection.xPx, inspection.yPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val x = (anchorBounds.left + inspection.xPx.toInt())
                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val below = anchorBounds.top + inspection.yPx.toInt() + 24
                val y = if (below + popupContentSize.height <= windowSize.height) {
                    below
                } else {
                    (anchorBounds.top + inspection.yPx.toInt() - popupContentSize.height - 12).coerceAtLeast(0)
                }
                return IntOffset(x, y)
            }
        }
    }
    Popup(
        popupPositionProvider = positionProvider,
        properties = PopupProperties(focusable = false, dismissOnBackPress = true, dismissOnClickOutside = true),
        onDismissRequest = onDismiss,
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp,
            modifier = Modifier.widthIn(min = 120.dp, max = 420.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    text = inspection.word,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = inspection.value,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 12,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun EditorLanguageAction.menuIcon(): JCodeIcon = when (this) {
    EditorLanguageAction.GoToDefinition -> JCodeIcon.Definition
    EditorLanguageAction.FindReferences -> JCodeIcon.References
    EditorLanguageAction.RenameSymbol -> JCodeIcon.Rename
    EditorLanguageAction.FormatDocument -> JCodeIcon.Format
}
