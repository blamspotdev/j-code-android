package dev.jcode.feature.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.jcode.design.JCodeTheme
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jcode.core.distro.Arch
import dev.jcode.core.distro.DistroEnvironmentState
import dev.jcode.core.distro.DistroProfile
import dev.jcode.core.distro.DistroWizardProgress
import dev.jcode.core.distro.EnvironmentInfo
import dev.jcode.core.distro.WizardStepId

object OnboardingFeature {

    @Composable
    fun FirstRunEnvironmentScreen(
        environmentState: DistroEnvironmentState,
        autoSetupProgress: DistroWizardProgress,
        onRefresh: () -> Unit,
        onSelectDistro: (DistroProfile) -> Unit,
        onAutoSetup: () -> Unit,
        onStorageAccessGranted: () -> Unit,
        onDismiss: (() -> Unit)? = null,
    ) {
        // Full-bleed backdrop first, insets padding inside: otherwise the workbench behind the
        // onboarding shows through the status/navigation-bar strips (visible in landscape).
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
                contentAlignment = Alignment.TopCenter,
            ) {
                StepperScreen(
                    environmentState = environmentState,
                    autoSetupProgress = autoSetupProgress,
                    onSelectDistro = onSelectDistro,
                    onAutoSetup = onAutoSetup,
                    onRefresh = onRefresh,
                    onDismiss = onDismiss,
                    modifier = Modifier
                        .widthIn(max = 840.dp)
                        .fillMaxSize(),
                    shape = RoundedCornerShape(0.dp),
                    showStorageStep = true,
                    onStorageAccessGranted = onStorageAccessGranted,
                )
            }
        }
    }

    /** Full-width environment setup, rendered as an in-editor page tab (replaces the cramped dialog). */
    @Composable
    fun EnvironmentSetupPage(
        environmentState: DistroEnvironmentState,
        autoSetupProgress: DistroWizardProgress,
        onRefresh: () -> Unit,
        onSelectDistro: (DistroProfile) -> Unit,
        onAutoSetup: () -> Unit,
    ) {
        val manager = LocalEnvironmentManager.current
        val context = LocalContext.current
        StepperScreen(
            environmentState = environmentState,
            autoSetupProgress = autoSetupProgress,
            onSelectDistro = onSelectDistro,
            onAutoSetup = onAutoSetup,
            onRefresh = onRefresh,
            onDismiss = null,
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(0.dp),
            // Surface the storage step here too so existing installs (which never see the
            // first-run screen again) still get a path to grant shared-storage access.
            showStorageStep = remember { !hasStorageAccess(context) },
            onStorageAccessGranted = manager.onStorageAccessGranted,
            installedEnvironments = manager.environments,
            onSwitchEnvironment = manager.onSwitch,
            onDeleteEnvironment = manager.onDelete,
        )
    }
}

@Composable
private fun StepperScreen(
    environmentState: DistroEnvironmentState,
    autoSetupProgress: DistroWizardProgress,
    onSelectDistro: (DistroProfile) -> Unit,
    onAutoSetup: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: (() -> Unit)?,
    modifier: Modifier,
    shape: RoundedCornerShape,
    showStorageStep: Boolean = false,
    onStorageAccessGranted: () -> Unit = {},
    installedEnvironments: List<EnvironmentInfo> = emptyList(),
    onSwitchEnvironment: (String) -> Unit = {},
    onDeleteEnvironment: (String) -> Unit = {},
) {
    val running = autoSetupProgress is DistroWizardProgress.Running
    val completed = autoSetupProgress is DistroWizardProgress.AllDone
    var logsExpanded by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(running) {
        if (running) logsExpanded = true
    }

    val distroStepNumber = if (showStorageStep) 2 else 1
    val selectionSteps: LazyListScope.() -> Unit = {
        if (installedEnvironments.isNotEmpty()) {
            item {
                InstalledEnvironmentsCard(
                    environments = installedEnvironments,
                    enabled = !running,
                    onSwitch = onSwitchEnvironment,
                    onDelete = onDeleteEnvironment,
                )
            }
        }
        if (showStorageStep) {
            item {
                StorageAccessCard(
                    number = 1,
                    enabled = !running,
                    onGranted = onStorageAccessGranted,
                )
            }
        }
        item {
            DistroSelectionCard(
                number = distroStepNumber,
                environmentState = environmentState,
                running = running,
                onSelectDistro = onSelectDistro,
                onAutoSetup = onAutoSetup,
                onRefresh = onRefresh,
            )
        }
    }

    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Header()
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            // Portrait: one scrolling column of steps. Landscape (wide + short): selection steps
            // on the left, the configure/log card as a full-height pane on the right, so the log
            // stays visible without scrolling past the other cards.
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                val twoPane = maxWidth > maxHeight && maxWidth >= 600.dp
                if (twoPane) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            content = selectionSteps,
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(top = 16.dp, end = 16.dp, bottom = 16.dp),
                        ) {
                            ConfigureStepCard(
                                number = distroStepNumber + 1,
                                environmentState = environmentState,
                                autoSetupProgress = autoSetupProgress,
                                running = running,
                                completed = completed,
                                logsExpanded = logsExpanded,
                                onToggleLogs = { logsExpanded = !logsExpanded },
                                onDismiss = onDismiss,
                                modifier = Modifier.weight(1f),
                                fillLog = true,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        selectionSteps()
                        item {
                            ConfigureStepCard(
                                number = distroStepNumber + 1,
                                environmentState = environmentState,
                                autoSetupProgress = autoSetupProgress,
                                running = running,
                                completed = completed,
                                logsExpanded = logsExpanded,
                                onToggleLogs = { logsExpanded = !logsExpanded },
                                onDismiss = onDismiss,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DistroSelectionCard(
    number: Int,
    environmentState: DistroEnvironmentState,
    running: Boolean,
    onSelectDistro: (DistroProfile) -> Unit,
    onAutoSetup: () -> Unit,
    onRefresh: () -> Unit,
) {
    StepCard(
        number = number,
        title = "Select a distro",
        active = !running,
    ) {
        Text(
            text = "Choose the Linux distro J Code should prepare for your embedded environment.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val availableDistros = environmentState.availableDistros.ifEmpty { DistroProfile.defaults() }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            availableDistros.forEach { profile ->
                val selected = environmentState.runtime.selectedDistro == profile
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = !running) { onSelectDistro(profile) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = { onSelectDistro(profile) },
                        enabled = !running,
                    )
                    Text(profile.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onAutoSetup, enabled = !running) {
                Text("Use ${environmentState.runtime.selectedDistro.label}")
            }
            OutlinedButton(onClick = onRefresh, enabled = !running) {
                Text("Refresh")
            }
        }
    }
}

@Composable
private fun ConfigureStepCard(
    number: Int,
    environmentState: DistroEnvironmentState,
    autoSetupProgress: DistroWizardProgress,
    running: Boolean,
    completed: Boolean,
    logsExpanded: Boolean,
    onToggleLogs: () -> Unit,
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
    fillLog: Boolean = false,
) {
    StepCard(
        number = number,
        title = "Configuring Environment...",
        active = running || completed,
        modifier = modifier,
        fillHeight = fillLog,
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
                completed -> Text("✓", color = JCodeTheme.semanticColors.success, fontWeight = FontWeight.Bold)
                autoSetupProgress is DistroWizardProgress.Failed -> Text("✕", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                else -> Text(number.toString(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Text(
                text = progressText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        val runningProgress = autoSetupProgress as? DistroWizardProgress.Running
        if (runningProgress != null &&
            (runningProgress.progressPercent != null || runningProgress.progressDetail != null)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val percent = runningProgress.progressPercent
                if (percent != null) {
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    text = listOfNotNull(
                        percent?.let { "$it%" },
                        runningProgress.progressDetail,
                    ).joinToString(" — "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        ActivityLogCard(
            activityLog = environmentState.activityLog,
            runningStep = environmentState.runningStep,
            expanded = logsExpanded,
            onToggle = onToggleLogs,
            modifier = if (fillLog && logsExpanded) Modifier.weight(1f) else Modifier,
            fixedLogHeight = !fillLog,
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

@Composable
private fun InstalledEnvironmentsCard(
    environments: List<EnvironmentInfo>,
    enabled: Boolean,
    onSwitch: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val hostArch = Arch.host()
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Installed environments", fontWeight = FontWeight.SemiBold)
            Text(
                text = "Switch which environment terminals and builds target. SDKs and language servers stay " +
                    "installed per environment. Open terminals keep their original environment.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                environments.forEach { env ->
                    EnvironmentRow(
                        env = env,
                        emulated = env.requiresEmulation(hostArch),
                        enabled = enabled,
                        canDelete = enabled && environments.size > 1,
                        onSwitch = { onSwitch(env.id) },
                        onDelete = { pendingDelete = env.id },
                    )
                }
            }
        }
    }

    val deleteId = pendingDelete
    if (deleteId != null) {
        val target = environments.firstOrNull { it.id == deleteId }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove environment") },
            text = {
                Text(
                    "Remove ${target?.label ?: deleteId}? Its rootfs and everything installed inside it " +
                        "(SDKs, language servers, packages) will be deleted. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(deleteId)
                    pendingDelete = null
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EnvironmentRow(
    env: EnvironmentInfo,
    emulated: Boolean,
    enabled: Boolean,
    canDelete: Boolean,
    onSwitch: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (env.isActive) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            Color.Transparent
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = enabled && !env.isActive, onClick = onSwitch)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RadioButton(
                selected = env.isActive,
                onClick = onSwitch,
                enabled = enabled && !env.isActive,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    env.label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(if (emulated) "Emulated (QEMU)" else "Native")
                        if (env.isActive) append(" · active")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (env.isActive) {
                        JCodeTheme.semanticColors.success
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            TextButton(onClick = onDelete, enabled = canDelete) {
                Text("Remove", color = MaterialTheme.colorScheme.error)
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
                text = "Pick a Linux distro and J Code configures the rest automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun hasStorageAccess(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

/** Runtime storage grant so projects can live in the shared /storage/emulated/0/JCode folder. */
@Composable
private fun StorageAccessCard(
    number: Int,
    enabled: Boolean,
    onGranted: () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasStorageAccess(context)) }
    var deniedOnce by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val now = hasStorageAccess(context)
        if (now && !granted) onGranted()
        if (!now) deniedOnce = true
        granted = now
    }
    StepCard(
        number = number,
        title = "Allow storage access",
        active = !granted,
    ) {
        Text(
            text = "Projects are stored in the shared /JCode folder (Internal storage), so they " +
                "survive reinstalling the app and are visible to other apps.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (granted) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("✓", color = JCodeTheme.semanticColors.success, fontWeight = FontWeight.Bold)
                Text("Storage access granted", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            FilledTonalButton(
                onClick = {
                    launcher.launch(
                        arrayOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        ),
                    )
                },
                enabled = enabled,
            ) {
                Text("Grant storage access")
            }
            if (deniedOnce) {
                Text(
                    text = "Without it, projects fall back to app-private storage and are removed " +
                        "when the app is uninstalled. If no dialog appears, enable Storage for " +
                        "J Code in Android Settings → Apps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StepCard(
    number: Int,
    title: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    fillHeight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
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
                .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier)
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
                modifier = Modifier
                    .weight(1f)
                    .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier),
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
    modifier: Modifier = Modifier,
    fixedLogHeight: Boolean = true,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (!fixedLogHeight && expanded) Modifier.fillMaxHeight() else Modifier),
        ) {
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
                        .then(if (fixedLogHeight) Modifier.height(320.dp) else Modifier.weight(1f))
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
            text.contains("done", ignoreCase = true) || text.contains("ok", ignoreCase = true) || text.contains("success", ignoreCase = true) -> JCodeTheme.semanticColors.success
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.88f)
        },
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
    )
}
