package dev.jcode.core.distro

data class DistroEnvironmentState(
    val termux: TermuxStatus = TermuxStatus(),
    val runtime: DistroRuntimeConfig = DistroRuntimeConfig(),
    val prootDistroInstalled: Boolean? = null,
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

data class TermuxStatus(
    val installed: Boolean = false,
    val versionName: String? = null,
    val meetsMinimumVersion: Boolean = false,
    val apiInstalled: Boolean = false,
    val runCommandGranted: Boolean = false,
    val allowExternalAppsEnabled: Boolean? = null,
)

data class DistroRuntimeConfig(
    val selectedDistro: DistroProfile = DistroProfile.Ubuntu,
    val user: String = DEFAULT_DISTRO_USER,
    val binds: List<DistroBind> = listOf(DistroBind(DEFAULT_PROJECTS_HOST_PATH, DEFAULT_DISTRO_WORKDIR)),
    val workdir: String = DEFAULT_DISTRO_WORKDIR,
)

data class DistroBind(
    val host: String,
    val target: String,
)

data class TermuxRunResult(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
    val truncated: Boolean = false,
    val internalError: String? = null,
) {
    val succeeded: Boolean
        get() = internalError == null && exitCode == 0
}

data class DistroEvent(
    val stage: String,
    val message: String,
    val result: TermuxRunResult? = null,
)

enum class WizardStepId(val key: String) {
    TermuxInstalled("termux-installed"),
    TermuxApiInstalled("termux-api-installed"),
    RunCommandPermission("run-command-permission"),
    AllowExternalApps("allow-external-apps"),
    ProotDistroInstalled("proot-distro-installed"),
    DistroSelected("distro-selected"),
    DistroInstalled("distro-installed"),
    WorkspaceReady("workspace-ready"),
    ToolchainBootstrapped("toolchain-bootstrapped"),
    JcodeUserCreated("jcode-user-created"),
    SmokeTest("smoke-test");

    companion object {
        fun fromKey(key: String): WizardStepId? = entries.firstOrNull { it.key == key }
    }
}

enum class DistroProfile(
    val id: String,
    val label: String,
    val installRecipe: String,
    val approxFootprint: String,
) {
    Ubuntu(
        id = "ubuntu",
        label = "Ubuntu 24.04 LTS",
        installRecipe = "ubuntu:24.04",
        approxFootprint = "~2.5 GB",
    ),
    Debian(
        id = "debian",
        label = "Debian 12 Bookworm",
        installRecipe = "debian:bookworm",
        approxFootprint = "~2.0 GB",
    );

    companion object {
        fun fromId(raw: String?): DistroProfile = entries.firstOrNull { profile ->
            raw != null && (profile.id == raw || profile.installRecipe == raw)
        } ?: Ubuntu
    }
}

internal const val DEFAULT_PROJECTS_HOST_PATH: String = "/storage/emulated/0/JCode/projects"
internal const val DEFAULT_DISTRO_WORKDIR: String = "/workspace"
internal const val DEFAULT_DISTRO_USER: String = "jcode"
internal const val MIN_SUPPORTED_TERMUX_VERSION: String = "0.118"

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
