package dev.jcode.core.term

import java.lang.ref.Cleaner
import java.nio.ByteBuffer

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
     * Feed input data to the parser.
     * The parser will process escape sequences and update the screen state.
     */
    fun feed(data: ByteArray) {
        check(nativeHandle != 0L) { "Parser is closed" }
        nativeFeed(data)
    }
    
    /**
     * Feed input data from a ByteBuffer.
     */
    fun feed(buffer: ByteBuffer, length: Int) {
        check(nativeHandle != 0L) { "Parser is closed" }
        val data = ByteArray(length)
        buffer.get(data, 0, length)
        nativeFeed(data)
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
            return nativeGetRows()
        }
    
    /**
     * Get the number of columns in the terminal.
     */
    val cols: Int
        get() {
            check(nativeHandle != 0L) { "Parser is closed" }
            return nativeGetCols()
        }
    
    /**
     * Get the current cursor row (0-indexed).
     */
    val cursorRow: Int
        get() {
            check(nativeHandle != 0L) { "Parser is closed" }
            return nativeGetCursorRow()
        }
    
    /**
     * Get the current cursor column (0-indexed).
     */
    val cursorCol: Int
        get() {
            check(nativeHandle != 0L) { "Parser is closed" }
            return nativeGetCursorCol()
        }
    
    /**
     * Check if the cursor is visible.
     */
    val isCursorVisible: Boolean
        get() {
            check(nativeHandle != 0L) { "Parser is closed" }
            return nativeIsCursorVisible()
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
            return nativeGetScrollbackSize()
        }
    
    /**
     * Get the character at a specific cell, truncated to a 16-bit [Char].
     * Prefer [getCellCodePoint] for correct rendering of non-BMP characters (emoji).
     */
    fun getCellChar(row: Int, col: Int): Char {
        check(nativeHandle != 0L) { "Parser is closed" }
        return nativeGetCellChar(row, col).toChar()
    }

    /**
     * Get the full Unicode codepoint at a specific cell. The native parser decodes UTF-8, so this is
     * the real codepoint (may be > 0xFFFF for emoji / supplementary-plane characters).
     */
    fun getCellCodePoint(row: Int, col: Int): Int {
        check(nativeHandle != 0L) { "Parser is closed" }
        return nativeGetCellChar(row, col)
    }
    
    /**
     * Get the foreground color of a cell.
     * Returns -1 for default color, 0-255 for indexed color, or RGB value for truecolor.
     */
    fun getCellFgColor(row: Int, col: Int): Int {
        check(nativeHandle != 0L) { "Parser is closed" }
        return nativeGetCellFgColor(row, col)
    }
    
    /**
     * Get the background color of a cell.
     * Returns -1 for default color, 0-255 for indexed color, or RGB value for truecolor.
     */
    fun getCellBgColor(row: Int, col: Int): Int {
        check(nativeHandle != 0L) { "Parser is closed" }
        return nativeGetCellBgColor(row, col)
    }
    
    /**
     * Get the color mode for foreground.
     * 0 = default, 1 = indexed (256-color), 2 = truecolor
     */
    fun getCellFgMode(row: Int, col: Int): Int {
        check(nativeHandle != 0L) { "Parser is closed" }
        return nativeGetCellFgMode(row, col)
    }
    
    /**
     * Get the color mode for background.
     * 0 = default, 1 = indexed (256-color), 2 = truecolor
     */
    fun getCellBgMode(row: Int, col: Int): Int {
        check(nativeHandle != 0L) { "Parser is closed" }
        return nativeGetCellBgMode(row, col)
    }
    
    /**
     * Get the attributes of a cell (bold, italic, underline, etc.).
     */
    fun getCellAttrs(row: Int, col: Int): Int {
        check(nativeHandle != 0L) { "Parser is closed" }
        return nativeGetCellAttrs(row, col)
    }
    
    /**
     * Clear the dirty flag for all rows.
     * Call this after rendering to track which rows need updating.
     */
    fun clearDirty() {
        check(nativeHandle != 0L) { "Parser is closed" }
        nativeClearDirty()
    }
    
    /**
     * Check if a specific row is dirty (needs redraw).
     */
    fun isRowDirty(row: Int): Boolean {
        check(nativeHandle != 0L) { "Parser is closed" }
        return nativeIsRowDirty(row)
    }
    
    /**
     * Check if a full screen refresh is needed.
     */
    val needsFullRefresh: Boolean
        get() {
            check(nativeHandle != 0L) { "Parser is closed" }
            return nativeNeedsFullRefresh()
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

    // Native methods
    private external fun nativeCreate(rows: Int, cols: Int): Long
    private external fun nativeFeed(data: ByteArray)
    private external fun nativeResize(rows: Int, cols: Int)
    private external fun nativeReset()
    private external fun nativeGetRows(): Int
    private external fun nativeGetCols(): Int
    private external fun nativeGetCursorRow(): Int
    private external fun nativeGetCursorCol(): Int
    private external fun nativeIsCursorVisible(): Boolean
    private external fun nativeIsAlternateScreen(): Boolean
    private external fun nativeGetScrollbackSize(): Int
    private external fun nativeGetCellChar(row: Int, col: Int): Int
    private external fun nativeGetCellFgColor(row: Int, col: Int): Int
    private external fun nativeGetCellBgColor(row: Int, col: Int): Int
    private external fun nativeGetCellFgMode(row: Int, col: Int): Int
    private external fun nativeGetCellBgMode(row: Int, col: Int): Int
    private external fun nativeGetCellAttrs(row: Int, col: Int): Int
    private external fun nativeClearDirty()
    private external fun nativeIsRowDirty(row: Int): Boolean
    private external fun nativeNeedsFullRefresh(): Boolean
    
    companion object {
        private val cleaner = Cleaner.create()

        init {
            System.loadLibrary("jcode_vt")
        }

        @JvmStatic
        private external fun nativeCloseByHandle(handle: Long)

        // Cell attribute flags
        const val ATTR_BOLD = 1 shl 0
        const val ATTR_DIM = 1 shl 1
        const val ATTR_ITALIC = 1 shl 2
        const val ATTR_UNDERLINE = 1 shl 3
        const val ATTR_BLINK = 1 shl 4
        const val ATTR_INVERSE = 1 shl 5
        const val ATTR_HIDDEN = 1 shl 6
        const val ATTR_STRIKETHROUGH = 1 shl 7
    }
}
