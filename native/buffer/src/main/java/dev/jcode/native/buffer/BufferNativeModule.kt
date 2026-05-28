package dev.jcode.native.buffer

/**
 * JNI façade for libjcodebuffer.so (piece tree).
 * Stub — actual implementation in Phase 4.1.
 */
object BufferNativeModule {
    const val LIBRARY_NAME: String = "jcodebuffer"

    fun loadLibrary() {
        System.loadLibrary(LIBRARY_NAME)
    }
}
