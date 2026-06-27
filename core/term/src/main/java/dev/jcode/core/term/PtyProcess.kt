package dev.jcode.core.term

import java.io.Closeable
import java.lang.ref.Cleaner

/**
 * PTY (pseudo-terminal) process wrapper.
 * Creates a PTY pair and forks a child process.
 * Uses native forkpty() via JNI for full terminal emulation support.
 */
class PtyProcess private constructor(
    private var nativeHandle: Long
) : Closeable {

    private val cleanable: Cleaner.Cleanable

    init {
        // Capture the handle in a local val so the cleanup action does not reference `this` (reading
        // the mutable field would implicitly capture the instance). Store the Cleanable so close() can
        // free immediately and deregister it — no later GC-triggered double free of a reused handle.
        val handle = nativeHandle
        cleanable = if (handle != 0L) {
            cleaner.register(this) { nativeCloseByHandle(handle) }
        } else {
            cleaner.register(this) {}
        }
    }

    /**
     * Read bytes from the PTY (non-blocking).
     * @return Number of bytes read, 0 if no data available, -1 on error/EOF
     */
    fun read(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): Int {
        check(nativeHandle != 0L) { "PTY is closed" }
        return nativeRead(buffer, offset, length)
    }

    /**
     * Write bytes to the PTY.
     * @return Number of bytes written, -1 on error
     */
    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        check(nativeHandle != 0L) { "PTY is closed" }
        return nativeWrite(data, offset, length)
    }

    /**
     * Write a string to the PTY.
     */
    fun write(text: String): Int {
        return write(text.toByteArray(Charsets.UTF_8))
    }

    /**
     * Resize the terminal.
     */
    fun resize(cols: Int, rows: Int): Boolean {
        check(nativeHandle != 0L) { "PTY is closed" }
        return nativeResize(cols, rows)
    }

    /**
     * Wait for the child process to exit.
     * @return Exit status (negative value indicates signal)
     */
    fun waitForExit(): Int {
        check(nativeHandle != 0L) { "PTY is closed" }
        return nativeWaitForExit()
    }

    /**
     * Check if the PTY is still open.
     */
    val isOpen: Boolean
        get() = nativeHandle != 0L && nativeIsOpen()

    @Synchronized
    override fun close() {
        if (nativeHandle != 0L) {
            nativeHandle = 0L
            // Runs nativeCloseByHandle(handle) exactly once and deregisters the Cleaner.
            cleanable.clean()
        }
    }

    // Native methods
    private external fun nativeRead(buffer: ByteArray, offset: Int, length: Int): Int
    private external fun nativeWrite(data: ByteArray, offset: Int, length: Int): Int
    private external fun nativeResize(cols: Int, rows: Int): Boolean
    private external fun nativeWaitForExit(): Int
    private external fun nativeIsOpen(): Boolean

    companion object {
        private val cleaner = Cleaner.create()

        init {
            try {
                System.loadLibrary("pty")
            } catch (e: UnsatisfiedLinkError) {
                // Native library not available
            }
        }

        /**
         * Create a new PTY and spawn a process.
         * @param exe Path to executable
         * @param argv Command-line arguments
         * @param envp Environment variables (KEY=VALUE format)
         * @param cwd Working directory (null for inherit)
         * @param cols Initial terminal columns
         * @param rows Initial terminal rows
         */
        fun create(
            exe: String,
            argv: List<String>,
            envp: List<String> = emptyList(),
            cwd: String? = null,
            cols: Int = 80,
            rows: Int = 24
        ): PtyProcess {
            val handle = nativeCreate(exe, argv.toTypedArray(), envp.toTypedArray(), cwd, cols, rows)
            if (handle == 0L) {
                throw RuntimeException("Failed to create PTY")
            }
            return PtyProcess(handle)
        }

        @JvmStatic
        private external fun nativeCreate(
            exe: String,
            argv: Array<String>,
            envp: Array<String>,
            cwd: String?,
            cols: Int,
            rows: Int
        ): Long

        @JvmStatic
        private external fun nativeCloseByHandle(handle: Long)
    }
}
