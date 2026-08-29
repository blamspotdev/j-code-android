package dev.blamspot.jcode.core.distro

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
    Uninstall("Remove"),
}

data class LspCatalogState(
    val entries: List<LspCatalogEntry> = emptyList(),
    val installedEntryIds: Set<String> = emptySet(),
    /**
     * Whether [installedEntryIds] has been loaded for the active distro at least once.
     *
     * Until then an empty set means "not known yet", not "nothing installed" — and the two are
     * indistinguishable by timing, because the load is quiet for ~10s on a cold launch while the
     * environment is probed first. Anything that would accuse a server of being missing must wait
     * for this.
     */
    val loaded: Boolean = false,
    val updatableEntryIds: Set<String> = emptySet(),
    val checking: Boolean = false,
    val runningEntryId: String? = null,
    val runningAction: LspCatalogAction? = null,
    val executionLabel: String? = null,
    val logLines: List<String> = emptyList(),
    val selectedDistroId: String? = null,
    val errorMessage: String? = null,
)

/**
 * Symlinks an npm-installed global binary into `/usr/local/bin`.
 *
 * Node is installed through nvm, whose init lives at the bottom of `~/.bashrc` — and Ubuntu's
 * `.bashrc` returns immediately when the shell is not interactive. Every catalog script and the LSP
 * launcher run non-interactively (`su - jcode -c '…'`), so nvm never loads and `$(npm prefix -g)/bin`
 * is never on PATH. `node`/`npm` themselves are usable only because the nodejs entry symlinks them;
 * anything installed by `npm i -g` afterwards needs the same treatment, or both `verifyCommand` and
 * the runtime launcher fail with "command not found". gopls and rust-analyzer solve the identical
 * problem for `$GOPATH/bin` and `~/.cargo/bin`.
 */
private fun linkNpmBin(vararg names: String): String =
    names.joinToString("; ") { name ->
        "sudo ln -sf \"\$(npm prefix -g)/bin/$name\" /usr/local/bin/$name"
    }

private fun unlinkNpmBin(vararg names: String): String =
    "sudo rm -f " + names.joinToString(" ") { "/usr/local/bin/$it" }

/** Built-in language servers offered by the LSP Manager. */
object LspServerCatalog {
    val BUILT_IN: List<LspCatalogEntry> = listOf(
        LspCatalogEntry(
            id = "clangd",
            category = "Systems",
            name = "clangd (C/C++)",
            description = "Clang-based language server for C and C++.",
            installCommand = "jcode_apt 0 100 'Installing clangd' clangd",
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
            installCommand = "set -e; jcode_progress 10 'Installing the TypeScript language server'; " +
                "sudo npm install -g typescript@5 typescript-language-server; " +
                linkNpmBin("typescript-language-server", "tsserver") + "; " +
                "jcode_progress 100 'TypeScript language server ready'",
            verifyCommand = "typescript-language-server --version",
            uninstallCommand = "sudo npm rm -g typescript typescript-language-server; " +
                unlinkNpmBin("typescript-language-server", "tsserver"),
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
            installCommand = "set -e; jcode_progress 10 'Installing csharp-ls'; " +
                "dotnet tool install --global csharp-ls; jcode_progress 100 'csharp-ls ready'",
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
            installCommand = "set -e; jcode_progress 10 'Installing Pyright'; " +
                "sudo npm install -g pyright; " +
                linkNpmBin("pyright", "pyright-langserver") + "; " +
                "jcode_progress 100 'Pyright ready'",
            // pyright-langserver itself rejects --version ("Connection input stream is not set",
            // exit 1) — only the pyright CLI answers it, so verify through that.
            verifyCommand = "pyright --version",
            uninstallCommand = "sudo npm rm -g pyright; " + unlinkNpmBin("pyright", "pyright-langserver"),
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
            installCommand = "set -e; jcode_progress 10 'Building gopls'; " +
                "go install golang.org/x/tools/gopls@latest; jcode_progress 90 'Putting gopls on PATH'; " +
                "sudo ln -sf \"\$(go env GOPATH)/bin/gopls\" /usr/local/bin/gopls; " +
                "jcode_progress 100 'gopls ready'",
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
            installCommand = "set -e; jcode_progress 10 'Adding the rust-analyzer component'; " +
                "\"\$HOME/.cargo/bin/rustup\" component add rust-analyzer; " +
                "jcode_progress 90 'Putting rust-analyzer on PATH'; " +
                "sudo ln -sf \"\$(\"\$HOME/.cargo/bin/rustup\" which rust-analyzer)\" /usr/local/bin/rust-analyzer; " +
                "jcode_progress 100 'rust-analyzer ready'",
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
            id = "dart-language-server",
            category = "Dart",
            name = "Dart Analysis Server",
            description = "Analysis server for Dart and Flutter. Ships inside the Flutter SDK, so " +
                "installing it is installing Flutter — there is nothing else to fetch.",
            // Nothing to install: `dart` is part of the Flutter SDK, and requiredSdks below is what
            // actually brings it. Saying so out loud beats a no-op that reads as a broken script.
            installCommand = "command -v dart >/dev/null 2>&1 || " +
                "{ echo 'The Flutter SDK provides dart; install it first.'; exit 1; }; " +
                "echo 'The Dart analysis server ships with Flutter — nothing further to install.'",
            verifyCommand = "dart language-server --help >/dev/null 2>&1 && echo ready",
            // Removing it would mean removing Flutter, which is the SDK entry's business and not
            // something a language server should do behind the user's back.
            uninstallCommand = "echo 'The Dart analysis server is part of the Flutter SDK. " +
                "Remove the Flutter SDK to remove it.'",
            runCommand = "dart language-server --protocol=lsp --client-id=jcode",
            languageIds = listOf("dart"),
            extensions = listOf(".dart"),
            rootDetectors = listOf("pubspec.yaml", ".git"),
            requiredSdks = listOf("flutter"),
        ),
        LspCatalogEntry(
            id = "kotlin-language-server",
            category = "JVM",
            name = "Kotlin Language Server",
            description = "Language server for Kotlin (needs a JDK). Installed from the fwcd release archive.",
            // fwcd/kotlin-language-server ships a `server.zip` on each release; /releases/latest/download
            // always resolves to the newest asset, so no version needs pinning. It's a JVM app, so `jdk`
            // (which provides `java`) is required first.
            installCommand = "set -e; jcode_apt 0 10 'Installing download prerequisites' curl unzip; " +
                // The server bundles kotlin-compiler 2.1, whose IntelliJ JavaVersion.parse throws
                // IllegalArgumentException on a "26.0.1" version string — so it cannot run on the
                // JDK 26 that Ubuntu 26.04's default-jdk provides. Install an LTS JVM for this
                // server and pin it in runCommand; jdtls is unaffected and keeps using `jdk`.
                "jcode_apt 10 35 'Installing a compatible JVM (Kotlin needs an LTS JDK)' openjdk-21-jdk-headless; " +
                "jcode_fetch https://github.com/fwcd/kotlin-language-server/releases/latest/download/server.zip " +
                "/tmp/kls.zip 35 85 'Downloading the Kotlin language server'; " +
                "jcode_progress 88 'Unpacking the Kotlin language server'; " +
                "sudo rm -rf /opt/kotlin-language-server; sudo unzip -q -o /tmp/kls.zip -d /opt/kotlin-language-server; " +
                "sudo ln -sf /opt/kotlin-language-server/server/bin/kotlin-language-server /usr/local/bin/kotlin-language-server; " +
                "rm -f /tmp/kls.zip; jcode_progress 100 'Kotlin language server ready'",
            // The server's arg parser knows only --tcpServerPort/--tcpClientPort/--tcpClientHost;
            // `--version` makes it throw and exit 1, so a launch-based verify misreports a good
            // install as failed. Check the launcher and the JVM it needs instead.
            verifyCommand = "test -x /opt/kotlin-language-server/server/bin/kotlin-language-server && " +
                "ls -d /usr/lib/jvm/java-21-openjdk-* >/dev/null 2>&1 && echo ready",
            uninstallCommand = "sudo rm -rf /opt/kotlin-language-server /usr/local/bin/kotlin-language-server",
            // JAVA_HOME pins the LTS JVM installed above; the launcher is a Gradle start script, which
            // prefers JAVA_HOME over whatever `java` PATH happens to resolve to.
            runCommand = "env JAVA_HOME=\"\$(ls -d /usr/lib/jvm/java-21-openjdk-* | head -1)\" " +
                "kotlin-language-server",
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
            installCommand = "set -e; jcode_apt 0 15 'Installing download prerequisites' curl; " +
                "jcode_fetch https://download.eclipse.org/jdtls/snapshots/jdt-language-server-latest.tar.gz " +
                "/tmp/jdtls.tar.gz 15 85 'Downloading Eclipse JDT LS'; " +
                "jcode_progress 88 'Unpacking Eclipse JDT LS'; " +
                "sudo rm -rf /opt/jdtls; sudo mkdir -p /opt/jdtls; sudo tar -xzf /tmp/jdtls.tar.gz -C /opt/jdtls; rm -f /tmp/jdtls.tar.gz; " +
                "rm -rf \"\$HOME/.jdtls\"; mkdir -p \"\$HOME/.jdtls\"; cp -r /opt/jdtls/config_linux \"\$HOME/.jdtls/config\"; " +
                "jcode_progress 100 'Eclipse JDT LS ready'",
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
            installCommand = "set -e; jcode_progress 10 'Installing the HTML/CSS/JSON servers'; " +
                "sudo npm install -g vscode-langservers-extracted; " +
                // One package ships all three binaries, so each entry links all three.
                linkNpmBin(
                    "vscode-html-language-server",
                    "vscode-css-language-server",
                    "vscode-json-language-server",
                ) + "; " +
                "jcode_progress 100 'HTML/CSS/JSON servers ready'",
            verifyCommand = "command -v vscode-html-language-server >/dev/null 2>&1 && node -e \"process.exit(0)\"",
            uninstallCommand = "sudo npm rm -g vscode-langservers-extracted; " +
                unlinkNpmBin(
                    "vscode-html-language-server",
                    "vscode-css-language-server",
                    "vscode-json-language-server",
                ),
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
            installCommand = "set -e; jcode_progress 10 'Installing the HTML/CSS/JSON servers'; " +
                "sudo npm install -g vscode-langservers-extracted; " +
                // One package ships all three binaries, so each entry links all three.
                linkNpmBin(
                    "vscode-html-language-server",
                    "vscode-css-language-server",
                    "vscode-json-language-server",
                ) + "; " +
                "jcode_progress 100 'HTML/CSS/JSON servers ready'",
            verifyCommand = "command -v vscode-css-language-server >/dev/null 2>&1 && node -e \"process.exit(0)\"",
            uninstallCommand = "sudo npm rm -g vscode-langservers-extracted; " +
                unlinkNpmBin(
                    "vscode-html-language-server",
                    "vscode-css-language-server",
                    "vscode-json-language-server",
                ),
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
            installCommand = "set -e; jcode_progress 10 'Installing the HTML/CSS/JSON servers'; " +
                "sudo npm install -g vscode-langservers-extracted; " +
                // One package ships all three binaries, so each entry links all three.
                linkNpmBin(
                    "vscode-html-language-server",
                    "vscode-css-language-server",
                    "vscode-json-language-server",
                ) + "; " +
                "jcode_progress 100 'HTML/CSS/JSON servers ready'",
            verifyCommand = "command -v vscode-json-language-server >/dev/null 2>&1 && node -e \"process.exit(0)\"",
            uninstallCommand = "sudo npm rm -g vscode-langservers-extracted; " +
                unlinkNpmBin(
                    "vscode-html-language-server",
                    "vscode-css-language-server",
                    "vscode-json-language-server",
                ),
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
            installCommand = "set -e; jcode_progress 10 'Installing the YAML language server'; " +
                "sudo npm install -g yaml-language-server; " +
                linkNpmBin("yaml-language-server") + "; " +
                "jcode_progress 100 'YAML language server ready'",
            verifyCommand = "yaml-language-server --version",
            uninstallCommand = "sudo npm rm -g yaml-language-server; " + unlinkNpmBin("yaml-language-server"),
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
