package dev.jcode.native.core

import java.io.Closeable

/**
 * Native wrapper for C++ EditorState
 * 
 * Provides high-performance editor state management including:
 * - Caret positions and selections
 * - Viewport state
 * - Fold ranges
 * - Event emission
 */
class EditorStateNative : Closeable {
    private var nativeHandle: Long = 0
    
    init {
        if (CoreNativeModule.isNativeAvailable()) {
            nativeHandle = nativeCreate()
        }
    }
    
    /**
     * Check if native backend is available
     */
    fun isNativeAvailable(): Boolean = nativeHandle != 0L
    
    /**
     * Set caret positions
     */
    fun setCarets(carets: Array<CaretData>) {
        if (!isNativeAvailable()) return
        nativeSetCarets(nativeHandle, carets)
    }
    
    /**
     * Get current caret positions
     */
    fun getCarets(): Array<CaretData> {
        if (!isNativeAvailable()) return emptyArray()
        return nativeGetCarets(nativeHandle)
    }
    
    /**
     * Set viewport state
     */
    fun setViewport(scrollY: Int, scrollX: Int, widthPx: Int, heightPx: Int, lineHeightPx: Int) {
        if (!isNativeAvailable()) return
        nativeSetViewport(nativeHandle, scrollY, scrollX, widthPx, heightPx, lineHeightPx)
    }
    
    /**
     * Scroll to a specific line and column
     */
    fun scrollTo(line: Int, column: Int, lineHeightPx: Int) {
        if (!isNativeAvailable()) return
        nativeScrollTo(nativeHandle, line, column, lineHeightPx)
    }
    
    /**
     * Add a fold range
     */
    fun addFold(startLine: Int, endLine: Int, summary: String? = null) {
        if (!isNativeAvailable()) return
        nativeAddFold(nativeHandle, startLine, endLine, summary)
    }
    
    /**
     * Remove a fold range
     */
    fun removeFold(startLine: Int, endLine: Int) {
        if (!isNativeAvailable()) return
        nativeRemoveFold(nativeHandle, startLine, endLine)
    }
    
    /**
     * Toggle a fold range
     */
    fun toggleFold(startLine: Int, endLine: Int) {
        if (!isNativeAvailable()) return
        nativeToggleFold(nativeHandle, startLine, endLine)
    }
    
    /**
     * Set read-only mode
     */
    fun setReadOnly(readOnly: Boolean) {
        if (!isNativeAvailable()) return
        nativeSetReadOnly(nativeHandle, readOnly)
    }
    
    /**
     * Check if read-only mode is enabled
     */
    fun isReadOnly(): Boolean {
        if (!isNativeAvailable()) return false
        return nativeIsReadOnly(nativeHandle)
    }
    
    override fun close() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0
        }
    }
    
    // Native methods
    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeSetCarets(handle: Long, carets: Array<CaretData>)
    private external fun nativeGetCarets(handle: Long): Array<CaretData>
    private external fun nativeSetViewport(handle: Long, scrollY: Int, scrollX: Int, 
                                           widthPx: Int, heightPx: Int, lineHeightPx: Int)
    private external fun nativeScrollTo(handle: Long, line: Int, column: Int, lineHeightPx: Int)
    private external fun nativeAddFold(handle: Long, startLine: Int, endLine: Int, summary: String?)
    private external fun nativeRemoveFold(handle: Long, startLine: Int, endLine: Int)
    private external fun nativeToggleFold(handle: Long, startLine: Int, endLine: Int)
    private external fun nativeSetReadOnly(handle: Long, readOnly: Boolean)
    private external fun nativeIsReadOnly(handle: Long): Boolean
}

/**
 * Caret position data for JNI bridge
 */
data class CaretData(
    val anchor: Int,
    val head: Int,
    val preferredColumn: Int = -1
)
