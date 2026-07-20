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
    /** Optional: exits 0 when a newer version is available. Empty = update detection skipped. */
    val updateCheckCommand: String = "",
    val languageIds: List<String> = emptyList(),
    val extensions: List<String> = emptyList(),
    val rootDetectors: List<String> = emptyList(),
    /** SDK catalog ids this server needs (e.g. csharp-ls needs the dotnet SDK). Installed first. */
    val requiredSdks: List<String> = emptyList(),
)

enum class LspCatalogAction(val label: String) {
    Install("Install"),
    Verify("Verify"),
    Uninstall("Remove"),
}

data class LspCatalogState(
    val entries: List<LspCatalogEntry> = emptyList(),
    val installedEntryIds: Set<String> = emptySet(),
    val updatableEntryIds: Set<String> = emptySet(),
    val checking: Boolean = false,
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
            updateCheckCommand = "apt list --upgradable 2>/dev/null | grep -qE '^clangd(-[0-9]+)?/'",
            languageIds = listOf("c", "cpp"),
            extensions = listOf(".c", ".h", ".cpp", ".hpp", ".cc", ".cxx"),
            rootDetectors = listOf(".git", "compile_commands.json", "CMakeLists.txt"),
        ),
        LspCatalogEntry(
            id = "typescript-language-server",
            category = "Web",
            name = "TypeScript / JavaScript",
            description = "Language server for TypeScript and JavaScript (needs Node.js).",
            // Pin typescript to the 5.x line: npm's `typescript` latest is now the 7.x Go rewrite,
            // which no longer ships lib/tsserver.js — typescript-language-server can't drive it and
            // fails to start for any project without a local typescript<=6. updateCheck below stays
            // scoped to the LSP only, so this pin isn't auto-bumped to 7.
            installCommand = "sudo npm install -g typescript@5 typescript-language-server",
            verifyCommand = "typescript-language-server --version",
            uninstallCommand = "sudo npm rm -g typescript typescript-language-server",
            runCommand = "typescript-language-server --stdio",
            updateCheckCommand = "test -n \"\$(npm outdated -g --parseable typescript-language-server 2>/dev/null)\"",
            languageIds = listOf("typescript", "javascript", "typescriptreact", "javascriptreact"),
            extensions = listOf(".ts", ".tsx", ".js", ".jsx"),
            rootDetectors = listOf("package.json", "tsconfig.json", ".git"),
            requiredSdks = listOf("nodejs"),
        ),
        LspCatalogEntry(
            id = "csharp-ls",
            category = ".NET",
            name = "C# (csharp-ls)",
            description = "Roslyn-based C# language server, installed as a .NET global tool (needs the .NET SDK toolchain).",
            // dotnet lives behind the /usr/local/bin/dotnet shim (GC heap cap + DOTNET_ROOT — see the
            // dotnet catalog entry); global tools land in ~/.dotnet/tools, which non-login shells
            // don't have on PATH, and the tool's apphost needs the same env to find the runtime.
            // Unpinned: current releases target .NET 10, which the dotnet toolchain's LTS channel
            // installs (the old 0.16.0 pin only mattered while that toolchain topped out at .NET 8).
            installCommand = "dotnet tool install --global csharp-ls",
            verifyCommand = "env DOTNET_ROOT=\"\$HOME/.dotnet\" DOTNET_GCHeapHardLimit=0x40000000 \"\$HOME/.dotnet/tools/csharp-ls\" --version",
            uninstallCommand = "dotnet tool uninstall --global csharp-ls",
            runCommand = "env DOTNET_ROOT=\"\$HOME/.dotnet\" DOTNET_GCHeapHardLimit=0x40000000 \"\$HOME/.dotnet/tools/csharp-ls\"",
            languageIds = listOf("csharp"),
            extensions = listOf(".cs"),
            rootDetectors = listOf(".sln", ".csproj", ".git"),
            requiredSdks = listOf("dotnet"),
        ),
        LspCatalogEntry(
            id = "pyright",
            category = "Scripting",
            name = "Pyright (Python)",
            description = "Static type checker and language server for Python (needs Node.js).",
            installCommand = "sudo npm install -g pyright",
            // pyright-langserver itself rejects --version ("Connection input stream is not set",
            // exit 1) — only the pyright CLI answers it, so verify through that.
            verifyCommand = "pyright --version",
            uninstallCommand = "sudo npm rm -g pyright",
            runCommand = "pyright-langserver --stdio",
            updateCheckCommand = "test -n \"\$(npm outdated -g --parseable pyright 2>/dev/null)\"",
            languageIds = listOf("python"),
            extensions = listOf(".py"),
            rootDetectors = listOf("pyproject.toml", "setup.py", ".git"),
            requiredSdks = listOf("nodejs"),
        ),
        LspCatalogEntry(
            id = "gopls",
            category = "Systems",
            name = "gopls (Go)",
            description = "Official Go language server (needs the Go toolchain).",
            // `go install` drops the binary into $GOPATH/bin, which is never on the fixed catalog
            // PATH — the /usr/local/bin symlink is what makes verify and the runtime launcher find it.
            installCommand = "set -e; go install golang.org/x/tools/gopls@latest; " +
                "sudo ln -sf \"\$(go env GOPATH)/bin/gopls\" /usr/local/bin/gopls",
            verifyCommand = "gopls version",
            uninstallCommand = "sudo rm -f /usr/local/bin/gopls; " +
                "rm -f \"\$(go env GOPATH 2>/dev/null || echo \"\$HOME/go\")/bin/gopls\"",
            runCommand = "gopls",
            languageIds = listOf("go"),
            extensions = listOf(".go"),
            rootDetectors = listOf("go.mod", ".git"),
            requiredSdks = listOf("go"),
        ),
        LspCatalogEntry(
            id = "rust-analyzer",
            category = "Systems",
            name = "rust-analyzer (Rust)",
            description = "Language server for Rust (needs rustup).",
            // rustup lives in ~/.cargo/bin, which is never on the fixed catalog PATH; the component's
            // real binary gets symlinked into /usr/local/bin so verify and the runtime launcher work.
            installCommand = "set -e; \"\$HOME/.cargo/bin/rustup\" component add rust-analyzer; " +
                "sudo ln -sf \"\$(\"\$HOME/.cargo/bin/rustup\" which rust-analyzer)\" /usr/local/bin/rust-analyzer",
            verifyCommand = "rust-analyzer --version",
            uninstallCommand = "sudo rm -f /usr/local/bin/rust-analyzer; " +
                "\"\$HOME/.cargo/bin/rustup\" component remove rust-analyzer",
            runCommand = "rust-analyzer",
            languageIds = listOf("rust"),
            extensions = listOf(".rs"),
            rootDetectors = listOf("Cargo.toml", ".git"),
            requiredSdks = listOf("rust"),
        ),
        LspCatalogEntry(
            id = "kotlin-language-server",
            category = "JVM",
            name = "Kotlin Language Server",
            description = "Language server for Kotlin (needs a JDK). Installed from the fwcd release archive.",
            // fwcd/kotlin-language-server ships a `server.zip` on each release; /releases/latest/download
            // always resolves to the newest asset, so no version needs pinning. It's a JVM app, so `jdk`
            // (which provides `java`) is required first.
            installCommand = "set -e; sudo apt-get install -y curl unzip; " +
                "curl -fsSL -o /tmp/kls.zip https://github.com/fwcd/kotlin-language-server/releases/latest/download/server.zip; " +
                "sudo rm -rf /opt/kotlin-language-server; sudo unzip -q -o /tmp/kls.zip -d /opt/kotlin-language-server; " +
                "sudo ln -sf /opt/kotlin-language-server/server/bin/kotlin-language-server /usr/local/bin/kotlin-language-server; " +
                "rm -f /tmp/kls.zip",
            // The server's arg parser knows only --tcpServerPort/--tcpClientPort/--tcpClientHost;
            // `--version` makes it throw and exit 1, so a launch-based verify misreports a good
            // install as failed. Check the launcher and the JVM it needs instead.
            verifyCommand = "test -x /opt/kotlin-language-server/server/bin/kotlin-language-server && " +
                "command -v java >/dev/null 2>&1 && echo ready",
            uninstallCommand = "sudo rm -rf /opt/kotlin-language-server /usr/local/bin/kotlin-language-server",
            runCommand = "kotlin-language-server",
            languageIds = listOf("kotlin"),
            extensions = listOf(".kt", ".kts"),
            rootDetectors = listOf("build.gradle.kts", "settings.gradle.kts", ".git"),
            requiredSdks = listOf("jdk"),
        ),
        LspCatalogEntry(
            id = "jdtls",
            category = "JVM",
            name = "Java (Eclipse JDT LS)",
            description = "Eclipse JDT language server for Java (needs a JDK). Installed from the latest Eclipse snapshot archive.",
            // The equinox launcher writes to its -configuration area, so the runtime uses a per-user
            // copy of config_linux instead of the root-owned /opt tree.
            installCommand = "set -e; sudo apt-get install -y curl; " +
                "curl -fsSL -o /tmp/jdtls.tar.gz https://download.eclipse.org/jdtls/snapshots/jdt-language-server-latest.tar.gz; " +
                "sudo rm -rf /opt/jdtls; sudo mkdir -p /opt/jdtls; sudo tar -xzf /tmp/jdtls.tar.gz -C /opt/jdtls; rm -f /tmp/jdtls.tar.gz; " +
                "rm -rf \"\$HOME/.jdtls\"; mkdir -p \"\$HOME/.jdtls\"; cp -r /opt/jdtls/config_linux \"\$HOME/.jdtls/config\"",
            verifyCommand = "ls /opt/jdtls/plugins/org.eclipse.equinox.launcher_*.jar >/dev/null 2>&1 && " +
                "command -v java >/dev/null 2>&1 && echo ready",
            uninstallCommand = "sudo rm -rf /opt/jdtls; rm -rf \"\$HOME/.jdtls\" \"\$HOME/.jdtls-data\"",
            runCommand = "java -Xmx512m -Declipse.application=org.eclipse.jdt.ls.core.id1 " +
                "-Declipse.product=org.eclipse.jdt.ls.core.product -Dosgi.bundles.defaultStartLevel=4 " +
                "--add-modules=ALL-SYSTEM --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED " +
                "-jar \$(ls /opt/jdtls/plugins/org.eclipse.equinox.launcher_*.jar | head -1) " +
                "-configuration \"\$HOME/.jdtls/config\" -data \"\$HOME/.jdtls-data\"",
            languageIds = listOf("java"),
            extensions = listOf(".java"),
            rootDetectors = listOf("pom.xml", "build.gradle", "build.gradle.kts", ".git"),
            requiredSdks = listOf("jdk"),
        ),
        // vscode-langservers-extracted bundles the HTML, CSS and JSON servers in one npm package; each
        // entry installs the same package but runs its own binary (needs Node.js). None of the three
        // answers --version (the HTML one even hangs on it), so verify checks presence + a working node.
        LspCatalogEntry(
            id = "vscode-html-language-server",
            category = "Web",
            name = "HTML",
            description = "HTML language server from vscode-langservers-extracted (needs Node.js).",
            installCommand = "sudo npm install -g vscode-langservers-extracted",
            verifyCommand = "command -v vscode-html-language-server >/dev/null 2>&1 && node -e \"process.exit(0)\"",
            uninstallCommand = "sudo npm rm -g vscode-langservers-extracted",
            runCommand = "vscode-html-language-server --stdio",
            updateCheckCommand = "test -n \"\$(npm outdated -g --parseable vscode-langservers-extracted 2>/dev/null)\"",
            languageIds = listOf("html"),
            extensions = listOf(".html", ".htm"),
            rootDetectors = listOf(".git"),
            requiredSdks = listOf("nodejs"),
        ),
        LspCatalogEntry(
            id = "vscode-css-language-server",
            category = "Web",
            name = "CSS / SCSS / LESS",
            description = "CSS language server from vscode-langservers-extracted (needs Node.js).",
            installCommand = "sudo npm install -g vscode-langservers-extracted",
            verifyCommand = "command -v vscode-css-language-server >/dev/null 2>&1 && node -e \"process.exit(0)\"",
            uninstallCommand = "sudo npm rm -g vscode-langservers-extracted",
            runCommand = "vscode-css-language-server --stdio",
            updateCheckCommand = "test -n \"\$(npm outdated -g --parseable vscode-langservers-extracted 2>/dev/null)\"",
            languageIds = listOf("css", "scss", "less"),
            extensions = listOf(".css", ".scss", ".less"),
            rootDetectors = listOf(".git"),
            requiredSdks = listOf("nodejs"),
        ),
        LspCatalogEntry(
            id = "vscode-json-language-server",
            category = "Web",
            name = "JSON",
            description = "JSON language server from vscode-langservers-extracted (needs Node.js).",
            installCommand = "sudo npm install -g vscode-langservers-extracted",
            verifyCommand = "command -v vscode-json-language-server >/dev/null 2>&1 && node -e \"process.exit(0)\"",
            uninstallCommand = "sudo npm rm -g vscode-langservers-extracted",
            runCommand = "vscode-json-language-server --stdio",
            updateCheckCommand = "test -n \"\$(npm outdated -g --parseable vscode-langservers-extracted 2>/dev/null)\"",
            languageIds = listOf("json", "jsonc"),
            extensions = listOf(".json", ".jsonc"),
            rootDetectors = listOf(".git"),
            requiredSdks = listOf("nodejs"),
        ),
        LspCatalogEntry(
            id = "yaml-language-server",
            category = "Web",
            name = "YAML",
            description = "YAML language server by Red Hat (needs Node.js).",
            installCommand = "sudo npm install -g yaml-language-server",
            verifyCommand = "yaml-language-server --version",
            uninstallCommand = "sudo npm rm -g yaml-language-server",
            runCommand = "yaml-language-server --stdio",
            updateCheckCommand = "test -n \"\$(npm outdated -g --parseable yaml-language-server 2>/dev/null)\"",
            languageIds = listOf("yaml"),
            extensions = listOf(".yaml", ".yml"),
            rootDetectors = listOf(".git"),
            requiredSdks = listOf("nodejs"),
        ),
    )

    fun findById(id: String): LspCatalogEntry? = BUILT_IN.firstOrNull { it.id == id }
}
