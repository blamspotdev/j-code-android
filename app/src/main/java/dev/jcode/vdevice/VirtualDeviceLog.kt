package dev.jcode.vdevice

import android.app.ActivityManager
import android.content.Context
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * The virtual device's own log — what `adb logcat` answers with, and the only way to see *why* a
 * guest died from outside the tab.
 *
 * It is not the phone's log, and it is not meant to be: `READ_LOGS` is `signature|privileged`, so
 * another app's entries are unreachable here and always will be. What it *is* is everything about
 * this device, from three sources that only together make it diagnosable:
 *
 *  1. The container's own account — what it loaded, what it bound, what it refused and why.
 *  2. The guest's `System.out` and `System.err` — see [captureStandardStreams].
 *  3. **The `:guest` process's own system log** — see [captureProcessLog], which is where an app's
 *     `android.util.Log` and native output live, and which the log daemon hands over unprivileged
 *     because a reader is scoped to its own uid rather than refused.
 *
 * The third one used to be missing, on the belief that an app can read nothing back from `logcat` at
 * all. That cost a whole investigation: a session in which the phone's document picker had opened
 * over the IDE and an app had been left waiting for a result for ever produced a device log of three
 * lines, while the system log for the same pid named the bug outright.
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
     * Everything an app prints: `println`, and `Throwable.printStackTrace()`, which is where a
     * caught-and-reported failure usually ends up. Tee'd rather than replaced, so anything already
     * watching the streams keeps seeing them.
     */
    fun captureStandardStreams(context: Context) {
        System.setOut(tee(context, System.out, "System.out", 'I'))
        System.setErr(tee(context, System.err, "System.err", 'W'))
    }

    /**
     * Tees this process's **own** system log into the device's log, which is the difference between
     * a device you can diagnose and one you cannot.
     *
     * The note that used to be here said an app can read nothing back from `logcat`, and it made the
     * device's log the container's hand-written narrative alone. That turns out to be half true and
     * the wrong half: `READ_LOGS` is indeed `signature|privileged`, but the log daemon scopes an
     * unprivileged reader **to its own uid** rather than refusing it — so a reader started here gets
     * `:guest`'s entries, and only those, with no permission at all.
     *
     * What that recovers is most of the log:
     *
     *  - the **guest's** own `android.util.Log` calls, which reach `logd` through a native call the
     *    container cannot stand in front of, and were therefore invisible;
     *  - its **native** logging — `__android_log_print` from an NDK app, which for a game engine is
     *    approximately all of its output;
     *  - the framework's own complaints on the guest's behalf, which is where a broken app usually
     *    says what is wrong;
     *  - and the container's ~60 `Log` calls, which were written to explain exactly the failures a
     *    driver is trying to diagnose and were going somewhere nobody could read.
     *
     * Measured on WaveRepo: the device's log held three lines for a session in which the phone's
     * document picker had opened over the IDE and an app had been left waiting for a result for ever.
     * The system log for the same pid held the GL context, the renderer, the audio stream, the app's
     * own "asking for the document picker", and the container's `outgoing startActivity: null` —
     * which names the bug outright.
     *
     * Filtered by **pid**, not uid: JCode's own process shares the uid, and the device's log is the
     * device's business. A platform that does refuse the reader costs one line saying so, which is
     * itself worth having in the log.
     */
    fun captureProcessLog(context: Context) {
        if (logcat != null) return
        // Fully qualified: an `android.os.Process` import would shadow the `java.lang.Process` the
        // reader itself is.
        val pid = android.os.Process.myPid()
        val process = runCatching {
            ProcessBuilder("logcat", "-v", "threadtime", "--pid=$pid", "-T", "1", "*:I")
                .redirectErrorStream(true)
                .start()
        }.getOrElse { error ->
            append(context, 'W', TAG, "the device cannot read its own system log: ${error.message}")
            return
        }
        logcat = process
        thread(isDaemon = true, name = "jcode-vdevice-logcat") {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (line.startsWith(BANNER)) return@forEachLine
                    val entry = ENTRY.find(line)
                    if (entry == null) {
                        // A stack trace's continuation lines arrive without a header of their own,
                        // so an unmatched line is kept rather than dropped — otherwise a crash comes
                        // back as its first frame and nothing else.
                        append(context, 'I', SYSTEM_TAG, line)
                    } else {
                        val (level, tag, message) = entry.destructured
                        append(context, level.first(), tag.trim(), message)
                    }
                }
            }
            append(context, 'W', TAG, "the device's system log reader ended")
        }
    }

    @Volatile
    private var logcat: Process? = null

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

    /** What `logcat` prints when it switches buffers; not an entry, and not worth a line. */
    private const val BANNER = "---------"

    /** Where a line the reader could not parse is filed, so it is still visible as itself. */
    private const val SYSTEM_TAG = "system"

    /** `MM-DD HH:MM:SS.mmm  pid  tid L TAG: message`, which is `logcat -v threadtime`. */
    private val ENTRY =
        Regex("""^\d{2}-\d{2} [\d:.]+\s+\d+\s+\d+ ([VDIWEFS]) (.{1,80}?)\s*: (.*)$""")
}
