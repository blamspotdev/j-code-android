package dev.jcode.native.core

/**
 * Marker class for the core native module.
 * This module provides the foundational C++ backend infrastructure including:
 * - Event loop and thread pool management
 * - JNI bridge utilities for safe cross-boundary calls
 * - Resource management with smart pointers
 * - Logging system
 * - Core services (config, editor state, etc.)
 */
object CoreNativeModule {
    const val LIBRARY_NAME = "jcode_core"
    
    init {
        try {
            System.loadLibrary(LIBRARY_NAME)
        } catch (e: UnsatisfiedLinkError) {
            // Native library not available - fallback to Kotlin implementation
        }
    }
    
    /**
     * Check if the native library is available.
     */
    fun isNativeAvailable(): Boolean {
        return try {
            nativeIsAvailable()
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }
    
    @JvmStatic
    private external fun nativeIsAvailable(): Boolean
}
