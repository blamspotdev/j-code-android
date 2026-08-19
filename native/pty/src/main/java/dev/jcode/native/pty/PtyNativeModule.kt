package dev.jcode.native.pty

/**
 * libpty.so (forkpty, openpty, TIOCSWINSZ).
 * Stub — actual implementation in Phase 7.3.
 */
object PtyNativeModule {
    const val LIBRARY_NAME: String = "pty"

    fun loadLibrary() {
        System.loadLibrary(LIBRARY_NAME)
    }
}
