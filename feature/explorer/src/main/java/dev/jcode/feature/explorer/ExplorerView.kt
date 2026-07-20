package dev.jcode.feature.explorer
import dev.jcode.design.JCodeIcon
import dev.jcode.design.jcIcon

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jcode.design.CompactContextMenu
import dev.jcode.design.ContextAction
import dev.jcode.design.JCodeTheme
import dev.jcode.design.JcTooltip
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
import dev.jcode.fs.copyLocalTreeToDocumentTree
import dev.jcode.fs.createFile
import dev.jcode.fs.createDirectory
import dev.jcode.fs.deletePermanently
import dev.jcode.fs.exportFileToUri
import dev.jcode.fs.importContentUris
import dev.jcode.fs.renameFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.io.File
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
    hiddenPatterns: List<String> = emptyList(),
    greyOutExcluded: Boolean = true,
    /** Auto-refresh the tree from filesystem events while the Explorer is actually on-screen. Gated
     *  by the caller (drawer open / sidebar visible) so watchers stop the moment it's hidden. */
    autoRefreshEnabled: Boolean = true,
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
    var showDeleteConfirm by remember { mutableStateOf<TreeRow?>(null) }

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

    // The project-root exclude-list + grey-out-vs-hide effect (Settings); re-apply when either changes
    // without remounting the tree.
    LaunchedEffect(hiddenPatterns, greyOutExcluded) {
        viewModel.setHiddenPatterns(hiddenPatterns, greyOutExcluded)
    }

    // VCS decorations pushed by the shell; re-badge rows when the extension reports new status (also
    // keyed on the view model so a recreated one is fed the current maps, not just future changes).
    val scmUi = LocalExplorerScmUi.current
    LaunchedEffect(viewModel, scmUi.status, scmUi.submodules) {
        viewModel.setScmDecorations(scmUi.status, scmUi.submodules)
    }

    // Live-update: while the Explorer is on-screen, watch the directories whose contents are actually
    // shown — the project root plus every expanded folder (Tree) or the current folder (List) — and
    // repaint on filesystem changes. Only visible dirs are watched (never the whole recursive tree),
    // so a large repo costs a handful of inotify watches, not thousands. The watch key is the *set* of
    // shown dirs, so a change inside an already-watched dir doesn't re-subscribe — only expand/collapse
    // re-targets. The whole watch tears down the instant [autoRefreshEnabled] goes false.
    val watchDirs = remember(treeRows, listRows, activeViewMode, currentPath) {
        when (activeViewMode) {
            ExplorerViewMode.Tree -> treeRows.asSequence()
                .filter { it.isExpanded && it.node.kind == FsKind.Directory }
                .map { it.node.path }
                .distinctBy { it.stableId }
                .toList()
            ExplorerViewMode.List -> listOfNotNull(currentPath)
        }
    }
    val watchKey = remember(watchDirs) { watchDirs.map { it.stableId }.sorted().joinToString(" ") }
    // Reopening the drawer does one catch-up refresh for changes missed while it was hidden.
    LaunchedEffect(autoRefreshEnabled) {
        if (autoRefreshEnabled) viewModel.refresh()
    }
    LaunchedEffect(autoRefreshEnabled, watchKey) {
        if (!autoRefreshEnabled || watchDirs.isEmpty()) return@LaunchedEffect
        merge(*watchDirs.map { dir -> fs.watch(dir).catch { } }.toTypedArray())
            .collectLatest {
                // Debounce: a fresh event cancels this wait, folding a burst into a single refresh.
                delay(300)
                viewModel.refresh()
                scmUi.onFsActivity?.invoke()
            }
    }

    // SAF import/export: import streams device-storage picks into a target dir (row menu or toolbar);
    // export saves a file via a create-document picker or copies a local folder into a picked tree.
    // Pending targets are saveable tokens (a process death behind the system picker would drop plain
    // remember state and silently ignore the pick), and the copies run in their own supervisor scope:
    // leaving the Explorer must not cancel a tree copy midway.
    val fileOpScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    var importTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val target = importTarget?.let(::fsPathFromToken)
        importTarget = null
        if (target != null && uris.isNotEmpty()) fileOpScope.launch {
            val result = runCatching { importContentUris(fs, context, uris, target) }
            // Refresh either way: a partial failure may have landed some files already.
            viewModel.refresh()
            scmUi.onFsActivity?.invoke()
            result.onSuccess { names -> onSnackbar?.invoke("Imported ${names.size} file(s)") }
                .onFailure { onSnackbar?.invoke("Import failed: ${it.message}") }
        }
    }
    var exportFileSource by rememberSaveable { mutableStateOf<String?>(null) }
    val exportFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val source = exportFileSource?.let(::fsPathFromToken)
        exportFileSource = null
        if (source != null && uri != null) fileOpScope.launch {
            runCatching { exportFileToUri(context, source, uri) }
                .onSuccess { onSnackbar?.invoke("Exported file") }
                .onFailure { onSnackbar?.invoke("Export failed: ${it.message}") }
        }
    }
    var exportDirSource by rememberSaveable { mutableStateOf<String?>(null) }
    val exportDirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val source = exportDirSource?.let(::File)
        exportDirSource = null
        if (source != null && uri != null) fileOpScope.launch {
            runCatching { copyLocalTreeToDocumentTree(context, source, uri, source.name) }
                .onSuccess { n -> onSnackbar?.invoke("Exported $n file(s) from '${source.name}'") }
                .onFailure { onSnackbar?.invoke("Export failed: ${it.message}") }
        }
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
            RowAction.Delete -> showDeleteConfirm = row
            RowAction.ImportHere -> {
                importTarget = fsPathToken(row.node.path)
                importLauncher.launch(arrayOf("*/*"))
            }
            RowAction.Export -> {
                val path = row.node.path
                when {
                    row.node.kind != FsKind.Directory -> {
                        exportFileSource = fsPathToken(path)
                        exportFileLauncher.launch(row.node.name)
                    }
                    path is FsPath.Local -> {
                        exportDirSource = path.file.absolutePath
                        exportDirLauncher.launch(null)
                    }
                    else -> onSnackbar?.invoke("Folder export supports local folders only")
                }
            }
        }
    }

    CompositionLocalProvider(LocalProjectRootId provides project.fsPath.stableId) {
    Column(modifier = modifier.fillMaxSize()) {
        // Compact action toolbar (always visible) with create/refresh/paste. The Tree|List view mode
        // is set in Settings, not here.
        ExplorerToolbar(
            viewMode = activeViewMode,
            onCreateFile = { showCreateDialog = CreateTarget(resolveCreateParent(), isDirectory = false) },
            onCreateFolder = { showCreateDialog = CreateTarget(resolveCreateParent(), isDirectory = true) },
            onImport = {
                importTarget = fsPathToken(resolveCreateParent())
                importLauncher.launch(arrayOf("*/*"))
            },
            onCollapseAll = { scope.launch { viewModel.collapseAll() } },
            onRefresh = {
                scope.launch { viewModel.refresh() }
                scmUi.onFsActivity?.invoke()
            },
            onPaste = {
                clipboard?.let { entry ->
                    scope.launch {
                        runCatching {
                            val targetParent = resolveCreateParent()
                            copyFileOrDir(fs, context, entry.sourcePath, targetParent)
                            viewModel.refresh()
                            onSnackbar?.invoke("Pasted '${entry.name}'")
                            if (entry.isCut) {
                                deletePermanently(fs, context, entry.sourcePath)
                                viewModel.refresh()
                            }
                            clipboard = null
                            scmUi.onFsActivity?.invoke()
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
                        scmUi.onFsActivity?.invoke()
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
                        scmUi.onFsActivity?.invoke()
                        onSnackbar?.invoke("Renamed to '$newName'")
                    }.onFailure {
                        onSnackbar?.invoke("Rename failed: ${it.message}")
                    }
                }
                showRenameDialog = null
            },
        )
    }

    // Delete confirmation — deletes are permanent (no trash bin); git is the recovery path.
    showDeleteConfirm?.let { target ->
        val isDir = target.node.kind == FsKind.Directory
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Delete '${target.node.name}'?") },
            text = {
                Text(
                    "This permanently deletes the ${if (isDir) "folder and its contents" else "file"}. " +
                        "If the project is under git, you can restore it there.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val node = target.node
                    showDeleteConfirm = null
                    scope.launch {
                        runCatching {
                            deletePermanently(fs, context, node.path)
                            viewModel.refresh()
                            scmUi.onFsActivity?.invoke()
                            onSnackbar?.invoke("Deleted '${node.name}'")
                        }.onFailure { onSnackbar?.invoke("Delete failed: ${it.message}") }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text("Cancel") }
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
enum class RowAction { Open, NewFile, NewFolder, Rename, Copy, Cut, Delete, ImportHere, Export }

/** Parent of a path, or null when there is no addressable parent (SAF tree URIs). */
private fun parentPathOf(path: FsPath): FsPath? = when (path) {
    is FsPath.Local -> path.file.parentFile?.let { FsPath.Local(it) }
    is FsPath.Saf -> null
}

// FsPath as a saveable string, for pending SAF-picker targets that must survive process death.
private fun fsPathToken(path: FsPath): String = when (path) {
    is FsPath.Local -> "local:" + path.file.absolutePath
    is FsPath.Saf -> "saf:" + path.uri
}

private fun fsPathFromToken(token: String): FsPath? = when {
    token.startsWith("local:") -> FsPath.Local(File(token.removePrefix("local:")))
    token.startsWith("saf:") -> FsPath.Saf(Uri.parse(token.removePrefix("saf:")))
    else -> null
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
    val scmUi = LocalExplorerScmUi.current
    // The project root must not be moved or deleted from the tree, so Cut/Delete are hidden on it.
    val isProjectRoot = LocalProjectRootId.current == row.node.path.stableId
    Box {
        JcTooltip("More actions") {
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
        }
        CompactContextMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            quickActions = buildList {
                add(ContextAction(JCodeIcon.Copy, "Copy") { onAction(row, RowAction.Copy) })
                if (!isProjectRoot) add(ContextAction(JCodeIcon.Cut, "Cut") { onAction(row, RowAction.Cut) })
                add(ContextAction(JCodeIcon.Rename, "Rename") { onAction(row, RowAction.Rename) })
                if (!isProjectRoot) {
                    add(ContextAction(JCodeIcon.Delete, "Delete", destructive = true) { onAction(row, RowAction.Delete) })
                }
            },
            listActions = buildList {
                if (!isDir) add(ContextAction(JCodeIcon.Open, "Open") { onAction(row, RowAction.Open) })
                if (isDir) {
                    add(ContextAction(JCodeIcon.NewFile, "New file") { onAction(row, RowAction.NewFile) })
                    add(ContextAction(JCodeIcon.NewFolder, "New folder") { onAction(row, RowAction.NewFolder) })
                    add(ContextAction(JCodeIcon.Add, "Import files…") { onAction(row, RowAction.ImportHere) })
                }
                add(ContextAction(JCodeIcon.Save, "Export…") { onAction(row, RowAction.Export) })
                // Extension-contributed actions (e.g. "Add to .gitignore") target a path inside the
                // repo — the project root isn't a valid target, so omit them there.
                if (!isProjectRoot) {
                    scmUi.onContextAction?.let { dispatch ->
                        scmUi.contextActions
                            .filter { explorerActionAppliesTo(it, row.node.name, isDir) }
                            .forEach { a -> add(ContextAction(a.icon, a.label) { dispatch(a, row.node) }) }
                    }
                }
            },
        )
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
            JcTooltip("Up one level") {
                IconButton(onClick = onNavigateUp, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = jcIcon(JCodeIcon.ArrowUp),
                        contentDescription = "Up one level",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
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

/** Opacity of a project-root entry that is excluded under the "grey out" effect. */
private const val EXCLUDED_ROW_ALPHA = 0.4f

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
            )
            // Excluded (grey-out effect): kept in the tree but dimmed so it reads as de-emphasized.
            .then(if (row.isExcluded) Modifier.alpha(EXCLUDED_ROW_ALPHA) else Modifier),
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
                color = row.vcsStatus?.let { vcsStatusColor(it) } ?: Color.Unspecified,
            )
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RowVcsBadges(row)
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
                    )
                    .then(if (row.isExcluded) Modifier.alpha(EXCLUDED_ROW_ALPHA) else Modifier),
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
                            color = row.vcsStatus?.let { vcsStatusColor(it) } ?: Color.Unspecified,
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
                        RowVcsBadges(row)
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
    onImport: () -> Unit,
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
        ToolbarIcon(jcIcon(JCodeIcon.Add), "Import files", onImport)
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
    JcTooltip(description) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(34.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                modifier = Modifier.size(18.dp),
                tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        }
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

// --- VCS badges ---

/** Trailing VCS chips for a row: an "S" submodule marker and/or the status letter. */
@Composable
private fun RowVcsBadges(row: TreeRow) {
    if (row.isSubmodule) {
        JcTooltip("Git submodule") {
            Text(
                text = "S",
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
    row.vcsStatus?.let { status ->
        Text(
            text = status,
            color = vcsStatusColor(status),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/** Shared status-letter → color mapping (badge and filename tint): modified amber, added/untracked
 *  green, deleted/conflicted red, renamed primary. */
@Composable
private fun vcsStatusColor(status: String): Color = when (status.firstOrNull()) {
    'M', DIR_CONTAINS_CHANGES.first() -> JCodeTheme.semanticColors.warning
    'A', '?' -> JCodeTheme.semanticColors.success
    'D', 'U' -> MaterialTheme.colorScheme.error
    'R', 'C' -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
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
