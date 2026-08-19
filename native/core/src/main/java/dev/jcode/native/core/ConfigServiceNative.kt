package dev.jcode.native.core

import java.io.Closeable

/**
 * Native wrapper for C++ ConfigService
 * 
 * Provides high-performance YAML parsing and configuration management
 * using yaml-cpp in native code.
 */
class ConfigServiceNative : Closeable {
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
     * Parse YAML from a string
     * @return true if parsing succeeded
     */
    fun parseYaml(yamlContent: String): Boolean {
        if (!isNativeAvailable()) return false
        return nativeParseYaml(nativeHandle, yamlContent)
    }
    
    /**
     * Parse YAML from a file
     * @return true if parsing succeeded
     */
    fun parseYamlFile(filePath: String): Boolean {
        if (!isNativeAvailable()) return false
        return nativeParseYamlFile(nativeHandle, filePath)
    }
    
    /**
     * Serialize current configuration to YAML string
     */
    fun toYaml(): String {
        if (!isNativeAvailable()) return ""
        return nativeToYaml(nativeHandle)
    }
    
    /**
     * Check if a configuration path exists
     */
    fun has(path: String): Boolean {
        if (!isNativeAvailable()) return false
        return nativeHas(nativeHandle, path)
    }
    
    /**
     * Get a string value
     */
    fun getString(path: String, defaultValue: String = ""): String {
        if (!isNativeAvailable()) return defaultValue
        return nativeGetString(nativeHandle, path, defaultValue)
    }
    
    /**
     * Get an integer value
     */
    fun getInteger(path: String, defaultValue: Long = 0L): Long {
        if (!isNativeAvailable()) return defaultValue
        return nativeGetInteger(nativeHandle, path, defaultValue)
    }
    
    /**
     * Get a float value
     */
    fun getFloat(path: String, defaultValue: Double = 0.0): Double {
        if (!isNativeAvailable()) return defaultValue
        return nativeGetFloat(nativeHandle, path, defaultValue)
    }
    
    /**
     * Get a boolean value
     */
    fun getBoolean(path: String, defaultValue: Boolean = false): Boolean {
        if (!isNativeAvailable()) return defaultValue
        return nativeGetBoolean(nativeHandle, path, defaultValue)
    }
    
    /**
     * Set a string value
     */
    fun setString(path: String, value: String) {
        if (!isNativeAvailable()) return
        nativeSetString(nativeHandle, path, value)
    }
    
    /**
     * Set an integer value
     */
    fun setInteger(path: String, value: Long) {
        if (!isNativeAvailable()) return
        nativeSetInteger(nativeHandle, path, value)
    }
    
    /**
     * Set a float value
     */
    fun setFloat(path: String, value: Double) {
        if (!isNativeAvailable()) return
        nativeSetFloat(nativeHandle, path, value)
    }
    
    /**
     * Set a boolean value
     */
    fun setBoolean(path: String, value: Boolean) {
        if (!isNativeAvailable()) return
        nativeSetBoolean(nativeHandle, path, value)
    }
    
    /**
     * Clear all configuration
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
    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeParseYaml(handle: Long, yamlContent: String): Boolean
    private external fun nativeParseYamlFile(handle: Long, filePath: String): Boolean
    private external fun nativeToYaml(handle: Long): String
    private external fun nativeHas(handle: Long, path: String): Boolean
    private external fun nativeGetString(handle: Long, path: String, defaultValue: String): String
    private external fun nativeGetInteger(handle: Long, path: String, defaultValue: Long): Long
    private external fun nativeGetFloat(handle: Long, path: String, defaultValue: Double): Double
    private external fun nativeGetBoolean(handle: Long, path: String, defaultValue: Boolean): Boolean
    private external fun nativeSetString(handle: Long, path: String, value: String)
    private external fun nativeSetInteger(handle: Long, path: String, value: Long)
    private external fun nativeSetFloat(handle: Long, path: String, value: Double)
    private external fun nativeSetBoolean(handle: Long, path: String, value: Boolean)
    private external fun nativeClear(handle: Long)
}
