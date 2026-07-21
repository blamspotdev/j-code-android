package dev.jcode.core.term

import java.lang.ref.Cleaner

/**
 * VT100/xterm terminal emulator parser.
 * Wraps the native C implementation for high-performance terminal emulation.
 */
class VtParser(rows: Int, cols: Int) : AutoCloseable {

    @Volatile
    private var nativeHandle: Long = nativeCreate(rows, cols)

    private val cleanable: Cleaner.Cleanable

    init {
        if (nativeHandle == 0L) {
            throw IllegalStateException("Failed to create VT parser")
        }
        // Capture-free Cleaner safety net (no finalizer — banned by repo rules). Captures only the
        // primitive handle so the parser can be reclaimed if a caller forgets close().
        val handle = nativeHandle
        cleanable = cleaner.register(this) { if (handle != 0L) nativeCloseByHandle(handle) }
    }
    
    /**
     * Feed the first [length] bytes of [data] to the parser (defaults to the whole array).
     * The parser will process escape sequences and update the screen state. The array is not
     * retained, so callers can pass a reused read buffer without copying it per chunk.
     */
    fun feed(data: ByteArray, length: Int = data.size) {
        check(nativeHandle != 0L) { "Parser is closed" }
        nativeFeed(nativeHandle, data, length)
    }

    /**
     * Drain the JCode shell-integration OSC events (7711 open-file, 7712 tab-title, 7713
     * task-complete, 7714 open-url, 7715 nested-shell open) queued by the native parser during
     * [feed]. Each native entry is encoded "<code>;<payload>" and split at the FIRST ';' — payloads
     * may themselves contain ';' (7713's is "<token>;<exitCode>"; 7715's is
     * "open;<token>;<b64label>;<b64cwd>;<b64user>"). Returns an empty list when nothing is queued.
     */
    fun drainOsc(): List<Pair<Int, String>> {
        check(nativeHandle != 0L) { "Parser is closed" }
        val events = nativeDrainOsc(nativeHandle) ?: return emptyList()
        return events.mapNotNull { entry ->
            val sep = entry.indexOf(';')
            val code = if (sep > 0) entry.substring(0, sep).toIntOrNull() else null
            code?.let { it to entry.substring(sep + 1) }
        }
    }

    /**
     * Drain the answerback bytes (DA/DSR/CPR/DECRQM/OSC-color replies) queued by the native parser
     * during [feed], or null when nothing is pending. The session reader writes these to the PTY —
     * programs that probe the terminal (Claude Code, fzf, vim) block or degrade without the replies.
     */
    fun takeResponses(): ByteArray? {
        check(nativeHandle != 0L) { "Parser is closed" }
        return nativeTakeResponses(nativeHandle)
    }

    /**
     * Packed snapshot of the DEC private modes that shape input encoding and scroll routing.
     * Decode with the `MODE_*` constants and [modeMouseMode]/[modeMouseEncoding].
     */
    fun inputModes(): Int {
        check(nativeHandle != 0L) { "Parser is closed" }
        return nativeGetInputModes(nativeHandle)
    }

    /**
     * Resize the terminal.
     */
    fun resize(rows: Int, cols: Int) {
        check(nativeHandle != 0L) { "Parser is closed" }
        nativeResize(rows, cols)
    }
    
    /**
     * Reset the parser to initial state.
     */
    fun reset() {
        check(nativeHandle != 0L) { "Parser is closed" }
        nativeReset()
    }
    
    /**
     * Get the number of rows in the terminal.
     */
    val rows: Int
        get() {
            check(nativeHandle != 0L) { "Parser is closed" }
            return nativeGetRows(nativeHandle)
        }

    /**
     * Get the number of columns in the terminal.
     */
    val cols: Int
        get() {
            check(nativeHandle != 0L) { "Parser is closed" }
            return nativeGetCols(nativeHandle)
        }

    /**
     * Get the current cursor row (0-indexed).
     */
    val cursorRow: Int
        get() {
            check(nativeHandle != 0L) { "Parser is closed" }
            return nativeGetCursorRow(nativeHandle)
        }

    /**
     * Get the current cursor column (0-indexed).
     */
    val cursorCol: Int
        get() {
            check(nativeHandle != 0L) { "Parser is closed" }
            return nativeGetCursorCol(nativeHandle)
        }

    /**
     * Check if the cursor is visible.
     */
    val isCursorVisible: Boolean
        get() {
            check(nativeHandle != 0L) { "Parser is closed" }
            return nativeIsCursorVisible(nativeHandle)
        }
    
    /**
     * Check if the alternate screen buffer is active.
     */
    val isAlternateScreen: Boolean
        get() {
            check(nativeHandle != 0L) { "Parser is closed" }
            return nativeIsAlternateScreen()
        }

    /**
     * Number of scrollback lines available above the live screen (0 on the alternate screen).
     * Cells in scrollback are read with a negative row: row -1 is the most recently scrolled-off
     * line, row -[scrollbackSize] is the oldest.
     */
    val scrollbackSize: Int
        get() {
            check(nativeHandle != 0L) { "Parser is closed" }
            return nativeGetScrollbackSize(nativeHandle)
        }

    /**
     * Get the full Unicode codepoint at a specific cell. The native parser decodes UTF-8, so this is
     * the real codepoint (may be > 0xFFFF for emoji / supplementary-plane characters).
     */
    fun getCellCodePoint(row: Int, col: Int): Int {
        check(nativeHandle != 0L) { "Parser is closed" }
        return nativeGetCellChar(nativeHandle, row, col)
    }

    /**
     * Read a whole row of cells in a single JNI call — [CELL_STRIDE] ints per cell:
     * `out[i*4]` = codepoint (0 = continuation cell of a wide character — skip it), `out[i*4+1]` =
     * fg, `out[i*4+2]` = bg (-1 default, 0-255 indexed, packed RGB truecolor), `out[i*4+3]` =
     * packed meta decoded via [metaFgMode]/[metaBgMode]/[metaAttrs]. Returns the number of cells
     * written (bounded by the screen width and `out.size / 4`). Row semantics match
     * [getCellCodePoint]: negative rows address scrollback.
     */
    fun readRow(row: Int, out: IntArray): Int {
        check(nativeHandle != 0L) { "Parser is closed" }
        return nativeReadRow(nativeHandle, row, out)
    }

    /**
     * Read [rowCount] whole rows starting at logical [topRow] in ONE JNI crossing (the renderer's
     * per-frame path — one crossing per frame instead of one per row). Cell encoding matches
     * [readRow]; each row occupies `cols * CELL_STRIDE` ints. Out-of-range rows pack as blanks.
     * Returns the number of rows packed.
     */
    fun readScreen(topRow: Int, rowCount: Int, out: IntArray): Int {
        check(nativeHandle != 0L) { "Parser is closed" }
        return nativeReadScreen(nativeHandle, topRow, rowCount, out)
    }

    /**
     * Monotonic total of lines ever pushed into scrollback. Unlike [scrollbackSize] it keeps
     * growing once the ring is at capacity, so a scrolled-back view can tell whether content
     * shifted underneath it (see TerminalView's onUpdate anchor + render-skip).
     */
    val scrollbackPushed: Long
        get() {
            check(nativeHandle != 0L) { "Parser is closed" }
            return nativeGetScrollbackPushed(nativeHandle)
        }

    /** Whether the native parser is still alive (false after [close]). Never throws — safe to poll
     *  from a renderer before reading cells, to avoid racing a close. */
    val isOpen: Boolean
        get() = nativeHandle != 0L

    override fun close() {
        if (nativeHandle != 0L) {
            nativeHandle = 0L
            // Runs nativeCloseByHandle(handle) exactly once and deregisters the Cleaner.
            cleanable.clean()
        }
    }

    // Native methods (rarely-called; still resolved through the instance's nativeHandle field)
    private external fun nativeCreate(rows: Int, cols: Int): Long
    private external fun nativeResize(rows: Int, cols: Int)
    private external fun nativeReset()
    private external fun nativeIsAlternateScreen(): Boolean

    companion object {
        private val cleaner = Cleaner.create()

        init {
            System.loadLibrary("jcode_vt")
        }

        @JvmStatic
        private external fun nativeCloseByHandle(handle: Long)

        // Hot-path natives are STATIC and take the parser handle directly, so the JNI side never
        // resolves the nativeHandle field reflectively (GetObjectClass+GetFieldID) per call.
        @JvmStatic
        private external fun nativeFeed(handle: Long, data: ByteArray, length: Int)
        @JvmStatic
        private external fun nativeDrainOsc(handle: Long): Array<String>?
        @JvmStatic
        private external fun nativeGetRows(handle: Long): Int
        @JvmStatic
        private external fun nativeGetCols(handle: Long): Int
        @JvmStatic
        private external fun nativeGetCursorRow(handle: Long): Int
        @JvmStatic
        private external fun nativeGetCursorCol(handle: Long): Int
        @JvmStatic
        private external fun nativeIsCursorVisible(handle: Long): Boolean
        @JvmStatic
        private external fun nativeGetScrollbackSize(handle: Long): Int
        @JvmStatic
        private external fun nativeGetScrollbackPushed(handle: Long): Long
        @JvmStatic
        private external fun nativeGetCellChar(handle: Long, row: Int, col: Int): Int
        @JvmStatic
        private external fun nativeReadRow(handle: Long, row: Int, out: IntArray): Int
        @JvmStatic
        private external fun nativeReadScreen(handle: Long, topRow: Int, rowCount: Int, out: IntArray): Int
        @JvmStatic
        private external fun nativeTakeResponses(handle: Long): ByteArray?
        @JvmStatic
        private external fun nativeGetInputModes(handle: Long): Int

        // Cell attribute flags
        const val ATTR_BOLD = 1 shl 0
        const val ATTR_DIM = 1 shl 1
        const val ATTR_ITALIC = 1 shl 2
        const val ATTR_UNDERLINE = 1 shl 3
        const val ATTR_BLINK = 1 shl 4
        const val ATTR_INVERSE = 1 shl 5
        const val ATTR_HIDDEN = 1 shl 6
        const val ATTR_STRIKETHROUGH = 1 shl 7

        // [readRow] layout: ints per cell, and decoders for the packed meta int at index i*4+3.
        const val CELL_STRIDE = 4
        fun metaFgMode(meta: Int): Int = meta and 0x3
        fun metaBgMode(meta: Int): Int = (meta shr 2) and 0x3
        fun metaAttrs(meta: Int): Int = meta shr 4

        // [inputModes] bit layout — mirrors VT_MODE_* in vt_parser.h.
        const val MODE_APP_CURSOR_KEYS = 1 shl 0  // ?1 DECCKM: arrows send SS3 (ESC O A) form
        const val MODE_BRACKETED_PASTE = 1 shl 1  // ?2004: wrap pastes in ESC[200~ / ESC[201~
        const val MODE_FOCUS_EVENTS = 1 shl 2     // ?1004: report focus as ESC[I / ESC[O
        const val MODE_ALT_SCROLL = 1 shl 3       // ?1007 (default on): wheel -> arrows on alt screen
        const val MODE_SYNC_OUTPUT = 1 shl 4      // ?2026: a synchronized update is open
        const val MODE_ALT_SCREEN = 1 shl 5       // alternate screen buffer active

        // Mouse tracking level from [inputModes] (ordinals of the native VtMouseMode enum).
        const val MOUSE_OFF = 0
        const val MOUSE_X10 = 1     // ?9    press only
        const val MOUSE_NORMAL = 2  // ?1000 press + release + wheel
        const val MOUSE_BUTTON = 3  // ?1002 + drag motion
        const val MOUSE_ANY = 4     // ?1003 + all motion
        fun modeMouseMode(modes: Int): Int = (modes shr 8) and 0x7

        // Mouse coordinate encoding from [inputModes] (?1005 is not recognized — X10 fallback).
        const val MOUSE_ENC_X10 = 0
        const val MOUSE_ENC_SGR = 2    // ?1006
        const val MOUSE_ENC_URXVT = 3  // ?1015
        fun modeMouseEncoding(modes: Int): Int = (modes shr 12) and 0x3
    }
}
