package dev.jcode.feature.explorer

import androidx.compose.runtime.Immutable
import dev.jcode.fs.Fs
import dev.jcode.fs.FsKind
import dev.jcode.fs.FsNode
import dev.jcode.fs.FsPath
import dev.jcode.fs.Project
import dev.jcode.fs.Workspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A single row in the explorer tree or list. */
@Immutable
data class TreeRow(
    val id: String,
    val node: FsNode,
    val depth: Int,
    val isExpanded: Boolean,
    val isSelected: Boolean,
    val badge: ExplorerBadge? = null,
    /** A non-interactive "(empty)" marker shown under an expanded, childless directory. */
    val isPlaceholder: Boolean = false,
)

/** Placeholder badge for VCS status, error counts, etc. */
@Immutable
sealed interface ExplorerBadge {
    data object Unsaved : ExplorerBadge
    data class VcsStatus(val status: String) : ExplorerBadge
    data class ProblemCount(val errors: Int, val warnings: Int) : ExplorerBadge
}

/** View mode for the explorer. */
enum class ExplorerViewMode {
    Tree,
    List,
}

/** Shared selection state between tree and list modes. */
class ExplorerSelectionState {
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    fun toggle(id: String) {
        _selected.update { current ->
            if (id in current) current - id else current + id
        }
    }

    fun clear() {
        _selected.value = emptySet()
    }

    fun selectSingle(id: String) {
        _selected.value = setOf(id)
    }
}

/**
 * ViewModel for the file explorer.
 *
 * Tree and List are two structurally different shapes, so each gets its OWN row flow:
 *  - [treeRows]: the hierarchical project tree (root + expanded descendants, indented by depth).
 *  - [listRows]: a flat listing of the immediate children of [currentPath] (a file-manager view).
 *
 * The active [viewMode] lives here (not in the composable) so the toolbar toggle reloads the right
 * shape, and so [refresh] can repaint the correct flow after a file operation. Because each content
 * composable binds its own flow, a List view can never accidentally render tree rows.
 *
 * Identity is always derived from [FsPath.stableId] (absolute path / URI), never the bare
 * display name, so same-named folders in different locations never share expansion or selection.
 */
class TreeViewModel(
    private val workspace: Workspace?,
    private val fs: Fs,
    initialViewMode: ExplorerViewMode = ExplorerViewMode.Tree,
    private val scmStatus: Map<String, String> = emptyMap(),
    private val problems: Map<String, Pair<Int, Int>> = emptyMap(),
) {
    private val _viewMode = MutableStateFlow(initialViewMode)
    val viewMode: StateFlow<ExplorerViewMode> = _viewMode.asStateFlow()

    private val _treeRows = MutableStateFlow<List<TreeRow>>(emptyList())
    val treeRows: StateFlow<List<TreeRow>> = _treeRows.asStateFlow()

    private val _listRows = MutableStateFlow<List<TreeRow>>(emptyList())
    val listRows: StateFlow<List<TreeRow>> = _listRows.asStateFlow()

    private val _expandedIds = MutableStateFlow<Set<String>>(emptySet())

    val selectionState = ExplorerSelectionState()

    private val _currentPath = MutableStateFlow<FsPath?>(null)
    val currentPath: StateFlow<FsPath?> = _currentPath.asStateFlow()

    private val _breadcrumb = MutableStateFlow<List<BreadcrumbEntry>>(emptyList())
    val breadcrumb: StateFlow<List<BreadcrumbEntry>> = _breadcrumb.asStateFlow()

    private var projectRootPath: FsPath? = null
    private var rootNode: FsNode? = null

    /** The directory List mode is currently showing (independent of Tree's expansion state). */
    private var listPath: FsPath? = null

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    data class BreadcrumbEntry(
        val label: String,
        val path: FsPath,
    )

    // Root-level hide patterns (Settings). Only project-root children are filtered.
    private var hiddenPatterns: List<String> = emptyList()

    /** Load the root of a project. */
    suspend fun loadProjectRoot(project: Project) {
        _isLoading.value = true
        try {
            val rootPath = project.fsPath
            projectRootPath = rootPath
            rootNode = FsNode(
                path = rootPath,
                name = project.name,
                kind = FsKind.Directory,
                sizeBytes = 0L,
                modifiedAtMillis = 0L,
            )
            listPath = rootPath
            _expandedIds.value = setOf(rootPath.stableId)
            selectionState.clear()
            applyMode()
        } finally {
            _isLoading.value = false
        }
    }

    /** Switch between Tree and List, materializing the correct flow for the new mode. */
    suspend fun setViewMode(mode: ExplorerViewMode) {
        if (_viewMode.value == mode) return
        _viewMode.value = mode
        selectionState.clear()
        applyMode()
    }

    /** Update the project-root hide patterns and re-materialize the current view. */
    suspend fun setHiddenPatterns(patterns: List<String>) {
        if (hiddenPatterns == patterns) return
        hiddenPatterns = patterns
        applyMode()
    }

    private suspend fun applyMode() {
        when (_viewMode.value) {
            ExplorerViewMode.Tree -> rebuildRows()
            ExplorerViewMode.List -> {
                val target = listPath ?: projectRootPath ?: return
                navigateTo(target, rootNode?.name ?: target.displayName)
            }
        }
    }

    // --- Tree mode ---

    /** Toggle expansion of a directory row (Tree mode only). */
    suspend fun toggleExpand(row: TreeRow) {
        if (row.node.kind != FsKind.Directory || row.isPlaceholder) return
        val key = row.node.path.stableId
        if (_expandedIds.value.contains(key)) {
            _expandedIds.update { it - key }
        } else {
            _expandedIds.update { it + key }
        }
        rebuildRows()
    }

    /** Collapse every directory except the project root. */
    suspend fun collapseAll() {
        val root = projectRootPath ?: return
        _expandedIds.value = setOf(root.stableId)
        rebuildRows()
    }

    /** Rebuild the flattened tree from the project root + the set of expanded ids. Deterministic:
     *  every path maps to exactly one row id, so it can never produce duplicate LazyColumn keys. */
    private suspend fun rebuildRows() {
        val root = rootNode ?: return
        val rootPath = projectRootPath ?: return
        val result = mutableListOf<TreeRow>()
        result.add(
            TreeRow(
                id = rootPath.stableId,
                node = root,
                depth = 0,
                isExpanded = true,
                isSelected = false,
            ),
        )
        addExpandedChildren(rootPath, depth = 1, out = result)
        _treeRows.value = result
    }

    private suspend fun addExpandedChildren(
        parentPath: FsPath,
        depth: Int,
        out: MutableList<TreeRow>,
    ) {
        if (depth > 64) return // safety against pathological/cyclic structures
        val children = filterHiddenAtRoot(parentPath, runCatching { fs.list(parentPath) }.getOrElse { emptyList() })
        if (children.isEmpty()) {
            out.add(placeholderRow(parentPath, depth))
            return
        }
        for (child in children) {
            val isDir = child.kind == FsKind.Directory
            val expanded = isDir && _expandedIds.value.contains(child.path.stableId)
            out.add(
                TreeRow(
                    id = child.path.stableId,
                    node = child,
                    depth = depth,
                    isExpanded = expanded,
                    isSelected = false,
                    badge = buildBadge(child.path.stableId),
                ),
            )
            if (expanded) {
                addExpandedChildren(child.path, depth + 1, out)
            }
        }
    }

    private fun placeholderRow(parentPath: FsPath, depth: Int): TreeRow = TreeRow(
        id = parentPath.stableId + "::empty",
        node = FsNode(parentPath, "(empty)", FsKind.File, 0L, 0L),
        depth = depth,
        isExpanded = false,
        isSelected = false,
        isPlaceholder = true,
    )

    /** Hide matching entries — ONLY at the project root (a nested folder with the same name stays). */
    private fun filterHiddenAtRoot(parentPath: FsPath, children: List<FsNode>): List<FsNode> {
        if (hiddenPatterns.isEmpty() || parentPath.stableId != projectRootPath?.stableId) return children
        return children.filterNot { child -> hiddenPatterns.any { matchesHidden(it, child.name) } }
    }

    private fun matchesHidden(pattern: String, name: String): Boolean {
        var pat = pattern.trim()
        if (pat.isEmpty() || pat.startsWith("#") || pat.startsWith("!")) return false // blank / comment / negation
        pat = pat.trimStart('/').trimEnd('/')
        val head = pat.substringBefore('/') // a nested pattern like "foo/bar" hides "foo" at the root
        if (head.isEmpty()) return false
        return if (head.any { it == '*' || it == '?' }) globToRegex(head).matches(name) else head == name
    }

    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        for (c in glob) when (c) {
            '*' -> sb.append(".*")
            '?' -> sb.append('.')
            '.', '(', ')', '+', '|', '^', '$', '{', '}', '[', ']', '\\' -> sb.append('\\').append(c)
            else -> sb.append(c)
        }
        return Regex(sb.toString())
    }

    // --- List mode ---

    /** Navigate into a directory (List mode); replaces [listRows] with that dir's flat children. */
    suspend fun navigateTo(path: FsPath, label: String) {
        _isLoading.value = true
        try {
            listPath = path
            _currentPath.value = path
            updateBreadcrumb(path, label)
            selectionState.clear()

            val children = filterHiddenAtRoot(path, fs.list(path))
            _listRows.value = children.map { child -> buildRow(child, depth = 0) }
        } finally {
            _isLoading.value = false
        }
    }

    /** Navigate up one level in the breadcrumb (List mode). */
    suspend fun navigateUp() {
        val bc = _breadcrumb.value
        if (bc.size <= 1) return
        val parent = bc[bc.size - 2]
        navigateTo(parent.path, parent.label)
    }

    /** Repaint the current view in-place (mode-aware) after a file operation. */
    suspend fun refresh() {
        when (_viewMode.value) {
            ExplorerViewMode.Tree -> rebuildRows()
            ExplorerViewMode.List -> {
                val path = listPath ?: _currentPath.value ?: return
                val label = _breadcrumb.value.lastOrNull()?.label ?: path.displayName
                navigateTo(path, label)
            }
        }
    }

    // --- Internal ---

    private fun updateBreadcrumb(path: FsPath, label: String) {
        val entries = mutableListOf<BreadcrumbEntry>()
        val root = projectRootPath

        when {
            root is FsPath.Local && path is FsPath.Local -> {
                // Always start the trail at the project root so there is a tappable crumb (and the
                // up button) the moment you descend even a single level.
                entries.add(BreadcrumbEntry(rootNode?.name ?: root.file.name, root))

                // Then append each segment below the root.
                val rootSegments = getRelativeSegments(root.file)
                val pathSegments = getRelativeSegments(path.file)
                val relativeSegments = pathSegments.drop(rootSegments.size)
                var accumulated = root.file
                for (segment in relativeSegments) {
                    accumulated = java.io.File(accumulated, segment)
                    entries.add(BreadcrumbEntry(segment, FsPath.Local(accumulated)))
                }
            }

            root is FsPath.Saf && path is FsPath.Saf -> {
                // For SAF, just show the current label
                entries.add(BreadcrumbEntry(label, path))
            }

            else -> {
                entries.add(BreadcrumbEntry(label, path))
            }
        }
        _breadcrumb.value = entries
    }

    private fun getRelativeSegments(file: java.io.File): List<String> {
        val segments = mutableListOf<String>()
        var current: java.io.File? = file
        while (current != null && current.name.isNotEmpty()) {
            segments.add(current.name)
            current = current.parentFile
        }
        return segments.reversed()
    }

    private fun buildRow(node: FsNode, depth: Int): TreeRow = TreeRow(
        id = node.path.stableId,
        node = node,
        depth = depth,
        isExpanded = false,
        isSelected = false,
        badge = buildBadge(node.path.stableId),
    )

    private fun buildBadge(pathKey: String): ExplorerBadge? {
        scmStatus[pathKey]?.let { status ->
            return ExplorerBadge.VcsStatus(status)
        }
        problems[pathKey]?.let { (errors, warnings) ->
            if (errors > 0 || warnings > 0) {
                return ExplorerBadge.ProblemCount(errors, warnings)
            }
        }
        return null
    }
}
