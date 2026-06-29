package dev.jcode

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.jcode.native.buffer.BufferNativeModule
import dev.jcode.native.editorrender.EditorRenderNativeModule
import dev.jcode.native.libgit2.Libgit2NativeModule
import dev.jcode.native.pty.PtyNativeModule
import dev.jcode.native.ripgrepffi.RipgrepFfiNativeModule
import dev.jcode.native.treesitter.TreeSitterNativeModule
import dev.jcode.native.vt.VtNativeModule
import dev.jcode.native.wasmtimeffi.WasmtimeFfiNativeModule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeLibrariesSmokeTest {

    @Test
    fun loadsAllNativeLibraries() {
        BufferNativeModule.loadLibrary()
        EditorRenderNativeModule.loadLibrary()
        TreeSitterNativeModule.loadLibrary()
        Libgit2NativeModule.loadLibrary()
        RipgrepFfiNativeModule.loadLibrary()
        PtyNativeModule.loadLibrary()
        VtNativeModule.loadLibrary()
        WasmtimeFfiNativeModule.loadLibrary()
    }
}
