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
    // Native-path equivalent. Invalidation only DROPS the reference (the Cleaner frees it after
    // GC) instead of closing: a reader that already grabbed it may still be mid-call.
    private var cachedNativeSnapshot: Snapshot? = null

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

    private fun nativeSnapshotCached(): Snapshot = synchronized(this) {
        cachedNativeSnapshot ?: Snapshot(nativeHandle = nativeSnapshot()).also { cachedNativeSnapshot = it }
    }

    /** Total byte length of the buffer (UTF-8). */
    val byteLength: Int
        get() = if (useNativeImpl && nativeHandle != 0L) {
            nativeByteLength().toInt()
        } else {
            synchronized(this) { table!!.length }
        }

    /** Number of lines (at least 1 for empty buffer). */
    val lineCount: Int
        get() = if (useNativeImpl && nativeHandle != 0L) {
            nativeLineCount().toInt()
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
            synchronized(this) { cachedNativeSnapshot = null }
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
            nativeSnapshotCached().readRange(start, end)
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
            nativeSnapshotCached().offsetToLineColumn(offset)
        } else {
            tableSnapshot().offsetToLineColumn(offset)
        }
    }

    /** Convert (line, column) to byte offset. */
    fun lineColumnToOffset(line: Int, column: Int): Int {
        checkNotClosed()
        return if (useNativeImpl && nativeHandle != 0L) {
            nativeSnapshotCached().lineColumnToOffset(line, column)
        } else {
            tableSnapshot().lineColumnToOffset(line, column)
        }
    }

    /** Get the byte range [start, end) for a 0-based line index. */
    fun lineAt(line: Int): Pair<Int, Int> {
        checkNotClosed()
        return if (useNativeImpl && nativeHandle != 0L) {
            nativeSnapshotCached().lineAt(line)
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
    private external fun nativeByteLength(): Long
    private external fun nativeLineCount(): Long
    private external fun nativeSnapshot(): Long
    private external fun nativeApplyEdits(ops: Array<NativeEditOp>): Long

    companion object {
        private val cleaner = Cleaner.create()

        @JvmStatic
        private external fun nativeCloseByHandle(handle: Long)

        // The C++ piece table (native/buffer/piece_tree.cpp) mirrors PieceTable.kt's semantics
        // byte-for-byte — NativeBufferDifferentialTest fuzzes it against the Kotlin implementation
        // and a naive reference through the real JNI on-device, and must stay green before any
        // change here ships. Its snapshots answer line/offset queries via prefix-sum binary search
        // (~100x faster line scans than the Kotlin walk); flip to false to fall back to Kotlin.
        private const val USE_NATIVE_BUFFER = true

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

    /** Non-zero only on the native path; lets NativeHighlighter run directly over this snapshot. */
    internal val nativeHandleOrZero: Long get() = nativeHandle

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
            unpack(nativeLineRange(line.toLong()))
        } else {
            pieces?.lineAt(line) ?: (0 to 0)
        }
    }

    fun offsetToLineColumn(offset: Int): Pair<Int, Int> {
        return if (useNativeImpl) {
            unpack(nativeOffsetToLineColumn(offset.toLong()))
        } else {
            pieces?.offsetToLineColumn(offset) ?: (0 to 0)
        }
    }

    /** Native pair results arrive packed as (hi shl 32) or lo — one JNI crossing per query. */
    private fun unpack(packed: Long): Pair<Int, Int> =
        (packed ushr 32).toInt() to (packed and 0xFFFFFFFFL).toInt()

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

    /**
     * Read [count] consecutive lines starting at [firstLine] as one batch — a single native
     * crossing on the native path, replacing the renderer's per-line lineAt + readRangeAsUtf16
     * pairs (2 JNI calls + a ByteArray per visible line per frame).
     */
    fun readLines(firstLine: Int, count: Int): LineWindow {
        if (count <= 0) return LineWindow.EMPTY
        if (useNativeImpl) {
            val starts = IntArray(count + 1)
            val bufferStarts = IntArray(count)
            var out = ByteArray(maxOf(1024, count * 96))
            while (true) {
                val needed = nativeReadLines(firstLine.toLong(), count, out, starts, bufferStarts)
                if (needed <= out.size) break
                out = ByteArray(needed.toInt())
            }
            val texts = Array(count) { i -> String(out, starts[i], starts[i + 1] - starts[i], StandardCharsets.UTF_8) }
            val lengths = IntArray(count) { i -> starts[i + 1] - starts[i] }
            return LineWindow(firstLine, texts, bufferStarts, lengths)
        }
        val snap = pieces ?: return LineWindow.EMPTY
        val bufferStarts = IntArray(count)
        val lengths = IntArray(count)
        val texts = Array(count) { i ->
            val (s, e) = snap.lineAt(firstLine + i)
            bufferStarts[i] = s
            lengths[i] = e - s
            String(snap.readRange(s, e), StandardCharsets.UTF_8)
        }
        return LineWindow(firstLine, texts, bufferStarts, lengths)
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
    private external fun nativeReadLines(
        firstLine: Long,
        count: Int,
        out: ByteArray,
        outStarts: IntArray,
        bufferStarts: IntArray,
    ): Long
    private external fun nativeOffsetToLineColumn(offset: Long): Long
    private external fun nativeLineColumnToOffset(line: Long, column: Long): Long
    private external fun nativeLineRange(line: Long): Long

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

/** A contiguous window of line texts plus their byte geometry, from [Snapshot.readLines]. */
class LineWindow internal constructor(
    val firstLine: Int,
    val texts: Array<String>,
    private val bufferStarts: IntArray,
    private val byteLengths: IntArray,
) {
    val count: Int get() = texts.size

    fun contains(line: Int): Boolean = line >= firstLine && line < firstLine + count

    /** Line text without its newline. */
    fun text(line: Int): String = texts[line - firstLine]

    /** Byte offset in the buffer where [line] starts. */
    fun byteStart(line: Int): Int = bufferStarts[line - firstLine]

    /** Byte offset in the buffer where [line] ends (exclusive, before the newline). */
    fun byteEnd(line: Int): Int = bufferStarts[line - firstLine] + byteLengths[line - firstLine]

    companion object {
        val EMPTY = LineWindow(0, emptyArray(), IntArray(0), IntArray(0))
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
