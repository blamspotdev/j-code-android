package dev.jcode.vdevice

import android.app.ActivityManager
import android.content.Context
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The virtual device's own log — what `adb logcat` answers with, and the only way to see *why* a
 * guest died from outside the tab.
 *
 * It cannot be the phone's log. Reading `logcat` needs `READ_LOGS`, which is `signature|privileged`,
 * and an app has not been able to read even its own entries since Android 4.1 — measured here on
 * Android 13, where `logcat` run as JCode's uid returns nothing at all. So a driver that wanted the
 * stack trace behind a crash had no way to get it.
 *
 * JCode does not need to read the system log, though: it *is* the process running the guest. Every
 * line here is written by the container itself — what it loaded, what it bound, what it refused, and
 * above all the uncaught exceptions it catches in `:guest` — which is a better log than a filtered
 * `logcat` would be, because it contains only this device's business.
 *
 * **A file, deliberately.** The `:guest` process and the IDE both write it, and a full-screen guest
 * has no session bound to carry the lines over — but the two processes share a uid and a data
 * directory, so an appending write is all the coordination needed. `VirtualDeviceApps.resetOnStart`
 * wipes it with everything else, so the log covers exactly one JCode session.
 */
internal object VirtualDeviceLog {

    private const val FILE = "vdevice/device.log"

    /** Trimmed to the newest half when it passes this, so a chatty guest cannot fill the disk. */
    private const val MAX_BYTES = 512L * 1024L

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun append(context: Context, level: Char, tag: String, message: String) {
        val file = file(context)
        runCatching {
            file.parentFile?.mkdirs()
            val prefix = "${stamp.format(Date())} $level/$tag: "
            // Indented continuations, so one stack trace stays one entry to anything reading lines.
            val body = message.trimEnd().lineSequence().joinToString("\n$CONTINUATION")
            file.appendText(prefix + body + "\n")
            if (file.length() > MAX_BYTES) trim(file)
        }
    }

    /** [tail] limits to the newest N lines, the way `logcat -t` does. */
    fun read(context: Context, tail: Int?): String {
        val lines = runCatching { file(context).readLines() }.getOrDefault(emptyList())
        if (lines.isEmpty()) return ""
        return lines.takeLast(tail ?: lines.size).joinToString("\n", postfix = "\n")
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /**
     * Tees the `:guest` process's `System.out` and `System.err` into the device's log.
     *
     * A guest's `android.util.Log` calls go straight to the system log through a native call there
     * is no reaching, and no app may read that back — so those are simply not available, and saying
     * so is better than pretending. What *is* available is everything an app prints: `println`, and
     * `Throwable.printStackTrace()`, which is where a caught-and-reported failure usually ends up.
     *
     * Still tee'd rather than replaced, so anything already watching the streams keeps seeing them.
     */
    fun captureStandardStreams(context: Context) {
        System.setOut(tee(context, System.out, "System.out", 'I'))
        System.setErr(tee(context, System.err, "System.err", 'W'))
    }

    private fun tee(context: Context, to: PrintStream, tag: String, level: Char): PrintStream {
        val line = StringBuilder()
        return PrintStream(
            object : OutputStream() {
                override fun write(byte: Int) {
                    to.write(byte)
                    when {
                        byte == '\n'.code -> {
                            append(context, level, tag, line.toString())
                            line.setLength(0)
                        }
                        // A stream that never breaks a line must not grow without bound.
                        line.length > MAX_LINE -> {
                            append(context, level, tag, line.toString())
                            line.setLength(0)
                        }
                        byte != '\r'.code -> line.append(byte.toChar())
                    }
                }
            },
            true,
        )
    }

    private fun trim(file: File) {
        val lines = file.readLines()
        file.writeText(lines.takeLast(lines.size / 2).joinToString("\n", postfix = "\n"))
    }

    /**
     * Why the `:guest` process is gone, when it went without leaving a stack trace — killed for
     * memory, ANR'd, or trimmed by the phantom-process reaper this platform applies to forked
     * children. `getHistoricalProcessExitReasons` is public API and asks nothing of the caller for
     * its own package, which is what makes it reachable where `logcat` is not.
     */
    fun appendExitReason(context: Context) {
        val activity = context.getSystemService(ActivityManager::class.java) ?: return
        val exits = runCatching {
            activity.getHistoricalProcessExitReasons(context.packageName, 0, EXIT_REASONS)
        }.getOrNull().orEmpty()
        val guest = exits.firstOrNull { it.processName.endsWith(GUEST_PROCESS) } ?: return
        append(
            context = context,
            level = 'E',
            tag = "ActivityManager",
            message = "${guest.processName} died: reason=${guest.reason} status=${guest.status} " +
                "importance=${guest.importance}${guest.description?.let { " ($it)" }.orEmpty()}",
        )
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    private const val CONTINUATION = "        "
    private const val MAX_LINE = 4096
    private const val EXIT_REASONS = 5
    private const val GUEST_PROCESS = ":guest"
}
