package dev.blamspot.jcode

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.blamspot.jcode.native.buffer.BufferNativeModule
import dev.blamspot.jcode.native.editorrender.EditorRenderNativeModule
import dev.blamspot.jcode.native.pty.PtyNativeModule
import dev.blamspot.jcode.native.ripgrepffi.RipgrepFfiNativeModule
import dev.blamspot.jcode.native.vt.VtNativeModule
import dev.blamspot.jcode.native.wasmtimeffi.WasmtimeFfiNativeModule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeLibrariesSmokeTest {

    @Test
    fun loadsAllNativeLibraries() {
        BufferNativeModule.loadLibrary()
        EditorRenderNativeModule.loadLibrary()
        RipgrepFfiNativeModule.loadLibrary()
        PtyNativeModule.loadLibrary()
        VtNativeModule.loadLibrary()
        WasmtimeFfiNativeModule.loadLibrary()
    }
}
