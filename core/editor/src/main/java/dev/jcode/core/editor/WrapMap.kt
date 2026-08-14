package dev.jcode.core.editor

import dev.jcode.core.buffer.NativeWrap
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
 * Built once per `(snapshot, charsPerRow)` and cached by the view. The full-file scan is O(document)
 * and re-runs on edits / width / font changes, so it runs natively (wrap_map.cpp, over the snapshot's
 * bytes) whenever the buffer is on the native path; [buildWithKotlin] is the reference implementation
 * behind that port and still serves Kotlin-path snapshots.
 *
 * Both paths produce the same packed layout: a single int array holding `cumRows`, `lineLen` and a
 * flat `rowStarts`, read in place by base offset. Row k of line l starts at column
 * `rowStarts[cumRows[l] + k]`; the line's last row ends at `lineLen[l]`.
 */
class WrapMap(
    snapshot: Snapshot,
    val charsPerRow: Int,
) {
    private val data: IntArray = NativeWrap.build(snapshot, charsPerRow) ?: buildWithKotlin(snapshot, charsPerRow)

    private val lineCount: Int = data[0]
    private val cumRowsBase: Int = NativeWrap.CUM_ROWS_BASE
    private val lineLenBase: Int = cumRowsBase + lineCount + 1
    private val rowStartsBase: Int = lineLenBase + lineCount

    val totalRows: Int get() = data[1].coerceAtLeast(1)

    private fun cumRows(line: Int): Int = data[cumRowsBase + line]

    /** First flat visual row of a logical line. */
    fun firstRowOf(line: Int): Int = cumRows(line.coerceIn(0, lineCount))

    /** The flat visual row that shows (line, column). */
    fun rowOf(line: Int, column: Int): Int {
        val l = line.coerceIn(0, lineCount - 1)
        val first = cumRows(l)
        val rowCount = cumRows(l + 1) - first
        // Row starts ascend strictly, so the last row starting at or before `column` is a search.
        var lo = 0
        var hi = rowCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (data[rowStartsBase + first + mid] <= column) lo = mid else hi = mid - 1
        }
        return first + lo
    }

    /** Resolve a flat visual row to its logical line and the [startColumn, endColumn) it displays. */
    fun rowToLine(row: Int): RowSpan {
        val r = row.coerceIn(0, totalRows - 1)
        var lo = 0
        var hi = lineCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (cumRows(mid) <= r) lo = mid else hi = mid - 1
        }
        val line = lo
        val first = cumRows(line)
        val rowCount = cumRows(line + 1) - first
        val rowInLine = (r - first).coerceIn(0, rowCount - 1)
        val startCol = data[rowStartsBase + first + rowInLine]
        val endCol = if (rowInLine + 1 < rowCount) {
            data[rowStartsBase + first + rowInLine + 1]
        } else {
            data[lineLenBase + line]
        }
        return RowSpan(line, startCol, endCol)
    }

    data class RowSpan(val line: Int, val startColumn: Int, val endColumn: Int)

    companion object {
        /** Columns that fit the text area, for a monospace advance. 0 when it can't be computed. */
        fun charsPerRow(textAreaPx: Float, advancePx: Float): Int =
            if (advancePx <= 0f || textAreaPx <= 0f) 0 else (textAreaPx / advancePx).toInt().coerceAtLeast(1)

        /**
         * Reference build, mirrored byte-for-byte by wrap_map.cpp and fuzzed against it by
         * WrapMapDifferentialTest. Produces the packed layout described on the class.
         */
        internal fun buildWithKotlin(snapshot: Snapshot, charsPerRow: Int): IntArray {
            val lineCount = snapshot.lineCount.coerceAtLeast(1)
            val cumRows = IntArray(lineCount + 1)
            val lineLen = IntArray(lineCount)
            val rowStarts = ArrayList<Int>(lineCount)

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
                    cumRows[l] = row
                    row += appendRowStarts(text, charsPerRow, rowStarts)
                }
                line += n
            }
            cumRows[lineCount] = row

            val out = IntArray(NativeWrap.CUM_ROWS_BASE + (lineCount + 1) + lineCount + row)
            out[0] = lineCount
            out[1] = row
            var w = NativeWrap.CUM_ROWS_BASE
            for (v in cumRows) out[w++] = v
            for (v in lineLen) out[w++] = v
            for (v in rowStarts) out[w++] = v
            return out
        }

        /** Appends [text]'s row start columns to [out]; returns how many rows it occupies. */
        private fun appendRowStarts(text: String, charsPerRow: Int, out: MutableList<Int>): Int {
            val len = text.length
            if (charsPerRow <= 0 || len <= charsPerRow) {
                out.add(0)
                return 1
            }
            var rows = 1
            out.add(0)
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
                out.add(start)
                rows++
            }
            return rows
        }

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
        fun charIndexToByteCol(text: String, charIndex: Int): Int {
            // Counted in place: this sits on the per-line hit-test and render paths, where the old
            // substring().toByteArray() cost two allocations per call.
            val end = charIndex.coerceIn(0, text.length)
            var bytes = 0
            var i = 0
            while (i < end) {
                val cp = text.codePointAt(i)
                val chars = Character.charCount(cp)
                // A cut through a surrogate pair leaves a lone high surrogate, which String.getBytes
                // encodes as a single '?' — keep that so the byte column matches the old conversion.
                if (i + chars > end) {
                    bytes += 1
                    break
                }
                bytes += if (cp < 0x80) 1 else if (cp < 0x800) 2 else if (cp < 0x10000) 3 else 4
                i += chars
            }
            return bytes
        }
    }
}
