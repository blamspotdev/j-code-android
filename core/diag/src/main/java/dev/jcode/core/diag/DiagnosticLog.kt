package dev.jcode.core.diag

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Suggested file name for a shared/exported diagnostic log. */
const val DIAGNOSTIC_LOG_FILE_NAME = "jcode-diagnostic.log"

/**
 * How much a session records. Each level includes the ones above it. [logcatPriority] is the same
 * choice applied to the captured system log, so the two stay in step.
 */
enum class DiagLevel(val label: String, val logcatPriority: String) {
    Errors("Errors only", "E"),
    Normal("Normal", "I"),
    Verbose("Verbose", "V"),
}

/** Which part of the app a line came from — the first filter to reach for when reading a log. */
enum class DiagArea {
    App,
    Terminal,
    Environment,
    Editor,
    Extensions,
    LanguageTools,
}

/**
 * Opt-in diagnostic logging. **Off by default and never enabled implicitly** — the user turns it on
 * in Settings → Diagnostics when they have something to report, and turns it off again afterwards.
 *
 * Three sources feed one file so a report is a single attachment:
 *
 * 1. Explicit [event] / [failure] calls from the app.
 * 2. The app's logcat, when [captureSystemLog] is on. The log daemon hands an unprivileged reader
 *    only its own **uid**'s entries, so this needs no permission — and since proot and every distro
 *    process share that uid, the Linux environment's output lands here too, not just the app's. It
 *    picks up every existing `android.util.Log` call for free.
 * 3. Uncaught exceptions, when [captureCrashes] is on. The previous handler still runs, so the
 *    normal crash dialog and any other reporter are unaffected.
 *
 * Every message is **path-redacted** before it is written (see [redact]) — never optional, because
 * a log is meant to be shareable.
 */
object DiagnosticLog {
    private const val LOG_DIR = "logs"
    private const val CURRENT = DIAGNOSTIC_LOG_FILE_NAME
    private const val PREVIOUS = "jcode-diagnostic.1.log"
    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val RECENT_LINES = 600

    private val timestamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private val lock = Any()
    private val recent = ArrayDeque<String>(RECENT_LINES)

    @Volatile private var enabled = false
    @Volatile private var level = DiagLevel.Normal
    @Volatile private var captureCrashes = false
    @Volatile private var logDir: File? = null
    @Volatile private var redactions: List<Pair<String, String>> = emptyList()

    private var logcatProcess: Process? = null
    /** The filter the running logcat was started with, so a level change restarts it. */
    private var logcatFilter: String? = null
    private var crashHandlerInstalled = false

    /** True while a session is recording — drives the Settings summary and the status indicator. */
    val isRecording: Boolean get() = enabled

    /** Where the files live, for the Settings "location" row. Null until [configure] has run. */
    val directory: File? get() = logDir

    /**
     * Apply the user's Settings → Diagnostics choices. Turning [enabled] on starts a new session and
     * writes the environment header; turning it off stops the logcat reader and leaves the files in
     * place so they can still be exported.
     */
    fun configure(
        context: Context,
        enabled: Boolean,
        level: DiagLevel,
        captureSystemLog: Boolean,
        captureCrashes: Boolean,
    ) {
        val appContext = context.applicationContext
        synchronized(lock) {
            val wasEnabled = this.enabled
            this.level = level
            this.captureCrashes = enabled && captureCrashes
            if (enabled && logDir == null) {
                logDir = resolveLogDir(appContext)
                redactions = buildRedactions(appContext)
            }
            this.enabled = enabled
            if (enabled && !wasEnabled) writeSessionHeader(appContext)
            if (this.captureCrashes) installCrashHandler()
            if (enabled && captureSystemLog) startSystemLogCapture() else stopSystemLogCapture()
            if (!enabled) append("--- logging stopped ---")
        }
    }

    /** Record something that happened. [detail] is evaluated only when the level admits the line. */
    inline fun event(area: DiagArea, tag: String, detail: () -> String) {
        if (!admits(DiagLevel.Normal)) return
        write(area, "INFO", tag, detail())
    }

    /** Record a fine-grained step — only kept at Verbose. */
    inline fun trace(area: DiagArea, tag: String, detail: () -> String) {
        if (!admits(DiagLevel.Verbose)) return
        write(area, "TRACE", tag, detail())
    }

    /** Record something that went wrong, with the exception chain when there is one. */
    fun failure(area: DiagArea, tag: String, detail: String, error: Throwable? = null) {
        if (!admits(DiagLevel.Errors)) return
        write(area, "ERROR", tag, if (error == null) detail else "$detail\n${error.stackTraceToString()}")
    }

    /** Whether a line at [required] would be kept. Public so the inline helpers can short-circuit. */
    fun admits(required: DiagLevel): Boolean = enabled && level.ordinal >= required.ordinal

    /** Public so the inline helpers can reach it; prefer [event] / [trace] / [failure]. */
    fun write(area: DiagArea, severity: String, tag: String, message: String) {
        if (!enabled) return
        append("${timestamp.format(Date())} $severity ${area.name}/$tag: ${redact(message)}")
    }

    /** The tail of the current session, newest last — what the in-app viewer shows. */
    fun recentLines(): List<String> = synchronized(lock) { recent.toList() }

    /** The file to export. Null when nothing has been recorded yet. */
    fun currentFile(): File? = logDir?.let { File(it, CURRENT) }?.takeIf { it.isFile && it.length() > 0 }

    /** Total bytes on disk across the current and rolled-over files. */
    fun sizeBytes(): Long = logDir?.let { dir ->
        listOf(File(dir, CURRENT), File(dir, PREVIOUS)).sumOf { if (it.isFile) it.length() else 0L }
    } ?: 0L

    /** Drop everything recorded so far, keeping the session running. */
    fun clear() {
        synchronized(lock) {
            recent.clear()
            logDir?.let { dir ->
                runCatching { File(dir, CURRENT).delete() }
                runCatching { File(dir, PREVIOUS).delete() }
            }
        }
    }

    /**
     * Replace absolute paths with stable tokens. The app's own data directory, the shared JCode
     * root and the external-storage root all carry the device owner's layout (and sometimes a
     * profile id), none of which helps read a log.
     */
    fun redact(message: String): String {
        var out = message
        for ((from, to) in redactions) out = out.replace(from, to)
        return out
    }

    // --- internals ---

    private fun append(line: String) {
        synchronized(lock) {
            if (recent.size >= RECENT_LINES) recent.removeFirst()
            recent.addLast(line)
            val dir = logDir ?: return
            runCatching {
                val file = File(dir, CURRENT)
                if (file.length() > MAX_BYTES) {
                    val previous = File(dir, PREVIOUS)
                    previous.delete()
                    file.renameTo(previous)
                }
                file.appendText(line + "\n")
            }
        }
    }

    /**
     * Prefer the shared `JCode/logs` folder: a report there can be attached from any file manager
     * and pulled over adb without the app being debuggable. Falls back to the app's own external
     * files directory (always writable, no permission) and finally to internal storage.
     */
    private fun resolveLogDir(context: Context): File {
        val candidates = listOfNotNull(
            File(Environment.getExternalStorageDirectory(), "JCode/$LOG_DIR"),
            context.getExternalFilesDir(null)?.let { File(it, LOG_DIR) },
            File(context.filesDir, LOG_DIR),
        )
        return candidates.firstOrNull { dir ->
            runCatching { dir.mkdirs(); dir.isDirectory && dir.canWrite() }.getOrDefault(false)
        } ?: File(context.filesDir, LOG_DIR).apply { runCatching { mkdirs() } }
    }

    private fun buildRedactions(context: Context): List<Pair<String, String>> = buildList {
        runCatching { context.dataDir.absolutePath }.getOrNull()?.let { add(it to "<app-data>") }
        // The same directory reached the other way: framework APIs report /data/user/0/<pkg> while
        // proot and the guest tooling log the /data/data/<pkg> form, so both have to be covered.
        add("/data/data/${context.packageName}" to "<app-data>")
        add(context.filesDir.absolutePath to "<app-files>")
        val external = Environment.getExternalStorageDirectory().absolutePath
        add("$external/JCode" to "<jcode>")
        add(external to "<storage>")
    }.sortedByDescending { it.first.length } // longest first, so nested roots are not half-replaced

    private fun writeSessionHeader(context: Context) {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        val metrics = context.resources.displayMetrics
        val dir = logDir
        val free = runCatching { (dir?.freeSpace ?: 0L) / (1024 * 1024) }.getOrDefault(0L)
        append("=== JCode diagnostic session ===")
        append("app        ${context.packageName} $version")
        append("device     ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        append("android    ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) ${Build.VERSION.INCREMENTAL}")
        append("abi        ${Build.SUPPORTED_ABIS.joinToString()}")
        append("display    ${metrics.widthPixels}x${metrics.heightPixels} density ${metrics.density}")
        append("locale     ${Locale.getDefault()}")
        append("log dir    ${redact(dir?.absolutePath.orEmpty())} ($free MB free)")
        append("detail     ${level.label}")
        append("--- logging started ---")
    }

    /**
     * Tee the app's logcat into the file.
     *
     * Deliberately **not** filtered by pid: the log daemon already restricts an unprivileged reader
     * to its own **uid**, and proot — plus every distro process under it — runs as a separate pid
     * sharing that uid. A `--pid` filter would have excluded exactly the environment the log is most
     * often needed for, while adding nothing to privacy (another app's entries are unreachable
     * either way). The detail level maps onto logcat's own priority filter so a Verbose session is
     * the only one that pays for the noise.
     */
    private fun startSystemLogCapture() {
        val filter = "*:${level.logcatPriority}"
        if (logcatProcess != null && filter == logcatFilter) return
        stopSystemLogCapture()
        logcatFilter = filter
        val started = runCatching {
            ProcessBuilder("logcat", "-v", "threadtime", "-T", "1", filter)
                .redirectErrorStream(true)
                .start()
        }.getOrElse { error ->
            append("${timestamp.format(Date())} ERROR App/diag: system log capture unavailable: ${error.message}")
            return
        }
        logcatProcess = started
        thread(isDaemon = true, name = "jcode-diag-logcat") {
            runCatching {
                started.inputStream.bufferedReader().forEachLine { line ->
                    if (!enabled) return@forEachLine
                    append("logcat ${redact(line)}")
                }
            }
        }
    }

    private fun stopSystemLogCapture() {
        logcatProcess?.let { runCatching { it.destroy() } }
        logcatProcess = null
        logcatFilter = null
    }

    private fun installCrashHandler() {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (captureCrashes) {
                runCatching {
                    write(DiagArea.App, "FATAL", "crash", "on \"${thread.name}\"\n${error.stackTraceToString()}")
                }
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
