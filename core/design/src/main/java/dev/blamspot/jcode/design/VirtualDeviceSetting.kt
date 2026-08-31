package dev.blamspot.jcode.design

import androidx.compose.runtime.compositionLocalOf

/**
 * "Run in a virtual device" preference, shared (via [LocalVirtualDevice]) with the settings screen and
 * the run flow without threading a param through JCodeShell (ART register limit). When [enabled], an
 * Android run config that builds for the container starts its APK inside JCode once the build
 * finishes, instead of installing it through adb.
 */
class VirtualDeviceSetting(
    val enabled: Boolean = false,
    /** Whether the runtime has the adb client the device is reached through. */
    val adbAvailable: Boolean = false,
    /** Re-attach the virtual device to the runtime's adb server. */
    val onReconnect: () -> Unit = {},
    /** True while a reconnect is in flight, so the action can show it is working. */
    val reconnecting: Boolean = false,
    /**
     * Whether the device belongs in the right drawer rather than in an editor tab.
     *
     * The pack that provides the device owns this as one of its own settings; what is here is the
     * answer, shared the way the rest of the device's facts already are. It rides along rather than
     * becoming another parameter because the workbench composable that needs it is eighty
     * parameters deep and already reads this local for the device's other answers.
     */
    val inDrawer: Boolean = false,
    /** True while the device has been stopped: it is placed in the drawer but not showing there. */
    val stopped: Boolean = false,
    /** Un-stop it, for whatever asks for the device next. */
    val onResume: () -> Unit = {},
    /** A device has started: bring its adb up if the setting says it should be. */
    val onDeviceStarted: () -> Unit = {},
)

val LocalVirtualDevice = compositionLocalOf { VirtualDeviceSetting() }
