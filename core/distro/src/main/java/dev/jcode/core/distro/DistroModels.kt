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
    // Empty by default so the projects->/workspace bind is always derived at runtime from
    // WorkspaceHostPaths (via DistroService.resolveBinds/primaryBind), never from a compile-time
    // path that could point at the legacy shared root before the ext4 migration completes.
    val binds: List<DistroBind> = emptyList(),
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
    /** Package lists refreshed (`apt-get update`) — best-effort, never blocks setup. */
    AptUpdated("apt-updated"),
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

/**
 * The host directory bound to the guest [DEFAULT_DISTRO_WORKDIR] ("/workspace"). Projects live on
 * app-private INTERNAL storage (`context.filesDir/workspace/projects`) — an ext4 volume that supports
 * symlinks/hardlinks (npm's `node_modules/.bin`) and is exec-capable, unlike the shared `/storage`
 * FUSE mount, and needs no runtime permission. The path is runtime-resolved (filesDir varies by
 * package/user), so it can't be a compile-time const; it is set once at startup via [init] and read by
 * every host<->guest path translator so they all move together. Defaults to the legacy shared path so
 * any read before [init] still resolves to something sane.
 *
 * The "workspace/projects" segment MUST stay in sync with core:fs `StorageRoots` (which computes the
 * same path independently — the two modules don't depend on each other).
 */
object WorkspaceHostPaths {
    @Volatile
    private var filesDir: java.io.File? = null

    // Latches true once the one-time move to ext4 has completed (WorkspaceManager writes the marker).
    // Until then projectsRoot stays on the legacy shared path so the app and the DB agree on where
    // projects physically live (avoids binding /workspace to an ext4 tree the files haven't reached).
    @Volatile
    private var migrated = false

    fun init(filesDir: java.io.File) {
        this.filesDir = filesDir
    }

    /** Host directory bound to guest "/workspace": ext4 once migrated, else the legacy shared path. */
    val projectsRoot: String
        get() {
            val fd = filesDir ?: return DEFAULT_PROJECTS_HOST_PATH
            if (!migrated && java.io.File(fd, "workspace/.migrated-ext4").exists()) migrated = true
            return if (migrated) java.io.File(fd, "workspace/projects").absolutePath else DEFAULT_PROJECTS_HOST_PATH
        }

    /** Host absolute path -> guest /workspace path (returned unchanged if not under the projects root). */
    fun hostToGuest(hostPath: String): String {
        val root = projectsRoot
        return when {
            hostPath == root -> DEFAULT_DISTRO_WORKDIR
            hostPath.startsWith("$root/") -> DEFAULT_DISTRO_WORKDIR + hostPath.removePrefix(root)
            else -> hostPath
        }
    }

    /** Guest /workspace path -> host absolute path (returned unchanged if not under /workspace). */
    fun guestToHost(guestPath: String): String = when {
        guestPath == DEFAULT_DISTRO_WORKDIR -> projectsRoot
        guestPath.startsWith("$DEFAULT_DISTRO_WORKDIR/") -> projectsRoot + guestPath.removePrefix(DEFAULT_DISTRO_WORKDIR)
        else -> guestPath
    }

    /** Guest mount of the clone-staging dir ([sourcesRoot]), bound into every proot spawn. */
    const val SOURCES_GUEST = "/sources"

    /** Guest mount of the extension transfer dir ([transferRoot]) used by the file.import/export bridges. */
    const val TRANSFER_GUEST = "/jcode-transfer"

    /**
     * Host clone-staging dir bound to guest [SOURCES_GUEST]: remote-repo clones land here until the
     * user classifies them as Project or Workspace (then they move under the projects root). Always
     * app-private ext4 — independent of the /workspace migration marker, which only concerns where
     * pre-existing projects live. Resolved from an explicit filesDir (not [init]) so ProotManager can
     * bind it on any spawn without an init-order dependency.
     */
    fun sourcesRoot(filesDir: java.io.File): java.io.File = java.io.File(filesDir, "sources")

    /** Host dir bound to guest [TRANSFER_GUEST] for the extension file.import/file.export bridges.
     *  App-private ext4 (the app no longer writes to the shared /storage JCode folder). */
    fun transferRoot(filesDir: java.io.File): java.io.File = java.io.File(filesDir, "jcode-transfer")
}

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
