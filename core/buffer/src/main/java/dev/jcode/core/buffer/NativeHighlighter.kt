package dev.jcode.core.buffer

import java.lang.ref.Cleaner

/**
 * JNI facade for the native syntax highlighter (highlight.cpp, shipped inside libjcodebuffer):
 * runs the tokenizers directly over a native [Snapshot]'s bytes — text never crosses the JNI
 * boundary — and returns packed `[startByte, endByte, colorArgb, styleFlags]` quadruples in scan
 * order (byte-sorted, matching the renderer's span sweep).
 */
object NativeHighlighter {
    const val MODE_TOKENIZE = 0
    const val MODE_MARKDOWN = 1
    const val MODE_MARKUP = 2
    const val MODE_KEYVALUE = 3
    const val MODE_JSON = 4

    /** A language configuration marshalled once into native memory; create per pack and reuse. */
    class Profile internal constructor(internal val handle: Long) : AutoCloseable {
        private val cleanable: Cleaner.Cleanable

        init {
            // Capture only the primitive handle, never `this` (see Buffer.init).
            val h = handle
            cleanable = cleaner.register(this) { if (h != 0L) nativeDestroyProfile(h) }
        }

        override fun close() = cleanable.clean()
    }

    fun createProfile(
        lineComments: List<String>,
        blockStart: String?,
        blockEnd: String?,
        delimiters: List<String>,
        keywords: Collection<String>,
        types: Collection<String>,
        sep: Char = ':',
        sections: Boolean = false,
    ): Profile = Profile(
        nativeCreateProfile(
            lineComments.toTypedArray(),
            blockStart,
            blockEnd,
            delimiters.toTypedArray(),
            keywords.toTypedArray(),
            types.toTypedArray(),
            sep.code,
            sections,
        ),
    )

    /**
     * Highlight [snapshot] natively. Returns null for a Kotlin-path snapshot (caller falls back
     * to the Kotlin tokenizers). [palette] is the 11 TokenPalette colors in constructor order.
     * [profile] may be null for the profile-free modes (markdown, markup, json).
     */
    fun highlight(snapshot: Snapshot, profile: Profile?, mode: Int, palette: IntArray): IntArray? {
        val handle = snapshot.nativeHandleOrZero
        if (handle == 0L) return null
        return nativeHighlight(handle, profile?.handle ?: 0L, mode, palette)
    }

    private val cleaner = Cleaner.create()

    init {
        runCatching { System.loadLibrary("jcodebuffer") }
    }

    private external fun nativeCreateProfile(
        lineComments: Array<String>,
        blockStart: String?,
        blockEnd: String?,
        delimiters: Array<String>,
        keywords: Array<String>,
        types: Array<String>,
        sep: Int,
        sections: Boolean,
    ): Long

    @JvmStatic
    private external fun nativeDestroyProfile(handle: Long)

    private external fun nativeHighlight(
        snapshotHandle: Long,
        profileHandle: Long,
        mode: Int,
        palette: IntArray,
    ): IntArray?
}
