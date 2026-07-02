package dev.jcode.core.distro

import java.io.File

/**
 * State of the embedded Linux environment.
 * Replaces the old Termux-dependent DistroEnvironmentState.
 */
data class DistroEnvironmentState(
    val prootInstalled: Boolean = false,
    val availableDistros: List<DistroProfile> = DistroProfile.defaults(),
    val runtime: DistroRuntimeConfig = DistroRuntimeConfig(),
    val firstRunSetupDeferred: Boolean = false,
    val distroInstalled: Boolean? = null,
    val workspaceReady: Boolean = false,
    val toolchainReady: Boolean? = null,
    val jcodeUserReady: Boolean? = null,
    val smokeTestPassed: Boolean? = null,
    val completedSteps: Set<WizardStepId> = emptySet(),
    val runningStep: WizardStepId? = null,
    val activityLog: List<String> = emptyList(),
    val errorMessage: String? = null,
)

data class DistroRuntimeConfig(
    val selectedDistro: DistroProfile = DistroProfile.defaultProfile(),
    val user: String = DEFAULT_DISTRO_USER,
    val binds: List<DistroBind> = listOf(DistroBind(DEFAULT_PROJECTS_HOST_PATH, DEFAULT_DISTRO_WORKDIR)),
    val workdir: String = DEFAULT_DISTRO_WORKDIR,
)

data class DistroBind(
    val host: String,
    val target: String,
)

/**
 * Result of executing a command inside the distro or on the host.
 * Replaces the old TermuxRunResult.
 */
data class ExecResult(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
    val truncated: Boolean = false,
    val internalError: String? = null,
) {
    val succeeded: Boolean
        get() = internalError == null && exitCode == 0
}

/** Backward-compatible alias for code that still references TermuxRunResult. */
typealias TermuxRunResult = ExecResult

data class DistroEvent(
    val stage: String,
    val message: String,
    val result: ExecResult? = null,
)

/** Progress emitted by the auto-setup orchestrator. */
sealed interface DistroWizardProgress {
    data object Idle : DistroWizardProgress
    data class Running(
        val step: WizardStepId,
        val label: String,
        /** 0-100 while the step reports determinate progress (e.g. rootfs download), else null. */
        val progressPercent: Int? = null,
        /** Human progress detail, e.g. "12.4 / 41.0 MB". */
        val progressDetail: String? = null,
    ) : DistroWizardProgress
    data class Completed(val step: WizardStepId, val detail: String) : DistroWizardProgress
    data class Failed(val step: WizardStepId, val error: String) : DistroWizardProgress
    data class AllDone(
        val totalSteps: Int,
        val completedSteps: Int,
        val summary: String,
    ) : DistroWizardProgress
}

/**
 * Wizard steps for the embedded environment setup.
 * Simplified from the Termux-dependent version.
 */
enum class WizardStepId(val key: String) {
    /** Check available storage space (need ~2GB free). */
    CheckStorage("check-storage"),
    /** proot binary extracted from assets. */
    ProotReady("proot-ready"),
    /** User picked a distro (Ubuntu or Debian). */
    DistroSelected("distro-selected"),
    /** Rootfs downloaded and extracted. */
    DistroInstalled("distro-installed"),
    /** Workspace host directory created. */
    WorkspaceReady("workspace-ready"),
    /** Base toolchain packages installed via apt. */
    ToolchainBootstrapped("toolchain-bootstrapped"),
    /** Non-root jcode user created inside the distro. */
    JcodeUserCreated("jcode-user-created"),
    /** Final smoke test passed. */
    SmokeTest("smoke-test");

    companion object {
        fun fromKey(key: String): WizardStepId? = entries.firstOrNull { it.key == key }
    }
}

data class DistroProfile(
    val id: String,
    val label: String,
    val installRecipe: String,
    val approxFootprint: String,
    val arch: Arch = Arch.ARM64,
) {
    companion object {
        private val defaultProfiles = listOf(
            DistroProfile(
                id = "ubuntu-24.04",
                label = "Ubuntu 24.04 LTS (ARM64)",
                installRecipe = "ubuntu:24.04",
                approxFootprint = "~2.5 GB",
                arch = Arch.ARM64,
            ),
            DistroProfile(
                id = "ubuntu-26.04",
                label = "Ubuntu 26.04 LTS (ARM64)",
                installRecipe = "ubuntu:26.04",
                approxFootprint = "~2.5 GB",
                arch = Arch.ARM64,
            ),
        )

        fun defaults(): List<DistroProfile> = defaultProfiles

        fun defaultProfile(): DistroProfile = defaultProfiles.first()

        fun fromId(
            raw: String?,
            available: List<DistroProfile> = defaults(),
        ): DistroProfile = available.firstOrNull { profile ->
            raw != null && (
                profile.id == raw ||
                    profile.installRecipe == raw ||
                    legacyMatches(profile, raw)
                )
        } ?: available.firstOrNull() ?: defaultProfile()

        private fun legacyMatches(profile: DistroProfile, raw: String): Boolean {
            return when (raw) {
                "ubuntu" -> profile.installRecipe == "ubuntu:24.04"
                else -> false
            }
        }
    }
}

internal const val DEFAULT_PROJECTS_HOST_PATH: String = "/storage/emulated/0/JCode/projects"
internal const val DEFAULT_DISTRO_WORKDIR: String = "/workspace"
internal const val DEFAULT_DISTRO_USER: String = "jcode"

internal val DEFAULT_BOOTSTRAP_PACKAGES: List<String> = listOf(
    "build-essential",
    "clang",
    "clangd",
    "lldb",
    "gdb",
    "cmake",
    "ninja-build",
    "git",
    "python3",
    "python3-pip",
    "nodejs",
    "npm",
    "openjdk-21-jdk-headless",
    "sudo",
)
