package dev.jcode
import dev.jcode.design.JCodeIcon
import dev.jcode.design.jcIcon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jcode.fs.Project
import dev.jcode.run.ProjectRunner

/**
 * The "Build & Run" side-panel. Lists the projects in scope — all of them inside a User Workspace,
 * or just the single open project in the Default Workspace — and for each offers Build & Run and a
 * Configure action (which opens the structured `.jcode/run.yaml` editor). The actual run + browser
 * launch is orchestrated by the workbench shell (see `handleRun`).
 */
@Composable
internal fun RunDebugPanel(
    projects: List<Project>,
    runningProjectId: Long?,
    runUrl: String?,
    runInProgress: Boolean,
    runConfigVersion: Int,
    onRun: (Project) -> Unit,
    onConfigure: (Project) -> Unit,
    onStop: () -> Unit,
    onOpenInBrowser: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = jcIcon(JCodeIcon.Run),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(text = "Build & Run", fontWeight = FontWeight.SemiBold)
        }

        if (projects.isEmpty()) {
            Text(
                text = "Open a project to build & run.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            projects.forEach { project ->
                ProjectRunRow(
                    project = project,
                    isRunning = runningProjectId == project.id,
                    runUrl = runUrl,
                    runInProgress = runInProgress,
                    runConfigVersion = runConfigVersion,
                    onRun = onRun,
                    onConfigure = onConfigure,
                    onStop = onStop,
                    onOpenInBrowser = onOpenInBrowser,
                )
            }
        }
    }
}

@Composable
private fun ProjectRunRow(
    project: Project,
    isRunning: Boolean,
    runUrl: String?,
    runInProgress: Boolean,
    runConfigVersion: Int,
    onRun: (Project) -> Unit,
    onConfigure: (Project) -> Unit,
    onStop: () -> Unit,
    onOpenInBrowser: () -> Unit,
) {
    // Re-resolve when the project changes or a config is saved (runConfigVersion bumps).
    val plan = remember(project.id, runConfigVersion) { ProjectRunner.effectivePlan(project) }
    val configured = plan != null

    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f)) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(project.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                text = plan?.kindLabel ?: "Not configured",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (configured && plan!!.readyPort > 0) {
                Text(
                    text = "Serves on ${plan.url}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { onRun(project) },
                    enabled = configured && !(isRunning && runInProgress),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(jcIcon(JCodeIcon.Run), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isRunning) "Re-run" else "Build & Run")
                }
                OutlinedButton(
                    onClick = { onConfigure(project) },
                    modifier = Modifier.weight(1f),
                ) { Text("Configure") }
            }

            if (isRunning) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = onStop,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Icon(jcIcon(JCodeIcon.Stop), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Stop")
                    }
                    if (runUrl != null) {
                        FilledTonalButton(onClick = onOpenInBrowser, modifier = Modifier.weight(1f)) {
                            Text("Open in browser")
                        }
                    }
                }
                if (runInProgress) {
                    Text(
                        text = "Building… the browser opens when the server is up.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
