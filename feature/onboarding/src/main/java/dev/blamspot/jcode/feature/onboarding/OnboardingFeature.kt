package dev.blamspot.jcode.feature.onboarding

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import dev.blamspot.jcode.design.AlertDialog
import dev.blamspot.jcode.design.CompactDestructiveButton
import dev.blamspot.jcode.design.CompactFilledButton
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
import androidx.compose.runtime.DisposableEffect
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
import dev.blamspot.jcode.design.JCodeTheme
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.blamspot.jcode.core.distro.Arch
import dev.blamspot.jcode.core.distro.DistroEnvironmentState
import dev.blamspot.jcode.core.distro.DistroProfile
import dev.blamspot.jcode.core.distro.DistroWizardProgress
import dev.blamspot.jcode.core.distro.EnvironmentInfo
import dev.blamspot.jcode.core.distro.WizardStepId
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.Space

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
        onRestoreEnvironment: (() -> Unit)? = null,
        onImportMigration: (() -> Unit)? = null,
        migrationSummary: String? = null,
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
                    shape = RoundedCornerShape(Radius.none),
                    showStorageStep = true,
                    onStorageAccessGranted = onStorageAccessGranted,
                    onRestoreEnvironment = onRestoreEnvironment,
                    onImportMigration = onImportMigration,
                    migrationSummary = migrationSummary,
                )
            }
        }
    }

    /**
     * Held over the workbench until the environment is usable.
     *
     * Editors, terminals and language servers are all backed by the distro, so letting them render
     * first means work can start against a rootfs nobody has resolved yet — that is how a terminal
     * opened during startup ended up bound to the catalog default instead of the active environment.
     * Blocking is the point: there is nothing useful to do here until the runtime answers.
     */
    @Composable
    fun StartupSplash(
        distroLabel: String,
        progress: DistroWizardProgress,
        modifier: Modifier = Modifier,
    ) {
        val running = progress as? DistroWizardProgress.Running
        Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(Space.xxxl),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "JCode",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    // A running setup step is the more specific thing to say; otherwise the wait is
                    // the environment being probed and started.
                    text = running?.label ?: "Starting $distroLabel…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                val percent = running?.progressPercent
                if (percent != null) {
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.fillMaxWidth(0.7f),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.7f))
                }
                running?.progressDetail?.let { detail ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
            shape = RoundedCornerShape(Radius.none),
            // Surface the storage step here too so existing installs (which never see the
            // first-run screen again) still get a path to grant shared-storage access.
            showStorageStep = remember { !hasStorageAccess() },
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
    onRestoreEnvironment: (() -> Unit)? = null,
    onImportMigration: (() -> Unit)? = null,
    migrationSummary: String? = null,
    installedEnvironments: List<EnvironmentInfo> = emptyList(),
    onSwitchEnvironment: (String) -> Unit = {},
    onDeleteEnvironment: (String) -> Unit = {},
) {
    val running = autoSetupProgress is DistroWizardProgress.Running
    val completed = autoSetupProgress is DistroWizardProgress.AllDone
    var logsExpanded by rememberSaveable { mutableStateOf(true) }

    // [autoSetupProgress] tracks a setup run in THIS session, not whether an environment exists — on
    // an ordinary launch it is Idle. Keying the wizard off it left the first-run steps on screen
    // forever, so a configured device showed "Choose the Linux distro JCode should prepare" and
    // "Waiting for you to choose a distro" directly under a card reporting one already active: two
    // pickers for one setting, disagreeing. Ask the environment itself instead, and once it is ready
    // leave [InstalledEnvironmentsCard] as the only control — the wizard returns on request, for
    // installing a distro that is not here yet.
    val configured = environmentState.smokeTestPassed == true && installedEnvironments.isNotEmpty()
    var addingEnvironment by rememberSaveable { mutableStateOf(false) }
    val showWizard = !configured || addingEnvironment || running || completed

    LaunchedEffect(running) {
        if (running) logsExpanded = true
    }

    val context = LocalContext.current
    // Storage grant gates distro selection: nothing can install into /JCode until it's granted.
    // When the storage step isn't shown (existing installs), there is nothing to gate on.
    var storageGranted by remember { mutableStateOf(hasStorageAccess()) }
    // The grant can also happen OUTSIDE the in-app dialog — the user flips File access in Android
    // Settings (before or during onboarding) and comes back. That path never fires the permission
    // launcher's callback, so re-check on every resume; otherwise Step 1 stays unchecked forever.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !storageGranted && hasStorageAccess()) {
                storageGranted = true
                onStorageAccessGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val distroStepEnabled = !showStorageStep || storageGranted

    val distroStepNumber = if (showStorageStep) 2 else 1
    // A bundle left by a previous install answers the same question this step asks — which
    // environment to set up — with the user's own, so it takes the step's place rather than adding
    // a fourth button to it. Choosing a fresh distro is still one tap away.
    var setUpFresh by remember { mutableStateOf(false) }
    val importing = onImportMigration != null && migrationSummary != null && !setUpFresh
    val idleLabel = if (importing) {
        "Waiting for you to start the import."
    } else {
        "Waiting for you to choose a distro."
    }
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
                    granted = storageGranted,
                    onGranted = {
                        storageGranted = true
                        onStorageAccessGranted()
                    },
                )
            }
        }
        if (showWizard) {
            item {
                if (importing) {
                    MigrationImportCard(
                        number = distroStepNumber,
                        summary = migrationSummary.orEmpty(),
                        running = running,
                        completed = completed,
                        enabled = distroStepEnabled,
                        onImport = { onImportMigration?.invoke() },
                        onSetUpFresh = { setUpFresh = true },
                    )
                } else {
                    DistroSelectionCard(
                        number = distroStepNumber,
                        environmentState = environmentState,
                        running = running,
                        completed = completed,
                        enabled = distroStepEnabled,
                        onSelectDistro = onSelectDistro,
                        onAutoSetup = onAutoSetup,
                        onRefresh = onRefresh,
                        onRestoreEnvironment = onRestoreEnvironment,
                    )
                }
            }
        } else {
            item {
                AddEnvironmentCard(onAdd = { addingEnvironment = true })
            }
        }
        item {
            WebEngineHintCard()
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
                // The right pane exists to keep the setup log visible; with no wizard there is no log,
                // and a half-empty split just strands the switcher in a narrow column.
                val twoPane = maxWidth > maxHeight && maxWidth >= 600.dp && showWizard
                if (twoPane) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentPadding = PaddingValues(Space.lg),
                            verticalArrangement = Arrangement.spacedBy(Space.lg),
                            content = selectionSteps,
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(top = Space.lg, end = Space.lg, bottom = Space.lg),
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
                                idleLabel = idleLabel,
                                modifier = Modifier.weight(1f),
                                fillLog = true,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(Space.lg),
                        verticalArrangement = Arrangement.spacedBy(Space.lg),
                    ) {
                        selectionSteps()
                        if (showWizard) {
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
                                    idleLabel = idleLabel,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Stands in for the distro step when the previous install left a bundle in shared storage: it
 *  already holds an environment, so downloading a fresh one would throw the user's away. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MigrationImportCard(
    number: Int,
    summary: String,
    running: Boolean,
    completed: Boolean,
    enabled: Boolean,
    onImport: () -> Unit,
    onSetUpFresh: () -> Unit,
) {
    val interactive = enabled && !running && !completed
    StepCard(
        number = number,
        active = interactive,
    ) {
        Text(
            text = when {
                completed -> "Your previous environment is set up."
                enabled -> "Your previous install left its Linux environment, projects, extensions " +
                    "and settings behind. Importing them puts JCode back where it was."
                else -> "Allow storage access above to continue."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            FilledTonalButton(onClick = onImport, enabled = interactive) {
                Text("Import")
            }
            OutlinedButton(onClick = onSetUpFresh, enabled = interactive) {
                Text("Set up fresh instead")
            }
        }
        if (interactive) {
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DistroSelectionCard(
    number: Int,
    environmentState: DistroEnvironmentState,
    running: Boolean,
    completed: Boolean,
    enabled: Boolean,
    onSelectDistro: (DistroProfile) -> Unit,
    onAutoSetup: () -> Unit,
    onRefresh: () -> Unit,
    onRestoreEnvironment: (() -> Unit)? = null,
) {
    // Interactive until the previous step (storage) is done; the whole card also locks while a setup
    // runs AND stays locked once it has succeeded (Step 3 done) — re-selecting/re-running from here
    // then makes no sense. A failed run leaves it interactive so the user can retry.
    val interactive = enabled && !running && !completed
    StepCard(
        number = number,
        active = interactive,
    ) {
        Text(
            text = when {
                completed -> "Environment ready — ${environmentState.runtime.selectedDistro.label} is set up."
                enabled -> "Choose the Linux distro JCode should prepare for your embedded environment."
                else -> "Allow storage access above to continue."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val availableDistros = environmentState.availableDistros.ifEmpty { DistroProfile.defaults() }
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            availableDistros.forEach { profile ->
                val selected = environmentState.runtime.selectedDistro == profile
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.xl))
                        .clickable(enabled = interactive) { onSelectDistro(profile) }
                        .padding(vertical = Space.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = { onSelectDistro(profile) },
                        enabled = interactive,
                    )
                    Text(profile.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        // FlowRow so the "Use <distro>" + Refresh buttons wrap instead of squishing on narrow portrait.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            FilledTonalButton(onClick = onAutoSetup, enabled = interactive) {
                Text("Use ${environmentState.runtime.selectedDistro.label}")
            }
            if (onRestoreEnvironment != null) {
                OutlinedButton(onClick = onRestoreEnvironment, enabled = interactive) {
                    Text("Restore from backup…")
                }
            }
            OutlinedButton(onClick = onRefresh, enabled = interactive) {
                Text("Refresh")
            }
        }
        if (onRestoreEnvironment != null && interactive) {
            Text(
                text = "Or restore a .tar.gz backup into the selected distro — brings back its toolchains, VMs and files instead of a fresh download. Pick the distro that matches your backup first.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    idleLabel: String,
    modifier: Modifier = Modifier,
    fillLog: Boolean = false,
) {
    StepCard(
        number = number,
        active = running || completed,
        modifier = modifier,
        fillHeight = fillLog,
    ) {
        val progressText = when (autoSetupProgress) {
            is DistroWizardProgress.Running -> autoSetupProgress.label
            is DistroWizardProgress.Completed -> autoSetupProgress.detail
            is DistroWizardProgress.Failed -> autoSetupProgress.error
            is DistroWizardProgress.AllDone -> autoSetupProgress.summary
            DistroWizardProgress.Idle -> idleLabel
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.ms),
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
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
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

/**
 * A hint, not a step: shown only when the device's WebView — the Chromium engine behind JCode's
 * browser and web previews — is old enough to break modern sites. Nothing here blocks setup, and
 * JCode works either way by falling back to the ROM's engine; the card exists because onboarding
 * is the one moment the user is already granting things, and a provider switch made now saves a
 * blank-page mystery later. Threshold and actions mirror the Settings → Web engine card.
 */
@Composable
private fun WebEngineHintCard() {
    val ctx = LocalContext.current
    val engineVersion = remember {
        runCatching { android.webkit.WebView.getCurrentWebViewPackage()?.versionName }.getOrNull()
    }
    val major = engineVersion?.substringBefore('.')?.toIntOrNull() ?: 0
    // Chromium 108 shipped dvh; below ~110 whole sites render blank rather than merely dated.
    if (major !in 1 until 110) return
    Surface(
        shape = RoundedCornerShape(Radius.sheet),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.ms),
        ) {
            Text("Browser engine is outdated", fontWeight = FontWeight.SemiBold)
            Text(
                text = "This device's WebView is Chromium $engineVersion, which modern sites can " +
                    "render blank or broken. JCode's built-in browser and web previews use it. " +
                    "Recommended: install the latest Android System WebView, then pick it under " +
                    "Developer options → WebView implementation. If your device doesn't allow the " +
                    "switch, JCode keeps working on the ROM's engine.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(
                onClick = {
                    val id = "com.google.android.webview"
                    val play = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id"))
                    runCatching { ctx.startActivity(play) }.onFailure {
                        runCatching {
                            ctx.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://play.google.com/store/apps/details?id=$id"),
                                ),
                            )
                        }
                    }
                },
            ) {
                Text("Get latest WebView")
            }
        }
    }
}

/** Reveals the setup wizard on a device that is already configured, for adding a second distro. */
@Composable
private fun AddEnvironmentCard(onAdd: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(Radius.sheet),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.ms),
        ) {
            Text("Add an environment", fontWeight = FontWeight.SemiBold)
            Text(
                text = "Install another Linux distro alongside the ones above. Each keeps its own SDKs " +
                    "and language servers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onAdd) { Text("Install another distro") }
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
        shape = RoundedCornerShape(Radius.sheet),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.ms),
        ) {
            Text("Installed environments", fontWeight = FontWeight.SemiBold)
            Text(
                text = "Switch which environment terminals and builds target. SDKs and language servers stay " +
                    "installed per environment. Open terminals keep their original environment.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
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
            // Destructive "Remove" sits away from the rightmost (reflexive-tap) slot, which Material
            // gives to the dismiss button.
            confirmButton = {
                CompactDestructiveButton(text = "Remove", onClick = {
                    onDelete(deleteId)
                    pendingDelete = null
                })
            },
            dismissButton = {
                CompactFilledButton(text = "Cancel", onClick = { pendingDelete = null })
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
        shape = RoundedCornerShape(Radius.xxl),
        color = if (env.isActive) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            Color.Transparent
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.xxl))
                .clickable(enabled = enabled && !env.isActive, onClick = onSwitch)
                .padding(horizontal = Space.sm, vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            RadioButton(
                selected = env.isActive,
                onClick = onSwitch,
                enabled = enabled && !env.isActive,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Space.xxs),
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
            .padding(horizontal = Space.xl, vertical = Space.xl),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Text("Environment setup", fontWeight = FontWeight.SemiBold)
            Text(
                text = "Pick a Linux distro and JCode configures the rest automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// /JCode lives on shared storage; at targetSdk 33 the only grant that gives raw file-path access
// to it is "All files access" (MANAGE_EXTERNAL_STORAGE) — a system-settings toggle, not a runtime
// permission dialog.
private fun hasStorageAccess(): Boolean = android.os.Environment.isExternalStorageManager()

/** Runtime storage grant used to read older shared-storage projects (one-time migration). */
@Composable
private fun StorageAccessCard(
    number: Int,
    enabled: Boolean,
    granted: Boolean,
    onGranted: () -> Unit,
) {
    val context = LocalContext.current
    var deniedOnce by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val now = hasStorageAccess()
        if (now && !granted) onGranted()
        if (!now) deniedOnce = true
    }
    StepCard(
        number = number,
        active = !granted,
    ) {
        Text(
            text = "Lets JCode migrate projects created by older versions from the shared /JCode " +
                "folder. Projects now live in app storage — browse them via the \"JCode Projects\" " +
                "entry in your Files app, or export them from the workspace menu.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (granted) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.ms),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("✓", color = JCodeTheme.semanticColors.success, fontWeight = FontWeight.Bold)
                Text("Storage access granted", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            FilledTonalButton(
                onClick = {
                    val packageUri = Uri.fromParts("package", context.packageName, null)
                    try {
                        launcher.launch(
                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri),
                        )
                    } catch (_: ActivityNotFoundException) {
                        launcher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                },
                enabled = enabled,
            ) {
                Text("Allow \"All files access\"")
            }
            if (deniedOnce) {
                Text(
                    text = "Without it, projects fall back to app-private storage and are removed " +
                        "when the app is uninstalled. In the settings screen that opens, turn on " +
                        "\"Allow access to manage all files\" for JCode, then return here.",
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
    active: Boolean,
    modifier: Modifier = Modifier,
    fillHeight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Radius.sheet),
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
                .padding(Space.lg),
            horizontalArrangement = Arrangement.spacedBy(Space.lg),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                shape = RoundedCornerShape(Radius.pill),
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
                verticalArrangement = Arrangement.spacedBy(Space.ms),
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
        shape = RoundedCornerShape(Radius.xxxl),
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
                    .padding(horizontal = Space.lg, vertical = Space.ms),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
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
                        .padding(horizontal = Space.lg, vertical = Space.ms),
                    verticalArrangement = Arrangement.spacedBy(Space.xs),
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
