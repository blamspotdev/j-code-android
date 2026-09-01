package dev.blamspot.jcode

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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.blamspot.jcode.core.buffer.EditTx
import dev.blamspot.jcode.core.distro.DistroEnvironmentState
import dev.blamspot.jcode.design.IconSize
import dev.blamspot.jcode.design.JCodeIcon
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.jcIcon
import dev.blamspot.jcode.core.lsp.LspModule
import dev.blamspot.jcode.feature.editor.pane.EditorTab
import dev.blamspot.jcode.fs.Project
import dev.blamspot.jcode.fs.ProjectKind
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

/** What the status bar says about the Linux environment, and whether that reads as a failure. */
internal data class DistroStatus(val label: String, val isError: Boolean)

/**
 * The environment's state as one short label.
 *
 * `runningStep` is checked before `errorMessage` on purpose: a wizard step clears `runningStep` and
 * writes `errorMessage` in the same update, so a stale error from an earlier attempt can still be set
 * while a fresh run is under way — and "setting up…" is the truer thing to say then. The ready gate
 * (`distroInstalled == true && jcodeUserReady == true`) is the same predicate the rest of the app
 * uses to decide the runtime is usable.
 */
internal fun distroStatusOf(state: DistroEnvironmentState): DistroStatus = when {
    state.runningStep != null -> DistroStatus("setting up…", isError = false)
    state.errorMessage != null -> DistroStatus("failed", isError = true)
    // Every unknown reads as "checking…", before any of the negative verdicts. Nothing is derived
    // until the startup probe runs, and reporting an environment missing or broken when it has simply
    // not been looked at yet is the one thing that is never true here: a device with no distro is held
    // on the onboarding screen, never shown a workbench.
    state.distroInstalled == null || state.jcodeUserReady == null ->
        DistroStatus("checking…", isError = false)
    !state.prootInstalled || state.distroInstalled == false -> DistroStatus("not installed", isError = false)
    state.jcodeUserReady != true -> DistroStatus("not ready", isError = false)
    else -> DistroStatus(state.runtime.selectedDistro.id, isError = false)
}

@Composable
internal fun WorkbenchStatusBar(
    activeTab: EditorTab?,
    selectedProject: Project?,
    distroStatus: DistroStatus,
    lspServers: List<dev.blamspot.jcode.lsp.LspServerStatus> = emptyList(),
) {
    // Collected here (not hoisted into JCodeShell): the caret/snapshot flows emit on every
    // keystroke and caret move, so reading them in this bottomBar scope keeps a keystroke from
    // recomposing the whole workbench body — only this 20dp status row invalidates.
    val metrics = rememberEditorMetrics(activeTab)
    val branch = rememberGitBranch(selectedProject)
    val issueCount by LspModule.diagnosticsBus.totalCount.collectAsStateWithLifecycle()
    Surface(
        modifier = Modifier.navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .padding(horizontal = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.ms),
        ) {
            if (branch != null) StatusCell("branch: $branch")
            StatusCell(
                "${issueCount.total}",
                color = if (issueCount.hasErrors) MaterialTheme.colorScheme.error else Color.Unspecified,
                icon = jcIcon(JCodeIcon.Problems),
            )
            // Every one of these describes an open buffer, so they all hang off the same condition.
            // Testing `isPage` instead only excluded page tabs (Settings) — with no tab at all the
            // bar still claimed "1:1 · lang: Plain Text" over an empty editor area.
            val editorState = activeTab?.editorState
            if (editorState != null) {
                StatusCell("${metrics.line}:${metrics.column}", icon = jcIcon(JCodeIcon.Cursor))
                StatusCell("lang: ${metrics.language}")
                EncodingCell(metrics.encoding)
                LineEndingCell(metrics.lineEnding, editorState)
            }
            StatusCell(
                "distro: ${distroStatus.label}",
                color = if (distroStatus.isError) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
            // Only while a server is coming up or has failed. A healthy server announces itself
            // through squiggles and completions, and this row has no space for a permanent cell.
            LanguageServerCell(lspServers)
        }
    }
}

/** Reports a language server that is still starting, or one that failed — nothing when all are ready. */
@Composable
private fun LanguageServerCell(servers: List<dev.blamspot.jcode.lsp.LspServerStatus>) {
    val failed = servers.firstOrNull { it.state == dev.blamspot.jcode.core.lsp.LspState.ERROR }
    val pending = servers.firstOrNull { it.state != dev.blamspot.jcode.core.lsp.LspState.READY }
    when {
        failed != null -> StatusCell("lsp: ${failed.name} failed", color = MaterialTheme.colorScheme.error)
        pending != null -> StatusCell("lsp: starting ${pending.name}")
    }
}

/**
 * Resolve the current git branch (or short detached-HEAD sha) for a local project by reading
 * `.git/HEAD` off the main thread. Null when there is no project, the project is a SAF (content-uri)
 * tree, or the folder is not a git repo — the status bar leaves the cell out entirely then, rather
 * than reserving room to say "branch: --" about a folder that was never going to have one.
 */
@Composable
private fun rememberGitBranch(project: Project?): String? {
    val location = if (project?.kind == ProjectKind.Local) project.location else null
    val branch by produceState<String?>(initialValue = null, location) {
        value = withContext(Dispatchers.IO) { readGitBranch(location) }
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
    icon: Painter? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    ) {
        if (icon != null) {
            Icon(
                painter = icon,
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
                leadingIcon = { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(IconSize.sm)) },
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
private fun LineEndingCell(current: String, editorState: dev.blamspot.jcode.core.editor.EditorState?) {
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
                            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(IconSize.sm))
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
private suspend fun convertLineEndings(state: dev.blamspot.jcode.core.editor.EditorState, target: String) {
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
