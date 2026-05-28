package dev.jcode.feature.explorer

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
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
    onFileSelected: ((FsNode) -> Unit)? = null,
    onSnackbar: ((String) -> Unit)? = null,
) {
    val viewModel = remember(workspace, project, fs) {
        TreeViewModel(workspace, fs)
    }
    val scope = rememberCoroutineScope()

    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val breadcrumb by viewModel.breadcrumb.collectAsStateWithLifecycle()
    val currentPath by viewModel.currentPath.collectAsStateWithLifecycle()
    val selectionState = viewModel.selectionState
    val selectedIds by selectionState.selected.collectAsStateWithLifecycle()

    var viewMode by rememberSaveable { mutableStateOf(ExplorerViewMode.Tree) }
    var contextMenuTarget by remember { mutableStateOf<TreeRow?>(null) }
    var showCreateDialog by remember { mutableStateOf<CreateTarget?>(null) }
    var showRenameDialog by remember { mutableStateOf<RenameTarget?>(null) }

    // Clipboard for copy/cut operations
    var clipboard by remember { mutableStateOf<ClipboardEntry?>(null) }

    // Load project root on first composition
    LaunchedEffect(project) {
        viewModel.loadProjectRoot(project)
    }

    Column(modifier = modifier.fillMaxSize()) {
        // View mode toggle
        ExplorerModeToggle(
            currentMode = viewMode,
            onModeChanged = { viewMode = it },
        )

        // Breadcrumb
        ExplorerBreadcrumb(
            entries = breadcrumb,
            onNavigate = { entry ->
                scope.launch {
                    viewModel.navigateTo(entry.path, entry.label)
                }
            },
            onNavigateUp = {
                scope.launch { viewModel.navigateUp() }
            },
        )

        // Content
        when (viewMode) {
            ExplorerViewMode.Tree -> TreeViewContent(
                rows = rows,
                isLoading = isLoading,
                selectedIds = selectedIds,
                selectionState = selectionState,
                onToggleExpand = { row ->
                    scope.launch { viewModel.toggleExpand(row) }
                },
                onRowClick = { row ->
                    if (row.node.kind == FsKind.Directory) {
                        scope.launch { viewModel.toggleExpand(row) }
                    } else {
                        onFileSelected?.invoke(row.node)
                    }
                },
                onRowDoubleClick = { row ->
                    if (row.node.kind == FsKind.Directory) {
                        scope.launch { viewModel.navigateTo(row.node.path, row.node.name) }
                    } else {
                        onFileSelected?.invoke(row.node)
                    }
                },
                onContextMenu = { contextMenuTarget = it },
            )

            ExplorerViewMode.List -> ListViewContent(
                rows = rows,
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
                onContextMenu = { contextMenuTarget = it },
            )
        }

        // Toolbar
        ExplorerToolbar(
            onCreateFile = {
                showCreateDialog = CreateTarget(currentPath ?: project.fsPath, isDirectory = false)
            },
            onCreateFolder = {
                showCreateDialog = CreateTarget(currentPath ?: project.fsPath, isDirectory = true)
            },
            onRefresh = {
                scope.launch { viewModel.refresh() }
            },
            onPaste = {
                clipboard?.let { entry ->
                    scope.launch {
                        runCatching {
                            val targetParent = currentPath ?: project.fsPath
                            copyFileOrDir(fs, context, entry.sourcePath, targetParent)
                            viewModel.refresh()
                            onSnackbar?.invoke("Pasted '${entry.name}'")

                            // If it was a cut operation, remove the original
                            if (entry.isCut) {
                                deleteToTrash(fs, context, entry.sourcePath, project.fsPath)
                                viewModel.refresh()
                            }
                            clipboard = null
                        }.onFailure {
                            onSnackbar?.invoke("Paste failed: ${it.message}")
                        }
                    }
                }
            },
            canPaste = clipboard != null,
        )
    }

    // Context menu
    contextMenuTarget?.let { target ->
        ExplorerContextMenu(
            row = target,
            onDismiss = { contextMenuTarget = null },
            onRename = {
                showRenameDialog = RenameTarget(target.node.path, target.node.name)
                contextMenuTarget = null
            },
            onDelete = {
                scope.launch {
                    runCatching {
                        deleteToTrash(fs, context, target.node.path, project.fsPath)
                        viewModel.refresh()
                        onSnackbar?.invoke("Moved '${target.node.name}' to trash")
                    }.onFailure {
                        onSnackbar?.invoke("Delete failed: ${it.message}")
                    }
                }
                contextMenuTarget = null
            },
            onCopy = {
                clipboard = ClipboardEntry(target.node.path, target.node.name, isCut = false)
                onSnackbar?.invoke("Copied '${target.node.name}' to clipboard")
                contextMenuTarget = null
            },
            onCut = {
                clipboard = ClipboardEntry(target.node.path, target.node.name, isCut = true)
                onSnackbar?.invoke("Cut '${target.node.name}' to clipboard")
                contextMenuTarget = null
            },
        )
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

// --- Mode Toggle ---

@Composable
private fun ExplorerModeToggle(
    currentMode: ExplorerViewMode,
    onModeChanged: (ExplorerViewMode) -> Unit,
) {
    val modes = ExplorerViewMode.entries.toTypedArray()
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                onClick = { onModeChanged(mode) },
                selected = mode == currentMode,
                label = { Text(mode.name, style = MaterialTheme.typography.labelMedium) },
            )
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
            TextButton(onClick = onNavigateUp, modifier = Modifier.padding(end = 4.dp)) {
                Text("↑", style = MaterialTheme.typography.labelSmall)
            }
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
    onRowDoubleClick: (TreeRow) -> Unit,
    onContextMenu: (TreeRow) -> Unit,
) {
    if (isLoading && rows.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        }
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
                onDoubleClick = { onRowDoubleClick(row) },
                onContextMenu = { onContextMenu(row) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeRowItem(
    row: TreeRow,
    isSelected: Boolean,
    onToggleExpand: (TreeRow) -> Unit,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onContextMenu: () -> Unit,
) {
    val iconSize = LocalIconSize.current
    val indent = (row.depth * 16).dp

    DenseRow(
        modifier = Modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = onContextMenu,
            )
            .then(
                if (isSelected) {
                    Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                } else {
                    Modifier
                }
            ),
        leading = {
            if (row.node.kind == FsKind.Directory) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.width(indent))
                    Text(
                        text = if (row.isExpanded) "▼" else "▶",
                        modifier = Modifier
                            .clickable { onToggleExpand(row) }
                            .padding(end = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(iconSize).height(iconSize),
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.width(indent + 16.dp))
                    Icon(
                        imageVector = Icons.Outlined.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(iconSize).height(iconSize),
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
            row.badge?.let { badge ->
                BadgeContent(badge)
            }
        },
    )
}

// --- List View ---

@Composable
private fun ListViewContent(
    rows: List<TreeRow>,
    isLoading: Boolean,
    selectedIds: Set<String>,
    selectionState: ExplorerSelectionState,
    onRowClick: (TreeRow) -> Unit,
    onContextMenu: (TreeRow) -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd HH:mm", Locale.getDefault()) }

    if (isLoading && rows.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Column headers
        DenseRow(
            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            content = {
                Text("Name", style = MaterialTheme.typography.labelMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
            },
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Size", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(70.dp))
                    Text("Modified", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(100.dp))
                }
            },
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(rows, key = { it.id }) { row ->
                DenseRow(
                    modifier = Modifier
                        .clickable {
                            selectionState.selectSingle(row.id)
                            onRowClick(row)
                        }
                        .then(
                            if (row.id in selectedIds) {
                                Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                            } else {
                                Modifier
                            }
                        ),
                    leading = {
                        val iconSize = LocalIconSize.current
                        Icon(
                            imageVector = if (row.node.kind == FsKind.Directory) Icons.Default.Folder else Icons.Outlined.Description,
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
                        Text(
                            text = row.node.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(
                                text = if (row.node.kind == FsKind.Directory) {
                                    "--"
                                } else {
                                    formatSize(row.node.sizeBytes)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.width(70.dp),
                            )
                            Text(
                                text = dateFormat.format(Date(row.node.modifiedAtMillis)),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.width(100.dp),
                            )
                        }
                    },
                )
            }
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

// --- Toolbar ---

@Composable
private fun ExplorerToolbar(
    onCreateFile: () -> Unit,
    onCreateFolder: () -> Unit,
    onRefresh: () -> Unit,
    onPaste: () -> Unit,
    canPaste: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onCreateFile) {
            Text("New File", style = MaterialTheme.typography.labelMedium)
        }
        TextButton(onClick = onCreateFolder) {
            Text("New Folder", style = MaterialTheme.typography.labelMedium)
        }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(
            onClick = onPaste,
            enabled = canPaste,
        ) {
            Text("Paste", style = MaterialTheme.typography.labelMedium)
        }
        TextButton(onClick = onRefresh) {
            Text("Refresh", style = MaterialTheme.typography.labelMedium)
        }
    }
}

// --- Context Menu ---

@Composable
private fun ExplorerContextMenu(
    row: TreeRow,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
) {
    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
    ) {
        DropdownMenuItem(
            text = { Text("Copy") },
            onClick = onCopy,
        )
        DropdownMenuItem(
            text = { Text("Cut") },
            onClick = onCut,
        )
        DropdownMenuItem(
            text = { Text("Rename") },
            onClick = onRename,
        )
        DropdownMenuItem(
            text = { Text("Delete") },
            onClick = onDelete,
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
