package dev.jcode.core.buffer

import java.lang.ref.Cleaner
import java.nio.charset.StandardCharsets
import kotlin.math.max
import kotlin.math.min

/**
 * In-memory piece-tree buffer backing store.
 * Shaped for future JNI replacement — all mutable state lives behind a single
 * writer thread concept (EditorDispatcher) at the editor layer.
 */
class Buffer internal constructor(
    private var content: ByteArray,
) : AutoCloseable {

    private val cleanerHandle: Cleaner.Cleanable
    private var closed = false

    init {
        val ref = CleanerHandle(this)
        cleanerHandle = cleaner.register(this, ref)
    }

    /** Total byte length of the buffer (UTF-8). */
    val byteLength: Int
        get() = synchronized(this) { content.size }

    /** Number of lines (at least 1 for empty buffer). */
    val lineCount: Int
        get() = synchronized(this) { countLines(content) + 1 }

    /** Take an immutable snapshot of current buffer state. */
    fun snapshot(): Snapshot {
        checkNotClosed()
        val copy = synchronized(this) { content.copyOf() }
        return Snapshot(copy)
    }

    /** Apply an edit transaction and return the new snapshot. */
    fun applyEdit(tx: EditTx): Snapshot {
        checkNotClosed()
        synchronized(this) {
            var current = content
            for (op in tx.ops) {
                current = when (op) {
                    is EditOp.Insert -> {
                        val bytes = op.text.toByteArray(StandardCharsets.UTF_8)
                        val before = current.copyOfRange(0, min(op.offset, current.size))
                        val after = current.copyOfRange(min(op.offset, current.size), current.size)
                        before + bytes + after
                    }
                    is EditOp.Delete -> {
                        val start = min(op.start, current.size)
                        val end = min(op.end, current.size)
                        if (start >= end) current else {
                            val before = current.copyOfRange(0, start)
                            val after = current.copyOfRange(end, current.size)
                            before + after
                        }
                    }
                }
            }
            content = current
        }
        return snapshot()
    }

    /** Read a byte range from the current buffer. */
    fun readRange(start: Int, end: Int): ByteArray {
        checkNotClosed()
        return synchronized(this) {
            content.copyOfRange(
                max(0, min(start, content.size)),
                max(0, min(end, content.size)),
            )
        }
    }

    /** Read a range as UTF-16 chars (for IME convenience). */
    fun readRangeAsUtf16(start: Int, end: Int): String {
        val bytes = readRange(start, end)
        return String(bytes, StandardCharsets.UTF_8)
    }

    /** Convert a byte offset to (line, column) — both 0-based. */
    fun offsetToLineColumn(offset: Int): Pair<Int, Int> {
        checkNotClosed()
        return synchronized(this) {
            offsetToLineColumnLocked(content, offset)
        }
    }

    /** Convert (line, column) to byte offset. */
    fun lineColumnToOffset(line: Int, column: Int): Int {
        checkNotClosed()
        return synchronized(this) {
            lineColumnToOffsetLocked(content, line, column)
        }
    }

    /** Get the byte range [start, end) for a 0-based line index. */
    fun lineAt(line: Int): Pair<Int, Int> {
        checkNotClosed()
        return synchronized(this) {
            lineRangeLocked(content, line)
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            // Cleaner will handle the actual cleanup; we just mark closed.
        }
    }

    private fun checkNotClosed() {
        check(!closed) { "Buffer is closed" }
    }

    private class CleanerHandle(private val buffer: Buffer) : Runnable {
        override fun run() {
            buffer.closed = true
        }
    }

    companion object {
        private val cleaner = Cleaner.create()

        /** Create an empty buffer. */
        fun create(): Buffer = Buffer(byteArrayOf())

        /** Create a buffer from initial text. */
        fun fromText(text: String): Buffer =
            Buffer(text.toByteArray(StandardCharsets.UTF_8))

        private fun countLines(bytes: ByteArray): Int {
            var count = 0
            for (b in bytes) {
                if (b == '\n'.code.toByte()) count++
            }
            return count
        }

        internal fun offsetToLineColumnLocked(content: ByteArray, offset: Int): Pair<Int, Int> {
            val clamped = max(0, min(offset, content.size))
            var line = 0
            var colStart = 0
            var i = 0
            while (i < clamped) {
                if (content[i] == '\n'.code.toByte()) {
                    line++
                    colStart = i + 1
                }
                i++
            }
            // Handle \r\n: if we're right after \r, column should account for it
            val col = clamped - colStart
            return line to col
        }

        internal fun lineColumnToOffsetLocked(content: ByteArray, line: Int, column: Int): Int {
            var currentLine = 0
            var i = 0
            while (i < content.size && currentLine < line) {
                if (content[i] == '\n'.code.toByte()) currentLine++
                i++
            }
            return i + column
        }

        internal fun lineRangeLocked(content: ByteArray, line: Int): Pair<Int, Int> {
            var currentLine = 0
            var start = 0
            var i = 0
            // Find start of the line
            while (i < content.size && currentLine < line) {
                if (content[i] == '\n'.code.toByte()) {
                    currentLine++
                    if (currentLine == line) {
                        start = i + 1
                        break
                    }
                }
                i++
            }
            if (currentLine < line) return content.size to content.size // past end

            // Find end of the line
            var end = start
            while (end < content.size && content[end] != '\n'.code.toByte()) {
                end++
            }
            return start to end
        }
    }
}

/** Immutable snapshot of buffer content at a point in time. */
class Snapshot internal constructor(
    internal val content: ByteArray,
) : AutoCloseable {

    private val cleanerHandle: Cleaner.Cleanable
    private var closed = false

    init {
        val ref = CleanerHandle(this)
        cleanerHandle = cleaner.register(this, ref)
    }

    val byteLength: Int get() = content.size

    val lineCount: Int
        get() {
            var count = 1
            for (b in content) {
                if (b == '\n'.code.toByte()) count++
            }
            return count
        }

    fun readRange(start: Int, end: Int): ByteArray =
        content.copyOfRange(
            max(0, min(start, content.size)),
            max(0, min(end, content.size)),
        )

    fun readRangeAsUtf16(start: Int, end: Int): String =
        String(readRange(start, end), StandardCharsets.UTF_8)

    fun lineAt(line: Int): Pair<Int, Int> = Buffer.lineRangeLocked(content, line)

    fun offsetToLineColumn(offset: Int): Pair<Int, Int> =
        Buffer.offsetToLineColumnLocked(content, offset)

    fun lineColumnToOffset(line: Int, column: Int): Int =
        Buffer.lineColumnToOffsetLocked(content, line, column)

    /** Get the text of a specific line (without line ending). */
    fun lineText(line: Int): String {
        val (start, end) = lineAt(line)
        return readRangeAsUtf16(start, end)
    }

    override fun close() {
        if (!closed) {
            closed = true
            // Cleaner will handle the actual cleanup; we just mark closed.
        }
    }

    private class CleanerHandle(private val snapshot: Snapshot) : Runnable {
        override fun run() {
            snapshot.closed = true
        }
    }

    companion object {
        private val cleaner = Cleaner.create()
    }
}

/** A reference to a line in a snapshot, holding its text and range. */
data class LineRef(
    val lineIndex: Int,
    val byteStart: Int,
    val byteEnd: Int,
    val text: String,
)

/** Edit operation within a transaction. */
sealed class EditOp {
    data class Insert(val offset: Int, val text: String) : EditOp()
    data class Delete(val start: Int, val end: Int) : EditOp()
}

/** A transaction containing one or more edit operations. */
data class EditTx(
    val ops: List<EditOp>,
) {
    companion object {
        fun insert(offset: Int, text: String): EditTx =
            EditTx(listOf(EditOp.Insert(offset, text)))

        fun delete(start: Int, end: Int): EditTx =
            EditTx(listOf(EditOp.Delete(start, end)))

        fun replace(start: Int, end: Int, text: String): EditTx =
            EditTx(listOf(EditOp.Delete(start, end), EditOp.Insert(start, text)))

        fun builder(): EditTxBuilder = EditTxBuilder()
    }
}

class EditTxBuilder {
    private val ops = mutableListOf<EditOp>()

    fun insert(offset: Int, text: String) = apply {
        ops.add(EditOp.Insert(offset, text))
    }

    fun delete(start: Int, end: Int) = apply {
        ops.add(EditOp.Delete(start, end))
    }

    fun build(): EditTx = EditTx(ops.toList())
}
