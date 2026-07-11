package dev.jcode.native.buffer

/**
 * JNI façade for libjcodebuffer.so — the C++ piece table backing core/buffer's Buffer/Snapshot
 * (their `external` methods bind directly; this object only hosts the library name/loader).
 */
object BufferNativeModule {
    const val LIBRARY_NAME: String = "jcodebuffer"

    fun loadLibrary() {
        System.loadLibrary(LIBRARY_NAME)
    }
}
