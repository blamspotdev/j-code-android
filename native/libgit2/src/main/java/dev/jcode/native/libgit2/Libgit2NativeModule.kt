package dev.jcode.native.libgit2

/**
 * libgit2_ffi.so + libssh2 + libmbedtls.
 * Stub — actual implementation in Phase 9.
 */
object Libgit2NativeModule {
    const val LIBRARY_NAME: String = "git2_ffi"

    fun loadLibrary() {
        System.loadLibrary(LIBRARY_NAME)
    }
}
