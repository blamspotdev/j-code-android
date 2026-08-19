package dev.jcode.core.distro.adb

/**
 * One entry of the adb server's device list. [state] is adb's own word for it — `device`, `offline`,
 * `unauthorized`, `connecting` — and is kept verbatim so the UI can report exactly what adb reported.
 */
data class AdbDevice(
    val serial: String,
    val state: String,
    val model: String? = null,
) {
    val isOnline: Boolean
        get() = state == STATE_DEVICE

    companion object {
        const val STATE_DEVICE: String = "device"
    }
}

/**
 * Output of one command run on a device. [exitCode] is null when the transport does not report one
 * (the `exec:` service never does), so a null exit code means "unknown", never "succeeded".
 */
data class AdbExec(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
) {
    val succeeded: Boolean
        get() = exitCode == 0
}

/** Lifecycle of the app-side adb bridge (relay + discovery + the guest's adb server). */
sealed interface AdbBridgeState {
    data object Stopped : AdbBridgeState

    data object Discovering : AdbBridgeState

    data class Ready(val relayPort: Int, val backendPort: Int) : AdbBridgeState

    /** Relay is up but the device is not usable yet — wireless debugging off, not paired, offline. */
    data class Degraded(val reason: String) : AdbBridgeState

    data class Failed(val message: String) : AdbBridgeState
}
