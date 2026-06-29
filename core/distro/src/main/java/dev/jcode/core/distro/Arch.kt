package dev.jcode.core.distro

import android.os.Build

/**
 * CPU architecture of a rootfs / environment, decoupled from the host device ABI.
 *
 * The host phone is almost always [ARM64]. A rootfs may target a *different* arch
 * (e.g. an [X86_64] Ubuntu image), in which case it runs under QEMU user-mode
 * emulation via proot's `--qemu` mechanism. When rootfs arch == host arch the
 * binaries run natively with no emulation (the fast path).
 *
 * @property rootfsKey the token used in rootfs naming / catalog metadata ("arm64", "amd64").
 * @property abi the Android ABI string for the matching native proot/qemu asset.
 * @property qemuUserBinary the qemu *user-mode* binary that emulates this arch on a foreign host.
 */
enum class Arch(
    val rootfsKey: String,
    val abi: String,
    val qemuUserBinary: String,
) {
    ARM64("arm64", "arm64-v8a", "qemu-aarch64"),
    X86_64("amd64", "x86_64", "qemu-x86_64");

    companion object {
        /** The architecture of the device running the app. */
        fun host(): Arch = fromAbiList(Build.SUPPORTED_ABIS)

        fun fromAbiList(abis: Array<String>): Arch = when {
            abis.any { it.contains("arm64") || it.contains("aarch64") } -> ARM64
            abis.any { it.contains("x86_64") } -> X86_64
            // 32-bit / unknown hosts fall back to ARM64 (no x86 native asset exists for them).
            else -> ARM64
        }

        /** Parse a catalog/metadata arch token; null when unrecognized. */
        fun fromKey(key: String?): Arch? = when (key?.lowercase()?.trim()) {
            "amd64", "x86_64", "x86-64", "x64" -> X86_64
            "arm64", "aarch64", "arm64-v8a" -> ARM64
            else -> null
        }
    }
}
