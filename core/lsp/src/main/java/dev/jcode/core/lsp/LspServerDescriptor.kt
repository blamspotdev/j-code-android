package dev.jcode.core.lsp

/**
 * Describes a language server that can be launched inside the distro.
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
        /** Built-in LSP descriptors for common language servers. */
        val BUILT_IN: List<LspServerDescriptor> = listOf(
            LspServerDescriptor(
                id = "clangd",
                languageIds = listOf("c", "cpp"),
                verifyCommand = "clangd --version",
                installCommand = "apt-get install -y clangd",
                runCommand = "/usr/bin/clangd --background-index",
                extensions = listOf(".c", ".h", ".cpp", ".hpp", ".cc", ".cxx"),
                rootDetectors = listOf(".git", "compile_commands.json", "CMakeLists.txt"),
            ),
            LspServerDescriptor(
                id = "typescript-language-server",
                languageIds = listOf("typescript", "javascript", "typescriptreact", "javascriptreact"),
                verifyCommand = "typescript-language-server --version",
                installCommand = "npm i -g typescript typescript-language-server",
                runCommand = "typescript-language-server --stdio",
                extensions = listOf(".ts", ".tsx", ".js", ".jsx"),
                rootDetectors = listOf("package.json", "tsconfig.json", ".git"),
            ),
            LspServerDescriptor(
                id = "pyright",
                languageIds = listOf("python"),
                verifyCommand = "pyright-langserver --version",
                installCommand = "npm i -g pyright",
                runCommand = "pyright-langserver --stdio",
                extensions = listOf(".py"),
                rootDetectors = listOf("pyproject.toml", "setup.py", ".git"),
            ),
            LspServerDescriptor(
                id = "gopls",
                languageIds = listOf("go"),
                verifyCommand = "gopls version",
                installCommand = "go install golang.org/x/tools/gopls@latest",
                runCommand = "gopls",
                extensions = listOf(".go"),
                rootDetectors = listOf("go.mod", ".git"),
            ),
            LspServerDescriptor(
                id = "rust-analyzer",
                languageIds = listOf("rust"),
                verifyCommand = "rust-analyzer --version",
                installCommand = "rustup component add rust-analyzer",
                runCommand = "rust-analyzer",
                extensions = listOf(".rs"),
                rootDetectors = listOf("Cargo.toml", ".git"),
            ),
            LspServerDescriptor(
                id = "kotlin-language-server",
                languageIds = listOf("kotlin"),
                verifyCommand = "kotlin-language-server --version",
                installCommand = "echo 'kotlin-lsp install via SDKMAN'",
                runCommand = "kotlin-language-server",
                extensions = listOf(".kt", ".kts"),
                rootDetectors = listOf("build.gradle.kts", "settings.gradle.kts", ".git"),
            ),
        )

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
