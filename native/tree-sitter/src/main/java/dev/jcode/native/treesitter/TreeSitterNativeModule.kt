package dev.jcode.native.treesitter

/**
 * libtreesitter.so (core) + libtree-sitter-<lang>.so per grammar.
 * Stub — actual implementation in Phase 5.1–5.2.
 */
object TreeSitterNativeModule {
    const val LIBRARY_NAME: String = "treesitter"

    fun loadLibrary() {
        System.loadLibrary(LIBRARY_NAME)
    }
}
