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
    /** VCS status letter for the badge + filename tint: M/A/D/R/U/? for the path itself, or
     *  [DIR_CONTAINS_CHANGES] propagated onto an ancestor directory. */
    val vcsStatus: String? = null,
    /** The row is a git submodule root ("S" chip; can combine with a dirty [vcsStatus]). */
    val isSubmodule: Boolean = false,
    /** A non-interactive "(empty)" marker shown under an expanded, childless directory. */
    val isPlaceholder: Boolean = false,
    /** A project-root entry matched by the exclude list while the effect is "grey out" — kept in the
     *  tree but dimmed (when the effect is "hide" the entry is dropped instead, so this stays false). */
    val isExcluded: Boolean = false,
)

/** Synthetic status for a directory containing changed files (never sent by extensions). */
const val DIR_CONTAINS_CHANGES = "•"

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

    // Root-level exclude patterns (Settings). Only project-root children are affected. When [greyOut]
    // is true the matched entries are kept but marked [TreeRow.isExcluded]; otherwise they are dropped.
    private var hiddenPatterns: List<String> = emptyList()
    private var greyOut: Boolean = true

    // VCS decorations pushed by the shell (extension-computed): explicit per-path status letters,
    // submodule roots, and the derived "contains changes" mark on ancestor directories.
    private var scmStatus: Map<String, String> = emptyMap()
    private var scmDirStatus: Map<String, String> = emptyMap()
    private var scmSubmodules: Set<String> = emptySet()

    /** Update VCS decorations ([status]/[submodules] keyed by [dev.jcode.fs.FsPath.stableId]) and
     *  repaint. Ancestor directories of every decorated path get a [DIR_CONTAINS_CHANGES] mark up to
     *  the project root, so collapsed folders still show that something changed inside. */
    suspend fun setScmDecorations(status: Map<String, String>, submodules: Set<String>) {
        if (scmStatus == status && scmSubmodules == submodules) return
        scmStatus = status
        scmSubmodules = submodules
        val rootId = projectRootPath?.stableId
        scmDirStatus = buildMap {
            for (path in status.keys) {
                var parent = java.io.File(path).parentFile
                while (parent != null) {
                    val id = parent.absolutePath
                    if (id == rootId || put(id, DIR_CONTAINS_CHANGES) != null) break
                    parent = parent.parentFile
                }
            }
        }
        repaintRows()
    }

    /** Re-materialize the current rows in place — unlike [refresh], keeps List-mode selection and
     *  breadcrumb untouched (decoration pushes must not wipe what the user is doing). */
    private suspend fun repaintRows() {
        when (_viewMode.value) {
            ExplorerViewMode.Tree -> rebuildRows()
            ExplorerViewMode.List -> {
                val path = listPath ?: return
                val children = filterHiddenAtRoot(path, runCatching { fs.list(path) }.getOrElse { return })
                _listRows.value = children.map { child -> buildRow(child, depth = 0, isExcludedAtRoot(path, child)) }
            }
        }
    }

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
        // A directory listing can transiently come back null/empty right as a project lands (e.g.
        // the folder was moved into place by a clone adoption moments ago) — File.listFiles reports
        // IO hiccups as null, which renders here as an empty tree with no error. One delayed
        // re-list self-heals that case; a genuinely empty project just pays one cheap extra stat.
        val looksEmpty = when (_viewMode.value) {
            ExplorerViewMode.Tree -> _treeRows.value.count { !it.isPlaceholder } <= 1
            ExplorerViewMode.List -> _listRows.value.isEmpty()
        }
        if (looksEmpty) {
            kotlinx.coroutines.delay(350)
            applyMode()
        }
    }

    /** Switch between Tree and List, materializing the correct flow for the new mode. */
    suspend fun setViewMode(mode: ExplorerViewMode) {
        if (_viewMode.value == mode) return
        _viewMode.value = mode
        selectionState.clear()
        applyMode()
    }

    /** Update the project-root exclude patterns + effect (grey-out vs hide) and re-materialize. */
    suspend fun setHiddenPatterns(patterns: List<String>, greyOutExcluded: Boolean = true) {
        if (hiddenPatterns == patterns && greyOut == greyOutExcluded) return
        hiddenPatterns = patterns
        greyOut = greyOutExcluded
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
            out.add(buildRow(child, depth, isExcludedAtRoot(parentPath, child)).copy(isExpanded = expanded))
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

    /** Root children to show — ONLY the project root is affected (a nested folder with the same name
     *  stays). In "hide" mode matched entries are dropped; in "grey out" mode they are kept and later
     *  dimmed via [isExcludedAtRoot]. */
    private fun filterHiddenAtRoot(parentPath: FsPath, children: List<FsNode>): List<FsNode> {
        if (greyOut || hiddenPatterns.isEmpty() || parentPath.stableId != projectRootPath?.stableId) return children
        return children.filterNot { child -> hiddenPatterns.any { matchesHidden(it, child.name) } }
    }

    /** Whether [node] is a project-root child matched by the exclude list while the effect is grey-out. */
    private fun isExcludedAtRoot(parentPath: FsPath, node: FsNode): Boolean {
        if (!greyOut || hiddenPatterns.isEmpty() || parentPath.stableId != projectRootPath?.stableId) return false
        return hiddenPatterns.any { matchesHidden(it, node.name) }
    }

    private fun matchesHidden(pattern: String, name: String): Boolean {
        var pat = pattern.trim()
        if (pat.isEmpty() || pat.startsWith("#") || pat.startsWith("!")) return false // blank / comment / negation
        pat = pat.trimStart('/').trimEnd('/')
        // gitignore's "any directory" prefix: `**/foo` may hide a root child named foo, but the `**`
        // segment itself must not become the head — as a glob it matches EVERY name, so one such
        // pattern (VisualStudio.gitignore ships `**/[Pp]ackages/*`) blanked the whole explorer.
        while (pat.startsWith("**/")) pat = pat.removePrefix("**/")
        // A pattern that still holds a '/' targets something NESTED (e.g. `src/generated`, `src/**/*.js`);
        // gitignore does not ignore the ancestor `src` itself, so it must not hide the root child. Only a
        // whole-name pattern (`foo`, `foo/`, `/foo`, `**/foo` — the leading/trailing slashes already
        // trimmed above) excludes a project-root entry.
        if (pat.isEmpty() || '/' in pat) return false
        return if (pat.any { it == '*' || it == '?' }) globToRegex(pat).matches(name) else pat == name
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
            _listRows.value = children.map { child -> buildRow(child, depth = 0, isExcludedAtRoot(path, child)) }
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

    private fun buildRow(node: FsNode, depth: Int, excluded: Boolean = false): TreeRow {
        val key = node.path.stableId
        return TreeRow(
            id = key,
            node = node,
            depth = depth,
            isExpanded = false,
            isSelected = false,
            vcsStatus = scmStatus[key] ?: scmDirStatus[key],
            isSubmodule = key in scmSubmodules,
            isExcluded = excluded,
        )
    }
}
