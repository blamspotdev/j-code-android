package dev.jcode

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jcode.core.buffer.EditTx
import dev.jcode.core.config.EffectiveConfig
import dev.jcode.design.JCodeIcon
import dev.jcode.design.jcIcon
import dev.jcode.core.lsp.LspModule
import dev.jcode.feature.editor.pane.EditorTab
import dev.jcode.fs.Project
import dev.jcode.fs.ProjectKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Lightweight per-file editor metrics surfaced in the bottom status bar. */
internal data class EditorMetrics(
    val line: Int = 1,
    val column: Int = 1,
    val language: String = "Plain Text",
    val encoding: String = "UTF-8",
    val lineEnding: String = "LF",
)

@Composable
internal fun WorkbenchStatusBar(
    activeTab: EditorTab?,
    selectedProject: Project?,
    effectiveConfig: EffectiveConfig,
    activeDistroId: String,
) {
    // Collected here (not hoisted into JCodeShell): the caret/snapshot flows emit on every
    // keystroke and caret move, so reading them in this bottomBar scope keeps a keystroke from
    // recomposing the whole workbench body — only this 20dp status row invalidates.
    val metrics = rememberEditorMetrics(activeTab)
    val branch = rememberGitBranch(selectedProject)
    val issueCount by LspModule.diagnosticsBus.totalCount.collectAsStateWithLifecycle()
    // A project's effective distro can be overridden in its `.jcode`; otherwise fall back to the
    // globally active environment so the cell still reflects what terminals/builds target.
    val distro = if (selectedProject != null) effectiveConfig.distro.id else activeDistroId
    Surface(
        modifier = Modifier.navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusCell("branch: $branch")
            StatusCell(
                "${issueCount.total}",
                color = if (issueCount.hasErrors) MaterialTheme.colorScheme.error else Color.Unspecified,
                icon = jcIcon(JCodeIcon.Problems),
            )
            // File-only metrics are meaningless for a page tab (e.g. Settings); hide them there.
            if (activeTab?.isPage != true) {
                StatusCell("${metrics.line}:${metrics.column}", icon = jcIcon(JCodeIcon.Cursor))
                StatusCell("lang: ${metrics.language}")
                // Encoding + line-ending apply to an actual open file; hide them when none is focused.
                val editorState = activeTab?.editorState
                if (editorState != null) {
                    EncodingCell(metrics.encoding)
                    LineEndingCell(metrics.lineEnding, editorState)
                }
            }
            StatusCell("distro: ${distro.ifBlank { "--" }}")
        }
    }
}

/**
 * Resolve the current git branch (or short detached-HEAD sha) for a local project by reading
 * `.git/HEAD` off the main thread. Returns "--" when there is no project, the project is a SAF
 * (content-uri) tree, or the folder is not a git repo.
 */
@Composable
private fun rememberGitBranch(project: Project?): String {
    val location = if (project?.kind == ProjectKind.Local) project.location else null
    val branch by produceState(initialValue = "--", location) {
        value = withContext(Dispatchers.IO) { readGitBranch(location) } ?: "--"
    }
    return branch
}

private fun readGitBranch(location: String?): String? {
    if (location.isNullOrBlank()) return null
    val dotGit = File(location, ".git")
    val headFile = when {
        dotGit.isDirectory -> File(dotGit, "HEAD")
        // Worktrees/submodules store ".git" as a file: "gitdir: <path-to-real-gitdir>".
        dotGit.isFile -> {
            val gitdir = runCatching { dotGit.readText() }.getOrNull()
                ?.lineSequence()?.firstOrNull { it.startsWith("gitdir:") }
                ?.removePrefix("gitdir:")?.trim()
                ?: return null
            val resolved = File(gitdir).let { if (it.isAbsolute) it else File(location, gitdir) }
            File(resolved, "HEAD")
        }
        else -> return null
    }
    if (!headFile.isFile) return null
    val head = runCatching { headFile.readText().trim() }.getOrNull() ?: return null
    return when {
        head.startsWith("ref:") -> head.substringAfterLast('/').ifBlank { null }
        head.length >= 7 -> head.take(7)
        else -> null
    }
}

@Composable
private fun StatusCell(
    text: String,
    color: Color = Color.Unspecified,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = if (color != Color.Unspecified) color else LocalContentColor.current,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Encoding cell: plain text, tappable; shows what's supported (all file IO is UTF-8 today). */
@Composable
private fun EncodingCell(encoding: String) {
    var menu by remember { mutableStateOf(false) }
    Box {
        StatusCell(encoding, onClick = { menu = true })
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("UTF-8") },
                leadingIcon = { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) },
                onClick = { menu = false },
            )
            DropdownMenuItem(
                text = { Text("Other encodings aren't supported yet", style = MaterialTheme.typography.bodySmall) },
                enabled = false,
                onClick = {},
            )
        }
    }
}

/** Line-ending cell (LF/CRLF/CR): tappable; selecting converts the whole document. */
@Composable
private fun LineEndingCell(current: String, editorState: dev.jcode.core.editor.EditorState?) {
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Box {
        StatusCell(current, onClick = { menu = true })
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            listOf("LF", "CRLF").forEach { target ->
                DropdownMenuItem(
                    text = { Text(target) },
                    leadingIcon = {
                        if (target == current) {
                            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    },
                    onClick = {
                        menu = false
                        if (target != current && editorState != null) {
                            scope.launch { convertLineEndings(editorState, target) }
                        }
                    },
                )
            }
        }
    }
}

/** Rewrite the document with the chosen line ending (marks the tab dirty; save persists it). */
private suspend fun convertLineEndings(state: dev.jcode.core.editor.EditorState, target: String) {
    val snap = state.snapshot.value
    val text = snap.readRangeAsUtf16(0, snap.byteLength)
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    val out = if (target == "CRLF") normalized.replace("\n", "\r\n") else normalized
    if (out != text) state.applyEdit(EditTx.replace(0, snap.byteLength, out))
}

@Composable
internal fun rememberEditorMetrics(activeTab: EditorTab?): EditorMetrics {
    val editorState = activeTab?.editorState
    if (editorState == null) {
        // No file backing (no tab, or a page tab such as Settings): report defaults only.
        return EditorMetrics(language = activeTab?.languageDescriptor?.name ?: "Plain Text")
    }

    val carets by editorState.carets.collectAsStateWithLifecycle()
    val snapshot by editorState.snapshot.collectAsStateWithLifecycle()
    val caret = carets.firstOrNull()
    val offset = caret?.head ?: 0
    val (line, column) = remember(snapshot, offset) {
        snapshot.offsetToLineColumn(offset)
    }
    // Detect the dominant line ending once per open document (keyed on the tab, not the snapshot):
    // it effectively never changes mid-session, so re-reading an 8 KB prefix + scanning it on every
    // keystroke was pure churn. The initial snapshot is read the first time this tab is shown.
    val lineEnding = remember(activeTab) {
        val sample = editorState.snapshot.value.readRangeAsUtf16(0, minOf(editorState.snapshot.value.byteLength, 8192))
        when {
            sample.contains("\r\n") -> "CRLF"
            sample.contains('\r') -> "CR"
            else -> "LF"
        }
    }

    return EditorMetrics(
        line = line + 1,
        column = column + 1,
        language = activeTab.languageDescriptor?.name ?: "Plain Text",
        encoding = "UTF-8",
        lineEnding = lineEnding,
    )
}
