package dev.jcode.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.jcode.core.distro.DistroEnvironmentState
import dev.jcode.core.distro.DistroProfile
import dev.jcode.core.distro.WizardStepId

object OnboardingFeature {

    @Composable
    fun TermuxDistroWizard(
        environmentState: DistroEnvironmentState,
        onDismiss: () -> Unit,
        onRefresh: () -> Unit,
        onSelectDistro: (DistroProfile) -> Unit,
        onRunStep: (WizardStepId) -> Unit,
        onInstallTermuxFromFdroid: () -> Unit,
        onOpenTermuxGitHubReleases: () -> Unit,
        onInstallTermuxApi: () -> Unit,
        onGrantRunCommandPermission: () -> Unit,
    ) {
        val steps = remember(environmentState) { buildSteps(environmentState) }

        Dialog(onDismissRequest = onDismiss) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 920.dp)
                    .heightIn(max = 760.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "Environment setup",
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "Phase 7 starter flow for Termux, proot-distro, and the first smoke test.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = onDismiss) {
                                Text("Close")
                            }
                        }

                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            DistroProfile.entries.forEachIndexed { index, profile ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = DistroProfile.entries.size,
                                    ),
                                    selected = environmentState.runtime.selectedDistro == profile,
                                    onClick = { onSelectDistro(profile) },
                                    label = { Text(profile.label) },
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = onRefresh) {
                                Text("Refresh checks")
                            }
                            OutlinedButton(onClick = { onRunStep(WizardStepId.SmokeTest) }) {
                                Text("Run smoke test")
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item {
                            EnvironmentOverviewCard(environmentState = environmentState)
                        }

                        items(steps, key = StepCardModel::id) { step ->
                            StepCard(
                                model = step,
                                environmentState = environmentState,
                                onRunStep = onRunStep,
                                onInstallTermuxFromFdroid = onInstallTermuxFromFdroid,
                                onOpenTermuxGitHubReleases = onOpenTermuxGitHubReleases,
                                onInstallTermuxApi = onInstallTermuxApi,
                                onGrantRunCommandPermission = onGrantRunCommandPermission,
                            )
                        }

                        if (environmentState.activityLog.isNotEmpty()) {
                            item {
                                LogCard(lines = environmentState.activityLog)
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class StepCardModel(
    val id: String,
    val stepId: WizardStepId,
    val title: String,
    val description: String,
    val complete: Boolean,
    val optional: Boolean = false,
)

@Composable
private fun EnvironmentOverviewCard(environmentState: DistroEnvironmentState) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Current state", fontWeight = FontWeight.SemiBold)
            StepSummaryRow(
                label = "Termux",
                value = when {
                    environmentState.termux.meetsMinimumVersion -> environmentState.termux.versionName ?: "Ready"
                    environmentState.termux.installed -> "Update needed"
                    else -> "Missing"
                },
            )
            StepSummaryRow(
                label = "RUN_COMMAND",
                value = if (environmentState.termux.runCommandGranted) "Granted" else "Not granted",
            )
            StepSummaryRow(
                label = "allow-external-apps",
                value = when (environmentState.termux.allowExternalAppsEnabled) {
                    true -> "Enabled"
                    false -> "Disabled"
                    null -> "Unchecked"
                },
            )
            StepSummaryRow(
                label = "Selected distro",
                value = environmentState.runtime.selectedDistro.label,
            )
            StepSummaryRow(
                label = "Primary bind",
                value = environmentState.runtime.binds.firstOrNull()?.let { "${it.host} -> ${it.target}" } ?: "--",
            )
        }
    }
}

@Composable
private fun StepCard(
    model: StepCardModel,
    environmentState: DistroEnvironmentState,
    onRunStep: (WizardStepId) -> Unit,
    onInstallTermuxFromFdroid: () -> Unit,
    onOpenTermuxGitHubReleases: () -> Unit,
    onInstallTermuxApi: () -> Unit,
    onGrantRunCommandPermission: () -> Unit,
) {
    val running = environmentState.runningStep == model.stepId
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (model.complete) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
        },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(model.title, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = model.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.widthIn(min = 12.dp))
                StatusPill(
                    text = when {
                        running -> "Running"
                        model.complete -> "Done"
                        model.optional -> "Optional"
                        else -> "Pending"
                    },
                    active = running || model.complete,
                )
            }

            when (model.stepId) {
                WizardStepId.TermuxInstalled -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = onInstallTermuxFromFdroid) {
                            Text("Open F-Droid")
                        }
                        OutlinedButton(onClick = onOpenTermuxGitHubReleases) {
                            Text("GitHub releases")
                        }
                    }
                }

                WizardStepId.TermuxApiInstalled -> {
                    FilledTonalButton(onClick = onInstallTermuxApi) {
                        Text("Install Termux:API")
                    }
                }

                WizardStepId.RunCommandPermission -> {
                    FilledTonalButton(onClick = onGrantRunCommandPermission) {
                        Text("Open app permissions")
                    }
                }

                WizardStepId.DistroSelected -> {
                    Text(
                        text = "Use the distro selector at the top of the dialog. Current pick: ${environmentState.runtime.selectedDistro.label}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    FilledTonalButton(
                        onClick = { onRunStep(model.stepId) },
                        enabled = !running,
                    ) {
                        Text(
                            when (model.stepId) {
                                WizardStepId.AllowExternalApps -> "Enable and validate"
                                WizardStepId.ProotDistroInstalled -> "Install proot-distro"
                                WizardStepId.DistroInstalled -> "Install distro"
                                WizardStepId.WorkspaceReady -> "Create workspace"
                                WizardStepId.ToolchainBootstrapped -> "Bootstrap toolchain"
                                WizardStepId.JcodeUserCreated -> "Create jcode user"
                                WizardStepId.SmokeTest -> "Run smoke test"
                                else -> "Run"
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogCard(lines: List<String>) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Recent activity", fontWeight = FontWeight.SemiBold)
            lines.takeLast(8).forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StepSummaryRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(contentAlignment = Alignment.CenterEnd) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun StatusPill(
    text: String,
    active: Boolean,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (active) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
        },
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun buildSteps(environmentState: DistroEnvironmentState): List<StepCardModel> {
    return listOf(
        StepCardModel(
            id = WizardStepId.TermuxInstalled.key,
            stepId = WizardStepId.TermuxInstalled,
            title = "1. Install or update Termux",
            description = "Requires Termux 0.118 or newer before J Code can issue RUN_COMMAND calls.",
            complete = environmentState.termux.meetsMinimumVersion,
        ),
        StepCardModel(
            id = WizardStepId.TermuxApiInstalled.key,
            stepId = WizardStepId.TermuxApiInstalled,
            title = "2. Install Termux:API",
            description = "Optional, but recommended for clipboard and notification helpers from inside the distro.",
            complete = environmentState.termux.apiInstalled,
            optional = true,
        ),
        StepCardModel(
            id = WizardStepId.RunCommandPermission.key,
            stepId = WizardStepId.RunCommandPermission,
            title = "3. Grant RUN_COMMAND",
            description = "Open J Code's app permissions and grant the custom Termux RUN_COMMAND permission.",
            complete = environmentState.termux.runCommandGranted,
        ),
        StepCardModel(
            id = WizardStepId.AllowExternalApps.key,
            stepId = WizardStepId.AllowExternalApps,
            title = "4. Enable allow-external-apps",
            description = "Writes `allow-external-apps=true` to `~/.termux/termux.properties` and validates the round-trip.",
            complete = environmentState.termux.allowExternalAppsEnabled == true,
        ),
        StepCardModel(
            id = WizardStepId.ProotDistroInstalled.key,
            stepId = WizardStepId.ProotDistroInstalled,
            title = "5. Install proot-distro",
            description = "Installs the base proot-distro package inside Termux.",
            complete = environmentState.prootDistroInstalled == true,
        ),
        StepCardModel(
            id = WizardStepId.DistroSelected.key,
            stepId = WizardStepId.DistroSelected,
            title = "6. Pick a distro",
            description = "Choose Ubuntu 24.04 LTS or Debian 12 Bookworm for the initial environment.",
            complete = true,
        ),
        StepCardModel(
            id = WizardStepId.DistroInstalled.key,
            stepId = WizardStepId.DistroInstalled,
            title = "7. Install the selected distro",
            description = "Runs `proot-distro install` for the selected distro profile.",
            complete = environmentState.distroInstalled == true,
        ),
        StepCardModel(
            id = WizardStepId.WorkspaceReady.key,
            stepId = WizardStepId.WorkspaceReady,
            title = "8. Prepare the workspace host path",
            description = "Creates the shared host directory that will bind into `/workspace`.",
            complete = environmentState.workspaceReady,
        ),
        StepCardModel(
            id = WizardStepId.ToolchainBootstrapped.key,
            stepId = WizardStepId.ToolchainBootstrapped,
            title = "9. Bootstrap the base toolchain",
            description = "Installs the baseline build/debug packages inside the distro.",
            complete = environmentState.toolchainReady == true,
        ),
        StepCardModel(
            id = WizardStepId.JcodeUserCreated.key,
            stepId = WizardStepId.JcodeUserCreated,
            title = "10. Create the jcode user",
            description = "Creates the non-root `jcode` account and grants passwordless sudo in the distro.",
            complete = environmentState.jcodeUserReady == true,
        ),
        StepCardModel(
            id = WizardStepId.SmokeTest.key,
            stepId = WizardStepId.SmokeTest,
            title = "11. Final smoke test",
            description = "Runs `clangd --version && node --version && echo ok` inside `/workspace` as `jcode`.",
            complete = environmentState.smokeTestPassed == true,
        ),
    )
}
