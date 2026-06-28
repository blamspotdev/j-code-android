package dev.jcode.core.buffer

import java.lang.ref.Cleaner
import java.nio.charset.StandardCharsets
import kotlin.math.max
import kotlin.math.min

/**
 * In-memory piece-tree buffer backing store.
 * Uses C++ JNI implementation when available for optimal performance,
 * falls back to pure Kotlin implementation otherwise.
 * All mutable state lives behind a single writer thread concept (EditorDispatcher) at the editor layer.
 */
class Buffer internal constructor(
    private var content: ByteArray,
    useNative: Boolean = nativeAvailable,
) : AutoCloseable {

    private val cleanable: Cleaner.Cleanable
    private var closed = false
    private val useNativeImpl: Boolean = useNative

    // Native handle for JNI implementation (0 = using Kotlin impl)
    @Volatile
    internal var nativeHandle: Long = 0L

    init {
        if (useNativeImpl) {
            nativeHandle = nativeOpenFromBytes(content)
        }
        // The cleanup action must capture ONLY the primitive handle, never `this` — capturing the
        // tracked object keeps it strongly reachable and permanently defeats the Cleaner.
        val handle = nativeHandle
        cleanable = cleaner.register(this) { if (handle != 0L) nativeCloseByHandle(handle) }
    }

    /** Total byte length of the buffer (UTF-8). */
    val byteLength: Int
        get() = if (useNativeImpl && nativeHandle != 0L) {
            snapshot().use { it.byteLength }
        } else {
            synchronized(this) { content.size }
        }

    /** Number of lines (at least 1 for empty buffer). */
    val lineCount: Int
        get() = if (useNativeImpl && nativeHandle != 0L) {
            snapshot().use { it.lineCount }
        } else {
            synchronized(this) { countLines(content) + 1 }
        }

    /** Take an immutable snapshot of current buffer state. */
    fun snapshot(): Snapshot {
        checkNotClosed()
        return if (useNativeImpl && nativeHandle != 0L) {
            val nativeSnapshotHandle = nativeSnapshot()
            Snapshot(nativeHandle = nativeSnapshotHandle)
        } else {
            val copy = synchronized(this) { content.copyOf() }
            Snapshot(content = copy)
        }
    }

    /** Apply an edit transaction and return the new snapshot. */
    fun applyEdit(tx: EditTx): Snapshot {
        checkNotClosed()
        return if (useNativeImpl && nativeHandle != 0L) {
            // Convert EditTx to native format
            val nativeOps = tx.ops.map { op ->
                when (op) {
                    is EditOp.Insert -> NativeEditOp(
                        type = 0, // INSERT
                        offset = op.offset.toLong(),
                        length = 0,
                        data = op.text.toByteArray(StandardCharsets.UTF_8)
                    )
                    is EditOp.Delete -> NativeEditOp(
                        type = 1, // DELETE
                        offset = op.start.toLong(),
                        length = (op.end - op.start).toLong(),
                        data = ByteArray(0)
                    )
                }
            }.toTypedArray()
            
            val nativeSnapshotHandle = nativeApplyEdits(nativeOps)
            Snapshot(nativeHandle = nativeSnapshotHandle)
        } else {
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
            snapshot()
        }
    }

    /** Read a byte range from the current buffer. */
    fun readRange(start: Int, end: Int): ByteArray {
        checkNotClosed()
        return if (useNativeImpl && nativeHandle != 0L) {
            snapshot().use { it.readRange(start, end) }
        } else {
            synchronized(this) {
                content.copyOfRange(
                    max(0, min(start, content.size)),
                    max(0, min(end, content.size)),
                )
            }
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
        return if (useNativeImpl && nativeHandle != 0L) {
            snapshot().use { it.offsetToLineColumn(offset) }
        } else {
            synchronized(this) {
                offsetToLineColumnLocked(content, offset)
            }
        }
    }

    /** Convert (line, column) to byte offset. */
    fun lineColumnToOffset(line: Int, column: Int): Int {
        checkNotClosed()
        return if (useNativeImpl && nativeHandle != 0L) {
            snapshot().use { it.lineColumnToOffset(line, column) }
        } else {
            synchronized(this) {
                lineColumnToOffsetLocked(content, line, column)
            }
        }
    }

    /** Get the byte range [start, end) for a 0-based line index. */
    fun lineAt(line: Int): Pair<Int, Int> {
        checkNotClosed()
        return if (useNativeImpl && nativeHandle != 0L) {
            snapshot().use { it.lineAt(line) }
        } else {
            synchronized(this) {
                lineRangeLocked(content, line)
            }
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            // clean() runs the (capture-free) cleanup action exactly once and deregisters it from the
            // Cleaner, so the native handle is freed deterministically here and never double-freed.
            cleanable.clean()
            nativeHandle = 0L
        }
    }

    private fun checkNotClosed() {
        check(!closed) { "Buffer is closed" }
    }

    // Native methods
    private external fun nativeOpenFromBytes(data: ByteArray): Long
    private external fun nativeOpenFromFd(fd: Int): Long
    private external fun nativeSnapshot(): Long
    private external fun nativeApplyEdits(ops: Array<NativeEditOp>): Long

    companion object {
        private val cleaner = Cleaner.create()

        @JvmStatic
        private external fun nativeCloseByHandle(handle: Long)

        // The native piece-tree (native/buffer/piece_tree.cpp) is an unfinished skeleton: its
        // insert/split orphans the inserted text and leaves the original piece in place (duplicating
        // content), and the red-black balancing + remove are empty stubs. It corrupts the buffer on
        // the first edit. Until it is correctly implemented we use the pure-Kotlin array-splice path,
        // which is correct (and fast enough for the file sizes edited on-device). Flip this back on
        // once the native tree is fixed and tested.
        private const val USE_NATIVE_BUFFER = false

        // Check if native library is available
        @Volatile
        private var nativeAvailable = false

        init {
            try {
                System.loadLibrary("jcodebuffer")
                nativeAvailable = USE_NATIVE_BUFFER
            } catch (e: UnsatisfiedLinkError) {
                nativeAvailable = false
            }
        }

        /** Check if native C++ implementation is available. */
        fun isNativeAvailable(): Boolean = nativeAvailable

        /** Create an empty buffer. */
        fun create(): Buffer = Buffer(byteArrayOf(), useNative = nativeAvailable)

        /** Create a buffer from initial text. */
        fun fromText(text: String): Buffer =
            Buffer(text.toByteArray(StandardCharsets.UTF_8), useNative = nativeAvailable)

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
    internal val content: ByteArray? = null,
    // Must be named `nativeHandle`: the native JNI (jni_buffer.cpp getSnapshot/setSnapshot) looks up
    // the field "nativeHandle" on this class and writes it on close. A name mismatch aborts the VM
    // with NoSuchFieldError the moment any native Snapshot method runs (e.g. opening a file).
    private var nativeHandle: Long = 0L,
) : AutoCloseable {

    private val cleanable: Cleaner.Cleanable
    private var closed = false
    private val useNativeImpl: Boolean = nativeHandle != 0L

    init {
        // Capture only the primitive handle so the Cleaner can actually fire (see Buffer.init).
        val handle = nativeHandle
        cleanable = cleaner.register(this) { if (handle != 0L) nativeCloseByHandle(handle) }
    }

    val byteLength: Int 
        get() = if (useNativeImpl) {
            nativeByteLength().toInt()
        } else {
            content?.size ?: 0
        }

    val lineCount: Int
        get() = if (useNativeImpl) {
            nativeLineCount().toInt()
        } else {
            var count = 1
            content?.forEach { b ->
                if (b == '\n'.code.toByte()) count++
            }
            count
        }

    fun readRange(start: Int, end: Int): ByteArray {
        return if (useNativeImpl) {
            val size = end - start
            val out = ByteArray(size)
            val read = nativeReadRange(start.toLong(), end.toLong(), out)
            if (read < size) out.copyOf(read) else out
        } else {
            content?.copyOfRange(
                max(0, min(start, content.size)),
                max(0, min(end, content.size)),
            ) ?: ByteArray(0)
        }
    }

    fun readRangeAsUtf16(start: Int, end: Int): String =
        String(readRange(start, end), StandardCharsets.UTF_8)

    fun lineAt(line: Int): Pair<Int, Int> {
        return if (useNativeImpl) {
            val start = nativeLineStart(line.toLong()).toInt()
            val end = nativeLineEnd(line.toLong()).toInt()
            start to end
        } else {
            content?.let { Buffer.lineRangeLocked(it, line) } ?: (0 to 0)
        }
    }

    fun offsetToLineColumn(offset: Int): Pair<Int, Int> {
        return if (useNativeImpl) {
            val line = nativeOffsetToLine(offset.toLong()).toInt()
            val col = nativeOffsetToColumn(offset.toLong()).toInt()
            line to col
        } else {
            content?.let { Buffer.offsetToLineColumnLocked(it, offset) } ?: (0 to 0)
        }
    }

    fun lineColumnToOffset(line: Int, column: Int): Int {
        return if (useNativeImpl) {
            nativeLineColumnToOffset(line.toLong(), column.toLong()).toInt()
        } else {
            content?.let { Buffer.lineColumnToOffsetLocked(it, line, column) } ?: 0
        }
    }

    /** Get the text of a specific line (without line ending). */
    fun lineText(line: Int): String {
        val (start, end) = lineAt(line)
        return readRangeAsUtf16(start, end)
    }

    override fun close() {
        if (!closed) {
            closed = true
            cleanable.clean()
            nativeHandle = 0L
        }
    }

    // Native methods
    private external fun nativeByteLength(): Long
    private external fun nativeLineCount(): Long
    private external fun nativeReadRange(start: Long, end: Long, out: ByteArray): Int
    private external fun nativeOffsetToLine(offset: Long): Long
    private external fun nativeOffsetToColumn(offset: Long): Long
    private external fun nativeLineColumnToOffset(line: Long, column: Long): Long
    private external fun nativeLineStart(line: Long): Long
    private external fun nativeLineEnd(line: Long): Long

    companion object {
        private val cleaner = Cleaner.create()

        @JvmStatic
        private external fun nativeCloseByHandle(handle: Long)
    }
}

/** Native edit operation for JNI bridge. */
internal data class NativeEditOp(
    val type: Int, // 0 = INSERT, 1 = DELETE
    val offset: Long,
    val length: Long,
    val data: ByteArray,
)

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
