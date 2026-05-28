package dev.jcode.feature.explorer

import androidx.compose.runtime.Immutable
import dev.jcode.fs.Fs
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
 * ViewModel for the file explorer tree/list.
 *
 * Manages expansion state, selection, and lazy loading of directory contents.
 * Badges for VCS status and problem counts are placeholder-safe — they accept
 * data from later phases (:core:vcs, :feature:problems) but render nothing
 * when those phases are absent.
 */
class TreeViewModel(
    private val workspace: Workspace?,
    private val fs: Fs,
    private val scmStatus: Map<String, String> = emptyMap(),
    private val problems: Map<String, Pair<Int, Int>> = emptyMap(),
) {
    private val _rows = MutableStateFlow<List<TreeRow>>(emptyList())
    val rows: StateFlow<List<TreeRow>> = _rows.asStateFlow()

    private val _expandedPaths = MutableStateFlow<Set<String>>(emptySet())
    val expandedPaths: StateFlow<Set<String>> = _expandedPaths.asStateFlow()

    val selectionState = ExplorerSelectionState()

    private val _currentPath = MutableStateFlow<FsPath?>(null)
    val currentPath: StateFlow<FsPath?> = _currentPath.asStateFlow()

    private val _breadcrumb = MutableStateFlow<List<BreadcrumbEntry>>(emptyList())
    val breadcrumb: StateFlow<List<BreadcrumbEntry>> = _breadcrumb.asStateFlow()

    private var projectRootPath: FsPath? = null

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    data class BreadcrumbEntry(
        val label: String,
        val path: FsPath,
    )

    /** Load the root of a project into tree view. */
    suspend fun loadProjectRoot(project: Project) {
        _isLoading.value = true
        try {
            val rootPath = project.fsPath
            projectRootPath = rootPath
            _currentPath.value = rootPath
            updateBreadcrumb(rootPath, project.name)

            val children = fs.list(rootPath)
            val rootRow = TreeRow(
                id = rootPath.displayName,
                node = FsNode(
                    path = rootPath,
                    name = project.name,
                    kind = dev.jcode.fs.FsKind.Directory,
                    sizeBytes = 0L,
                    modifiedAtMillis = 0L,
                ),
                depth = 0,
                isExpanded = true,
                isSelected = false,
            )
            _expandedPaths.value = setOf(rootPath.displayName)

            val childRows = children.mapIndexed { index, child ->
                buildRow(child, depth = 1, parentId = rootPath.displayName, index = index)
            }
            _rows.value = listOf(rootRow) + childRows
        } finally {
            _isLoading.value = false
        }
    }

    /** Toggle expansion of a directory row. */
    suspend fun toggleExpand(row: TreeRow) {
        val pathKey = row.node.path.displayName
        val isCurrentlyExpanded = _expandedPaths.value.contains(pathKey)

        if (isCurrentlyExpanded) {
            // Collapse: remove this node's children from rows
            _expandedPaths.update { it - pathKey }
            collapseNode(pathKey)
        } else {
            // Expand: load children and insert after this row
            _expandedPaths.update { it + pathKey }
            expandNode(row)
        }
    }

    /** Navigate into a directory (for list mode or tree double-click). */
    suspend fun navigateTo(path: FsPath, label: String) {
        _isLoading.value = true
        try {
            _currentPath.value = path
            updateBreadcrumb(path, label)

            val children = fs.list(path)
            _rows.value = children.mapIndexed { index, child ->
                buildRow(child, depth = 0, parentId = path.displayName, index = index)
            }
        } finally {
            _isLoading.value = false
        }
    }

    /** Navigate up one level in the breadcrumb. */
    suspend fun navigateUp() {
        val bc = _breadcrumb.value
        if (bc.size <= 1) return
        val parent = bc[bc.size - 2]
        navigateTo(parent.path, parent.label)
    }

    /** Refresh the current directory listing. */
    suspend fun refresh() {
        val path = _currentPath.value ?: return
        val label = _breadcrumb.value.lastOrNull()?.label ?: return
        navigateTo(path, label)
    }

    // --- Internal ---

    private fun updateBreadcrumb(path: FsPath, label: String) {
        val entries = mutableListOf<BreadcrumbEntry>()
        val root = projectRootPath

        when {
            root is FsPath.Local && path is FsPath.Local -> {
                // Build breadcrumb relative to project root
                val rootSegments = getRelativeSegments(root.file)
                val pathSegments = getRelativeSegments(path.file)

                // Find common prefix and build from there
                val relativeSegments = pathSegments.drop(rootSegments.size)
                var accumulated = root.file
                for ((i, segment) in relativeSegments.withIndex()) {
                    accumulated = java.io.File(accumulated, segment)
                    entries.add(
                        BreadcrumbEntry(
                            label = if (i == 0 && relativeSegments.isNotEmpty()) relativeSegments[0] else segment,
                            path = FsPath.Local(accumulated),
                        )
                    )
                }
                if (entries.isEmpty()) {
                    entries.add(BreadcrumbEntry(label, root))
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

    private suspend fun expandNode(row: TreeRow) {
        if (row.node.kind != dev.jcode.fs.FsKind.Directory) return

        val children = fs.list(row.node.path)
        val newRows = _rows.value.toMutableList()
        val insertIndex = newRows.indexOfFirst { it.id == row.id } + 1

        val childRows = children.mapIndexed { index, child ->
            buildRow(child, depth = row.depth + 1, parentId = row.id, index = index)
        }
        newRows.addAll(insertIndex, childRows)
        _rows.value = newRows
    }

    private fun collapseNode(pathKey: String) {
        val rowsToRemove = mutableSetOf<String>()
        // Find all rows that are descendants of this path
        fun isDescendant(row: TreeRow): Boolean {
            // A row is a descendant if its id starts with the pathKey + "/"
            return row.id.startsWith("$pathKey/") || row.id == pathKey && row.depth > 0
        }

        val newRows = _rows.value.filter { row ->
            if (row.id == pathKey) return@filter true
            !isDescendantInSubtree(row, pathKey)
        }
        _rows.value = newRows
    }

    private fun isDescendantInSubtree(row: TreeRow, ancestorId: String): Boolean {
        // Check if this row's id path contains the ancestor id as a prefix
        return row.id.startsWith("$ancestorId/")
    }

    private fun buildRow(node: FsNode, depth: Int, parentId: String, index: Int): TreeRow {
        val id = "$parentId/${node.name}"
        val badge = buildBadge(node.path.displayName)
        return TreeRow(
            id = id,
            node = node,
            depth = depth,
            isExpanded = false,
            isSelected = false,
            badge = badge,
        )
    }

    private fun buildBadge(pathKey: String): ExplorerBadge? {
        // Placeholder: check scmStatus and problems maps
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
