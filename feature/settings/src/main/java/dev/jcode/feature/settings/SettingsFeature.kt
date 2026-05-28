package dev.jcode.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jcode.core.config.ConfigScope
import dev.jcode.core.config.EffectiveConfig
import dev.jcode.core.config.ProjectConfig
import dev.jcode.core.config.WorkspaceConfig
import dev.jcode.core.distro.DistroEnvironmentState

object SettingsFeature {

    @Composable
    fun Content(
        effectiveConfig: EffectiveConfig,
        workspaceConfig: WorkspaceConfig?,
        projectConfig: ProjectConfig?,
        workspaceError: String?,
        projectError: String?,
        projectOverridesAvailable: Boolean,
        environmentState: DistroEnvironmentState,
        onOpenWorkspaceConfig: () -> Unit,
        onOpenProjectConfig: () -> Unit,
        onOpenEnvironmentWizard: () -> Unit,
        onRefreshEnvironment: () -> Unit,
        onUpdateFontSize: (ConfigScope, Float) -> Unit,
        onUpdateTabSize: (ConfigScope, Int) -> Unit,
        onUpdateWordWrap: (ConfigScope, Boolean) -> Unit,
        onUpdateMinimap: (ConfigScope, Boolean) -> Unit,
        onUpdateLigatures: (ConfigScope, Boolean) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        var selectedScope by rememberSaveable(projectOverridesAvailable) {
            mutableStateOf(if (projectOverridesAvailable) ConfigScope.Project else ConfigScope.Workspace)
        }
        LaunchedEffect(projectOverridesAvailable) {
            if (!projectOverridesAvailable) {
                selectedScope = ConfigScope.Workspace
            }
        }

        val scopedEditor = when (selectedScope) {
            ConfigScope.Workspace -> workspaceConfig?.editor
            ConfigScope.Project -> projectConfig?.editor
        }

        val fontSize = scopedEditor?.fontSize ?: effectiveConfig.editor.fontSize
        val tabSize = scopedEditor?.tabSize ?: effectiveConfig.editor.tabSize
        val wordWrap = scopedEditor?.wordWrap ?: effectiveConfig.editor.wordWrap
        val minimap = scopedEditor?.minimap ?: effectiveConfig.editor.minimap
        val ligatures = scopedEditor?.ligatures ?: effectiveConfig.editor.ligatures

        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SettingsCard(
                title = "Effective config",
                description = "Project values override workspace values. Invalid YAML keeps the last working config active.",
            ) {
                SummaryRow("Font size", "${effectiveConfig.editor.fontSize.toInt()} sp")
                SummaryRow("Tab size", "${effectiveConfig.editor.tabSize}")
                SummaryRow("Word wrap", if (effectiveConfig.editor.wordWrap) "On" else "Off")
                SummaryRow("Minimap", if (effectiveConfig.editor.minimap) "On" else "Off")
                SummaryRow("Ligatures", if (effectiveConfig.editor.ligatures) "On" else "Off")
                SummaryRow("Distro", effectiveConfig.distro.id)
            }

            SettingsCard(
                title = "Environment",
                description = "Phase 7 setup lives here: Termux checks, distro bootstrap, and the final smoke test.",
            ) {
                SummaryRow(
                    "Termux",
                    when {
                        environmentState.termux.meetsMinimumVersion -> environmentState.termux.versionName ?: "Ready"
                        environmentState.termux.installed -> "Update required (${environmentState.termux.versionName})"
                        else -> "Missing"
                    },
                )
                SummaryRow(
                    "RUN_COMMAND",
                    if (environmentState.termux.runCommandGranted) "Granted" else "Missing",
                )
                SummaryRow(
                    "allow-external-apps",
                    when (environmentState.termux.allowExternalAppsEnabled) {
                        true -> "Enabled"
                        false -> "Disabled"
                        null -> "Unchecked"
                    },
                )
                SummaryRow("Distro", environmentState.runtime.selectedDistro.label)
                SummaryRow(
                    "Primary bind",
                    environmentState.runtime.binds.firstOrNull()?.target ?: "/workspace",
                )
                environmentState.runningStep?.let { runningStep ->
                    Text(
                        text = "Running: ${runningStep.key}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                environmentState.activityLog.takeLast(3).forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onOpenEnvironmentWizard, modifier = Modifier.weight(1f)) {
                        Text("Open setup")
                    }
                    OutlinedButton(onClick = onRefreshEnvironment, modifier = Modifier.weight(1f)) {
                        Text("Refresh checks")
                    }
                }
            }

            SettingsCard(
                title = "Edit scope",
                description = if (projectOverridesAvailable) {
                    "Change workspace defaults or set project-specific overrides."
                } else {
                    "Only workspace-level editing is available until a local project is selected."
                },
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ConfigScope.entries.forEachIndexed { index, scope ->
                        val enabled = scope == ConfigScope.Workspace || projectOverridesAvailable
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = ConfigScope.entries.size),
                            onClick = {
                                if (enabled) {
                                    selectedScope = scope
                                }
                            },
                            selected = selectedScope == scope,
                            enabled = enabled,
                            label = { Text(scope.name) },
                        )
                    }
                }
                Text(
                    text = when (selectedScope) {
                        ConfigScope.Workspace -> "Workspace changes apply across projects unless a project override exists."
                        ConfigScope.Project -> "Project changes only affect the selected local project."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            workspaceError?.let { message ->
                WarningCard(title = "Workspace YAML warning", message = message)
            }

            if (projectOverridesAvailable) {
                projectError?.let { message ->
                    WarningCard(title = "Project YAML warning", message = message)
                }
            }

            environmentState.errorMessage?.let { message ->
                WarningCard(title = "Environment warning", message = message)
            }

            SettingsCard(
                title = "Editor behavior",
                description = "These controls write back to YAML and update the open editor immediately.",
            ) {
                StepperRow(
                    label = "Font size",
                    value = "${fontSize.toInt()} sp",
                    onDecrease = { onUpdateFontSize(selectedScope, (fontSize - 1f).coerceAtLeast(8f)) },
                    onIncrease = { onUpdateFontSize(selectedScope, (fontSize + 1f).coerceAtMost(72f)) },
                )
                OptionRow(
                    label = "Tab size",
                    supporting = "Good defaults are 2, 4, or 8 spaces depending on the project.",
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(2, 4, 8).forEach { option ->
                            val selected = tabSize == option
                            if (selected) {
                                FilledTonalButton(
                                    onClick = { onUpdateTabSize(selectedScope, option) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("$option")
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { onUpdateTabSize(selectedScope, option) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("$option")
                                }
                            }
                        }
                    }
                }
                ToggleRow(
                    label = "Word wrap",
                    supporting = "Helpful on portrait phones; many codebases still prefer it off by default.",
                    checked = wordWrap,
                    onCheckedChange = { onUpdateWordWrap(selectedScope, it) },
                )
                ToggleRow(
                    label = "Minimap",
                    supporting = "Useful on tablets and desktop windows. Keep it optional on smaller widths.",
                    checked = minimap,
                    onCheckedChange = { onUpdateMinimap(selectedScope, it) },
                )
                ToggleRow(
                    label = "Ligatures",
                    supporting = "Keep enabled for the editor surface, but let users disable it for long coding sessions.",
                    checked = ligatures,
                    onCheckedChange = { onUpdateLigatures(selectedScope, it) },
                )
            }

            SettingsCard(
                title = "YAML files",
                description = "Open the backing config files directly when you want full control.",
            ) {
                FilledTonalButton(onClick = onOpenWorkspaceConfig, modifier = Modifier.fillMaxWidth()) {
                    Text("Open workspace YAML")
                }
                OutlinedButton(
                    onClick = onOpenProjectConfig,
                    enabled = projectOverridesAvailable,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open project YAML")
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    description: String,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = title, fontWeight = FontWeight.SemiBold)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            content()
        }
    }
}

@Composable
private fun WarningCard(
    title: String,
    message: String,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun SummaryRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium)
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onDecrease) { Text("-") }
        FilledTonalButton(onClick = onIncrease) { Text("+") }
    }
}

@Composable
private fun OptionRow(
    label: String,
    supporting: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontWeight = FontWeight.Medium)
        Text(
            text = supporting,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@Composable
private fun ToggleRow(
    label: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, fontWeight = FontWeight.Medium)
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
