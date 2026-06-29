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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jcode.adaptive.JCodePosture
import dev.jcode.adaptive.JCodeWindowInfo
import dev.jcode.core.config.EffectiveConfig
import dev.jcode.feature.editor.pane.EditorTab
import dev.jcode.fs.Project
import dev.jcode.fs.Workspace

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
    windowInfo: JCodeWindowInfo,
) {
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
            StatusCell("branch: --")
            StatusCell("problems: 0")
            // File-only metrics are meaningless for a page tab (e.g. Settings); hide them there.
            if (activeTab?.isPage != true) {
                StatusCell("cursor: ${metrics.line}:${metrics.column}")
                StatusCell("lang: ${metrics.language}")
                StatusCell("enc: ${metrics.encoding}")
            }
            StatusCell("distro: ${if (selectedProject != null) effectiveConfig.distro.id else "--"}")
            Spacer(modifier = Modifier.weight(1f))
            StatusCell(workspace?.name ?: "Workspace")
            StatusCell("posture: ${windowInfo.posture.shortLabel()}")
        }
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

private fun JCodePosture.shortLabel(): String = when (this) {
    JCodePosture.Flat -> "flat"
    JCodePosture.TableTop -> "tabletop"
    JCodePosture.Book -> "book"
}
