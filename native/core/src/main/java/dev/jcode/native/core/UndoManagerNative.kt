package dev.jcode.native.core

import java.io.Closeable

/**
 * Native wrapper for C++ UndoManager
 * 
 * Provides high-performance undo/redo with smart grouping:
 * - Groups edits within 500ms typing bursts
 * - Groups edits within IME composing sessions
 * - Groups edits until selection moves
 * - Configurable history limits
 */
class UndoManagerNative(
    maxGroups: Int = 500,
    maxInvertedBytes: Int = 50 * 1024 * 1024
) : Closeable {
    private var nativeHandle: Long = 0
    
    init {
        if (CoreNativeModule.isNativeAvailable()) {
            nativeHandle = nativeCreate(maxGroups, maxInvertedBytes)
        }
    }
    
    /**
     * Check if native backend is available
     */
    fun isNativeAvailable(): Boolean = nativeHandle != 0L
    
    /**
     * Mark the start of an IME composing session
     */
    fun beginComposing() {
        if (!isNativeAvailable()) return
        nativeBeginComposing(nativeHandle)
    }
    
    /**
     * Mark the end of an IME composing session
     */
    fun endComposing() {
        if (!isNativeAvailable()) return
        nativeEndComposing(nativeHandle)
    }
    
    /**
     * Flush the current group boundary
     */
    fun flushGroup() {
        if (!isNativeAvailable()) return
        nativeFlushGroup(nativeHandle)
    }
    
    /**
     * Check if undo is available
     */
    fun canUndo(): Boolean {
        if (!isNativeAvailable()) return false
        return nativeCanUndo(nativeHandle)
    }
    
    /**
     * Check if redo is available
     */
    fun canRedo(): Boolean {
        if (!isNativeAvailable()) return false
        return nativeCanRedo(nativeHandle)
    }
    
    /**
     * Clear all undo/redo history
     */
    fun clear() {
        if (!isNativeAvailable()) return
        nativeClear(nativeHandle)
    }
    
    override fun close() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0
        }
    }
    
    // Native methods
    private external fun nativeCreate(maxGroups: Int, maxInvertedBytes: Int): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeBeginComposing(handle: Long)
    private external fun nativeEndComposing(handle: Long)
    private external fun nativeFlushGroup(handle: Long)
    private external fun nativeCanUndo(handle: Long): Boolean
    private external fun nativeCanRedo(handle: Long): Boolean
    private external fun nativeClear(handle: Long)
}
