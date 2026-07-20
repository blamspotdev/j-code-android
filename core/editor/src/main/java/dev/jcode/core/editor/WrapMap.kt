package dev.jcode.core.editor

import dev.jcode.core.buffer.Snapshot

/**
 * Soft word-wrap layout: maps between logical `(line, column)` and flat **visual rows**.
 *
 * When word wrap is on, one logical line renders as one-or-more visual rows, each at most
 * [charsPerRow] UTF-16 chars wide (a monospace column count derived from the text-area width). A row
 * breaks after the last space within its width where one exists — so words stay intact — otherwise it
 * hard-breaks at the width. Every editor coordinate transform (draw loop, hit-testing, caret follow,
 * scroll range) routes through this one map so rendering and hit-testing stay consistent.
 *
 * Built once per `(snapshot, charsPerRow)` and cached by the view; the full-file scan is O(document)
 * and re-runs on edits / width / font changes (acceptable for typical files, heavier for very large
 * ones — the view only builds it while wrap is enabled).
 */
class WrapMap(
    private val snapshot: Snapshot,
    val charsPerRow: Int,
) {
    private val lineCount: Int = snapshot.lineCount.coerceAtLeast(1)

    // rowStartCols[line] = start column of each visual row within that logical line (index 0 is 0).
    private val rowStartCols: Array<IntArray> = Array(lineCount) { EMPTY }

    // lineLen[line] = UTF-16 length of the logical line (its last row ends here).
    private val lineLen: IntArray = IntArray(lineCount)

    // cumRows[line] = first flat visual-row index of the line; cumRows[lineCount] = total rows.
    private val cumRows: IntArray = IntArray(lineCount + 1)

    init {
        var row = 0
        var line = 0
        val batch = 1024
        while (line < lineCount) {
            val n = minOf(batch, lineCount - line)
            val window = snapshot.readLines(line, n)
            for (i in 0 until n) {
                val l = line + i
                val text = if (window.contains(l)) window.text(l) else ""
                lineLen[l] = text.length
                val starts = computeRowStarts(text)
                rowStartCols[l] = starts
                cumRows[l] = row
                row += starts.size
            }
            line += n
        }
        cumRows[lineCount] = row
    }

    val totalRows: Int get() = cumRows[lineCount].coerceAtLeast(1)

    private fun computeRowStarts(text: String): IntArray {
        val len = text.length
        if (charsPerRow <= 0 || len <= charsPerRow) return SINGLE
        val starts = ArrayList<Int>()
        starts.add(0)
        var start = 0
        while (start + charsPerRow < len) {
            var hardEnd = start + charsPerRow
            // Never hard-break between a surrogate pair (a lone surrogate renders as tofu and
            // misencodes in byte conversion) — back off onto the pair's leading unit.
            if (Character.isLowSurrogate(text[hardEnd])) hardEnd--
            // Prefer breaking just after the last whitespace in (start, hardEnd] so words stay whole.
            var brk = -1
            var j = hardEnd
            while (j > start) {
                val c = text[j - 1]
                if (c == ' ' || c == '\t') { brk = j; break }
                j--
            }
            var next = if (brk > start) brk else hardEnd
            if (next <= start) next = start + charsPerRow // safety: always advance past `start`
            start = next
            starts.add(start)
        }
        return starts.toIntArray()
    }

    /** First flat visual row of a logical line. */
    fun firstRowOf(line: Int): Int = cumRows[line.coerceIn(0, lineCount)]

    /** The flat visual row that shows (line, column). */
    fun rowOf(line: Int, column: Int): Int {
        val l = line.coerceIn(0, lineCount - 1)
        val starts = rowStartCols[l]
        var idx = 0
        for (k in starts.indices) {
            if (starts[k] <= column) idx = k else break
        }
        return cumRows[l] + idx
    }

    /** Resolve a flat visual row to its logical line and the [startColumn, endColumn) it displays. */
    fun rowToLine(row: Int): RowSpan {
        val r = row.coerceIn(0, totalRows - 1)
        var lo = 0
        var hi = lineCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (cumRows[mid] <= r) lo = mid else hi = mid - 1
        }
        val line = lo
        val starts = rowStartCols[line]
        val rowInLine = (r - cumRows[line]).coerceIn(0, starts.size - 1)
        val startCol = starts[rowInLine]
        val endCol = if (rowInLine + 1 < starts.size) starts[rowInLine + 1] else lineLen[line]
        return RowSpan(line, startCol, endCol)
    }

    data class RowSpan(val line: Int, val startColumn: Int, val endColumn: Int)

    companion object {
        private val EMPTY = IntArray(0)
        private val SINGLE = intArrayOf(0)

        /** Columns that fit the text area, for a monospace advance. 0 when it can't be computed. */
        fun charsPerRow(textAreaPx: Float, advancePx: Float): Int =
            if (advancePx <= 0f || textAreaPx <= 0f) 0 else (textAreaPx / advancePx).toInt().coerceAtLeast(1)

        // WrapMap columns are UTF-16 char indices (they index the line String for measuring/drawing),
        // but the buffer's offsetToLineColumn/lineColumnToOffset speak UTF-8 byte columns. These two
        // converters bridge that at every buffer boundary so wrap stays exact on non-ASCII lines.

        /** Char index into [text] for a UTF-8 byte column; a mid-codepoint byte snaps to the start of
         *  its codepoint so a caret never lands between a surrogate pair. */
        fun byteColToCharIndex(text: String, byteCol: Int): Int {
            if (byteCol <= 0) return 0
            var bytes = 0
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val n = if (cp < 0x80) 1 else if (cp < 0x800) 2 else if (cp < 0x10000) 3 else 4
                if (bytes + n > byteCol) return i
                bytes += n
                i += Character.charCount(cp)
                if (bytes >= byteCol) return i
            }
            return text.length
        }

        /** UTF-8 byte column for a char index into [text] (inverse of [byteColToCharIndex]). */
        fun charIndexToByteCol(text: String, charIndex: Int): Int =
            text.substring(0, charIndex.coerceIn(0, text.length)).toByteArray(Charsets.UTF_8).size
    }
}
