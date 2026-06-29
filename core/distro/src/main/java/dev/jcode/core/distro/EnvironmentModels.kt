package dev.jcode.core.distro

/**
 * A lightweight summary of an installed Linux environment.
 *
 * "Docker-style" model: each installed rootfs (one directory under `filesDir/distros/<id>`) is an
 * environment the user can switch between. Exactly one is *active* at a time (the one terminals and
 * `exec` target). Foreign-arch environments (e.g. [Arch.X86_64] on an arm64 phone) run under QEMU.
 */
data class EnvironmentInfo(
    val id: String,
    val label: String,
    val arch: Arch,
    val installedAt: Long,
    val isActive: Boolean,
    /** Approximate on-disk size in bytes; -1 when not yet measured. */
    val sizeBytes: Long = -1L,
) {
    /** Whether running this environment requires QEMU user-mode emulation on the given [hostArch]. */
    fun requiresEmulation(hostArch: Arch): Boolean = arch != hostArch
}
