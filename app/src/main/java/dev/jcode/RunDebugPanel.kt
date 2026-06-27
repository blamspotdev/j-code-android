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
 * The "Run & Debug" side-panel: detects how the selected project builds & runs and exposes a
 * one-tap "Build & Run" plus an "Open in browser" shortcut. The actual build/run terminal and the
 * server-ready browser launch are orchestrated by the workbench shell (see `handleRun`).
 */
@Composable
internal fun RunDebugPanel(
    selectedProject: Project?,
    runUrl: String?,
    runInProgress: Boolean,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onOpenInBrowser: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Filesystem detection is cheap but does touch disk; key it to the project so it only re-runs
    // when the selection changes, not on every recomposition.
    val plan = remember(selectedProject?.id) { selectedProject?.let(ProjectRunner::detectRunPlan) }

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

        when {
            selectedProject == null -> Text(
                text = "Open a project to build & run.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            plan == null -> Text(
                text = "No run configuration detected. Supported: ASP.NET Core + Vite React, or a Vite/React app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> {
                Text(
                    text = plan.kindLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "Serves on ${plan.url}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val isActive = runInProgress || runUrl != null
                if (isActive) {
                    // A run is in flight or its server is up: offer Stop (Ctrl-C the run terminal) and
                    // Re-run side by side.
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
                        FilledTonalButton(
                            onClick = onRun,
                            enabled = !runInProgress,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(jcIcon(JCodeIcon.Run), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (runInProgress) "Building…" else "Re-run")
                        }
                    }
                } else {
                    FilledTonalButton(
                        onClick = onRun,
                        enabled = !runInProgress,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = jcIcon(JCodeIcon.Run),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Build & Run")
                    }
                }
                if (runInProgress) {
                    Text(
                        text = "Building… the browser opens when the server is up.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (runUrl != null) {
                    FilledTonalButton(
                        onClick = onOpenInBrowser,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Open in browser")
                    }
                }
            }
        }
    }
}
