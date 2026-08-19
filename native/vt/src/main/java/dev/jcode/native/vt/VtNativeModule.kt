package dev.jcode.native.vt

/**
 * libvtparser.so — VT100/ANSI parser for the terminal View.
 * Stub — actual implementation in Phase 7.5.
 */
object VtNativeModule {
    const val LIBRARY_NAME: String = "vtparser"

    fun loadLibrary() {
        System.loadLibrary(LIBRARY_NAME)
    }
}
