package dev.blamspot.jcode.design

import androidx.compose.runtime.compositionLocalOf

/**
 * One device an Android run or debug can be sent to, as the runtime's adb server reports it: JCode's
 * own virtual device, this phone through the relay, or anything else the guest has connected.
 */
data class AndroidRunTarget(
    /** adb serial — a socket spec, so also exactly what `ANDROID_SERIAL` takes. */
    val serial: String,
    val label: String,
    /** adb's own state word, verbatim: `device`, `offline`, `unauthorized`, `connecting`. */
    val state: String,
    val isVirtual: Boolean = false,
) {
    val isOnline: Boolean get() = state == "device"
}

/**
 * Which device an Android project's runs go to, shared (via [LocalAndroidRunTargets]) with the Run
 * panel. A project with no pick left on [AUTO] keeps whatever `ANDROID_SERIAL` the terminal session
 * already carries, so choosing nothing behaves exactly as it did before there was a picker.
 */
class AndroidRunTargets(
    val available: List<AndroidRunTarget> = emptyList(),
    /** The serial sessions already carry — the target used while a project is still on [AUTO]. */
    val defaultSerial: String = "",
    /** Per-project pick, keyed by a stable project key; [AUTO] when the project has none. */
    val projectChoice: (projectKey: String) -> String = { AUTO },
    val loading: Boolean = false,
    val onSetProject: (projectKey: String, serial: String) -> Unit = { _, _ -> },
    val onRefresh: () -> Unit = {},
) {
    /** The device [projectKey] launches on: its pick while that device is still connected, else the
     *  session default, else whatever is there — so an unplugged pick degrades instead of dead-ending. */
    fun effective(projectKey: String): AndroidRunTarget? {
        val saved = projectChoice(projectKey)
        if (saved != AUTO) available.firstOrNull { it.serial == saved }?.let { return it }
        return available.firstOrNull { it.serial == defaultSerial }
            ?: available.firstOrNull { it.isOnline }
            ?: available.firstOrNull()
    }

    /** The serial to force through `ANDROID_SERIAL`, or blank to leave the session's own alone. Only a
     *  pick that is still connected forces anything; a stale one would send the run nowhere. */
    fun serialFor(projectKey: String): String =
        projectChoice(projectKey).takeIf { it != AUTO && available.any { d -> d.serial == it } }.orEmpty()

    companion object {
        /** No pick — follow the session's own `ANDROID_SERIAL`. */
        const val AUTO = ""
    }
}

val LocalAndroidRunTargets = compositionLocalOf { AndroidRunTargets() }
