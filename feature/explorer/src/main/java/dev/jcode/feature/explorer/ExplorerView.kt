package dev.jcode.feature.explorer
import dev.jcode.design.JCodeIcon
import dev.jcode.design.jcIcon

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jcode.design.DenseRow
import dev.jcode.design.LocalDensityMode
import dev.jcode.design.LocalIconSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jcode.fs.Fs
import dev.jcode.fs.FsKind
import dev.jcode.fs.FsNode
import dev.jcode.fs.FsPath
import dev.jcode.fs.Project
import dev.jcode.fs.Workspace
import dev.jcode.fs.copyFileOrDir
import dev.jcode.fs.createFile
import dev.jcode.fs.createDirectory
import dev.jcode.fs.deleteToTrash
import dev.jcode.fs.renameFile
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main explorer composable with tree/list toggle, breadcrumb, and file operations.
 *
 * @param workspace current workspace
 * @param project the project to explore
 * @param fs filesystem implementation for the project
 * @param context android context for SAF operations
 * @param onFileSelected callback when a file is tapped
 * @param onSnackbar callback for showing user messages
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExplorerView(
    workspace: Workspace?,
    project: Project,
    fs: Fs,
    context: Context,
    modifier: Modifier = Modifier,
    viewMode: ExplorerViewMode = ExplorerViewMode.Tree,
    onFileSelected: ((FsNode) -> Unit)? = null,
    onSnackbar: ((String) -> Unit)? = null,
) {
    // Key only on project + fs (not the workspace object, which gets a new identity on every
    // roster mutation). Otherwise the view model is rebuilt but LaunchedEffect(project) — keyed on
    // the data-equal project — doesn't re-fire, leaving the tree stuck on "No files yet".
    val viewModel = remember(project, fs) {
        TreeViewModel(workspace, fs, initialViewMode = viewMode)
    }
    val scope = rememberCoroutineScope()

    val treeRows by viewModel.treeRows.collectAsStateWithLifecycle()
    val listRows by viewModel.listRows.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val breadcrumb by viewModel.breadcrumb.collectAsStateWithLifecycle()
    val currentPath by viewModel.currentPath.collectAsStateWithLifecycle()
    // The configured [viewMode] is pushed into the view model below; [activeViewMode] reflects the
    // shape the view model has actually materialized, so the UI never renders an empty mismatched flow.
    val activeViewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    val selectionState = viewModel.selectionState
    val selectedIds by selectionState.selected.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf<CreateTarget?>(null) }
    var showRenameDialog by remember { mutableStateOf<RenameTarget?>(null) }

    // Clipboard for copy/cut operations
    var clipboard by remember { mutableStateOf<ClipboardEntry?>(null) }

    // Load project root on first composition
    LaunchedEffect(project) {
        viewModel.loadProjectRoot(project)
    }

    // The view mode is an app preference (Settings); react when it changes.
    LaunchedEffect(viewMode) {
        viewModel.setViewMode(viewMode)
    }

    // Where toolbar create/paste should land: the viewed dir in List mode; the selected dir (or the
    // selected file's parent) in Tree mode, falling back to the project root.
    fun resolveCreateParent(): FsPath {
        if (activeViewMode == ExplorerViewMode.List) return currentPath ?: project.fsPath
        val selected = treeRows.firstOrNull { it.id in selectedIds && !it.isPlaceholder }
            ?: return project.fsPath
        return when (selected.node.kind) {
            FsKind.Directory -> selected.node.path
            else -> parentPathOf(selected.node.path) ?: project.fsPath
        }
    }

    fun runRowAction(row: TreeRow, action: RowAction) {
        when (action) {
            RowAction.Open -> onFileSelected?.invoke(row.node)
            RowAction.NewFile -> showCreateDialog = CreateTarget(row.node.path, isDirectory = false)
            RowAction.NewFolder -> showCreateDialog = CreateTarget(row.node.path, isDirectory = true)
            RowAction.Rename -> showRenameDialog = RenameTarget(row.node.path, row.node.name)
            RowAction.Copy -> {
                clipboard = ClipboardEntry(row.node.path, row.node.name, isCut = false)
                onSnackbar?.invoke("Copied '${row.node.name}'")
            }
            RowAction.Cut -> {
                clipboard = ClipboardEntry(row.node.path, row.node.name, isCut = true)
                onSnackbar?.invoke("Cut '${row.node.name}'")
            }
            RowAction.Delete -> scope.launch {
                runCatching {
                    deleteToTrash(fs, context, row.node.path, project.fsPath)
                    viewModel.refresh()
                    onSnackbar?.invoke("Moved '${row.node.name}' to trash")
                }.onFailure { onSnackbar?.invoke("Delete failed: ${it.message}") }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Compact action toolbar (always visible) with create/refresh/paste. The Tree|List view mode
        // is set in Settings, not here.
        ExplorerToolbar(
            viewMode = activeViewMode,
            onCreateFile = { showCreateDialog = CreateTarget(resolveCreateParent(), isDirectory = false) },
            onCreateFolder = { showCreateDialog = CreateTarget(resolveCreateParent(), isDirectory = true) },
            onCollapseAll = { scope.launch { viewModel.collapseAll() } },
            onRefresh = { scope.launch { viewModel.refresh() } },
            onPaste = {
                clipboard?.let { entry ->
                    scope.launch {
                        runCatching {
                            val targetParent = resolveCreateParent()
                            copyFileOrDir(fs, context, entry.sourcePath, targetParent)
                            viewModel.refresh()
                            onSnackbar?.invoke("Pasted '${entry.name}'")
                            if (entry.isCut) {
                                deleteToTrash(fs, context, entry.sourcePath, project.fsPath)
                                viewModel.refresh()
                            }
                            clipboard = null
                        }.onFailure { onSnackbar?.invoke("Paste failed: ${it.message}") }
                    }
                }
            },
            canPaste = clipboard != null,
        )

        // Content. Tree shows the hierarchy from the root (no breadcrumb); List is a flat
        // file-manager for one directory, navigated via the breadcrumb + up button. The scrollable
        // body must take the *remaining* height (weight) so its LazyColumn is bounded and scrolls;
        // a bare fillMaxSize would overflow under the toolbar and hide the last rows.
        when (activeViewMode) {
            ExplorerViewMode.Tree -> Box(modifier = Modifier.weight(1f)) {
                TreeViewContent(
                    rows = treeRows,
                    isLoading = isLoading,
                    selectedIds = selectedIds,
                    selectionState = selectionState,
                    onToggleExpand = { row -> scope.launch { viewModel.toggleExpand(row) } },
                    onRowClick = { row ->
                        if (row.node.kind == FsKind.Directory) {
                            scope.launch { viewModel.toggleExpand(row) }
                        } else {
                            onFileSelected?.invoke(row.node)
                        }
                    },
                    onAction = { row, action -> runRowAction(row, action) },
                )
            }

            ExplorerViewMode.List -> {
                ExplorerBreadcrumb(
                    entries = breadcrumb,
                    onNavigate = { entry -> scope.launch { viewModel.navigateTo(entry.path, entry.label) } },
                    onNavigateUp = { scope.launch { viewModel.navigateUp() } },
                )
                Box(modifier = Modifier.weight(1f)) {
                    ListViewContent(
                        rows = listRows,
                        isLoading = isLoading,
                        selectedIds = selectedIds,
                        selectionState = selectionState,
                        onRowClick = { row ->
                            if (row.node.kind == FsKind.Directory) {
                                scope.launch { viewModel.navigateTo(row.node.path, row.node.name) }
                            } else {
                                onFileSelected?.invoke(row.node)
                            }
                        },
                        onAction = { row, action -> runRowAction(row, action) },
                    )
                }
            }
        }
    }

    // Create dialog
    showCreateDialog?.let { target ->
        CreateRenameDialog(
            title = if (target.isDirectory) "New Folder" else "New File",
            initialName = if (target.isDirectory) "new-folder" else "new-file.txt",
            onDismiss = { showCreateDialog = null },
            onConfirm = { name ->
                scope.launch {
                    runCatching {
                        if (target.isDirectory) {
                            createDirectory(fs, context, target.parentPath, name)
                        } else {
                            createFile(fs, context, target.parentPath, name)
                        }
                        viewModel.refresh()
                        onSnackbar?.invoke("Created '$name'")
                    }.onFailure {
                        onSnackbar?.invoke("Create failed: ${it.message}")
                    }
                }
                showCreateDialog = null
            },
        )
    }

    // Rename dialog
    showRenameDialog?.let { target ->
        CreateRenameDialog(
            title = "Rename",
            initialName = target.currentName,
            onDismiss = { showRenameDialog = null },
            onConfirm = { newName ->
                scope.launch {
                    runCatching {
                        renameFile(fs, context, target.path, newName)
                        viewModel.refresh()
                        onSnackbar?.invoke("Renamed to '$newName'")
                    }.onFailure {
                        onSnackbar?.invoke("Rename failed: ${it.message}")
                    }
                }
                showRenameDialog = null
            },
        )
    }
}

data class CreateTarget(val parentPath: FsPath, val isDirectory: Boolean)
data class RenameTarget(val path: FsPath, val currentName: String)

/** Represents a file/directory in the clipboard for copy/cut operations. */
data class ClipboardEntry(
    val sourcePath: FsPath,
    val name: String,
    val isCut: Boolean, // true if cut, false if copy
)

/** Per-row actions surfaced via the visible overflow (⋮) menu. */
enum class RowAction { Open, NewFile, NewFolder, Rename, Copy, Cut, Delete }

/** Parent of a path, or null when there is no addressable parent (SAF tree URIs). */
private fun parentPathOf(path: FsPath): FsPath? = when (path) {
    is FsPath.Local -> path.file.parentFile?.let { FsPath.Local(it) }
    is FsPath.Saf -> null
}

// --- Row overflow menu (visible ⋮ on every row) ---

@Composable
private fun RowOverflowMenu(
    row: TreeRow,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onAction: (TreeRow, RowAction) -> Unit,
) {
    val isDir = row.node.kind == FsKind.Directory
    Box {
        IconButton(
            onClick = { onExpandedChange(true) },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = jcIcon(JCodeIcon.MoreVert),
                contentDescription = "More actions",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            fun act(a: RowAction) {
                onExpandedChange(false)
                onAction(row, a)
            }
            if (!isDir) {
                DropdownMenuItem(text = { Text("Open") }, onClick = { act(RowAction.Open) })
            }
            if (isDir) {
                DropdownMenuItem(text = { Text("New File…") }, onClick = { act(RowAction.NewFile) })
                DropdownMenuItem(text = { Text("New Folder…") }, onClick = { act(RowAction.NewFolder) })
            }
            DropdownMenuItem(text = { Text("Rename…") }, onClick = { act(RowAction.Rename) })
            DropdownMenuItem(text = { Text("Copy") }, onClick = { act(RowAction.Copy) })
            DropdownMenuItem(text = { Text("Cut") }, onClick = { act(RowAction.Cut) })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { act(RowAction.Delete) })
        }
    }
}

// --- Breadcrumb ---

@Composable
private fun ExplorerBreadcrumb(
    entries: List<TreeViewModel.BreadcrumbEntry>,
    onNavigate: (TreeViewModel.BreadcrumbEntry) -> Unit,
    onNavigateUp: () -> Unit,
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entries.size > 1) {
            IconButton(onClick = onNavigateUp, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = jcIcon(JCodeIcon.ArrowUp),
                    contentDescription = "Up one level",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
        }
        entries.forEachIndexed { index, entry ->
            if (index > 0) {
                Text(
                    text = "›",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
            Text(
                text = entry.label,
                style = MaterialTheme.typography.labelSmall,
                color = if (index == entries.lastIndex) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier
                    .clickable { onNavigate(entry) }
                    .padding(vertical = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// --- Tree View ---

@Composable
private fun TreeViewContent(
    rows: List<TreeRow>,
    isLoading: Boolean,
    selectedIds: Set<String>,
    selectionState: ExplorerSelectionState,
    onToggleExpand: (TreeRow) -> Unit,
    onRowClick: (TreeRow) -> Unit,
    onAction: (TreeRow, RowAction) -> Unit,
) {
    if (isLoading && rows.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        }
        return
    }
    if (rows.isEmpty()) {
        EmptyExplorerHint()
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(rows, key = { it.id }) { row ->
            TreeRowItem(
                row = row,
                isSelected = row.id in selectedIds,
                onToggleExpand = onToggleExpand,
                onClick = {
                    selectionState.selectSingle(row.id)
                    onRowClick(row)
                },
                onAction = onAction,
            )
        }
    }
}

@Composable
private fun EmptyExplorerHint() {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = "No files yet.\nUse the New File / New Folder buttons above.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Width reserved for the disclosure chevron so files (no chevron) align under folders. */
private val ChevronSlot = 28.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeRowItem(
    row: TreeRow,
    isSelected: Boolean,
    onToggleExpand: (TreeRow) -> Unit,
    onClick: () -> Unit,
    onAction: (TreeRow, RowAction) -> Unit,
) {
    val iconSize = LocalIconSize.current
    val indent = (row.depth * 16).dp

    if (row.isPlaceholder) {
        DenseRow(
            leading = { Spacer(modifier = Modifier.width(indent + ChevronSlot + iconSize)) },
            content = {
                Text(
                    text = row.node.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            },
        )
        return
    }

    var menuExpanded by remember { mutableStateOf(false) }
    val isDir = row.node.kind == FsKind.Directory

    DenseRow(
        modifier = Modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuExpanded = true },
            )
            .then(
                if (isSelected) {
                    Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                } else {
                    Modifier
                }
            ),
        leading = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(modifier = Modifier.width(indent))
                if (isDir) {
                    Box(
                        modifier = Modifier
                            .size(ChevronSlot)
                            .clickable { onToggleExpand(row) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (row.isExpanded) jcIcon(JCodeIcon.ChevronDown) else jcIcon(JCodeIcon.ChevronRight),
                            contentDescription = if (row.isExpanded) "Collapse" else "Expand",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(iconSize),
                        )
                    }
                    Icon(
                        imageVector = jcIcon(JCodeIcon.Folder),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(iconSize),
                    )
                } else {
                    Spacer(modifier = Modifier.width(ChevronSlot))
                    Icon(
                        imageVector = jcIcon(JCodeIcon.Output),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(iconSize),
                    )
                }
            }
        },
        content = {
            Text(
                text = row.node.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                row.badge?.let { badge -> BadgeContent(badge) }
                RowOverflowMenu(
                    row = row,
                    expanded = menuExpanded,
                    onExpandedChange = { menuExpanded = it },
                    onAction = onAction,
                )
            }
        },
    )
}

// --- List View ---

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListViewContent(
    rows: List<TreeRow>,
    isLoading: Boolean,
    selectedIds: Set<String>,
    selectionState: ExplorerSelectionState,
    onRowClick: (TreeRow) -> Unit,
    onAction: (TreeRow, RowAction) -> Unit,
) {
    if (isLoading && rows.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        }
        return
    }
    if (rows.isEmpty()) {
        EmptyExplorerHint()
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(rows, key = { it.id }) { row ->
            var menuExpanded by remember { mutableStateOf(false) }
            val iconSize = LocalIconSize.current
            DenseRow(
                modifier = Modifier
                    .combinedClickable(
                        onClick = {
                            selectionState.selectSingle(row.id)
                            onRowClick(row)
                        },
                        onLongClick = { menuExpanded = true },
                    )
                    .then(
                        if (row.id in selectedIds) {
                            Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                        } else {
                            Modifier
                        }
                    ),
                leading = {
                    Icon(
                        imageVector = if (row.node.kind == FsKind.Directory) jcIcon(JCodeIcon.Folder) else jcIcon(JCodeIcon.Output),
                        contentDescription = null,
                        tint = if (row.node.kind == FsKind.Directory) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.width(iconSize).height(iconSize),
                    )
                },
                content = {
                    Column {
                        Text(
                            text = row.node.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (row.node.kind == FsKind.File) {
                            Text(
                                text = "${formatSize(row.node.sizeBytes)} · ${formatModified(row.node.modifiedAtMillis)}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                    }
                },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        row.badge?.let { badge -> BadgeContent(badge) }
                        RowOverflowMenu(
                            row = row,
                            expanded = menuExpanded,
                            onExpandedChange = { menuExpanded = it },
                            onAction = onAction,
                        )
                    }
                },
            )
        }
    }
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}

private val listDateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

private fun formatModified(millis: Long): String =
    if (millis <= 0L) "—" else listDateFormat.format(Date(millis))

// --- Toolbar ---

@Composable
private fun ExplorerToolbar(
    viewMode: ExplorerViewMode,
    onCreateFile: () -> Unit,
    onCreateFolder: () -> Unit,
    onCollapseAll: () -> Unit,
    onRefresh: () -> Unit,
    onPaste: () -> Unit,
    canPaste: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarIcon(jcIcon(JCodeIcon.NewFile), "New File", onCreateFile)
        ToolbarIcon(jcIcon(JCodeIcon.NewFolder), "New Folder", onCreateFolder)
        ToolbarIcon(jcIcon(JCodeIcon.Paste), "Paste", onPaste, enabled = canPaste)
        ToolbarIcon(jcIcon(JCodeIcon.Refresh), "Refresh", onRefresh)
        if (viewMode == ExplorerViewMode.Tree) {
            ToolbarIcon(jcIcon(JCodeIcon.Collapse), "Collapse all", onCollapseAll)
        }
    }
}

@Composable
private fun ToolbarIcon(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(34.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(18.dp),
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
    }
}

// --- Create/Rename Dialog ---

@Composable
private fun CreateRenameDialog(
    title: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var textState by remember { mutableStateOf(TextFieldValue(initialName, TextRange(initialName.length))) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = textState,
                onValueChange = { textState = it },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (textState.text.isNotBlank()) {
                        onConfirm(textState.text)
                    }
                }),
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (textState.text.isNotBlank()) {
                        onConfirm(textState.text)
                    }
                },
                enabled = textState.text.isNotBlank(),
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

// --- Badge ---

@Composable
private fun BadgeContent(badge: ExplorerBadge) {
    when (badge) {
        is ExplorerBadge.Unsaved -> {
            Text(
                text = "●",
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        is ExplorerBadge.VcsStatus -> {
            val color = when (badge.status) {
                "M" -> MaterialTheme.colorScheme.primary
                "A" -> MaterialTheme.colorScheme.tertiary
                "D" -> MaterialTheme.colorScheme.error
                "?" -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                text = badge.status,
                color = color,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }

        is ExplorerBadge.ProblemCount -> {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (badge.errors > 0) {
                    Text(
                        text = "✕ ${badge.errors}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (badge.warnings > 0) {
                    Text(
                        text = "⚠ ${badge.warnings}",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

// --- Drag and Drop Scaffold ---

/**
 * Placeholder drag-and-drop target for the explorer.
 * Accepts URI list drops from outside the app and can be wired to copy files via SAF.
 */
@Composable
fun rememberExplorerDropTarget(
    onDrop: (List<android.net.Uri>) -> Unit,
): DragAndDropTarget {
    return remember(onDrop) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val uris = event
                    .mimeTypes()
                    .filter { it == "text/uri-list" }
                    .flatMap { mimeType ->
                        event.toAndroidDragEvent().clipData?.let { clipData ->
                            (0 until clipData.itemCount).map { i ->
                                clipData.getItemAt(i).uri
                            }
                        }.orEmpty()
                    }
                if (uris.isNotEmpty()) {
                    onDrop(uris)
                    return true
                }
                return false
            }
        }
    }
}
