package dev.jcode.native.editorrender

/**
 * libjcodernd.so — shaping cache, glyph atlas helpers.
 * Stub — actual implementation in Phase 4.4.
 */
object EditorRenderNativeModule {
    const val LIBRARY_NAME: String = "jcodernd"

    fun loadLibrary() {
        System.loadLibrary(LIBRARY_NAME)
    }
}
