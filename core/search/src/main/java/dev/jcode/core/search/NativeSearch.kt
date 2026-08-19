package dev.jcode.core.search

/**
 * Bridge to libripgrep_ffi.so (Rust: grep + ignore crates). Runs a gitignore-aware, in-process
 * ripgrep search over the host filesystem and streams matches back through a [Sink].
 *
 * The CMake stub library (built when cargo is unavailable) lacks the `nativeProbe` symbol, so
 * [isAvailable] returns false and callers fall back to the pure-Kotlin walk in [SearchEngine].
 */
internal object NativeSearch {
    // Must mirror the FLAG_* constants in native/ripgrep-ffi/rust/src/lib.rs.
    const val FLAG_REGEX = 1
    const val FLAG_CASE_SENSITIVE = 2
    const val FLAG_WHOLE_WORD = 4
    const val FLAG_INCLUDE_HIDDEN = 8

    val isAvailable: Boolean by lazy { probe() }

    private fun probe(): Boolean = try {
        System.loadLibrary("ripgrep_ffi")
        nativeProbe() == 1
    } catch (e: Throwable) {
        // UnsatisfiedLinkError (stub .so without the symbol) or any load failure → use the fallback.
        false
    }

    /** Return false from [onMatch] to stop the search early (e.g. the collector was cancelled). */
    fun interface Sink {
        fun onMatch(
            filePath: String,
            lineNumber: Int,
            columnStart: Int,
            columnEnd: Int,
            lineText: String,
        ): Boolean
    }

    fun flagsOf(options: SearchOptions): Int {
        var f = 0
        if (options.isRegex) f = f or FLAG_REGEX
        if (options.caseSensitive) f = f or FLAG_CASE_SENSITIVE
        if (options.wholeWord) f = f or FLAG_WHOLE_WORD
        if (options.includeHidden) f = f or FLAG_INCLUDE_HIDDEN
        return f
    }

    fun search(
        root: String,
        query: String,
        flags: Int,
        includeGlobs: Array<String>,
        excludeGlobs: Array<String>,
        maxResults: Int,
        sink: Sink,
    ): Int = nativeSearch(root, query, flags, includeGlobs, excludeGlobs, maxResults, sink)

    @JvmStatic
    private external fun nativeProbe(): Int

    @JvmStatic
    private external fun nativeSearch(
        root: String,
        query: String,
        flags: Int,
        includeGlobs: Array<String>,
        excludeGlobs: Array<String>,
        maxResults: Int,
        sink: Sink,
    ): Int
}
