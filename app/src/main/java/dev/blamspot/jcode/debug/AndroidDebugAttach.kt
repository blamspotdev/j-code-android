package dev.blamspot.jcode.debug

import dev.blamspot.jcode.core.debug.DebugException
import dev.blamspot.jcode.core.distro.DistroService
import dev.blamspot.jcode.core.distro.adb.AdbHostClient
import kotlinx.coroutines.delay
import java.io.File
import java.net.ServerSocket

/**
 * Puts an installed Android app into a state the JVM debug adapter can attach to: marks it
 * debuggable-on-launch, makes sure a process exists, and exposes that process's ART JDWP channel on a
 * local TCP port through the ADB bridge.
 *
 * ART speaks JDWP only for a *debuggable* process, and only over adb's `jdwp:<pid>` service — there is
 * no `-agentlib:jdwp` leg to launch, so the session is always an attach.
 */
internal class AndroidDebugAttach(
    private val distroService: DistroService,
    private val hostToDistro: (String) -> String,
    private val log: (String) -> Unit,
) {
    private val client = AdbHostClient()

    /** Serial the debug-app flag is currently set on, so [detach] can undo it after a partial attach. */
    private var debugAppSerial: String? = null

    /** Local port currently forwarded to the debuggee's JDWP channel, for [detach] to remove. */
    private var forwardedPort: Int? = null

    /** @return the local TCP port the app's JDWP channel is forwarded to. */
    suspend fun attach(module: File): Int {
        val (packageName, launchActivity) = readBadging(module)
        val serial = onlineSerial()
        requireInstalled(serial, packageName)
        // `-w` holds the NEXT launch at "Waiting For Debugger" until a debugger attaches, so
        // breakpoints in Application/Activity start-up still bind in time. It has no effect on an
        // already-running process, which is why it is set before the process is resolved.
        client.shellV2(serial, "am set-debug-app -w $packageName")
        debugAppSerial = serial
        val pid = resolvePid(serial, packageName, launchActivity)
        val port = freeLocalPort()
        runCatching { client.forward(serial, "tcp:$port", "jdwp:$pid") }.onFailure { error ->
            throw DebugException(
                "adb refused to forward tcp:$port to jdwp:$pid for $packageName — only a debuggable " +
                    "(debug-variant) build exposes a JDWP channel. ${error.message.orEmpty()}".trim(),
            )
        }
        forwardedPort = port
        log("Attaching to $packageName (pid $pid) on $serial through 127.0.0.1:$port…\n")
        return port
    }

    /** Undo everything [attach] changed on the device. */
    suspend fun detach() {
        // `set-debug-app -w` outlives the session: left set, it would strand the app at "Waiting For
        // Debugger" the next time the user launches it by hand.
        val serial = debugAppSerial ?: return
        debugAppSerial = null
        runCatching { client.shellV2(serial, "am clear-debug-app") }
        forwardedPort?.let { port ->
            forwardedPort = null
            client.killForward(serial, "tcp:$port")
        }
    }

    /** The application id and launcher activity, read back out of the built APK. */
    private suspend fun readBadging(module: File): Pair<String, String?> {
        val apk = AndroidAppProject.debugApk(module) ?: throw DebugException(
            "No debug APK under ${module.name}/${AndroidAppProject.APK_DIR} — run the app once " +
                "(Run → \"Run on this device\") so Gradle builds and installs it, then Debug.",
        )
        // Never parsed from Gradle: flavors, `applicationIdSuffix` and build types all rewrite the
        // final id, so only the APK knows what it actually is (the Run flow reads it the same way).
        val badging = distroService.exec(
            command = "aapt dump badging '${hostToDistro(apk.path)}'",
            timeoutMs = AAPT_TIMEOUT_MS,
        )
        if (!badging.succeeded) {
            throw DebugException(
                "`aapt dump badging ${apk.name}` failed — install the Android SDK from Toolchains " +
                    "(the Run flow reads the package id with the same tool)." +
                    detail(badging.stderr, badging.stdout, badging.internalError),
            )
        }
        val packageName = BADGING_PACKAGE.find(badging.stdout)?.groupValues?.get(1)
            ?: throw DebugException("Could not read a package name from ${apk.name}.")
        return packageName to BADGING_ACTIVITY.find(badging.stdout)?.groupValues?.get(1)
    }

    private suspend fun onlineSerial(): String {
        if (client.version() == null) {
            throw DebugException(
                "The ADB bridge isn't running — open the Android Device page, connect this device, " +
                    "then start Debug again.",
            )
        }
        val devices = runCatching { client.devices() }.getOrDefault(emptyList()).filter { it.isOnline }
        // The bridge mints a loopback serial (127.0.0.1:<relayPort>); prefer it over any other transport.
        return devices.firstOrNull { it.serial.startsWith(AdbHostClient.LOOPBACK) }?.serial
            ?: devices.firstOrNull()?.serial
            ?: throw DebugException(
                "No device is paired with the ADB bridge — open the Android Device page and connect, " +
                    "then start Debug again.",
            )
    }

    private suspend fun requireInstalled(serial: String, packageName: String) {
        val path = runCatching { client.shellV2(serial, "pm path $packageName") }.getOrNull()
        if (path?.stdout?.contains("package:") != true) {
            throw DebugException(
                "$packageName isn't installed on $serial — run the app once " +
                    "(Run → \"Run on this device\") first.",
            )
        }
    }

    /** The app's pid, launching it (and waiting for the process to appear) when it isn't running. */
    private suspend fun resolvePid(serial: String, packageName: String, launchActivity: String?): Int {
        pidOf(serial, packageName)?.let { return it }
        val start = launchActivity?.let { "am start -n $packageName/$it" }
            ?: "monkey -p $packageName -c android.intent.category.LAUNCHER 1"
        log("Starting $packageName — it will wait for the debugger…\n")
        val started = runCatching { client.shellV2(serial, start) }.getOrNull()
        val deadline = System.currentTimeMillis() + PID_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            pidOf(serial, packageName)?.let { return it }
            delay(PID_POLL_MS)
        }
        throw DebugException(
            "$packageName is installed but no process appeared after `$start` — check the device " +
                "screen for a crash or a permission prompt." + detail(started?.stderr, started?.stdout),
        )
    }

    private suspend fun pidOf(serial: String, packageName: String): Int? {
        val out = runCatching { client.shellV2(serial, "pidof $packageName") }.getOrNull() ?: return null
        return out.stdout.trim().split(WHITESPACE).firstOrNull()?.toIntOrNull()
    }

    private fun freeLocalPort(): Int = ServerSocket(0).use { it.localPort }

    private fun detail(vararg parts: String?): String {
        val text = parts.firstOrNull { !it.isNullOrBlank() }?.trim()?.takeLast(MAX_DETAIL) ?: return ""
        return " ($text)"
    }

    private companion object {
        const val AAPT_TIMEOUT_MS = 60_000L
        const val PID_WAIT_MS = 20_000L
        const val PID_POLL_MS = 250L
        const val MAX_DETAIL = 240
        val WHITESPACE = Regex("\\s+")
        val BADGING_PACKAGE = Regex("""^package: name='([^']+)'""", RegexOption.MULTILINE)
        val BADGING_ACTIVITY = Regex("""^launchable-activity: name='([^']+)'""", RegexOption.MULTILINE)
    }
}
