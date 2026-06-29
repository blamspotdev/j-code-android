package dev.jcode.native.ripgrepffi

/**
 * libripgrep_ffi.so (Rust + cargo-ndk).
 * Stub — actual implementation in Phase 0.3 / Phase 10.
 */
object RipgrepFfiNativeModule {
    const val LIBRARY_NAME: String = "ripgrep_ffi"

    fun loadLibrary() {
        System.loadLibrary(LIBRARY_NAME)
    }
}
