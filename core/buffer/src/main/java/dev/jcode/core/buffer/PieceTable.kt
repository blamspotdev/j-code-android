package dev.jcode.core.buffer

/**
 * Piece-table backing store for the pure-Kotlin buffer path.
 *
 * Content is described as a sequence of pieces referencing two byte buffers: the immutable
 * [original] bytes the buffer was opened with, and an append-only add buffer receiving every
 * insertion. Edits only split/trim the piece list, so an edit costs O(pieces) instead of
 * re-copying the whole file, and [snapshot] shares both byte buffers with the live table and
 * copies only the piece list.
 *
 * Newline positions are indexed once per buffer (the original up-front, the add buffer as text is
 * appended), so line/offset conversions are piece walks plus binary searches — never byte scans.
 *
 * Not thread-safe: callers (Buffer) serialize access.
 */
internal class PieceTable(private val original: ByteArray) {

    private val originalNewlines: IntArray = scanNewlines(original)
    private var add = ByteArray(INITIAL_ADD_CAPACITY)
    private var addLen = 0
    private var addNewlines = IntArray(16)
    private var addNewlineCount = 0
    private val pieces = ArrayList<Piece>()

    var length = 0
        private set
    var newlineTotal = 0
        private set

    init {
        if (original.isNotEmpty()) {
            pieces.add(Piece(fromAdd = false, start = 0, length = original.size, newlineFrom = 0, newlineCount = originalNewlines.size))
            length = original.size
            newlineTotal = originalNewlines.size
        }
    }

    fun insert(offset: Int, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val off = offset.coerceIn(0, length)

        val addStart = addLen
        ensureAddCapacity(bytes.size)
        System.arraycopy(bytes, 0, add, addLen, bytes.size)
        var insertedNewlines = 0
        for (i in bytes.indices) {
            if (bytes[i] == NEWLINE) {
                if (addNewlineCount == addNewlines.size) addNewlines = addNewlines.copyOf(addNewlines.size * 2)
                addNewlines[addNewlineCount++] = addStart + i
                insertedNewlines++
            }
        }
        addLen += bytes.size

        var idx = 0
        var acc = 0
        while (idx < pieces.size && acc + pieces[idx].length < off) {
            acc += pieces[idx].length
            idx++
        }

        if (idx == pieces.size) {
            pieces.add(makePiece(fromAdd = true, start = addStart, len = bytes.size))
        } else {
            val p = pieces[idx]
            val local = off - acc
            when {
                local == p.length ->
                    // Insertion right after this piece. Typing fast path: a piece that already ends
                    // exactly where the new bytes were appended just grows, keeping the piece count
                    // flat during bursts of consecutive single-character inserts.
                    if (p.fromAdd && p.start + p.length == addStart) {
                        pieces[idx] = makePiece(fromAdd = true, start = p.start, len = p.length + bytes.size)
                    } else {
                        pieces.add(idx + 1, makePiece(fromAdd = true, start = addStart, len = bytes.size))
                    }
                local == 0 -> pieces.add(idx, makePiece(fromAdd = true, start = addStart, len = bytes.size))
                else -> {
                    pieces[idx] = makePiece(p.fromAdd, p.start, local)
                    pieces.add(idx + 1, makePiece(fromAdd = true, start = addStart, len = bytes.size))
                    pieces.add(idx + 2, makePiece(p.fromAdd, p.start + local, p.length - local))
                }
            }
        }

        length += bytes.size
        newlineTotal += insertedNewlines
    }

    fun delete(startOffset: Int, endOffset: Int) {
        val s = startOffset.coerceIn(0, length)
        val e = endOffset.coerceIn(0, length)
        if (s >= e) return

        var idx = 0
        var acc = 0
        while (idx < pieces.size && acc + pieces[idx].length <= s) {
            acc += pieces[idx].length
            idx++
        }

        var remaining = e - s
        var localStart = s - acc
        var removedNewlines = 0
        while (idx < pieces.size && remaining > 0) {
            val p = pieces[idx]
            val delLen = minOf(p.length - localStart, remaining)
            removedNewlines += newlinesInPieceRange(p, localStart, localStart + delLen)
            val leftLen = localStart
            val rightLen = p.length - localStart - delLen
            when {
                leftLen == 0 && rightLen == 0 -> pieces.removeAt(idx)
                leftLen == 0 -> {
                    pieces[idx] = makePiece(p.fromAdd, p.start + delLen, rightLen)
                    idx++
                }
                rightLen == 0 -> {
                    pieces[idx] = makePiece(p.fromAdd, p.start, leftLen)
                    idx++
                }
                else -> {
                    pieces[idx] = makePiece(p.fromAdd, p.start, leftLen)
                    pieces.add(idx + 1, makePiece(p.fromAdd, p.start + leftLen + delLen, rightLen))
                    idx += 2
                }
            }
            remaining -= delLen
            localStart = 0
        }

        length -= (e - s)
        newlineTotal -= removedNewlines
    }

    /**
     * O(pieces) freeze of the current state. The byte buffers are shared, not copied: the original
     * is immutable, and the add buffer is append-only so a snapshot's pieces never reference bytes
     * written after it was taken (growth reallocates, leaving the captured array intact).
     */
    fun snapshot(): PieceSnapshot = PieceSnapshot(
        original = original,
        originalNewlines = originalNewlines,
        add = add,
        addNewlines = addNewlines,
        addNewlineCount = addNewlineCount,
        pieces = pieces.toTypedArray(),
        length = length,
        newlineTotal = newlineTotal,
    )

    private fun ensureAddCapacity(extra: Int) {
        if (addLen + extra > add.size) {
            var newSize = add.size * 2
            while (newSize < addLen + extra) newSize *= 2
            add = add.copyOf(newSize)
        }
    }

    private fun makePiece(fromAdd: Boolean, start: Int, len: Int): Piece {
        val arr = if (fromAdd) addNewlines else originalNewlines
        val n = if (fromAdd) addNewlineCount else originalNewlines.size
        val from = lowerBound(arr, n, start)
        val to = lowerBound(arr, n, start + len)
        return Piece(fromAdd, start, len, from, to - from)
    }

    private fun newlinesInPieceRange(p: Piece, fromLocal: Int, toLocal: Int): Int {
        val arr = if (p.fromAdd) addNewlines else originalNewlines
        val n = if (p.fromAdd) addNewlineCount else originalNewlines.size
        return lowerBound(arr, n, p.start + toLocal) - lowerBound(arr, n, p.start + fromLocal)
    }

    companion object {
        private const val INITIAL_ADD_CAPACITY = 256
        internal const val NEWLINE = '\n'.code.toByte()

        internal fun scanNewlines(bytes: ByteArray): IntArray {
            var count = 0
            for (b in bytes) if (b == NEWLINE) count++
            val out = IntArray(count)
            var j = 0
            for (i in bytes.indices) if (bytes[i] == NEWLINE) out[j++] = i
            return out
        }

        /** First index i in [0, n) with arr[i] >= key, else n. */
        internal fun lowerBound(arr: IntArray, n: Int, key: Int): Int {
            var lo = 0
            var hi = n
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (arr[mid] < key) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }
}

/** A run of [length] bytes at [start] in either the original buffer or the add buffer. */
internal class Piece(
    val fromAdd: Boolean,
    val start: Int,
    val length: Int,
    /** Index into the owning buffer's newline array of the first newline at/after [start]. */
    val newlineFrom: Int,
    /** Newlines inside [start, start + length). */
    val newlineCount: Int,
)

/** Immutable view of a [PieceTable] at a point in time. All reads are piece walks + binary searches. */
internal class PieceSnapshot(
    private val original: ByteArray,
    private val originalNewlines: IntArray,
    private val add: ByteArray,
    private val addNewlines: IntArray,
    private val addNewlineCount: Int,
    private val pieces: Array<Piece>,
    val length: Int,
    val newlineTotal: Int,
) {
    val lineCount: Int get() = newlineTotal + 1

    fun readRange(startOffset: Int, endOffset: Int): ByteArray {
        val s = startOffset.coerceIn(0, length)
        val e = endOffset.coerceIn(0, length)
        if (s >= e) return ByteArray(0)
        val out = ByteArray(e - s)
        var acc = 0
        var written = 0
        for (p in pieces) {
            val pieceEnd = acc + p.length
            if (pieceEnd > s) {
                val fromLocal = maxOf(0, s - acc)
                val toLocal = minOf(p.length, e - acc)
                val buf = if (p.fromAdd) add else original
                System.arraycopy(buf, p.start + fromLocal, out, written, toLocal - fromLocal)
                written += toLocal - fromLocal
                if (pieceEnd >= e) break
            }
            acc = pieceEnd
        }
        return out
    }

    fun offsetToLineColumn(offset: Int): Pair<Int, Int> {
        val off = offset.coerceIn(0, length)
        var acc = 0
        var line = 0
        var lastNewlineGlobal = -1
        for (p in pieces) {
            if (acc + p.length <= off) {
                if (p.newlineCount > 0) {
                    line += p.newlineCount
                    lastNewlineGlobal = acc + (newlineOffsetAt(p, p.newlineCount - 1) - p.start)
                }
                acc += p.length
            } else {
                val local = off - acc
                val arr = if (p.fromAdd) addNewlines else originalNewlines
                val n = if (p.fromAdd) addNewlineCount else originalNewlines.size
                val k = PieceTable.lowerBound(arr, n, p.start + local) - p.newlineFrom
                if (k > 0) {
                    line += k
                    lastNewlineGlobal = acc + (newlineOffsetAt(p, k - 1) - p.start)
                }
                break
            }
        }
        return line to (off - lastNewlineGlobal - 1)
    }

    fun lineColumnToOffset(line: Int, column: Int): Int {
        val lineStart = when {
            line <= 0 -> 0
            line > newlineTotal -> length
            else -> newlineGlobalOffset(line) + 1
        }
        return (lineStart + maxOf(0, column)).coerceAtMost(length)
    }

    /** Byte range [start, end) of a 0-based line, excluding the newline. Past-end lines → (length, length). */
    fun lineAt(line: Int): Pair<Int, Int> {
        if (line > newlineTotal) return length to length
        val start = if (line <= 0) 0 else newlineGlobalOffset(line) + 1
        val end = if (line + 1 <= newlineTotal) newlineGlobalOffset(line + 1) else length
        return start to end
    }

    /** Global byte offset of the k-th newline (k in 1..newlineTotal). */
    private fun newlineGlobalOffset(k: Int): Int {
        var acc = 0
        var seen = 0
        for (p in pieces) {
            if (seen + p.newlineCount >= k) {
                return acc + (newlineOffsetAt(p, k - seen - 1) - p.start)
            }
            seen += p.newlineCount
            acc += p.length
        }
        return length
    }

    /** Buffer-local offset of the i-th newline (0-based) inside piece [p]. */
    private fun newlineOffsetAt(p: Piece, i: Int): Int {
        val arr = if (p.fromAdd) addNewlines else originalNewlines
        return arr[p.newlineFrom + i]
    }
}
