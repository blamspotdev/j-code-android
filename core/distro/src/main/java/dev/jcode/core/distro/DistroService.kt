package dev.jcode.core.distro

import android.content.Context
import android.content.pm.PackageManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.jcode.core.config.BindMount
import dev.jcode.core.config.EffectiveDistroConfig
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DistroService(
    private val context: Context,
) {
    private val appContext = context.applicationContext
    private val commandRunner = TermuxOneShot(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val dataStore = PreferenceDataStoreFactory.create {
        appContext.preferencesDataStoreFile("distro-environment.preferences_pb")
    }

    private val _environmentState = MutableStateFlow(DistroEnvironmentState())
    val environmentState: StateFlow<DistroEnvironmentState> = _environmentState.asStateFlow()

    init {
        scope.launch {
            refreshEnvironment()
        }
    }

    fun updateRuntimeConfig(
        distroConfig: EffectiveDistroConfig,
        projectHostPath: String?,
        projectTargetPath: String?,
    ) {
        val runtime = DistroRuntimeConfig(
            selectedDistro = DistroProfile.fromId(distroConfig.id),
            user = distroConfig.user.ifBlank { DEFAULT_DISTRO_USER },
            binds = resolveBinds(
                distroBinds = distroConfig.bind,
                projectHostPath = projectHostPath,
                projectTargetPath = projectTargetPath,
            ),
        )
        _environmentState.value = _environmentState.value.copy(runtime = runtime)
    }

    fun setSelectedDistro(profile: DistroProfile) {
        _environmentState.value = _environmentState.value.copy(
            runtime = _environmentState.value.runtime.copy(selectedDistro = profile),
            completedSteps = _environmentState.value.completedSteps + WizardStepId.DistroSelected,
            errorMessage = null,
        )
        scope.launch {
            persistSelectedDistro(profile)
            persistCompletedSteps(_environmentState.value.completedSteps)
        }
    }

    suspend fun refreshEnvironment() {
        lock.withLock {
            val persistedSelection = readSelectedDistro()
            if (persistedSelection != null) {
                _environmentState.value = _environmentState.value.copy(
                    runtime = _environmentState.value.runtime.copy(selectedDistro = persistedSelection),
                )
            }

            val termuxStatus = inspectTermux()
            var completedSteps = readCompletedSteps().toMutableSet()
            var prootInstalled: Boolean? = null
            var distroInstalled: Boolean? = null
            var toolchainReady: Boolean? = null
            var jcodeUserReady: Boolean? = null
            var smokeTestPassed: Boolean? = null

            if (termuxStatus.meetsMinimumVersion) completedSteps += WizardStepId.TermuxInstalled
            if (termuxStatus.apiInstalled) completedSteps += WizardStepId.TermuxApiInstalled
            if (termuxStatus.runCommandGranted) completedSteps += WizardStepId.RunCommandPermission
            completedSteps += WizardStepId.DistroSelected

            if (termuxStatus.allowExternalAppsEnabled == true) {
                completedSteps += WizardStepId.AllowExternalApps
                prootInstalled = checkHostCommand("command -v proot-distro >/dev/null 2>&1 && echo ready")
                if (prootInstalled == true) {
                    completedSteps += WizardStepId.ProotDistroInstalled
                    distroInstalled = checkDistroInstalled()
                    if (distroInstalled == true) {
                        completedSteps += WizardStepId.DistroInstalled
                        toolchainReady = checkToolchainReady()
                        if (toolchainReady == true) {
                            completedSteps += WizardStepId.ToolchainBootstrapped
                        }
                        jcodeUserReady = checkDistroUser()
                        if (jcodeUserReady == true) {
                            completedSteps += WizardStepId.JcodeUserCreated
                        }
                        if (toolchainReady == true && jcodeUserReady == true && primaryBind().hostFile.exists()) {
                            smokeTestPassed = checkSmokeTest()
                            if (smokeTestPassed == true) {
                                completedSteps += WizardStepId.SmokeTest
                            }
                        }
                    }
                }
            }

            val workspaceReady = primaryBind().hostFile.exists()
            if (workspaceReady) {
                completedSteps += WizardStepId.WorkspaceReady
            }

            val finalSteps = completedSteps.toSet()
            persistCompletedSteps(finalSteps)
            _environmentState.value = _environmentState.value.copy(
                termux = termuxStatus,
                prootDistroInstalled = prootInstalled,
                distroInstalled = distroInstalled,
                workspaceReady = workspaceReady,
                toolchainReady = toolchainReady,
                jcodeUserReady = jcodeUserReady,
                smokeTestPassed = smokeTestPassed,
                completedSteps = finalSteps,
                errorMessage = null,
            )
        }
    }

    suspend fun runWizardStep(stepId: WizardStepId) {
        lock.withLock {
            updateRunningStep(stepId, "Running ${stepId.key}.")
            val result = when (stepId) {
                WizardStepId.AllowExternalApps -> enableAllowExternalApps()
                WizardStepId.ProotDistroInstalled -> installProotDistro()
                WizardStepId.DistroInstalled -> installSelectedDistro()
                WizardStepId.WorkspaceReady -> ensureWorkspaceDirectory()
                WizardStepId.ToolchainBootstrapped -> bootstrapToolchain()
                WizardStepId.JcodeUserCreated -> createDistroUser()
                WizardStepId.SmokeTest -> smokeTest()
                WizardStepId.TermuxInstalled,
                WizardStepId.TermuxApiInstalled,
                WizardStepId.RunCommandPermission,
                WizardStepId.DistroSelected,
                -> TermuxRunResult(internalError = "This step requires user action outside J Code.")
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

            val nextLog = (_environmentState.value.activityLog + logLine).takeLast(12)
            _environmentState.value = _environmentState.value.copy(
                runningStep = null,
                activityLog = nextLog,
                errorMessage = result.internalError,
            )
        }
        refreshEnvironment()
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
    ): TermuxRunResult {
        return runInDistro(command = command, workdir = workdir, env = env)
    }

    suspend fun backup(outPath: String): TermuxRunResult {
        val profile = _environmentState.value.runtime.selectedDistro
        return runHostCommand(
            script = "proot-distro backup ${profile.id} > '${escapeSingleQuoted(outPath)}'",
            label = "Backup ${profile.label}",
        )
    }

    suspend fun restore(inPath: String): TermuxRunResult {
        val profile = _environmentState.value.runtime.selectedDistro
        return runHostCommand(
            script = "proot-distro restore '${escapeSingleQuoted(inPath)}'",
            label = "Restore ${profile.label}",
        )
    }

    fun canonicalLoginArguments(
        command: String,
        workdir: String = _environmentState.value.runtime.workdir,
        noSeccomp: Boolean = false,
        env: Map<String, String> = emptyMap(),
    ): List<String> {
        val runtime = _environmentState.value.runtime
        val arguments = mutableListOf(
            "login",
            runtime.selectedDistro.id,
            "--user",
            runtime.user,
            "-w",
            workdir,
        )
        runtime.binds.forEach { bind ->
            arguments += listOf("-b", "${bind.host}:${bind.target}")
        }
        arguments += listOf("-e", "JCODE=1")
        if (noSeccomp) {
            arguments += listOf("-e", "PROOT_NO_SECCOMP=1")
        }
        env.entries.sortedBy { it.key }.forEach { (key, value) ->
            arguments += listOf("-e", "$key=$value")
        }
        arguments += listOf("--", "bash", "-lc", command)
        return arguments
    }

    private suspend fun installSelectedDistro(profile: DistroProfile = _environmentState.value.runtime.selectedDistro): TermuxRunResult {
        return runHostCommand(
            script = "proot-distro install ${profile.installRecipe}",
            label = "Install ${profile.label}",
            timeoutMs = 1_800_000L,
        )
    }

    private suspend fun installProotDistro(): TermuxRunResult {
        return runHostCommand(
            script = "pkg install -y proot-distro",
            label = "Install proot-distro",
            timeoutMs = 900_000L,
        )
    }

    private suspend fun enableAllowExternalApps(): TermuxRunResult {
        val script = """
            mkdir -p ~/.termux
            touch ~/.termux/termux.properties
            if grep -q '^allow-external-apps=' ~/.termux/termux.properties; then
              sed -i 's/^allow-external-apps=.*/allow-external-apps=true/' ~/.termux/termux.properties
            else
              printf '\nallow-external-apps=true\n' >> ~/.termux/termux.properties
            fi
            termux-reload-settings >/dev/null 2>&1 || true
            echo OK
        """.trimIndent()
        return runHostCommand(
            script = script,
            label = "Enable allow-external-apps",
        )
    }

    private suspend fun ensureWorkspaceDirectory(): TermuxRunResult {
        val bind = primaryBind()
        val created = bind.hostFile.mkdirs() || bind.hostFile.exists()
        return if (created) {
            TermuxRunResult(stdout = "Workspace ready at ${bind.host}.", exitCode = 0)
        } else {
            TermuxRunResult(internalError = "Failed to create ${bind.host}.")
        }
    }

    private suspend fun bootstrapToolchain(
        packages: List<String> = DEFAULT_BOOTSTRAP_PACKAGES,
    ): TermuxRunResult {
        val packageList = packages.joinToString(" ")
        val command = "apt-get update && apt-get install -y $packageList"
        return runInDistro(command = command, timeoutMs = 1_800_000L)
    }

    private suspend fun createDistroUser(): TermuxRunResult {
        val runtime = _environmentState.value.runtime
        val command = """
            id -u ${runtime.user} >/dev/null 2>&1 || useradd -m -u 1000 -s /bin/bash ${runtime.user}
            passwd -d ${runtime.user} >/dev/null 2>&1 || true
            install -d /etc/sudoers.d
            printf '%s ALL=(ALL) NOPASSWD:ALL\n' ${runtime.user} > /etc/sudoers.d/${runtime.user}
            chmod 440 /etc/sudoers.d/${runtime.user}
            echo OK
        """.trimIndent()
        return runInDistro(command = command, timeoutMs = 300_000L)
    }

    private suspend fun smokeTest(): TermuxRunResult = runSmokeTest()

    private suspend fun runSmokeTest(): TermuxRunResult {
        return runInDistro(
            command = "clangd --version && node --version && echo ok",
            timeoutMs = 120_000L,
        )
    }

    private suspend fun checkHostCommand(checkScript: String): Boolean? {
        val result = runHostCommand(
            script = checkScript,
            label = "Check host command",
        )
        return result.succeeded
    }

    private suspend fun checkDistroInstalled(): Boolean? {
        val profile = _environmentState.value.runtime.selectedDistro
        val script = "[ -d '${TermuxRunCommandContract.PrefixPath}/var/lib/proot-distro/installed-rootfs/${profile.id}' ] && echo ready"
        return checkHostCommand(script)
    }

    private suspend fun checkToolchainReady(): Boolean? {
        val result = runInDistro("command -v clangd >/dev/null 2>&1 && command -v node >/dev/null 2>&1 && command -v javac >/dev/null 2>&1 && echo ready")
        return result.succeeded
    }

    private suspend fun checkDistroUser(): Boolean? {
        val runtime = _environmentState.value.runtime
        val result = runInDistro("id -u ${runtime.user} >/dev/null 2>&1 && echo ready")
        return result.succeeded
    }

    private suspend fun checkSmokeTest(): Boolean? {
        val result = runSmokeTest()
        return result.succeeded && result.stdout.lowercase(Locale.US).contains("ok")
    }

    private suspend fun runHostCommand(
        script: String,
        label: String,
        timeoutMs: Long = 30_000L,
    ): TermuxRunResult {
        return commandRunner.run(
            commandPath = TermuxRunCommandContract.BashPath,
            arguments = listOf("-lc", script),
            label = label,
            description = "Triggered by J Code environment setup.",
            timeoutMs = timeoutMs,
        )
    }

    private suspend fun runInDistro(
        command: String,
        workdir: String = _environmentState.value.runtime.workdir,
        env: Map<String, String> = emptyMap(),
        timeoutMs: Long = 60_000L,
    ): TermuxRunResult {
        val initial = commandRunner.run(
            commandPath = TermuxRunCommandContract.ProotDistroPath,
            arguments = canonicalLoginArguments(
                command = command,
                workdir = workdir,
                noSeccomp = false,
                env = env,
            ),
            label = "Run in distro",
            description = "Executed through proot-distro login from J Code.",
            timeoutMs = timeoutMs,
        )
        if (initial.exitCode == 1 && mentionsSeccomp(initial)) {
            appendLog("Seccomp fallback triggered for distro command.")
            return commandRunner.run(
                commandPath = TermuxRunCommandContract.ProotDistroPath,
                arguments = canonicalLoginArguments(
                    command = command,
                    workdir = workdir,
                    noSeccomp = true,
                    env = env,
                ),
                label = "Run in distro (seccomp fallback)",
                description = "Executed through proot-distro login from J Code.",
                timeoutMs = timeoutMs,
            )
        }
        return initial
    }

    private suspend fun inspectTermux(): TermuxStatus {
        val packageManager = appContext.packageManager
        val termuxPackage = packageManager.safePackageInfo(TermuxRunCommandContract.TermuxPackageName)
        val termuxApiPackage = packageManager.safePackageInfo(TermuxRunCommandContract.TermuxApiPackageName)
        val versionName = termuxPackage?.versionName
        val installed = termuxPackage != null
        val meetsMinimum = versionAtLeast(versionName, MIN_SUPPORTED_TERMUX_VERSION)
        val runCommandGranted = packageManager.checkPermission(
            TermuxRunCommandContract.RunCommandPermission,
            appContext.packageName,
        ) == PackageManager.PERMISSION_GRANTED

        val allowExternalApps = if (installed && runCommandGranted) {
            val result = runHostCommand(
                script = "grep -Eq '^allow-external-apps=true$' ~/.termux/termux.properties && echo enabled",
                label = "Check allow-external-apps",
            )
            result.succeeded
        } else {
            null
        }

        return TermuxStatus(
            installed = installed,
            versionName = versionName,
            meetsMinimumVersion = meetsMinimum,
            apiInstalled = termuxApiPackage != null,
            runCommandGranted = runCommandGranted,
            allowExternalAppsEnabled = allowExternalApps,
        )
    }

    private fun resolveBinds(
        distroBinds: List<BindMount>,
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

    private suspend fun readSelectedDistro(): DistroProfile? {
        val selected = dataStore.data.first()[PreferencesKeys.SelectedDistro]
        return selected?.let(DistroProfile::fromId)
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

    private fun updateRunningStep(
        stepId: WizardStepId,
        message: String,
    ) {
        _environmentState.value = _environmentState.value.copy(
            runningStep = stepId,
            errorMessage = null,
            activityLog = (_environmentState.value.activityLog + message).takeLast(12),
        )
    }

    private fun appendLog(message: String) {
        _environmentState.value = _environmentState.value.copy(
            activityLog = (_environmentState.value.activityLog + message).takeLast(12),
        )
    }

    private fun mentionsSeccomp(result: TermuxRunResult): Boolean {
        val combined = buildString {
            append(result.stdout)
            append('\n')
            append(result.stderr)
            append('\n')
            append(result.internalError.orEmpty())
        }
        return combined.lowercase(Locale.US).contains("seccomp")
    }

    private fun summarizeResult(result: TermuxRunResult): String {
        return result.internalError
            ?: result.stdout.lineSequence().firstOrNull()?.takeIf(String::isNotBlank)
            ?: result.stderr.lineSequence().firstOrNull()?.takeIf(String::isNotBlank)
            ?: "Command exited with ${result.exitCode ?: "unknown"}."
    }

    private fun PackageManager.safePackageInfo(packageName: String) = runCatching {
        getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    }.getOrNull()

    private fun versionAtLeast(
        current: String?,
        required: String,
    ): Boolean {
        if (current.isNullOrBlank()) return false
        val currentParts = current.split('.', '-', '_').mapNotNull(String::toIntOrNull)
        val requiredParts = required.split('.').mapNotNull(String::toIntOrNull)
        val maxSize = maxOf(currentParts.size, requiredParts.size)
        for (index in 0 until maxSize) {
            val currentValue = currentParts.getOrElse(index) { 0 }
            val requiredValue = requiredParts.getOrElse(index) { 0 }
            if (currentValue != requiredValue) {
                return currentValue > requiredValue
            }
        }
        return true
    }

    private fun escapeSingleQuoted(value: String): String = value.replace("'", "'\"'\"'")

    private data class PrimaryBind(
        val host: String,
        val hostFile: File,
        val target: String,
    )

    private object PreferencesKeys {
        val SelectedDistro = stringPreferencesKey("selected_distro")
        val CompletedSteps = stringSetPreferencesKey("completed_steps")
    }
}
