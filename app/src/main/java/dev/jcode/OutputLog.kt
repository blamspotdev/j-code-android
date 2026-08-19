package dev.jcode

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OutputKind { Stdout, Header, Info, Error }

/** One line in the Output channel. */
data class OutputLine(val text: String, val kind: OutputKind)

/**
 * App-wide, read-only Output channel rendered in the right drawer's Output tab. It collects:
 *  - app activity / status (the snackbar message stream, via [append]),
 *  - run lifecycle headers ([beginRun]),
 *  - and the actual build/run logs teed from the run terminals' PTY output ([appendRaw]),
 *    ANSI-stripped and assembled into lines.
 * Interactive typing still lives in the Terminal; this is the quiet, scrollable log of what ran.
 */
object OutputLog {
    private const val MAX_LINES = 2000

    private val lock = Any()
    private val buf = ArrayDeque<OutputLine>()
    private val partial = HashMap<String, StringBuilder>() // per-session incomplete line
    // Read unsynchronized on the PTY reader thread (appendRaw); @Volatile so it sees captureSession's
    // writes from the main thread — otherwise teed run output is dropped against a stale empty set.
    @Volatile
    private var captured = emptySet<String>()              // session ids whose output we mirror

    private val _lines = MutableStateFlow<List<OutputLine>>(emptyList())
    val lines: StateFlow<List<OutputLine>> = _lines.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val flushScheduled = AtomicBoolean(false)

    /** Start mirroring a new run: drop a header and reset which sessions are captured. */
    fun beginRun(label: String) {
        synchronized(lock) {
            captured = emptySet()
            partial.clear()
            addLine(OutputLine("── Running $label · ${timeNow()} ──", OutputKind.Header))
        }
        scheduleFlush()
    }

    /** Begin capturing a run terminal's output (called as each run terminal is spawned). */
    fun captureSession(sessionId: String) {
        synchronized(lock) { captured = captured + sessionId }
    }

    /** Append an app-authored status line (run lifecycle, snackbar messages, errors). */
    fun append(text: String, kind: OutputKind = OutputKind.Info) {
        synchronized(lock) { addLine(OutputLine(text, kind)) }
        scheduleFlush()
    }

    /** Mirror a run session's raw PTY output (no-op unless [sessionId] is being captured). */
    fun appendRaw(sessionId: String, data: ByteArray, length: Int) {
        if (sessionId !in captured) return
        val chunk = String(data, 0, length, Charsets.UTF_8)
        synchronized(lock) {
            val sb = partial.getOrPut(sessionId) { StringBuilder() }
            sb.append(chunk)
            var nl = sb.indexOf("\n")
            while (nl >= 0) {
                addLine(OutputLine(cleanLine(sb.substring(0, nl)), OutputKind.Stdout))
                sb.delete(0, nl + 1)
                nl = sb.indexOf("\n")
            }
            // A long line that only uses carriage returns (progress bars) would never flush; cap it.
            if (sb.length > 4096) {
                addLine(OutputLine(cleanLine(sb.toString()), OutputKind.Stdout))
                sb.setLength(0)
            }
        }
        scheduleFlush()
    }

    fun clear() {
        synchronized(lock) { buf.clear(); partial.clear() }
        scheduleFlush()
    }

    private fun addLine(line: OutputLine) {
        buf.addLast(line)
        while (buf.size > MAX_LINES) buf.removeFirst()
    }

    private fun scheduleFlush() {
        if (flushScheduled.compareAndSet(false, true)) {
            main.postDelayed({
                flushScheduled.set(false)
                _lines.value = synchronized(lock) { buf.toList() }
            }, 60)
        }
    }

    // Strip ANSI/VT escapes + control chars; collapse \r overwrites to the final segment of the line.
    private fun cleanLine(raw: String): String {
        // Terminal lines are CRLF: drop the trailing CR FIRST, otherwise the \r-overwrite collapse
        // below takes the (empty) text after it and blanks the whole line.
        var s = ANSI.replace(raw, "").removeSuffix("\r")
        val cr = s.lastIndexOf('\r') // a mid-line CR is a progress overwrite: keep what's after it
        if (cr >= 0) s = s.substring(cr + 1)
        s = s.filter { it == '\t' || it.code >= 0x20 }
        return s.trimEnd()
    }

    private fun timeNow(): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())

    // ESC/BEL built from code points (no literal control bytes in source). Every alternative starts
    // with ESC, so plain bracketed text in the build output ("[setup]", "[1/2]") is left untouched.
    private val esc = 27.toChar().toString()
    private val bel = 7.toChar().toString()
    private val ANSI = Regex(
        "$esc\\[[0-?]*[ -/]*[@-~]" +                 // CSI: ESC [ ... final
            "|$esc\\][^$bel$esc]*(?:$bel|$esc\\\\)" + // OSC: ESC ] ... BEL or ST
            "|$esc[=>]" +                            // keypad mode
            "|$esc[()][0-9A-Za-z]"                   // charset designators
    )
}
