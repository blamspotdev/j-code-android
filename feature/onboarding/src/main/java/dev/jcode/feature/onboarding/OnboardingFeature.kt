package dev.jcode.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.jcode.core.distro.DistroEnvironmentState
import dev.jcode.core.distro.DistroProfile
import dev.jcode.core.distro.DistroWizardProgress
import dev.jcode.core.distro.WizardStepId

object OnboardingFeature {

    @Composable
    fun FirstRunEnvironmentScreen(
        environmentState: DistroEnvironmentState,
        autoSetupProgress: DistroWizardProgress,
        onRefresh: () -> Unit,
        onSelectDistro: (DistroProfile) -> Unit,
        onRunStep: (WizardStepId) -> Unit,
        onAutoSetup: () -> Unit,
        onSetupManualLater: (() -> Unit)?,
        onDismiss: (() -> Unit)? = null,
    ) {
        StepperScreen(
            environmentState = environmentState,
            autoSetupProgress = autoSetupProgress,
            onSelectDistro = onSelectDistro,
            onAutoSetup = onAutoSetup,
            onRefresh = onRefresh,
            onSetupManualLater = onSetupManualLater,
            onDismiss = onDismiss,
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .navigationBarsPadding(),
            shape = RoundedCornerShape(0.dp),
        )
    }

    @Composable
    fun TermuxDistroWizard(
        environmentState: DistroEnvironmentState,
        autoSetupProgress: DistroWizardProgress,
        onDismiss: () -> Unit,
        onRefresh: () -> Unit,
        onSelectDistro: (DistroProfile) -> Unit,
        onRunStep: (WizardStepId) -> Unit,
        onAutoSetup: () -> Unit,
    ) {
        Dialog(onDismissRequest = onDismiss) {
            StepperScreen(
                environmentState = environmentState,
                autoSetupProgress = autoSetupProgress,
                onSelectDistro = onSelectDistro,
                onAutoSetup = onAutoSetup,
                onRefresh = onRefresh,
                onSetupManualLater = null,
                onDismiss = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 760.dp),
                shape = RoundedCornerShape(28.dp),
            )
        }
    }
}

@Composable
private fun StepperScreen(
    environmentState: DistroEnvironmentState,
    autoSetupProgress: DistroWizardProgress,
    onSelectDistro: (DistroProfile) -> Unit,
    onAutoSetup: () -> Unit,
    onRefresh: () -> Unit,
    onSetupManualLater: (() -> Unit)?,
    onDismiss: (() -> Unit)?,
    modifier: Modifier,
    shape: RoundedCornerShape,
) {
    val running = autoSetupProgress is DistroWizardProgress.Running
    val completed = autoSetupProgress is DistroWizardProgress.AllDone
    var logsExpanded by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(running) {
        if (running) logsExpanded = true
    }

    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Header()
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    StepCard(
                        number = 1,
                        title = "Select Distro or Setup Later",
                        active = !running,
                    ) {
                        Text(
                            text = "Choose the Linux distro J Code should prepare for your embedded environment.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val availableDistros = environmentState.availableDistros.ifEmpty { DistroProfile.defaults() }
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            availableDistros.forEachIndexed { index, profile ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(index, availableDistros.size),
                                    selected = environmentState.runtime.selectedDistro == profile,
                                    onClick = { onSelectDistro(profile) },
                                    enabled = !running,
                                    label = { Text(profile.label) },
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = onAutoSetup, enabled = !running) {
                                Text("Use ${environmentState.runtime.selectedDistro.label}")
                            }
                            if (onSetupManualLater != null) {
                                OutlinedButton(onClick = onSetupManualLater, enabled = !running) {
                                    Text("Setup later")
                                }
                            } else {
                                OutlinedButton(onClick = onRefresh, enabled = !running) {
                                    Text("Refresh")
                                }
                            }
                        }
                    }
                }

                item {
                    StepCard(
                        number = 2,
                        title = "Configuring Environment...",
                        active = running || completed,
                    ) {
                        val progressText = when (autoSetupProgress) {
                            is DistroWizardProgress.Running -> autoSetupProgress.label
                            is DistroWizardProgress.Completed -> autoSetupProgress.detail
                            is DistroWizardProgress.Failed -> autoSetupProgress.error
                            is DistroWizardProgress.AllDone -> autoSetupProgress.summary
                            DistroWizardProgress.Idle -> "Waiting for you to choose a distro."
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            when {
                                running -> CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                completed -> Text("✓", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                                autoSetupProgress is DistroWizardProgress.Failed -> Text("✕", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                                else -> Text("2", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                text = progressText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        ActivityLogCard(
                            activityLog = environmentState.activityLog,
                            runningStep = environmentState.runningStep,
                            expanded = logsExpanded,
                            onToggle = { logsExpanded = !logsExpanded },
                        )

                        if (completed && onDismiss != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            FilledTonalButton(
                                onClick = onDismiss,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Done")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Environment setup", fontWeight = FontWeight.SemiBold)
            Text(
                text = "Step 1: choose a distro. Step 2: J Code configures the rest automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StepCard(
    number: Int,
    title: String,
    active: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (active) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
            ) {
                Box(
                    modifier = Modifier.size(36.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(number.toString(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun ActivityLogCard(
    activityLog: List<String>,
    runningStep: WizardStepId?,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Setup log", fontWeight = FontWeight.SemiBold)
                    Text(
                        text = runningStep?.key ?: "Waiting for progress",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onToggle) {
                    Text(if (expanded) "Hide" else "Show")
                }
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                val listState = rememberLazyListState()
                LaunchedEffect(activityLog.size) {
                    if (activityLog.isNotEmpty()) {
                        listState.animateScrollToItem(activityLog.size - 1)
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (activityLog.isEmpty()) {
                        item {
                            LogLine("setup has not started yet")
                        }
                    } else {
                        items(activityLog) { line ->
                            LogLine(line)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogLine(text: String) {
    Text(
        text = "$ $text",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = when {
            text.contains("failed", ignoreCase = true) || text.contains("error", ignoreCase = true) -> MaterialTheme.colorScheme.error
            text.contains("done", ignoreCase = true) || text.contains("ok", ignoreCase = true) || text.contains("success", ignoreCase = true) -> Color(0xFF4CAF50)
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.88f)
        },
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
    )
}
