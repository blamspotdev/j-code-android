package dev.jcode.core.buffer

import java.lang.ref.Cleaner
import java.nio.charset.StandardCharsets

/**
 * In-memory piece-tree buffer backing store.
 * Uses C++ JNI implementation when available for optimal performance,
 * falls back to a pure Kotlin piece table otherwise.
 * All mutable state lives behind a single writer thread concept (EditorDispatcher) at the editor layer.
 */
class Buffer internal constructor(
    initialContent: ByteArray,
    useNative: Boolean = nativeAvailable,
) : AutoCloseable {

    private val cleanable: Cleaner.Cleanable
    private var closed = false
    private val useNativeImpl: Boolean = useNative

    // Native handle for JNI implementation (0 = using Kotlin impl)
    @Volatile
    internal var nativeHandle: Long = 0L

    private val table: PieceTable? = if (useNative) null else PieceTable(initialContent)
    // Reused between edits so repeated snapshot()/read calls don't re-copy the piece list.
    private var cachedSnapshot: PieceSnapshot? = null

    init {
        if (useNativeImpl) {
            nativeHandle = nativeOpenFromBytes(initialContent)
        }
        // The cleanup action must capture ONLY the primitive handle, never `this` — capturing the
        // tracked object keeps it strongly reachable and permanently defeats the Cleaner.
        val handle = nativeHandle
        cleanable = cleaner.register(this) { if (handle != 0L) nativeCloseByHandle(handle) }
    }

    private fun tableSnapshot(): PieceSnapshot = synchronized(this) {
        cachedSnapshot ?: table!!.snapshot().also { cachedSnapshot = it }
    }

    /** Total byte length of the buffer (UTF-8). */
    val byteLength: Int
        get() = if (useNativeImpl && nativeHandle != 0L) {
            snapshot().use { it.byteLength }
        } else {
            synchronized(this) { table!!.length }
        }

    /** Number of lines (at least 1 for empty buffer). */
    val lineCount: Int
        get() = if (useNativeImpl && nativeHandle != 0L) {
            snapshot().use { it.lineCount }
        } else {
            synchronized(this) { table!!.newlineTotal + 1 }
        }

    /** Take an immutable snapshot of current buffer state. */
    fun snapshot(): Snapshot {
        checkNotClosed()
        return if (useNativeImpl && nativeHandle != 0L) {
            val nativeSnapshotHandle = nativeSnapshot()
            Snapshot(nativeHandle = nativeSnapshotHandle)
        } else {
            Snapshot(pieces = tableSnapshot())
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
                val t = table!!
                for (op in tx.ops) {
                    when (op) {
                        is EditOp.Insert -> t.insert(op.offset, op.text.toByteArray(StandardCharsets.UTF_8))
                        is EditOp.Delete -> t.delete(op.start, op.end)
                    }
                }
                cachedSnapshot = null
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
            tableSnapshot().readRange(start, end)
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
            tableSnapshot().offsetToLineColumn(offset)
        }
    }

    /** Convert (line, column) to byte offset. */
    fun lineColumnToOffset(line: Int, column: Int): Int {
        checkNotClosed()
        return if (useNativeImpl && nativeHandle != 0L) {
            snapshot().use { it.lineColumnToOffset(line, column) }
        } else {
            tableSnapshot().lineColumnToOffset(line, column)
        }
    }

    /** Get the byte range [start, end) for a 0-based line index. */
    fun lineAt(line: Int): Pair<Int, Int> {
        checkNotClosed()
        return if (useNativeImpl && nativeHandle != 0L) {
            snapshot().use { it.lineAt(line) }
        } else {
            tableSnapshot().lineAt(line)
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
        // the first edit. Until it is correctly implemented we use the pure-Kotlin piece table
        // (PieceTable.kt), which is correct and edits in O(pieces) rather than O(file size). Flip
        // this back on once the native tree is fixed and tested.
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
    }
}

/** Immutable snapshot of buffer content at a point in time. */
class Snapshot internal constructor(
    internal val pieces: PieceSnapshot? = null,
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
            pieces?.length ?: 0
        }

    val lineCount: Int
        get() = if (useNativeImpl) {
            nativeLineCount().toInt()
        } else {
            pieces?.lineCount ?: 1
        }

    fun readRange(start: Int, end: Int): ByteArray {
        return if (useNativeImpl) {
            val size = end - start
            val out = ByteArray(size)
            val read = nativeReadRange(start.toLong(), end.toLong(), out)
            if (read < size) out.copyOf(read) else out
        } else {
            pieces?.readRange(start, end) ?: ByteArray(0)
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
            pieces?.lineAt(line) ?: (0 to 0)
        }
    }

    fun offsetToLineColumn(offset: Int): Pair<Int, Int> {
        return if (useNativeImpl) {
            val line = nativeOffsetToLine(offset.toLong()).toInt()
            val col = nativeOffsetToColumn(offset.toLong()).toInt()
            line to col
        } else {
            pieces?.offsetToLineColumn(offset) ?: (0 to 0)
        }
    }

    fun lineColumnToOffset(line: Int, column: Int): Int {
        return if (useNativeImpl) {
            nativeLineColumnToOffset(line.toLong(), column.toLong()).toInt()
        } else {
            pieces?.lineColumnToOffset(line, column) ?: 0
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
