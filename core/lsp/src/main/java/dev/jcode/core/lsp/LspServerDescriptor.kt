package dev.jcode.core.lsp

import dev.jcode.core.distro.LspServerCatalog

/**
 * Describes a language server that can be launched inside the distro. The set of known servers is
 * defined once in `:core:distro` ([LspServerCatalog]); this is the runtime-facing view used by the
 * LSP client, derived from that single source so the catalog never drifts.
 */
data class LspServerDescriptor(
    /** Unique identifier for this language server */
    val id: String,
    /** Language IDs this server handles */
    val languageIds: List<String>,
    /** Command to verify the server is installed */
    val verifyCommand: String,
    /** Command to install the server (apt/npm/cargo) */
    val installCommand: String,
    /** Command to run the server (stdio mode) */
    val runCommand: String,
    /** File extensions this server handles */
    val extensions: List<String> = emptyList(),
    /** Root file detectors (e.g., ".git", "package.json") */
    val rootDetectors: List<String> = emptyList(),
) {
    /**
     * The LSP `languageId` to announce for a file with this extension.
     *
     * Most catalog entries list extensions and language ids in matching order (`.ts`/`.tsx`/`.js`
     * -> `typescript`/`typescriptreact`/`javascript`), so index matching is exact for them. Entries
     * where one server covers more extensions than language ids (clangd's six C/C++ extensions over
     * `c` and `cpp`) fall back to the C-family split, then to the primary language id.
     */
    fun languageIdFor(extension: String): String {
        val ext = extension.lowercase()
        if (extensions.size == languageIds.size) {
            val index = extensions.indexOfFirst { it.equals(ext, ignoreCase = true) }
            if (index >= 0) return languageIds[index]
        }
        if (languageIds.size > 1 && ext in C_EXTENSIONS) {
            val id = if (ext == ".c" || ext == ".h") "c" else "cpp"
            if (id in languageIds) return id
        }
        return languageIds.firstOrNull() ?: "plaintext"
    }

    companion object {
        private val C_EXTENSIONS = setOf(".c", ".h", ".cpp", ".hpp", ".cc", ".cxx")

        /** Built-in LSP descriptors, derived from the shared `:core:distro` catalog. */
        val BUILT_IN: List<LspServerDescriptor> = LspServerCatalog.BUILT_IN.map { entry ->
            LspServerDescriptor(
                id = entry.id,
                languageIds = entry.languageIds,
                verifyCommand = entry.verifyCommand,
                installCommand = entry.installCommand,
                runCommand = entry.runCommand,
                extensions = entry.extensions,
                rootDetectors = entry.rootDetectors,
            )
        }

        /** Find a descriptor for a given language ID. */
        fun findForLanguage(languageId: String): LspServerDescriptor? {
            return BUILT_IN.firstOrNull { languageId in it.languageIds }
        }

        /** Find a descriptor for a given file extension (leading dot, case-insensitive). */
        fun findForExtension(extension: String): LspServerDescriptor? {
            val ext = extension.lowercase()
            return BUILT_IN.firstOrNull { descriptor ->
                descriptor.extensions.any { it.equals(ext, ignoreCase = true) }
            }
        }

        /** Find the descriptor that handles [fileName], matching on its extension. */
        fun findForFile(fileName: String): LspServerDescriptor? {
            val dot = fileName.lastIndexOf('.')
            if (dot < 0 || dot == fileName.length - 1) return null
            return findForExtension(fileName.substring(dot))
        }
    }
}
