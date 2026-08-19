package dev.jcode.core.lsp

/**
 * LSP (Language Server Protocol) client framework.
 *
 * Provides:
 * - [LspServerDescriptor] - describes a language server (built-in: clangd, tsserver, pyright, gopls, rust-analyzer, kotlin-lsp)
 * - [LspSession] - manages a running LSP session over PTY transport
 * - [DiagnosticsBus] - aggregates diagnostics from LSP, tree-sitter, and other sources
 * - Path translation between host paths and distro URIs
 *
 * Language servers run inside the proot-distro and communicate via JSON-RPC over stdio.
 */
object LspModule {
    val diagnosticsBus = DiagnosticsBus()
}
