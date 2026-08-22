package dev.blamspot.jcode.feature.editor.pane

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import dev.blamspot.jcode.core.editor.CompletionAnchor
import dev.blamspot.jcode.core.editor.EditorContextRequest
import dev.blamspot.jcode.core.editor.EditorTheme
import dev.blamspot.jcode.core.editor.EditorLanguageAction
import dev.blamspot.jcode.core.editor.EditorView
import dev.blamspot.jcode.core.buffer.offsetToUtf16Position
import dev.blamspot.jcode.core.editor.completion.CompletionContext
import dev.blamspot.jcode.core.editor.completion.CompletionItem
import dev.blamspot.jcode.core.editor.completion.CompletionQuery
import dev.blamspot.jcode.core.editor.completion.CompletionWindow
import dev.blamspot.jcode.core.editor.completion.EditorCompletionModule
import dev.blamspot.jcode.core.editor.completion.LocalCompletionSource
import dev.blamspot.jcode.design.CompactContextMenu
import dev.blamspot.jcode.design.ContextAction
import dev.blamspot.jcode.design.LocalChromeControls
import dev.blamspot.jcode.design.LocalEditorTabColors
import dev.blamspot.jcode.design.LocalTabMaxSize
import dev.blamspot.jcode.design.MiddleEllipsisText
import dev.blamspot.jcode.design.TabColorDialog
import dev.blamspot.jcode.design.tabColorToHex
import dev.blamspot.jcode.design.ExtraKey
import dev.blamspot.jcode.design.ExtraKeysTarget
import dev.blamspot.jcode.design.LocalEditorDragMovesCursor
import dev.blamspot.jcode.design.LocalEditorSaveActions
import dev.blamspot.jcode.design.LocalEditorTabActions
import dev.blamspot.jcode.design.LocalEditorTypeface
import dev.blamspot.jcode.design.LocalExtraKeysState
import dev.blamspot.jcode.design.JCodeIcon
import dev.blamspot.jcode.design.JcTooltip
import dev.blamspot.jcode.design.LocalTabCloseButtonSetting
import dev.blamspot.jcode.design.jcIcon
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
    onFind: () -> Unit = {},
    languageActionsEnabled: Boolean = false,
    onLanguageAction: (EditorLanguageAction, String, Int) -> Unit = { _, _, _ -> },
    breakpointLinesFor: (EditorTab) -> Set<Int> = { emptySet() },
    stoppedLineFor: (EditorTab) -> Int? = { null },
    onToggleBreakpoint: (EditorTab, Int) -> Unit = { _, _ -> },
    evaluateInDebugFrame: ((String, (InspectedValue?) -> Unit) -> Unit)? = null,
    /** Children of an expandable inspected value, by its opaque [InspectedValue.reference]. */
    expandInDebugFrame: ((Int, (List<InspectedValue>) -> Unit) -> Unit)? = null,
    /** Ctrl and the wheel over the editor: +1 a step bigger, -1 smaller. */
    onFontSizeStep: ((Int) -> Unit)? = null,
    pageContent: @Composable (EditorTab) -> Unit = {},
) {
    Column(modifier = modifier.clipToBounds()) {
        // Tab strip — explicit fixed height so it's never compressed. Collapses together with the
        // workbench header when the palette's "Hide Header and Tabs" mode is on.
        AnimatedVisibility(
            visible = !LocalChromeControls.current.chromeHidden,
            enter = expandVertically(animationSpec = tween(200)),
            exit = shrinkVertically(animationSpec = tween(200)),
        ) {
            TabStrip(
                group = group,
                onTabSelected = onTabSelected,
                onTabClosed = onTabClosed,
                onOpenFile = onOpenFile,
            )
        }

        // Active tab body: a file tab hosts the editor view; a page tab renders host content.
        val activeTab = group.activeTab
        if (activeTab != null) {
            Box(
                modifier = Modifier
                    .weight(1f, fill = true)
                    .clipToBounds(),
            ) {
                val editorState = activeTab.editorState
                if (editorState != null && !activeTab.previewMode) {
                    EditorViewHost(
                        editorState = editorState,
                        documentPath = activeTab.filePath.path,
                        onSave = onSave,
                        onFind = onFind,
                        onCloseTab = { onTabClosed(activeTab.id) },
                        languageActionsEnabled = languageActionsEnabled,
                        onLanguageAction = onLanguageAction,
                        breakpointLines = breakpointLinesFor(activeTab),
                        stoppedLine = stoppedLineFor(activeTab),
                        onToggleBreakpoint = { line -> onToggleBreakpoint(activeTab, line) },
                        evaluateInDebugFrame = evaluateInDebugFrame,
                        expandInDebugFrame = expandInDebugFrame,
                        onFontSizeStep = onFontSizeStep,
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
    var colorDialogOpen by remember { mutableStateOf(false) }
    val tabActions = LocalEditorTabActions.current
    val tabColors = LocalEditorTabColors.current
    val accent = if (tab.isPage) null else tabColors.colorFor(tab.filePath.path)
    Box {
        Row(
            modifier = Modifier
                // Long-press always offers Close, so the tab stays closeable even when the "×" is
                // hidden (pinned tab, or the avoid-accidental-close setting).
                .combinedClickable(onClick = onSelected, onLongClick = { menuOpen = true })
                .background(
                    if (isActive) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.surface
                )
                // Tab-color accent: a thin bar along the top edge (Settings → Tabs → Tab coloring).
                .drawBehind { accent?.let { drawRect(it, size = Size(size.width, 3.dp.toPx())) } }
                .height(36.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // A pinned tab shows a leading pin instead of a close "×": it sorts to the front and is
            // protected from accidental close (close it via the long-press menu).
            if (tab.pinned) {
                Icon(
                    imageVector = jcIcon(JCodeIcon.Pin),
                    contentDescription = "Pinned",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp),
                )
            }
            MiddleEllipsisText(
                text = tab.title,
                maxWidth = LocalTabMaxSize.current.size.titleMaxWidth,
                style = MaterialTheme.typography.labelMedium,
            )

            // Trailing slot: a dirty tab shows the unsaved-changes dot; the "×" appears on the active,
            // unpinned tab. An active dirty tab shows BOTH (dot + ×) so it stays one-tap closeable —
            // the close then routes through the "unsaved changes" prompt.
            val showClose = isActive && !tab.pinned && !LocalTabCloseButtonSetting.current.hidden
            if (tab.isDirty || showClose) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (tab.isDirty) {
                        JcTooltip("Unsaved changes") {
                            Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                                ModifiedDot()
                            }
                        }
                    }
                    if (showClose) {
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
        }
        CompactContextMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            listActions = buildList {
                add(ContextAction(JCodeIcon.Pin, if (tab.pinned) "Unpin" else "Pin") { tabActions.onTogglePin(tab.id) })
                // Real file tabs only (non-blank path), and hidden when tab coloring is Disabled.
                if (!tab.isPage && tab.filePath.path.isNotBlank() && tabColors.pickerEnabled) {
                    add(ContextAction(JCodeIcon.Palette, "Change Tab Color") { colorDialogOpen = true })
                }
                add(ContextAction(JCodeIcon.Close, "Close") { onClosed() })
                add(ContextAction(JCodeIcon.Close, "Close others") { tabActions.onCloseOthers(tab.id) })
                add(ContextAction(JCodeIcon.Close, "Close to the right") { tabActions.onCloseToRight(tab.id) })
            },
        )
        if (colorDialogOpen) {
            TabColorDialog(
                currentHex = accent?.let { tabColorToHex(it) },
                onPick = { tabActions.onSetTabColor(tab.id, tabColorToHex(it)); colorDialogOpen = false },
                onClear = { tabActions.onSetTabColor(tab.id, null); colorDialogOpen = false },
                onDismiss = { colorDialogOpen = false },
            )
        }
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
    editorState: dev.blamspot.jcode.core.editor.EditorState,
    modifier: Modifier = Modifier,
    /** Host path of the open document; a language server resolves requests against it. */
    documentPath: String = "",
    onSave: () -> Unit = {},
    onFind: () -> Unit = {},
    onCloseTab: () -> Unit = {},
    languageActionsEnabled: Boolean = false,
    onLanguageAction: (EditorLanguageAction, String, Int) -> Unit = { _, _, _ -> },
    breakpointLines: Set<Int> = emptySet(),
    stoppedLine: Int? = null,
    onToggleBreakpoint: (Int) -> Unit = {},
    evaluateInDebugFrame: ((String, (InspectedValue?) -> Unit) -> Unit)? = null,
    /** Children of an expandable inspected value, by its opaque [InspectedValue.reference]. */
    expandInDebugFrame: ((Int, (List<InspectedValue>) -> Unit) -> Unit)? = null,
    /** Ctrl and the wheel: +1 a step bigger, -1 smaller. Null leaves the editor un-zoomable. */
    onFontSizeStep: ((Int) -> Unit)? = null,
) {
    val density = LocalDensity.current
    var view by remember { mutableStateOf<EditorView?>(null) }
    var menu by remember { mutableStateOf<EditorContextRequest?>(null) }
    var completionAnchor by remember { mutableStateOf<CompletionAnchor?>(null) }
    var inspection by remember { mutableStateOf<VariableInspection?>(null) }
    var inspectDetail by remember { mutableStateOf<InspectedValue?>(null) }
    // Variable inspection is active only while a debug session is stopped (the host passes a non-null
    // evaluator). Rather than consuming the press, it lets the normal path run — the word gets selected
    // (highlighted) and the context menu opens — and resolves the value alongside; when it arrives it
    // shows as that menu's header, so one long-press yields the peek AND the editor actions together.
    val wordLongPressHandler: ((String, Float, Float) -> Boolean)? = evaluateInDebugFrame?.let { eval ->
        { word, _, _ ->
            inspection = null
            eval(word) { resolved ->
                if (resolved != null) inspection = VariableInspection(word, resolved)
            }
            false
        }
    }
    val completionSource = LocalCompletionSource.current
    val menuExtras = LocalEditorMenuExtras.current
    val saveActions = LocalEditorSaveActions.current
    val dragSetting = LocalEditorDragMovesCursor.current
    val dragCursorEnabled = dragSetting.enabled
    val dragCursorVLevel = dragSetting.verticalLevel
    val dragCursorHLevel = dragSetting.horizontalLevel
    val extraKeys = LocalExtraKeysState.current
    val editorTypeface = LocalEditorTypeface.current

    // A completion popup belongs to its file; clear it when the active editor (tab) changes.
    LaunchedEffect(editorState) { completionAnchor = null }

    // An inspection belongs to the stopped debug frame; clear it on tab switch or resume.
    LaunchedEffect(editorState, evaluateInDebugFrame == null) { inspection = null; inspectDetail = null }

    // The editor is a custom Canvas view and doesn't inherit MaterialTheme, so derive its colors from
    // the active theme bundle here: content background follows the app background (true black under the
    // OLED bundle), while the gutter/line-number strip uses the faintly-lighter surface so it lifts off
    // the black. Keeps every bundle's editor in sync with its palette (Catppuccin's background/surface
    // already equal the old fixed defaults, so it's unchanged).
    val cs = MaterialTheme.colorScheme
    val editorTheme = remember(cs.background, cs.surface) {
        // Only the two backgrounds follow the bundle: content bg from the app background (true black
        // under OLED) and the gutter from the faintly-lighter surface. Foreground/line-number/selection/
        // cursor stay on the shared dark/light presets, so non-OLED bundles (e.g. Catppuccin, whose
        // background/surface already equal the presets) render exactly as before.
        val base = if (cs.background.luminance() < 0.5f) EditorTheme.DARK else EditorTheme.LIGHT
        base.copy(
            background = cs.background.toArgb().toLong() and 0xFFFFFFFFL,
            gutterBackground = cs.surface.toArgb().toLong() and 0xFFFFFFFFL,
        )
    }
    LaunchedEffect(editorState, editorTheme) {
        editorState.updateTheme { editorTheme }
    }

    // Apply breakpoint dots (GUTTER) + the current-stopped line marker/highlight (BACKGROUND). These
    // layers are independent of syntax (GLYPH_COLOR), so replacing them never clobbers highlighting.
    LaunchedEffect(editorState, breakpointLines, stoppedLine) {
        val markers = buildList<dev.blamspot.jcode.core.editor.decor.Decoration> {
            breakpointLines.forEach { line ->
                add(
                    dev.blamspot.jcode.core.editor.decor.GutterMarkerDecoration(
                        id = "bp:$line", line = line, color = 0xFFE5484D.toInt(),
                        kind = dev.blamspot.jcode.core.editor.decor.GutterMarkerDecoration.Kind.Breakpoint,
                    ),
                )
            }
            stoppedLine?.let { l ->
                add(
                    dev.blamspot.jcode.core.editor.decor.GutterMarkerDecoration(
                        id = "cur", line = l, color = 0xFFF2C94C.toInt(),
                        kind = dev.blamspot.jcode.core.editor.decor.GutterMarkerDecoration.Kind.CurrentLine,
                    ),
                )
            }
        }
        val highlights = buildList<dev.blamspot.jcode.core.editor.decor.Decoration> {
            stoppedLine?.let { l ->
                add(dev.blamspot.jcode.core.editor.decor.LineHighlightDecoration(id = "curline", line = l, color = 0x33F2C94C))
            }
        }
        editorState.updateDecorations {
            it.replaceLayer(dev.blamspot.jcode.core.editor.decor.Layer.GUTTER, markers)
                .replaceLayer(dev.blamspot.jcode.core.editor.decor.Layer.BACKGROUND, highlights)
        }
    }

    Box(modifier = modifier.clipToBounds()) {
        AndroidView(
            factory = { context ->
                EditorView(context).apply {
                    this.onFontSizeStep = onFontSizeStep
                    setEditorTypeface(editorTypeface)
                    attach(editorState)
                    onContextRequest = { menu = it }
                    onSaveRequest = { onSave() }
                    onSaveAllRequest = { saveActions.onSaveAll() }
                    onFindRequest = { onFind() }
                    onGoToLineRequest = { menuExtras.onGoToLine?.invoke() }
                    onCloseTabRequest = { onCloseTab() }
                    onCompletionAnchorChanged = { completionAnchor = it }
                    onGutterTap = { onToggleBreakpoint(it) }
                    onWordLongPress = wordLongPressHandler
                    dragMovesCursor = dragCursorEnabled
                    cursorDragVerticalLevel = dragCursorVLevel
                    cursorDragHorizontalLevel = dragCursorHLevel
                    // Points the extra-keys row at this editor while it owns the IME.
                    val keysAdapter = EditorExtraKeysTarget(this)
                    onFocusStateChanged = { focused ->
                        if (focused) {
                            extraKeys.clearModifiers()
                            extraKeys.target = keysAdapter
                        } else if (extraKeys.target === keysAdapter) {
                            extraKeys.clearModifiers()
                            extraKeys.target = null
                        }
                    }
                    if (isFocused) extraKeys.target = keysAdapter
                    view = this
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { v ->
                v.setEditorTypeface(editorTypeface)
                v.attach(editorState)
                v.onContextRequest = { menu = it }
                v.onSaveRequest = { onSave() }
                v.onSaveAllRequest = { saveActions.onSaveAll() }
                v.onFindRequest = { onFind() }
                v.onGoToLineRequest = { menuExtras.onGoToLine?.invoke() }
                v.onCloseTabRequest = { onCloseTab() }
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
        // A language server answers over IPC, so the list arrives after the popup could have opened.
        // Keyed on the caret as well as the prefix: the same prefix at a different position is a
        // different question, and re-typing over a stale list would show the wrong suggestions.
        var completionItems by remember { mutableStateOf<List<CompletionItem>>(emptyList()) }
        LaunchedEffect(anchor?.prefix, anchor?.caret, completionSource, documentPath) {
            val current = anchor
            if (current == null) {
                completionItems = emptyList()
                return@LaunchedEffect
            }
            val snapshot = editorState.snapshot.value
            val (line, character) = snapshot.offsetToUtf16Position(current.caret)
            completionItems = completionSource.completions(
                CompletionQuery(
                    prefix = current.prefix,
                    path = documentPath,
                    line = line,
                    character = character,
                ),
            )
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

        // Opened from the menu header's "Inspect object" — the full value + field tree.
        inspectDetail?.let { root ->
            VariableDetailDialog(
                root = root,
                expand = expandInDebugFrame,
                onDismiss = { inspectDetail = null },
            )
        }

        menu?.let { req ->
            val offset = with(density) { DpOffset(req.xPx.toDp(), req.yPx.toDp()) }
            CompactContextMenu(
                expanded = true,
                onDismissRequest = { menu = null; inspection = null },
                offset = offset,
                // Show the resolved value as a header, but only for the word this menu is for — a
                // stale resolution from a previous long-press must not leak into a different word.
                header = inspection?.takeIf { it.word == req.word }?.let { insp ->
                    {
                        VariableInspectHeader(
                            inspection = insp,
                            expand = expandInDebugFrame,
                            onInspect = { menu = null; inspection = null; inspectDetail = insp.resolved },
                            onCopied = { menu = null; inspection = null },
                        )
                    }
                },
                quickActions = listOf(
                    ContextAction(JCodeIcon.Copy, "Copy") { view?.copySelection() },
                    ContextAction(JCodeIcon.Cut, "Cut") { view?.cutSelection() },
                    ContextAction(JCodeIcon.Paste, "Paste") { view?.pasteClipboard() },
                ),
                listActions = buildList {
                    add(ContextAction(JCodeIcon.Cursor, "Select Text") { view?.beginTextSelection() })
                    add(ContextAction(JCodeIcon.SelectAll, "Select all") { view?.selectAll() })
                    menuExtras.onGoToLine?.let { go ->
                        add(ContextAction(JCodeIcon.GoToLine, "Go to line") { go() })
                    }
                    menuExtras.onFindText?.let { find ->
                        add(ContextAction(JCodeIcon.Search, "Find text") { find(req.word) })
                    }
                    menuExtras.previewToggle?.let { toggle ->
                        add(ContextAction(menuExtras.previewIcon, menuExtras.previewLabel) { toggle() })
                    }
                    if (languageActionsEnabled) {
                        EditorLanguageAction.entries.forEach { action ->
                            add(ContextAction(action.menuIcon(), action.label) {
                                onLanguageAction(action, req.word, req.offset)
                            })
                        }
                    }
                    menuExtras.contributions.forEach { c ->
                        add(ContextAction(c.icon, c.label) { menuExtras.onContribution(c, req.word) })
                    }
                },
            )
        }
    }

    DisposableEffect(editorState) {
        onDispose { /* EditorState lifecycle managed by EditorTab */ }
    }
}

private fun EditorLanguageAction.menuIcon(): JCodeIcon = when (this) {
    EditorLanguageAction.GoToDefinition -> JCodeIcon.Definition
    EditorLanguageAction.FindReferences -> JCodeIcon.References
    EditorLanguageAction.RenameSymbol -> JCodeIcon.Rename
    EditorLanguageAction.FormatDocument -> JCodeIcon.Format
}

/** Routes extra-keys row presses to the editor as synthesized key events ([EditorView.dispatchKeyEvent]
 *  handles them against the attached state without needing focus or an IME session). */
private class EditorExtraKeysTarget(private val view: EditorView) : ExtraKeysTarget {

    override val keys = listOf(
        ExtraKey.Esc, ExtraKey.Tab,
        ExtraKey.Left, ExtraKey.Up, ExtraKey.Down, ExtraKey.Right,
        ExtraKey.Home, ExtraKey.End, ExtraKey.PageUp, ExtraKey.PageDown,
    )

    override fun onExtraKey(key: ExtraKey, ctrl: Boolean, alt: Boolean, shift: Boolean) {
        val keyCode = when (key) {
            ExtraKey.Esc -> android.view.KeyEvent.KEYCODE_ESCAPE
            ExtraKey.Tab -> android.view.KeyEvent.KEYCODE_TAB
            ExtraKey.Left -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
            ExtraKey.Up -> android.view.KeyEvent.KEYCODE_DPAD_UP
            ExtraKey.Down -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
            ExtraKey.Right -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            ExtraKey.Home -> android.view.KeyEvent.KEYCODE_MOVE_HOME
            ExtraKey.End -> android.view.KeyEvent.KEYCODE_MOVE_END
            ExtraKey.PageUp -> android.view.KeyEvent.KEYCODE_PAGE_UP
            ExtraKey.PageDown -> android.view.KeyEvent.KEYCODE_PAGE_DOWN
            else -> return
        }
        view.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
    }

    // [lines] positive = earlier content (up); the editor's scrollY grows downward, so negate.
    override fun onScroll(lines: Int) {
        view.scrollLines(-lines)
    }
}
