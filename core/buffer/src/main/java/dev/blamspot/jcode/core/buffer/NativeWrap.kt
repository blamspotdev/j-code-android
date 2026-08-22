package dev.blamspot.jcode.core.buffer

/**
 * JNI facade for the native soft-wrap layout builder (wrap_map.cpp, shipped inside libjcodebuffer):
 * walks a native [Snapshot]'s bytes directly — line text never crosses the JNI boundary — and
 * returns the whole document's layout as one packed int array.
 *
 * Packed layout (see jni_wrap.cpp), read in place by base offset rather than sliced apart:
 * ```
 * [0]                lineCount
 * [1]                totalRows
 * [CUM_ROWS_BASE..]  cumRows    (lineCount + 1)
 * [..]               lineLen    (lineCount, UTF-16 units)
 * [..]               rowStarts  (totalRows)
 * ```
 */
object NativeWrap {
    /** Index of the first `cumRows` entry; the two header slots precede it. */
    const val CUM_ROWS_BASE = 2

    /** Build the layout for [snapshot], or null on a Kotlin-path snapshot (caller falls back). */
    fun build(snapshot: Snapshot, charsPerRow: Int): IntArray? {
        val handle = snapshot.nativeHandleOrZero
        if (handle == 0L) return null
        return nativeBuild(handle, charsPerRow)
    }

    init {
        runCatching { System.loadLibrary("jcodebuffer") }
    }

    private external fun nativeBuild(snapshotHandle: Long, charsPerRow: Int): IntArray?
}
