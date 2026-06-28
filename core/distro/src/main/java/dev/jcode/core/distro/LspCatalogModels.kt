package dev.jcode.core.distro

/**
 * A language server in the LSP catalog. This is the single source of truth for both the LSP Manager
 * (install/verify/uninstall) and the runtime launcher (run/languageIds/extensions). It lives in
 * `:core:distro` because the install machinery (`DistroService`) cannot depend on `:core:lsp`
 * (that would close a module cycle); `:core:lsp` derives its runtime descriptors from this list.
 */
data class LspCatalogEntry(
    val id: String,
    val category: String,
    val name: String,
    val description: String,
    val installCommand: String,
    val verifyCommand: String,
    val uninstallCommand: String,
    val runCommand: String,
    val languageIds: List<String> = emptyList(),
    val extensions: List<String> = emptyList(),
    val rootDetectors: List<String> = emptyList(),
)

enum class LspCatalogAction(val label: String) {
    Install("Install"),
    Verify("Verify"),
    Uninstall("Remove"),
}

data class LspCatalogState(
    val entries: List<LspCatalogEntry> = emptyList(),
    val installedEntryIds: Set<String> = emptySet(),
    val runningEntryId: String? = null,
    val runningAction: LspCatalogAction? = null,
    val executionLabel: String? = null,
    val logLines: List<String> = emptyList(),
    val selectedDistroId: String? = null,
    val errorMessage: String? = null,
)

/** Built-in language servers offered by the LSP Manager. */
object LspServerCatalog {
    val BUILT_IN: List<LspCatalogEntry> = listOf(
        LspCatalogEntry(
            id = "clangd",
            category = "Systems",
            name = "clangd (C/C++)",
            description = "Clang-based language server for C and C++.",
            installCommand = "sudo apt-get update && sudo apt-get install -y clangd",
            verifyCommand = "clangd --version",
            uninstallCommand = "sudo apt-get remove -y clangd",
            runCommand = "clangd --background-index",
            languageIds = listOf("c", "cpp"),
            extensions = listOf(".c", ".h", ".cpp", ".hpp", ".cc", ".cxx"),
            rootDetectors = listOf(".git", "compile_commands.json", "CMakeLists.txt"),
        ),
        LspCatalogEntry(
            id = "typescript-language-server",
            category = "Web",
            name = "TypeScript / JavaScript",
            description = "Language server for TypeScript and JavaScript (needs Node.js).",
            installCommand = "sudo npm install -g typescript typescript-language-server",
            verifyCommand = "typescript-language-server --version",
            uninstallCommand = "sudo npm rm -g typescript typescript-language-server",
            runCommand = "typescript-language-server --stdio",
            languageIds = listOf("typescript", "javascript", "typescriptreact", "javascriptreact"),
            extensions = listOf(".ts", ".tsx", ".js", ".jsx"),
            rootDetectors = listOf("package.json", "tsconfig.json", ".git"),
        ),
        LspCatalogEntry(
            id = "csharp-ls",
            category = ".NET",
            name = "C# (csharp-ls)",
            description = "Roslyn-based C# language server, installed as a .NET global tool.",
            installCommand = "dotnet tool install --global csharp-ls",
            verifyCommand = "csharp-ls --version",
            uninstallCommand = "dotnet tool uninstall --global csharp-ls",
            runCommand = "csharp-ls",
            languageIds = listOf("csharp"),
            extensions = listOf(".cs"),
            rootDetectors = listOf(".sln", ".csproj", ".git"),
        ),
        LspCatalogEntry(
            id = "pyright",
            category = "Scripting",
            name = "Pyright (Python)",
            description = "Static type checker and language server for Python (needs Node.js).",
            installCommand = "sudo npm install -g pyright",
            verifyCommand = "pyright-langserver --version",
            uninstallCommand = "sudo npm rm -g pyright",
            runCommand = "pyright-langserver --stdio",
            languageIds = listOf("python"),
            extensions = listOf(".py"),
            rootDetectors = listOf("pyproject.toml", "setup.py", ".git"),
        ),
        LspCatalogEntry(
            id = "gopls",
            category = "Systems",
            name = "gopls (Go)",
            description = "Official Go language server (needs the Go toolchain).",
            installCommand = "go install golang.org/x/tools/gopls@latest",
            verifyCommand = "gopls version",
            uninstallCommand = "rm -f \"\$(go env GOPATH 2>/dev/null || echo \"\$HOME/go\")/bin/gopls\"",
            runCommand = "gopls",
            languageIds = listOf("go"),
            extensions = listOf(".go"),
            rootDetectors = listOf("go.mod", ".git"),
        ),
        LspCatalogEntry(
            id = "rust-analyzer",
            category = "Systems",
            name = "rust-analyzer (Rust)",
            description = "Language server for Rust (needs rustup).",
            installCommand = "rustup component add rust-analyzer",
            verifyCommand = "rust-analyzer --version",
            uninstallCommand = "rustup component remove rust-analyzer",
            runCommand = "rust-analyzer",
            languageIds = listOf("rust"),
            extensions = listOf(".rs"),
            rootDetectors = listOf("Cargo.toml", ".git"),
        ),
        LspCatalogEntry(
            id = "kotlin-language-server",
            category = "JVM",
            name = "Kotlin Language Server",
            description = "Language server for Kotlin. Install manually via SDKMAN or a release archive.",
            installCommand = "echo 'Install kotlin-language-server via SDKMAN or a release archive, then re-run Verify.'",
            verifyCommand = "kotlin-language-server --version",
            uninstallCommand = "echo 'Remove kotlin-language-server from the location it was installed to.'",
            runCommand = "kotlin-language-server",
            languageIds = listOf("kotlin"),
            extensions = listOf(".kt", ".kts"),
            rootDetectors = listOf("build.gradle.kts", "settings.gradle.kts", ".git"),
        ),
    )

    fun findById(id: String): LspCatalogEntry? = BUILT_IN.firstOrNull { it.id == id }
}
