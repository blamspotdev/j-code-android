package dev.blamspot.jcode.design

import androidx.compose.runtime.compositionLocalOf

/**
 * ADB bridge status + the entry point to its pairing page, shared (via [LocalAndroidDevice]) with the
 * settings screen and the Run panel without threading params through JCodeShell (ART register limit).
 * [ready] is true only once a device is connected through the relay; [status] is a short label for
 * every other state ("Not set up", "Connecting…", a failure reason).
 */
class AndroidDeviceSetting(
    val ready: Boolean = false,
    val status: String = "Not set up",
    val serial: String? = null,
    val onOpenPage: () -> Unit = {},
)

val LocalAndroidDevice = compositionLocalOf { AndroidDeviceSetting() }
