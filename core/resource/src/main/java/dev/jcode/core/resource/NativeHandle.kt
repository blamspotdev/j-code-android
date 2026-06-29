package dev.jcode.core.resource

import java.lang.ref.Cleaner
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wrapper for native handles (JNI pointers, file descriptors, etc.)
 * Ensures cleanup via AutoCloseable + Cleaner as a safety net.
 */
abstract class NativeHandle : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val cleanable: Cleaner.Cleanable

    init {
        cleanable = cleaner.register(this, CleanupAction(this::release))
    }

    /**
     * Release the native resource. Called exactly once.
     * Implementations should free native memory, close file descriptors, etc.
     */
    protected abstract fun release()

    /**
     * Check if this handle has been closed.
     */
    val isClosed: Boolean
        get() = closed.get()

    /**
     * Close this handle, releasing the native resource.
     * Safe to call multiple times; only the first call has effect.
     */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                release()
            } finally {
                cleanable.clean()
            }
        }
    }

    /**
     * Ensure the handle is not closed before using it.
     */
    protected fun checkNotClosed() {
        check(!isClosed) { "NativeHandle already closed" }
    }

    private class CleanupAction(private val release: () -> Unit) : Runnable {
        override fun run() {
            try {
                release()
            } catch (_: Exception) {
                // Ignore cleanup errors
            }
        }
    }

    companion object {
        private val cleaner = Cleaner.create()
    }
}
