package dev.jcode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jcode.core.config.EffectiveConfig
import dev.jcode.feature.editor.pane.EditorTab
import dev.jcode.fs.Project
import dev.jcode.fs.ProjectKind
import dev.jcode.fs.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Lightweight per-file editor metrics surfaced in the bottom status bar. */
internal data class EditorMetrics(
    val line: Int = 1,
    val column: Int = 1,
    val language: String = "Plain Text",
    val encoding: String = "UTF-8",
)

@Composable
internal fun WorkbenchStatusBar(
    metrics: EditorMetrics,
    activeTab: EditorTab?,
    selectedProject: Project?,
    workspace: Workspace?,
    effectiveConfig: EffectiveConfig,
    activeDistroId: String,
) {
    val branch = rememberGitBranch(selectedProject)
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
            StatusCell("problems: 0")
            // File-only metrics are meaningless for a page tab (e.g. Settings); hide them there.
            if (activeTab?.isPage != true) {
                StatusCell("cursor: ${metrics.line}:${metrics.column}")
                StatusCell("lang: ${metrics.language}")
                StatusCell("enc: ${metrics.encoding}")
            }
            StatusCell("distro: ${distro.ifBlank { "--" }}")
            Spacer(modifier = Modifier.weight(1f))
            StatusCell(workspace?.name ?: "Workspace")
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
private fun RowScope.StatusCell(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
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

    return EditorMetrics(
        line = line + 1,
        column = column + 1,
        language = activeTab.languageDescriptor?.name ?: "Plain Text",
        encoding = "UTF-8",
    )
}
