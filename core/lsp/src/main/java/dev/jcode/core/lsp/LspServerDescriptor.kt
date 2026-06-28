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
    companion object {
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

        /** Find a descriptor for a given file extension. */
        fun findForExtension(extension: String): LspServerDescriptor? {
            return BUILT_IN.firstOrNull { extension in it.extensions }
        }
    }
}
