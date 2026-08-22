package dev.blamspot.jcode.design

import androidx.compose.runtime.compositionLocalOf

/**
 * "Run in a virtual device" preference, shared (via [LocalVirtualDevice]) with the settings screen and
 * the run flow without threading a param through JCodeShell (ART register limit). When [enabled], an
 * Android run config that builds for the container starts its APK inside JCode once the build
 * finishes, instead of installing it through adb; [onChange] toggles it.
 */
class VirtualDeviceSetting(
    val enabled: Boolean = false,
    val onChange: (Boolean) -> Unit = {},
    /** Whether the runtime has the adb client the device is reached through. */
    val adbAvailable: Boolean = false,
    /** Re-attach the virtual device to the runtime's adb server. */
    val onReconnect: () -> Unit = {},
    /** True while a reconnect is in flight, so the action can show it is working. */
    val reconnecting: Boolean = false,
)

val LocalVirtualDevice = compositionLocalOf { VirtualDeviceSetting() }
