package dev.jcode.adb

import android.content.Context
import android.os.Build
import android.util.Log
import dev.jcode.core.distro.adb.AdbAuthorizedKeys
import dev.jcode.core.distro.adb.AdbDaemon
import dev.jcode.core.distro.adb.AdbServiceHandler
import dev.jcode.core.distro.adb.AdbStream
import dev.jcode.core.distro.adb.adbCommandArgs
import dev.jcode.core.distro.adb.unsupportedService
import dev.jcode.vdevice.AppSandbox
import dev.jcode.vdevice.AppSandboxSession
import dev.jcode.vdevice.LauncherApp
import dev.jcode.vdevice.VirtualDevice
import dev.jcode.vdevice.VirtualDeviceApps
import dev.jcode.vdevice.VirtualDeviceLog
import dev.jcode.vdevice.VirtualIdentity
import dev.jcode.vdevice.VirtualInput
import dev.jcode.vdevice.VirtualLauncher
import dev.jcode.vdevice.VirtualScreen
import java.io.File

/**
 * The adb services JCode's virtual device answers: everything an adb client asks of a device is
 * served out of the [VirtualDevice] container, and nothing is ever forwarded to the host phone.
 *
 * The shape of the surface is deliberate. Between `install`, `am start`, `input`, `uiautomator dump`
 * and `screencap`, an agent with nothing but a terminal can put an app on the device, drive it, read
 * what is on screen and take it off again — the same loop a person has through the tab, over a
 * protocol that was already there. Everything else answers [unsupportedService] on one line rather
 * than hanging or pretending to have worked.
 *
 * Commands answer on `shell:` and on `exec:` alike, and the reply is bytes rather than text, so
 * `adb exec-out screencap -p > shot.png` returns a PNG intact. Nothing here allocates a PTY — that
 * is the line discipline which would otherwise rewrite every `\n` in it into `\r\n`.
 *
 * "Installing" here means staging the APK under the container's own storage; there is no system
 * package database involved, so a guest is still invisible to the real `pm` — and
 * [VirtualDeviceApps] empties the whole tree on every J Code start.
 */
class VirtualDeviceAdbService(context: Context) : AdbServiceHandler {

    private val appContext = context.applicationContext

    /** What `getprop` answers with — the subset ddmlib and AGP actually read off a device. */
    private val properties: Map<String, String> by lazy {
        mapOf(
            "ro.product.name" to VirtualIdentity.PRODUCT,
            "ro.product.device" to VirtualIdentity.DEVICE,
            "ro.product.model" to VirtualIdentity.MODEL,
            "ro.product.brand" to BRAND,
            "ro.product.manufacturer" to BRAND,
            "ro.serialno" to VirtualIdentity.SERIAL,
            "ro.build.version.sdk" to Build.VERSION.SDK_INT.toString(),
            "ro.build.version.release" to Build.VERSION.RELEASE,
            "ro.build.version.codename" to Build.VERSION.CODENAME,
            "ro.build.version.preview_sdk" to "0",
            "ro.build.type" to "user",
            "ro.build.characteristics" to "default",
            "ro.product.cpu.abi" to Build.SUPPORTED_ABIS.first(),
            "ro.product.cpu.abilist" to Build.SUPPORTED_ABIS.joinToString(","),
            "ro.sf.lcd_density" to appContext.resources.displayMetrics.densityDpi.toString(),
        )
    }

    override suspend fun handle(stream: AdbStream) {
        val service = stream.service
        val command = when {
            service.startsWith(SHELL) -> service.removePrefix(SHELL)
            service.startsWith(EXEC) -> service.removePrefix(EXEC)
            else -> return stream.write(unsupportedService(service))
        }
        dispatch(unwrap(adbCommandArgs(command)), stream)
    }

    /**
     * Strips the shell wrapper adb puts around some commands before the command itself.
     *
     * `adb logcat` does not send `logcat`; it sends
     * `export ANDROID_LOG_TAGS="…"; exec logcat …`, because on a real device there is a shell to
     * run that. There is none here, so the environment assignments and the `exec` are dropped and
     * what is left is the command — which is what a shell would have run anyway.
     */
    private fun unwrap(args: List<String>): List<String> {
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            val isAssignment = arg.contains('=') && !arg.startsWith("-")
            if (arg == "export" || arg == "exec" || arg == ";" || isAssignment) index++ else break
        }
        return args.drop(index)
    }

    private suspend fun dispatch(args: List<String>, stream: AdbStream) {
        when (args.firstOrNull()) {
            "getprop" -> stream.write(getprop(args.getOrNull(1)))
            "echo" -> stream.write(args.drop(1).joinToString(" ") + "\n")
            "pm" -> stream.write(pm(args.drop(1)) ?: unsupportedService(stream.service))
            "am" -> stream.write(am(args.drop(1)) ?: unsupportedService(stream.service))
            "wm" -> stream.write(wm(args.drop(1)) ?: unsupportedService(stream.service))
            "input" -> stream.write(input(args.drop(1)))
            "logcat" -> stream.write(logcat(args.drop(1)))
            "uiautomator" -> uiautomator(args.drop(1), stream)
            "screencap" -> screencap(args.drop(1), stream)
            "cmd" -> install(args, stream)
            else -> stream.write(unsupportedService(stream.service))
        }
    }

    /**
     * `screencap [-p] [-d <display>]`, answering the device sandbox's screen as a PNG.
     *
     * This is what lets whoever is driving the device *see* it, so it never fails: an idle device
     * answers its own wallpaper, not an error. `-p` is accepted and ignored — a PNG is the only
     * encoding offered, because the raw form only makes sense next to a filesystem this device does
     * not have.
     */
    private suspend fun screencap(args: List<String>, stream: AdbStream) {
        pathArgument(args)?.let { path ->
            return stream.write(noFilesystem("screencap", path, "screencap -p > shot.png"))
        }
        stream.write(VirtualScreen.png(appContext))
    }

    /**
     * `uiautomator dump`, answering the running guest's view tree as XML on the stream.
     *
     * Real `uiautomator` writes the dump to a file and prints where it went; this device has nowhere
     * to write one, so — exactly as `screencap` does — the bytes come back on the stream and a path
     * argument is answered with how to redirect it instead.
     */
    private suspend fun uiautomator(args: List<String>, stream: AdbStream) {
        if (args.firstOrNull() != "dump") return stream.write(unsupportedService(stream.service))
        pathArgument(args.drop(1))?.let { path ->
            return stream.write(noFilesystem("uiautomator", path, "uiautomator dump > window.xml"))
        }
        // An idle device is showing its launcher, and the launcher is tappable — so it is what the
        // dump answers with, rather than claiming there is nothing on the screen.
        val session = running() ?: return stream.write(
            home { width, height, density, apps -> VirtualLauncher.dump(width, height, density, apps) },
        )
        val xml = File(appContext.filesDir, DUMP_FILE)
        if (!session.dump(xml)) {
            return stream.write("uiautomator: could not read the guest's view tree\n")
        }
        stream.write(xml.readBytes())
    }

    /**
     * `input tap|swipe|text|keyevent`, synthesised into the running guest.
     *
     * The optional leading source word real `input` takes (`input touchscreen tap …`) is skipped
     * rather than honoured: this device has one input path, and a driver that names the source it is
     * used to should not be told the command does not exist.
     */
    private suspend fun input(args: List<String>): String {
        val rest = if (args.firstOrNull() in INPUT_SOURCES) args.drop(1) else args
        val points = rest.drop(1).mapNotNull { it.toFloatOrNull() }
        val session = running() ?: return launcherTap(rest.firstOrNull(), points)
        return when (rest.firstOrNull()) {
            "tap" -> {
                if (points.size < 2) return "input: tap needs <x> <y>\n"
                VirtualInput.tap(session, points[0], points[1])
                ""
            }

            "swipe" -> {
                if (points.size < 4) return "input: swipe needs <x1> <y1> <x2> <y2> [duration_ms]\n"
                VirtualInput.swipe(
                    session = session,
                    fromX = points[0],
                    fromY = points[1],
                    toX = points[2],
                    toY = points[3],
                    durationMs = points.getOrNull(4)?.toLong(),
                )
                ""
            }

            // Everything after the verb, so an unquoted sentence types as one.
            "text" -> rest.drop(1).joinToString(" ").ifEmpty { null }
                ?.let { session.text(it); "" }
                ?: "input: text needs something to type\n"

            "keyevent" -> {
                val codes = rest.drop(1).map { it to VirtualInput.keyCode(it) }
                codes.firstOrNull { it.second == null }?.let { return "input: unknown keycode ${it.first}\n" }
                if (codes.isEmpty()) return "input: keyevent needs a key code or name\n"
                codes.forEach { (_, code) -> VirtualInput.key(session, code!!) }
                ""
            }

            else -> "input: expected tap, swipe, text or keyevent\n"
        }
    }

    /**
     * `logcat`, answering the **virtual device's** log rather than the phone's.
     *
     * The phone's is not on offer and could not be: reading it needs `READ_LOGS`, and an app cannot
     * read back even its own entries — measured on Android 13, where `logcat` run as J Code's uid
     * returns nothing whatever. What this answers instead is written by the container itself, and is
     * the more useful log for a driver anyway: it contains this device's business and nothing else —
     * what was loaded and started, what the container refused and why, anything the guest printed,
     * the full stack trace of an uncaught exception in it, and the system's reason when the guest
     * process was killed outright rather than crashing.
     *
     * `-d` is implied and `-t <n>`, `-c` and `-b <buffer>` are honoured; there is no follow mode,
     * because there is no `logcat` process here to keep open. A guest's own `android.util.Log` calls
     * go to the system log through a native call there is no reaching, so they are absent — said
     * plainly rather than quietly missing.
     */
    private fun logcat(args: List<String>): String {
        if (args.contains("-c") || args.contains("--clear")) {
            VirtualDeviceLog.clear(appContext)
            return ""
        }
        val tail = args.zipWithNext().firstOrNull { it.first == "-t" }?.second?.toIntOrNull()
        return VirtualDeviceLog.read(appContext, tail).ifEmpty { EMPTY_LOG }
    }

    /** `wm size` / `wm density`, in the words real `wm` answers them. */
    private fun wm(args: List<String>): String? {
        val (width, height) = VirtualScreen.resolution(appContext)
        return when (args.firstOrNull()) {
            "size" -> "Physical size: ${width}x$height\n"
            "density" -> "Physical density: ${appContext.resources.displayMetrics.densityDpi}\n"
            else -> null
        }
    }

    private suspend fun install(args: List<String>, stream: AdbStream) {
        if (args.getOrNull(1) != "package" || args.getOrNull(2) != "install") {
            stream.write(unsupportedService(stream.service))
            return
        }
        val size = args.zipWithNext().firstOrNull { it.first == "-S" }?.second?.toLongOrNull()
        if (size == null) {
            // Without -S the client would stream until it closed the stream, which is the sync:
            // style install this daemon does not implement.
            stream.write("Failure [INSTALL_FAILED_INVALID_ARGS: '${stream.service}' has no -S size]\n")
            return
        }
        stream.write(receiveApk(size, stream))
    }

    private suspend fun receiveApk(size: Long, stream: AdbStream): String {
        val staged = VirtualDeviceApps.staging(appContext)
        var received = 0L
        staged.outputStream().use { out ->
            while (received < size) {
                val chunk = stream.read() ?: break
                out.write(chunk)
                received += chunk.size
            }
        }
        if (received != size) {
            staged.delete()
            return "Failure [INSTALL_FAILED_INVALID_APK: got $received of $size bytes]\n"
        }
        return VirtualDeviceApps.install(appContext, staged).fold(
            onSuccess = { "Success\n" },
            onFailure = { "Failure [INSTALL_PARSE_FAILED_NOT_APK: ${it.message}]\n" },
        )
    }

    private fun getprop(key: String?): String = when (key) {
        null -> properties.entries.joinToString("\n", postfix = "\n") { "[${it.key}]: [${it.value}]" }
        else -> properties[key].orEmpty() + "\n"
    }

    private fun pm(args: List<String>): String? {
        val target = args.getOrNull(1)
        return when {
            args.getOrNull(0) == "list" && target == "packages" ->
                VirtualDeviceApps.packages(appContext).joinToString("") { "package:$it\n" }

            args.getOrNull(0) == "uninstall" && target != null -> {
                // An app that is being removed must not still be on the screen behind its own icon.
                if (AppSandbox.apkPath.value == VirtualDeviceApps.apk(appContext, target)?.absolutePath) {
                    AppSandbox.requestStop()
                }
                if (VirtualDeviceApps.uninstall(appContext, target)) "Success\n"
                else "Failure [DELETE_FAILED_INTERNAL_ERROR: $target is not installed]\n"
            }

            args.getOrNull(0) == "clear" && target != null ->
                if (VirtualDeviceApps.clearData(appContext, target)) "Success\n"
                else "Failed\n"

            args.getOrNull(0) == "path" && target != null ->
                VirtualDeviceApps.apk(appContext, target)?.let { "package:${it.absolutePath}\n" }
                    ?: ""

            else -> null
        }
    }

    /**
     * `am start -n <pkg>/<activity>` and `am force-stop <pkg>`.
     *
     * The app opens on the device sandbox's screen in its editor tab, so whoever ran this — an agent
     * driving the terminal as much as the user — still has the IDE, and the terminal it typed into,
     * around the running app. `--windowingMode 1` (`WINDOWING_MODE_FULLSCREEN`, the same value real
     * `am` takes) asks for the old behaviour, where the guest takes over the screen as its own task.
     *
     * Either way this answers as soon as the launch is handed over: an adb client waits on the
     * `Starting:` line, and a tab takes frames to compose that the stream must not sit through.
     */
    private fun am(args: List<String>): String? {
        if (args.firstOrNull() == "force-stop") {
            AppSandbox.requestStop()
            return ""
        }
        if (args.firstOrNull() != "start") return null
        val component = args.zipWithNext().firstOrNull { it.first == "-n" }?.second ?: return null
        val packageName = component.substringBefore('/')
        val activity = component.substringAfter('/', missingDelimiterValue = "")
        val apk = VirtualDeviceApps.apk(appContext, packageName)
            ?: return "Error: Package $packageName is not installed on the virtual device\n"
        val className = activity.takeIf { it.isNotEmpty() }?.let { qualify(it, packageName) }
        val fullScreen =
            args.zipWithNext().firstOrNull { it.first == "--windowingMode" }?.second == FULLSCREEN_MODE
        val started = if (fullScreen) {
            VirtualDevice.launch(appContext, apk.absolutePath, className)
        } else {
            // inspect() is the same parse launch() would do, so a broken APK still fails here rather
            // than silently opening an empty tab.
            VirtualDevice.inspect(appContext, apk.absolutePath)
                .onSuccess { AppSandbox.requestOpen(apk.absolutePath, className, run = true) }
        }
        return started.fold(
            onSuccess = { "Starting: Intent { cmp=$component }\n" },
            onFailure = { "Error: ${it.message}\n" },
        )
    }

    /** The session behind a guest that is actually up; null is a device showing its launcher. */
    private fun running(): AppSandboxSession? = AppSandbox.sessionOrNull()?.takeIf { it.isRunning }

    /**
     * Reads the home screen at the size and density it is drawn at, which is what makes a capture,
     * a dump and a tap agree on where an icon is.
     */
    private fun <T> home(block: (Int, Int, Float, List<LauncherApp>) -> T): T {
        val (width, height) = VirtualScreen.resolution(appContext)
        return block(
            width,
            height,
            appContext.resources.displayMetrics.density,
            VirtualLauncher.load(appContext),
        )
    }

    /**
     * A tap on the device's own home screen: the launcher is what is on the screen when no app is,
     * so tapping an icon starts it, exactly as a finger on the tab would. Anything else there is
     * still "nothing is running" — the wallpaper has no other affordances.
     */
    private fun launcherTap(verb: String?, points: List<Float>): String {
        if (verb != "tap" || points.size < 2) return NOTHING_RUNNING
        val app = home { width, height, density, apps ->
            VirtualLauncher.hit(width, height, density, apps, points[0], points[1])
        } ?: return "input: no app icon at (${points[0].toInt()}, ${points[1].toInt()})\n"
        AppSandbox.requestOpen(app.apkPath, null, run = true)
        return "Starting: ${app.packageName}\n"
    }

    /** The first non-flag argument, which for this device is always a file it cannot write. */
    private fun pathArgument(args: List<String>): String? {
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            when {
                // -d <display>: the value belongs to the flag, not to the command.
                arg == "-d" -> index++
                !arg.startsWith("-") -> return arg
            }
            index++
        }
        return null
    }

    private fun noFilesystem(command: String, path: String, redirect: String): String =
        "$command: the virtual device has no filesystem to write '$path' to — read it off the " +
            "stream with `adb -s ${VirtualIdentity.SERIAL} exec-out $redirect`\n"

    private fun qualify(activity: String, packageName: String): String = when {
        activity.startsWith(".") -> packageName + activity
        !activity.contains('.') -> "$packageName.$activity"
        else -> activity
    }

    companion object {
        /**
         * `cmd` is the load-bearing feature: with it `adb install` opens exactly one
         * `exec:cmd package 'install' -S <n>` stream, and without it the client falls back to
         * `push` + `pm install`, which would need the whole `sync:` service. `shell_v2` is
         * deliberately absent — the client falls back to the simpler legacy `shell:` happily.
         */
        private const val FEATURES = "cmd,stat_v2,ls_v2,fixed_push_mkdir,apex,fixed_push_symlink_timestamp"

        private const val BRAND = "JCode"

        /** `WindowingMode.WINDOWING_MODE_FULLSCREEN`, what `am start --windowingMode` names. */
        private const val FULLSCREEN_MODE = "1"

        /** What real `input` calls the source; accepted and ignored, since this device has one. */
        private val INPUT_SOURCES = setOf("touchscreen", "touchpad", "touchnavigation", "keyboard", "mouse")

        private const val EMPTY_LOG =
            "--------- beginning of jcode virtual device\n" +
                "(nothing logged yet — the device's log covers this J Code session only, and holds " +
                "what the container did plus anything the guest printed or crashed with)\n"

        private const val NOTHING_RUNNING =
            "error: no app is running on the virtual device — `am start -n <pkg>/<activity>` first\n"

        private const val DUMP_FILE = "vdevice/window_dump.xml"
        private const val SHELL = "shell:"
        private const val EXEC = "exec:"
        private const val TAG = "VirtualDeviceAdb"

        /**
         * The adb daemon for JCode's virtual device, bound to loopback and authenticated against the
         * distro's own `~/.android/adbkey.pub`. Call [AdbDaemon.start] to bind (it returns the port
         * for `adb connect 127.0.0.1:<port>`) and [AdbDaemon.stop] to tear it down.
         */
        fun daemon(context: Context): AdbDaemon {
            val app = context.applicationContext
            // Whichever of the workbench and this daemon gets there first empties the device; the
            // other must not, or an install could land in the window between them and be wiped.
            VirtualDeviceApps.resetOnStart(app)
            return AdbDaemon(
                banner = "device::ro.product.name=${VirtualIdentity.PRODUCT};" +
                    "ro.product.model=${VirtualIdentity.MODEL};" +
                    "ro.product.device=${VirtualIdentity.DEVICE};" +
                    "features=$FEATURES",
                authorizedKeys = AdbAuthorizedKeys(File(app.filesDir, "distros")),
                handler = VirtualDeviceAdbService(app),
                log = { message -> Log.i(TAG, message) },
            )
        }
    }
}
