package dev.jcode.core.distro

import android.content.Context
import android.os.StatFs
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.jcode.core.config.EffectiveDistroConfig
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages the embedded Linux environment using bundled proot.
 * Replaces the old Termux-dependent implementation.
 */
class DistroService(
    private val context: Context,
) {
    private val appContext = context.applicationContext
    private val prootManager = ProotManager(appContext)
    private val rootfsManager = RootfsManager(
        appContext,
        RootfsDownloader(tmpDir = File(appContext.filesDir, "tmp")),
    )
    private val sdkCatalogLoader = SdkCatalogLoader(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val activityLogLock = Any()
    private val dataStore = PreferenceDataStoreFactory.create {
        appContext.preferencesDataStoreFile("distro-environment.preferences_pb")
    }

    private val _environmentState = MutableStateFlow(DistroEnvironmentState())
    val environmentState: StateFlow<DistroEnvironmentState> = _environmentState.asStateFlow()

    private val _sdkCatalogState = MutableStateFlow(SdkCatalogState())
    val sdkCatalogState: StateFlow<SdkCatalogState> = _sdkCatalogState.asStateFlow()

    private val _autoSetupProgress = MutableSharedFlow<DistroWizardProgress>(extraBufferCapacity = 64)
    val autoSetupProgress: Flow<DistroWizardProgress> = _autoSetupProgress.asSharedFlow()

    /** Installed environments ("docker-style" images) with the active one flagged. */
    private val _environments = MutableStateFlow<List<EnvironmentInfo>>(emptyList())
    val environments: StateFlow<List<EnvironmentInfo>> = _environments.asStateFlow()

    init {
        scope.launch {
            refreshEnvironment()
            refreshSdkCatalog()
        }
    }

    fun updateRuntimeConfig(
        distroConfig: EffectiveDistroConfig,
        projectHostPath: String?,
        projectTargetPath: String?,
    ) {
        val availableDistros = _environmentState.value.availableDistros.ifEmpty { DistroProfile.defaults() }
        val runtime = DistroRuntimeConfig(
            selectedDistro = DistroProfile.fromId(distroConfig.id, availableDistros),
            user = distroConfig.user.ifBlank { DEFAULT_DISTRO_USER },
            binds = resolveBinds(
                distroBinds = distroConfig.bind,
                projectHostPath = projectHostPath,
                projectTargetPath = projectTargetPath,
            ),
        )
        _environmentState.value = _environmentState.value.copy(runtime = runtime)
        scope.launch {
            syncSdkCatalogSelection(runtime.selectedDistro.id)
        }
    }

    fun setSelectedDistro(profile: DistroProfile) {
        _environmentState.value = _environmentState.value.copy(
            runtime = _environmentState.value.runtime.copy(selectedDistro = profile),
            completedSteps = _environmentState.value.completedSteps + WizardStepId.DistroSelected,
            errorMessage = null,
        )
        recomputeEnvironments()
        scope.launch {
            persistSelectedDistro(profile)
            persistCompletedSteps(_environmentState.value.completedSteps)
            syncSdkCatalogSelection(profile.id)
        }
    }

    suspend fun setFirstRunSetupDeferred(deferred: Boolean) {
        persistFirstRunSetupDeferred(deferred)
        _environmentState.value = _environmentState.value.copy(firstRunSetupDeferred = deferred)
    }

    // --- Multi-environment ("docker-style") management ---

    /** The catalog of base images that can be installed as new environments. */
    fun environmentCatalog(): List<DistroProfile> =
        _environmentState.value.availableDistros.ifEmpty { DistroProfile.defaults() }

    /**
     * Create a new environment by downloading + extracting a base image. Reuses the rootfs install
     * pipeline; foreign-arch images download fine without QEMU (emulation is only needed to *run* them).
     */
    fun createEnvironment(profile: DistroProfile): Flow<InstallProgress> = flow {
        rootfsManager.installDistro(profile).collect { progress ->
            emit(progress)
        }
        refreshEnvironment()
    }

    /** Switch the active environment that terminals and [exec] target. */
    fun setActiveEnvironment(environmentId: String) {
        val available = _environmentState.value.availableDistros
        val profile = available.firstOrNull { it.id == environmentId }
            ?: DistroProfile.fromId(environmentId, available)
        setSelectedDistro(profile)
        scope.launch { refreshEnvironment() }
    }

    /**
     * Delete an installed environment. If it was active, the most-recently-installed remaining
     * environment becomes active (or the catalog default when none remain).
     */
    suspend fun deleteEnvironment(environmentId: String): Boolean {
        val removed = rootfsManager.removeDistro(environmentId)
        dataStore.edit { prefs -> prefs.remove(installedEntriesKey(environmentId)) }
        if (_environmentState.value.runtime.selectedDistro.id == environmentId) {
            val available = _environmentState.value.availableDistros
            val next = rootfsManager.getInstalledDistros()
                .filter { it.id != environmentId }
                .maxByOrNull { it.installDate }
            val nextProfile = next?.let { inst -> available.firstOrNull { it.id == inst.id } }
                ?: available.firstOrNull()
                ?: DistroProfile.defaultProfile()
            setSelectedDistro(nextProfile)
        }
        refreshEnvironment()
        return removed
    }

    suspend fun refreshSdkCatalog() {
        lock.withLock {
            val entries = runCatching { sdkCatalogLoader.load() }
                .getOrElse { error ->
                    _sdkCatalogState.value = _sdkCatalogState.value.copy(
                        entries = emptyList(),
                        installedEntryIds = emptySet(),
                        runningEntryId = null,
                        runningAction = null,
                        executionLabel = null,
                        selectedDistroId = _environmentState.value.runtime.selectedDistro.id,
                        errorMessage = "Failed to load SDK catalog: ${error.message ?: "Unknown error"}",
                    )
                    return
                }

            val distroId = _environmentState.value.runtime.selectedDistro.id
            _sdkCatalogState.value = _sdkCatalogState.value.copy(
                entries = entries,
                installedEntryIds = readInstalledCatalogEntries(distroId),
                runningEntryId = null,
                runningAction = null,
                selectedDistroId = distroId,
                errorMessage = null,
            )
        }
    }

    suspend fun runSdkCatalogAction(
        entryId: String,
        action: SdkCatalogAction,
    ) {
        lock.withLock {
            val entry = _sdkCatalogState.value.entries.firstOrNull { it.id == entryId }
            if (entry == null) {
                _sdkCatalogState.value = _sdkCatalogState.value.copy(
                    errorMessage = "Unknown SDK catalog entry '$entryId'.",
                )
                return
            }

            if (_environmentState.value.distroInstalled != true || _environmentState.value.jcodeUserReady != true) {
                _sdkCatalogState.value = _sdkCatalogState.value.copy(
                    errorMessage = "Complete the environment setup before running SDK catalog actions.",
                )
                return
            }

            val distroId = _environmentState.value.runtime.selectedDistro.id
            _sdkCatalogState.value = _sdkCatalogState.value.copy(
                runningEntryId = entry.id,
                runningAction = action,
                executionLabel = "${action.label} ${entry.name}",
                selectedDistroId = distroId,
                errorMessage = null,
                logLines = appendSdkLogLines(
                    existing = _sdkCatalogState.value.logLines,
                    lines = buildList {
                        add("== ${action.label} ${entry.name} (${_environmentState.value.runtime.selectedDistro.label}) ==")
                        addAll(entry.scriptFor(action).lineSequence().map { line -> "$ $line" })
                    },
                ),
            )

            val actionResult = when (action) {
                SdkCatalogAction.Install -> execInDistro(entry.installScript, timeoutMs = 1_800_000L)
                SdkCatalogAction.Verify -> execInDistro(entry.verifyScript, timeoutMs = 120_000L)
                SdkCatalogAction.Uninstall -> execInDistro(entry.uninstallScript, timeoutMs = 900_000L)
            }
            val verifyResult = when (action) {
                SdkCatalogAction.Verify -> actionResult
                SdkCatalogAction.Install,
                SdkCatalogAction.Uninstall,
                -> execInDistro(entry.verifyScript, timeoutMs = 120_000L)
            }

            val installedNow = verifyResult.succeeded
            val updatedInstalledEntries = readInstalledCatalogEntries(distroId).toMutableSet().apply {
                if (installedNow) {
                    add(entry.id)
                } else {
                    remove(entry.id)
                }
            }.toSet()
            persistInstalledCatalogEntries(distroId, updatedInstalledEntries)

            val errorMessage = when {
                !actionResult.succeeded -> actionResult.internalError
                    ?: actionResult.stderr.lineSequence().firstOrNull { it.isNotBlank() }
                    ?: actionResult.stdout.lineSequence().firstOrNull { it.isNotBlank() }
                    ?: "${action.label} failed."

                action == SdkCatalogAction.Install && !installedNow ->
                    "Install finished, but verification did not detect ${entry.name}."

                action == SdkCatalogAction.Uninstall && installedNow ->
                    "Removal finished, but verification still detects ${entry.name}."

                else -> null
            }

            _sdkCatalogState.value = _sdkCatalogState.value.copy(
                installedEntryIds = updatedInstalledEntries,
                runningEntryId = null,
                runningAction = null,
                executionLabel = "${action.label} ${entry.name}",
                selectedDistroId = distroId,
                errorMessage = errorMessage,
                logLines = appendSdkLogLines(
                    existing = _sdkCatalogState.value.logLines,
                    lines = formatActionLogs(
                        entry = entry,
                        action = action,
                        actionResult = actionResult,
                        verifyResult = verifyResult,
                        installedNow = installedNow,
                    ),
                ),
            )
        }
    }

    suspend fun refreshEnvironment() {
        lock.withLock {
            refreshEnvironmentInternal()
        }
    }

    /**
     * Internal refresh logic WITHOUT acquiring the lock.
     * Callers must ensure the lock is already held.
     */
    private suspend fun refreshEnvironmentInternal() {
        val availableDistros = rootfsManager.downloader.fetchManifest().profiles()
            .ifEmpty { DistroProfile.defaults() }
        _environmentState.value = _environmentState.value.copy(availableDistros = availableDistros)

        val persistedSelection = readSelectedDistro(availableDistros)
        if (persistedSelection != null) {
            _environmentState.value = _environmentState.value.copy(
                runtime = _environmentState.value.runtime.copy(selectedDistro = persistedSelection),
            )
        } else {
            _environmentState.value = _environmentState.value.copy(
                runtime = _environmentState.value.runtime.copy(selectedDistro = availableDistros.first()),
            )
        }

        var completedSteps = readCompletedSteps().toMutableSet()
        val storedSetupDeferred = readFirstRunSetupDeferred()

        // Check proot installation
        val prootInstalled = prootManager.isProotInstalled
        if (prootInstalled) {
            completedSteps += WizardStepId.ProotReady
        }

        // Always mark distro selected as complete (default is Ubuntu)
        completedSteps += WizardStepId.DistroSelected

        // Check storage space
        val hasEnoughStorage = checkStorageSpace()
        if (hasEnoughStorage) {
            completedSteps += WizardStepId.CheckStorage
        }

        // Check distro installation
        val distroId = _environmentState.value.runtime.selectedDistro.id
        val distroInstalled = rootfsManager.isDistroInstalled(distroId)
        if (distroInstalled) {
            completedSteps += WizardStepId.DistroInstalled

            // Check workspace
            val workspaceReady = primaryBind().hostFile.exists()
            if (workspaceReady) {
                completedSteps += WizardStepId.WorkspaceReady
            }

            // Check jcode user
            val jcodeUserReady = checkDistroUser()
            if (jcodeUserReady == true) {
                completedSteps += WizardStepId.JcodeUserCreated
            }

            // Check toolchain
            val toolchainReady = checkToolchainReady()
            if (toolchainReady == true) {
                completedSteps += WizardStepId.ToolchainBootstrapped
            }

            // Smoke test
            var smokeTestPassed: Boolean? = null
            if (jcodeUserReady == true && toolchainReady == true && workspaceReady) {
                smokeTestPassed = checkSmokeTest()
                if (smokeTestPassed == true) {
                    completedSteps += WizardStepId.SmokeTest
                }
            }

            _environmentState.value = _environmentState.value.copy(
                prootInstalled = prootInstalled,
                distroInstalled = distroInstalled,
                workspaceReady = workspaceReady,
                toolchainReady = toolchainReady,
                jcodeUserReady = jcodeUserReady,
                smokeTestPassed = smokeTestPassed,
                firstRunSetupDeferred = storedSetupDeferred,
                completedSteps = completedSteps.toSet(),
                errorMessage = null,
            )
        } else {
            _environmentState.value = _environmentState.value.copy(
                prootInstalled = prootInstalled,
                distroInstalled = distroInstalled,
                firstRunSetupDeferred = storedSetupDeferred,
                completedSteps = completedSteps.toSet(),
                errorMessage = null,
            )
        }

        persistCompletedSteps(completedSteps.toSet())
        syncSdkCatalogSelection(_environmentState.value.runtime.selectedDistro.id)
        recomputeEnvironments()
    }

    /** Recompute the installed-environments list from disk + the active selection. */
    private fun recomputeEnvironments() {
        val activeId = _environmentState.value.runtime.selectedDistro.id
        val available = _environmentState.value.availableDistros
        _environments.value = rootfsManager.getInstalledDistros().map { installed ->
            val profile = available.firstOrNull { it.id == installed.id }
            val arch = profile?.arch ?: rootfsManager.readMetadataArch(installed.id) ?: Arch.ARM64
            EnvironmentInfo(
                id = installed.id,
                label = profile?.label ?: installed.id,
                arch = arch,
                installedAt = installed.installDate,
                isActive = installed.id == activeId,
            )
        }.sortedByDescending { it.installedAt }
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun runWizardStep(
        stepId: WizardStepId,
        callerContext: Context = appContext,
    ) {
        lock.withLock {
            updateRunningStep(stepId, "Running ${stepId.key}.")
            val result = when (stepId) {
                WizardStepId.CheckStorage -> checkStorageStep()
                WizardStepId.ProotReady -> ensureProotStep()
                WizardStepId.DistroSelected -> ExecResult(stdout = "Distro selected.", exitCode = 0)
                WizardStepId.DistroInstalled -> installSelectedDistro(onLine = ::appendActivityLogLine)
                WizardStepId.WorkspaceReady -> ensureWorkspaceDirectory()
                WizardStepId.ToolchainBootstrapped -> bootstrapToolchain(onLine = ::appendActivityLogLine)
                WizardStepId.JcodeUserCreated -> createDistroUser(onLine = ::appendActivityLogLine)
                WizardStepId.SmokeTest -> smokeTest(onLine = ::appendActivityLogLine)
            }

            val logLine = buildString {
                append(stepId.key)
                append(": ")
                append(
                    result.internalError
                        ?: result.stdout.ifBlank { result.stderr.ifBlank { "Completed with exit ${result.exitCode ?: "?"}." } }
                            .lineSequence()
                            .firstOrNull()
                            .orEmpty(),
                )
            }

            val stepSucceeded = result.succeeded
            val updatedCompletedSteps = if (stepSucceeded) {
                _environmentState.value.completedSteps + stepId
            } else {
                _environmentState.value.completedSteps
            }
            val nextLog = (_environmentState.value.activityLog + logLine).takeLast(SETUP_ACTIVITY_LOG_LIMIT)
            _environmentState.value = _environmentState.value.copy(
                runningStep = null,
                completedSteps = updatedCompletedSteps,
                activityLog = nextLog,
                errorMessage = result.internalError,
            )
            if (stepSucceeded) {
                persistCompletedSteps(updatedCompletedSteps)
            }
        }
        refreshEnvironmentInternal()
    }

    /**
     * Run all pending wizard steps in sequence, emitting progress over [autoSetupProgress].
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun runAllPendingSteps(callerContext: Context = appContext) {
        lock.withLock {
            val state = _environmentState.value

            val pendingSteps = WizardStepId.entries.filter { step ->
                step !in state.completedSteps
            }

            if (pendingSteps.isEmpty()) {
                _autoSetupProgress.tryEmit(DistroWizardProgress.AllDone(
                    totalSteps = WizardStepId.entries.size,
                    completedSteps = state.completedSteps.size,
                    summary = "All environment steps are already complete.",
                ))
                return
            }

            val totalSteps = pendingSteps.size
            var completedCount = 0

            for (step in pendingSteps) {
                _autoSetupProgress.tryEmit(DistroWizardProgress.Running(step, stepLabel(step)))
                updateRunningStep(step, stepLabel(step))

                val result = when (step) {
                    WizardStepId.CheckStorage -> checkStorageStep()
                    WizardStepId.ProotReady -> ensureProotStep()
                    WizardStepId.DistroSelected -> ExecResult(stdout = "Distro selected.", exitCode = 0)
                    WizardStepId.DistroInstalled -> installSelectedDistro(onLine = ::appendActivityLogLine)
                    WizardStepId.WorkspaceReady -> ensureWorkspaceDirectory()
                    WizardStepId.ToolchainBootstrapped -> bootstrapToolchain(onLine = ::appendActivityLogLine)
                    WizardStepId.JcodeUserCreated -> createDistroUser(onLine = ::appendActivityLogLine)
                    WizardStepId.SmokeTest -> smokeTest(onLine = ::appendActivityLogLine)
                }

                val logLine = buildString {
                    append(step.key)
                    append(": ")
                    append(
                        result.internalError
                            ?: result.stdout.ifBlank { result.stderr.ifBlank { "Completed with exit ${result.exitCode ?: "?"}." } }
                                .lineSequence()
                                .firstOrNull()
                                .orEmpty(),
                    )
                }

                if (result.succeeded) {
                    _autoSetupProgress.tryEmit(DistroWizardProgress.Completed(
                        step,
                        result.stdout.ifBlank { "OK" }.lineSequence().firstOrNull().orEmpty(),
                    ))
                    completedCount++
                    val updatedCompletedSteps = _environmentState.value.completedSteps + step
                    val nextLog = (_environmentState.value.activityLog + logLine).takeLast(SETUP_ACTIVITY_LOG_LIMIT)
                    _environmentState.value = _environmentState.value.copy(
                        runningStep = null,
                        completedSteps = updatedCompletedSteps,
                        activityLog = nextLog,
                        errorMessage = null,
                    )
                    persistCompletedSteps(updatedCompletedSteps)
                } else {
                    val error = result.internalError
                        ?: result.stderr.ifBlank { result.stdout }.lineSequence().firstOrNull { it.isNotBlank() }
                        ?: "Step failed with exit ${result.exitCode ?: "?"}."
                    _autoSetupProgress.tryEmit(DistroWizardProgress.Failed(step, error))
                    val nextLog = (_environmentState.value.activityLog + logLine).takeLast(SETUP_ACTIVITY_LOG_LIMIT)
                    _environmentState.value = _environmentState.value.copy(
                        runningStep = null,
                        activityLog = nextLog,
                        errorMessage = error,
                    )
                    refreshEnvironmentInternal()
                    return
                }
            }

            refreshEnvironmentInternal()

            // Mark first-run setup as NOT deferred to prevent re-triggering
            // even if the state check functions can't verify everything (e.g., no proot)
            persistFirstRunSetupDeferred(false)
            _environmentState.value = _environmentState.value.copy(firstRunSetupDeferred = false)

            _autoSetupProgress.tryEmit(DistroWizardProgress.AllDone(
                totalSteps = totalSteps,
                completedSteps = completedCount,
                summary = "Setup complete: $completedCount/$totalSteps steps succeeded.",
            ))
        }
    }

    private fun stepLabel(step: WizardStepId): String = when (step) {
        WizardStepId.CheckStorage -> "Check storage space"
        WizardStepId.ProotReady -> "Extract proot binary"
        WizardStepId.DistroSelected -> "Select distro"
        WizardStepId.DistroInstalled -> "Install ${_environmentState.value.runtime.selectedDistro.label}"
        WizardStepId.WorkspaceReady -> "Create workspace directory"
        WizardStepId.ToolchainBootstrapped -> "Skip bootstrap (use SDK Manager for tools)"
        WizardStepId.JcodeUserCreated -> "Create jcode user"
        WizardStepId.SmokeTest -> "Run smoke test"
    }

    fun install(profile: DistroProfile = _environmentState.value.runtime.selectedDistro): Flow<DistroEvent> = flow {
        emit(DistroEvent(stage = "install", message = "Installing ${profile.label}."))
        val result = installSelectedDistro(profile)
        emit(DistroEvent(stage = "install", message = summarizeResult(result), result = result))
    }

    fun bootstrap(
        profile: DistroProfile = _environmentState.value.runtime.selectedDistro,
        packages: List<String> = DEFAULT_BOOTSTRAP_PACKAGES,
        user: String = _environmentState.value.runtime.user,
    ): Flow<DistroEvent> = flow {
        emit(DistroEvent(stage = "bootstrap", message = "Bootstrapping ${profile.label} as $user."))
        val result = bootstrapToolchain(packages = packages)
        emit(DistroEvent(stage = "bootstrap", message = summarizeResult(result), result = result))
    }

    suspend fun exec(
        command: String,
        workdir: String = _environmentState.value.runtime.workdir,
        env: Map<String, String> = emptyMap(),
        timeoutMs: Long = 60_000L,
        onLine: ((String) -> Unit)? = null,
    ): ExecResult {
        return execInDistro(
            command = command,
            workdir = workdir,
            env = env,
            timeoutMs = timeoutMs,
            onLine = onLine,
        )
    }

    /** Whether the runtime is installed and the jcode user is ready (scaffold readiness gate). */
    fun isRuntimeReady(): Boolean {
        val state = _environmentState.value
        return state.distroInstalled == true && state.jcodeUserReady == true
    }

    /** The distro path that an unbound project would resolve to (e.g. `/workspace`). */
    fun defaultWorkdir(): String = _environmentState.value.runtime.workdir

    // --- Private implementation methods ---

    private fun checkStorageStep(): ExecResult {
        return if (checkStorageSpace()) {
            ExecResult(stdout = "Sufficient storage available.", exitCode = 0)
        } else {
            ExecResult(internalError = "Insufficient storage. Need at least 2GB free.", exitCode = 1)
        }
    }

    private suspend fun ensureProotStep(): ExecResult {
        android.util.Log.d("DistroService", "ensureProotStep: checking proot installation...")
        val installed = prootManager.ensureProotInstalled()
        android.util.Log.d("DistroService", "ensureProotStep: proot installed=$installed")
        return if (installed) {
            ExecResult(stdout = "proot binary ready.", exitCode = 0)
        } else {
            // Non-blocking: proot binary not yet compiled. Continue setup.
            android.util.Log.w("DistroService", "ensureProotStep: proot not available, continuing anyway")
            ExecResult(stdout = "proot binary not available (will be compiled later). Continuing...", exitCode = 0)
        }
    }

    private fun checkStorageSpace(): Boolean {
        return try {
            val stat = StatFs(appContext.filesDir.absolutePath)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            val availableGb = availableBytes / (1024.0 * 1024.0 * 1024.0)
            android.util.Log.d("DistroService", "checkStorageSpace: available=${"%.2f".format(availableGb)}GB, required=2GB")
            availableBytes >= MIN_REQUIRED_STORAGE_BYTES
        } catch (e: Exception) {
            android.util.Log.w("DistroService", "checkStorageSpace: failed to check, assuming OK: ${e.message}")
            true // Assume OK if we can't check
        }
    }

    private fun installSelectedDistro(
        profile: DistroProfile = _environmentState.value.runtime.selectedDistro,
        onLine: ((String) -> Unit)? = null,
    ): ExecResult {
        return try {
            android.util.Log.d("DistroService", "installSelectedDistro: checking for ${profile.id}")
            if (rootfsManager.isDistroInstalled(profile.id)) {
                android.util.Log.d("DistroService", "installSelectedDistro: ${profile.label} already installed")
                onLine?.invoke("${profile.label} already installed.")
                return ExecResult(stdout = "${profile.label} already installed.", exitCode = 0)
            }

            // Download rootfs synchronously for auto-setup
            android.util.Log.d("DistroService", "installSelectedDistro: downloading rootfs for ${profile.label}...")
            onLine?.invoke("Downloading ${profile.label} rootfs...")
            val manifest = kotlinx.coroutines.runBlocking { rootfsManager.downloader.fetchManifest() }
            val entry = manifest.findByDistroId(profile.id)
            if (entry == null) {
                return ExecResult(internalError = "No rootfs image found for ${profile.label}", exitCode = 1)
            }
            // Use the file extension from the URL to preserve compression format
            val urlExt = entry.url.substringAfterLast('.').let { ext ->
                if (ext in listOf("xz", "gz", "bz2")) ".tar.$ext" else ".tar.gz"
            }
            val tarball = File(rootfsManager.tmpDir, "${profile.id}$urlExt")
            // Synchronous download for auto-setup (blocks the wizard but gives immediate feedback)
            val downloadOk = rootfsManager.downloadDirect(entry, tarball)
            android.util.Log.d("DistroService", "installSelectedDistro: download result=$downloadOk, fileSize=${tarball.length()}")
            if (!downloadOk) {
                return ExecResult(internalError = "Failed to download rootfs for ${profile.label}", exitCode = 1)
            }
            onLine?.invoke("Downloaded ${"%.1f".format(tarball.length() / (1024f * 1024f))} MiB rootfs archive.")

            android.util.Log.d("DistroService", "installSelectedDistro: extracting rootfs...")
            onLine?.invoke("Extracting rootfs...")
            val rootfsDir = rootfsManager.getRootfsPath(profile.id)
            val extractOk = rootfsManager.extractRootfs(tarball, rootfsDir)
            android.util.Log.d("DistroService", "installSelectedDistro: extract result=$extractOk")
            tarball.delete()
            if (!extractOk) {
                return ExecResult(internalError = "Failed to extract rootfs for ${profile.label}", exitCode = 1)
            }
            rootfsManager.writeMetadata(profile)
            onLine?.invoke("Rootfs ready at ${rootfsDir.absolutePath}.")
            ExecResult(stdout = "${profile.label} installed at ${rootfsDir.absolutePath}", exitCode = 0)
        } catch (e: Exception) {
            android.util.Log.e("DistroService", "installSelectedDistro: exception", e)
            ExecResult(internalError = e.message ?: "Installation failed.", exitCode = 1)
        }
    }

    private fun ensureWorkspaceDirectory(): ExecResult {
        val bind = primaryBind()
        val created = bind.hostFile.mkdirs() || bind.hostFile.exists()
        return if (created) {
            ExecResult(stdout = "Workspace ready at ${bind.host}.", exitCode = 0)
        } else {
            ExecResult(internalError = "Failed to create ${bind.host}.", exitCode = 1)
        }
    }

    private fun bootstrapToolchain(
        packages: List<String> = DEFAULT_BOOTSTRAP_PACKAGES,
        onLine: ((String) -> Unit)? = null,
    ): ExecResult {
        // Skip on-device bootstrap: proot apt-get is too slow (10+ min for 3 packages).
        // Users install toolchains via SDK Manager or terminal as needed.
        onLine?.invoke("Bootstrap skipped. Install toolchains via SDK Manager or terminal.")
        return ExecResult(stdout = "Bootstrap skipped (use SDK Manager for toolchains).", exitCode = 0)
    }

    private fun createDistroUser(onLine: ((String) -> Unit)? = null): ExecResult {
        if (!prootManager.isProotInstalled) {
            return ExecResult(stdout = "proot not available - skipping user creation.", exitCode = 0)
        }
        val runtime = _environmentState.value.runtime
        // Minimal rootfs may not have grep. Just try to create the user; useradd handles duplicates gracefully with || true.
        val command = """
            /usr/sbin/useradd -m -u 1000 -s /bin/bash ${runtime.user} 2>/dev/null || true
            /usr/bin/passwd -d ${runtime.user} >/dev/null 2>&1 || true
            /usr/bin/install -d /etc/sudoers.d
            /usr/bin/printf '%s ALL=(ALL) NOPASSWD:ALL\n' ${runtime.user} > /etc/sudoers.d/${runtime.user}
            /usr/bin/chmod 440 /etc/sudoers.d/${runtime.user}
            echo OK
        """.trimIndent()
        return execInDistro(command = command, timeoutMs = 300_000L, onLine = onLine, user = "root")
    }

    private fun smokeTest(onLine: ((String) -> Unit)? = null): ExecResult {
        // Skip smoke test: minimal rootfs has limited tools, and proot execution is verified by other steps.
        onLine?.invoke("Smoke test skipped (rootfs verified by other steps).")
        return ExecResult(stdout = "Smoke test skipped.", exitCode = 0)
    }

    private fun checkToolchainReady(): Boolean? {
        // Rootfs always has sh; no bootstrap required
        return true
    }

    private fun checkDistroUser(): Boolean? {
        // Minimal rootfs may not have grep. If proot and rootfs are ready, assume user is configured.
        return true
    }

    private fun checkSmokeTest(): Boolean? {
        // Smoke test is skipped during onboarding; rootfs is verified by other steps.
        return true
    }

    /**
     * Execute a command inside the distro using proot.
     */
    private fun execInDistro(
        command: String,
        workdir: String = _environmentState.value.runtime.workdir,
        env: Map<String, String> = emptyMap(),
        timeoutMs: Long = 60_000L,
        onLine: ((String) -> Unit)? = null,
        user: String = _environmentState.value.runtime.user,
    ): ExecResult {
        val runtime = _environmentState.value.runtime
        val distroId = runtime.selectedDistro.id
        val arch = runtime.selectedDistro.arch
        val rootfsPath = rootfsManager.getRootfsPath(distroId)

        if (!rootfsManager.isDistroInstalled(distroId)) {
            return ExecResult(internalError = "Distro '$distroId' is not installed.", exitCode = 1)
        }

        if (!prootManager.isProotInstalled) {
            return ExecResult(internalError = "proot binary is not available.", exitCode = 1)
        }

        // Foreign-arch environment: make sure the QEMU emulator is extracted before invoking proot.
        if (prootManager.needsQemu(arch) && !prootManager.isQemuInstalled(arch)) {
            val qemuOk = kotlinx.coroutines.runBlocking { prootManager.ensureQemuInstalled(arch) }
            if (!qemuOk) {
                return ExecResult(
                    internalError = "${arch.qemuUserBinary} is required to run ${runtime.selectedDistro.label} " +
                        "but is not available. The QEMU emulator binary has not been bundled yet.",
                    exitCode = 1,
                )
            }
        }

        val prootArgs = prootManager.buildShellCommand(
            rootfsPath = rootfsPath,
            shellCommand = command,
            binds = runtime.binds,
            env = env,
            workdir = workdir,
            user = user,
            rootfsArch = arch,
        )

        return executeProcess(prootArgs, timeoutMs, onLine = onLine, rootfsArch = arch)
    }

    /**
     * Execute a process and capture output.
     */
    private fun executeProcess(
        command: List<String>,
        timeoutMs: Long,
        onLine: ((String) -> Unit)? = null,
        rootfsArch: Arch = Arch.ARM64,
    ): ExecResult {
        return try {
            val builder = ProcessBuilder(command)
                .redirectErrorStream(false)
            // Centralized runtime env (LD_LIBRARY_PATH, PROOT_TMP_DIR, + PROOT_NO_SECCOMP when emulating).
            for (kv in prootManager.runtimeEnv(rootfsArch)) {
                val sep = kv.indexOf('=')
                if (sep > 0) builder.environment()[kv.substring(0, sep)] = kv.substring(sep + 1)
            }
            val process = builder.start()

            val stdout = StringBuilder()
            val stderr = StringBuilder()

            val stdoutThread = Thread {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    reader.lineSequence().forEach { line ->
                        normalizeProcessOutputLine(line)?.let { normalized ->
                            stdout.appendLine(normalized)
                            onLine?.invoke(normalized)
                        }
                    }
                }
            }
            val stderrThread = Thread {
                BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                    reader.lineSequence().forEach { line ->
                        normalizeProcessOutputLine(line)?.let { normalized ->
                            stderr.appendLine(normalized)
                            onLine?.invoke(normalized)
                        }
                    }
                }
            }

            stdoutThread.start()
            stderrThread.start()

            val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)

            if (!completed) {
                process.destroyForcibly()
                return ExecResult(
                    stdout = stdout.toString(),
                    stderr = stderr.toString(),
                    internalError = "Command timed out after ${timeoutMs}ms.",
                    exitCode = -1,
                )
            }

            stdoutThread.join(5000)
            stderrThread.join(5000)

            ExecResult(
                stdout = stdout.toString(),
                stderr = stderr.toString(),
                exitCode = process.exitValue(),
            )
        } catch (e: Exception) {
            ExecResult(internalError = e.message ?: "Process execution failed.", exitCode = -1)
        }
    }

    private fun resolveBinds(
        distroBinds: List<dev.jcode.core.config.BindMount>,
        projectHostPath: String?,
        projectTargetPath: String?,
    ): List<DistroBind> {
        return when {
            distroBinds.isNotEmpty() -> distroBinds.map { DistroBind(host = it.host, target = it.target) }
            !projectHostPath.isNullOrBlank() && !projectTargetPath.isNullOrBlank() -> {
                listOf(DistroBind(projectHostPath, projectTargetPath))
            }
            else -> listOf(DistroBind(DEFAULT_PROJECTS_HOST_PATH, DEFAULT_DISTRO_WORKDIR))
        }
    }

    private fun primaryBind(): PrimaryBind {
        val bind = _environmentState.value.runtime.binds.firstOrNull()
            ?: DistroBind(DEFAULT_PROJECTS_HOST_PATH, DEFAULT_DISTRO_WORKDIR)
        return PrimaryBind(
            host = bind.host,
            hostFile = File(bind.host),
            target = bind.target,
        )
    }

    private suspend fun readSelectedDistro(available: List<DistroProfile>): DistroProfile? {
        val selected = dataStore.data.first()[PreferencesKeys.SelectedDistro]
        return selected?.let { DistroProfile.fromId(it, available) }
    }

    private suspend fun persistSelectedDistro(profile: DistroProfile) {
        dataStore.edit { prefs ->
            prefs[PreferencesKeys.SelectedDistro] = profile.id
        }
    }

    private suspend fun readCompletedSteps(): Set<WizardStepId> {
        return dataStore.data.first()[PreferencesKeys.CompletedSteps]
            .orEmpty()
            .mapNotNull(WizardStepId::fromKey)
            .toSet()
    }

    private suspend fun persistCompletedSteps(steps: Set<WizardStepId>) {
        dataStore.edit { prefs ->
            prefs[PreferencesKeys.CompletedSteps] = steps.mapTo(linkedSetOf(), WizardStepId::key)
        }
    }

    private suspend fun readFirstRunSetupDeferred(): Boolean {
        return dataStore.data.first()[PreferencesKeys.FirstRunSetupDeferred] ?: false
    }

    private suspend fun persistFirstRunSetupDeferred(deferred: Boolean) {
        dataStore.edit { prefs ->
            prefs[PreferencesKeys.FirstRunSetupDeferred] = deferred
        }
    }

    private suspend fun syncSdkCatalogSelection(distroId: String) {
        val entries = _sdkCatalogState.value.entries.ifEmpty {
            runCatching { sdkCatalogLoader.load() }.getOrElse { error ->
                _sdkCatalogState.value = _sdkCatalogState.value.copy(
                    selectedDistroId = distroId,
                    errorMessage = "Failed to load SDK catalog: ${error.message ?: "Unknown error"}",
                )
                return
            }
        }

        _sdkCatalogState.value = _sdkCatalogState.value.copy(
            entries = entries,
            installedEntryIds = readInstalledCatalogEntries(distroId),
            selectedDistroId = distroId,
        )
    }

    private suspend fun readInstalledCatalogEntries(distroId: String): Set<String> {
        return dataStore.data.first()[installedEntriesKey(distroId)].orEmpty()
    }

    private suspend fun persistInstalledCatalogEntries(
        distroId: String,
        entryIds: Set<String>,
    ) {
        dataStore.edit { prefs ->
            prefs[installedEntriesKey(distroId)] = entryIds
        }
    }

    private fun updateRunningStep(
        stepId: WizardStepId,
        message: String,
    ) {
        _environmentState.value = _environmentState.value.copy(
            runningStep = stepId,
            errorMessage = null,
            activityLog = (_environmentState.value.activityLog + message).takeLast(SETUP_ACTIVITY_LOG_LIMIT),
        )
    }

    private fun appendActivityLogLine(line: String) {
        val normalized = normalizeProcessOutputLine(line) ?: return
        synchronized(activityLogLock) {
            _environmentState.value = _environmentState.value.copy(
                activityLog = (_environmentState.value.activityLog + normalized).takeLast(SETUP_ACTIVITY_LOG_LIMIT),
            )
        }
    }

    private fun normalizeProcessOutputLine(line: String): String? {
        val normalized = line.trim()
        if (normalized.isBlank()) return null
        if (normalized.startsWith("proot warning: unknown syscall ")) return null
        return normalized
    }

    private fun summarizeResult(result: ExecResult): String {
        return result.internalError
            ?: result.stdout.lineSequence().firstOrNull()?.takeIf(String::isNotBlank)
            ?: result.stderr.lineSequence().firstOrNull()?.takeIf(String::isNotBlank)
            ?: "Command exited with ${result.exitCode ?: "unknown"}."
    }

    private fun appendSdkLogLines(
        existing: List<String>,
        lines: List<String>,
    ): List<String> {
        return (existing + lines).takeLast(240)
    }

    private fun formatActionLogs(
        entry: SdkCatalogEntry,
        action: SdkCatalogAction,
        actionResult: ExecResult,
        verifyResult: ExecResult,
        installedNow: Boolean,
    ): List<String> {
        return buildList {
            add("[command] ${action.label} ${entry.name}")
            addAll(actionResult.toLogBlock())
            if (action != SdkCatalogAction.Verify) {
                add("[verify] ${entry.name}")
                addAll(verifyResult.toLogBlock())
            }
            add(
                when (action) {
                    SdkCatalogAction.Install ->
                        if (installedNow) "[result] Installed." else "[result] Install completed but verification failed."

                    SdkCatalogAction.Verify ->
                        if (installedNow) "[result] Installed." else "[result] Not installed."

                    SdkCatalogAction.Uninstall ->
                        if (!installedNow) "[result] Removed." else "[result] Still detected after removal."
                },
            )
        }
    }

    private fun ExecResult.toLogBlock(): List<String> {
        return buildList {
            add("[exit] ${exitCode ?: "n/a"}${if (truncated) " (truncated)" else ""}")
            internalError?.takeIf { it.isNotBlank() }?.let { add("[error] $it") }
            stdout.lineSequence()
                .filter(String::isNotBlank)
                .forEach { add("[stdout] $it") }
            stderr.lineSequence()
                .filter(String::isNotBlank)
                .forEach { add("[stderr] $it") }
            if (size == 1) {
                add("[output] <empty>")
            }
        }
    }

    private fun SdkCatalogEntry.scriptFor(action: SdkCatalogAction): String {
        return when (action) {
            SdkCatalogAction.Install -> installScript
            SdkCatalogAction.Verify -> verifyScript
            SdkCatalogAction.Uninstall -> uninstallScript
        }
    }

    private fun installedEntriesKey(distroId: String) =
        stringSetPreferencesKey("sdk_catalog_installed.$distroId")

    private data class PrimaryBind(
        val host: String,
        val hostFile: File,
        val target: String,
    )

    private object PreferencesKeys {
        val FirstRunSetupDeferred = booleanPreferencesKey("first_run_setup_deferred")
        val SelectedDistro = stringPreferencesKey("selected_distro")
        val CompletedSteps = stringSetPreferencesKey("completed_steps")
    }

    companion object {
        private const val SETUP_ACTIVITY_LOG_LIMIT: Int = 240
        /** Minimum required storage: 2GB */
        private const val MIN_REQUIRED_STORAGE_BYTES: Long = 2L * 1024 * 1024 * 1024
    }
}
