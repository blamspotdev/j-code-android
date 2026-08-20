package dev.blamspot.jcode.native.vt

/**
 * libjcode_vt.so — VT100/ANSI parser for the terminal View.
 *
 * The terminal loads this itself, in [dev.blamspot.jcode.core.term.VtParser]; what this object is
 * for is the smoke test that checks every shipped native library still dlopen's on a real device.
 * It named `vtparser` until 1.6.2 — a library that has never been built under that name — so that
 * check had been failing for VT since the parser was written.
 */
object VtNativeModule {
    const val LIBRARY_NAME: String = "jcode_vt"

    fun loadLibrary() {
        System.loadLibrary(LIBRARY_NAME)
    }
}
