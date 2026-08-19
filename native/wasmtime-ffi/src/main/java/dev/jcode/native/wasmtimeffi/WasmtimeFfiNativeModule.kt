package dev.jcode.native.wasmtimeffi

/**
 * libjcode_wasm.so (Rust + cargo-ndk, wasmtime + WIT bindings).
 * Stub — actual implementation in Phase 14.
 */
object WasmtimeFfiNativeModule {
    const val LIBRARY_NAME: String = "jcode_wasm"

    fun loadLibrary() {
        System.loadLibrary(LIBRARY_NAME)
    }
}
