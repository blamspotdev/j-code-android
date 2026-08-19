package dev.jcode.feature.explorer

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import dev.jcode.design.JCodeIcon
import dev.jcode.fs.FsNode

/** An extension-contributed entry for the explorer's file/folder context menu. */
@Immutable
data class ExplorerContextAction(
    /** Dispatch key, "extensionId:actionId". */
    val key: String,
    val label: String,
    val icon: JCodeIcon,
    /** Show only on files with a matching extension (lowercase, no dot); empty = every file. */
    val fileExtensions: List<String> = emptyList(),
    /** Show only on these row kinds ("file"/"directory"); empty = both. */
    val targets: List<String> = emptyList(),
)

/**
 * VCS state and extension hooks the shell pushes into the explorer (as a CompositionLocal so the
 * deep JCodeShell call chain doesn't grow more parameters). [status]/[submodules] are keyed by
 * [dev.jcode.fs.FsPath.stableId] (absolute host path).
 */
@Immutable
data class ExplorerScmUi(
    val status: Map<String, String> = emptyMap(),
    val submodules: Set<String> = emptySet(),
    val contextActions: List<ExplorerContextAction> = emptyList(),
    val onContextAction: ((ExplorerContextAction, FsNode) -> Unit)? = null,
    /** Files changed on disk via the explorer (create/rename/delete/paste/Refresh) — lets the shell
     *  hint decoration-pushing extensions to re-run status. */
    val onFsActivity: (() -> Unit)? = null,
)

val LocalExplorerScmUi = compositionLocalOf { ExplorerScmUi() }

/** [dev.jcode.fs.FsPath.stableId] of the open project's root folder, or null when unknown. The
 *  explorer suppresses move (Cut) and Delete on this row — the project root must not be moved or
 *  deleted from the file tree. */
val LocalProjectRootId = compositionLocalOf<String?> { null }

/** True when [action] applies to a row: kind allowed by [ExplorerContextAction.targets] and, for
 *  files, the name matches [ExplorerContextAction.fileExtensions]. */
fun explorerActionAppliesTo(action: ExplorerContextAction, name: String, isDirectory: Boolean): Boolean {
    val kind = if (isDirectory) "directory" else "file"
    if (action.targets.isNotEmpty() && kind !in action.targets) return false
    if (!isDirectory && action.fileExtensions.isNotEmpty()) {
        return name.substringAfterLast('.', "").lowercase() in action.fileExtensions
    }
    return true
}
