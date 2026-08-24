package dev.blamspot.jcode.core.distro

/**
 * A debug engine (Debug Adapter Protocol adapter) in the debug catalog. Single source of truth for
 * both the Debug Engine Manager (install/verify/uninstall) and the runtime DAP launcher
 * (`adapterCommand`/`debugType`/`languageIds`). It lives in `:core:distro` for the same reason as
 * [LspCatalogEntry]: the install machinery ([DistroService]) must not depend on `:core:debug`
 * (that would close a module cycle); `:core:debug` derives its runtime descriptors from this list.
 */
data class DebugEngineEntry(
    val id: String,
    val category: String,
    val name: String,
    val description: String,
    val installCommand: String,
    val verifyCommand: String,
    val uninstallCommand: String,
    /** Shell command that launches the debug adapter speaking DAP. `{{port}}` is substituted for tcp adapters. */
    val adapterCommand: String,
    /** "stdio" (adapter talks DAP over its stdin/stdout) or "tcp" (adapter listens on a port). */
    val transport: String = "stdio",
    /** The DAP `type` these launch/attach configs use (e.g. "python", "lldb", "coreclr", "pwa-node"). */
    val debugType: String = "",
    /** Optional: exits 0 when a newer version is available. Empty = update detection skipped. */
    val updateCheckCommand: String = "",
    val languageIds: List<String> = emptyList(),
    val extensions: List<String> = emptyList(),
    /** SDK catalog ids this adapter needs (e.g. netcoredbg needs the dotnet SDK). Installed first. */
    val requiredSdks: List<String> = emptyList(),
    /** False for entries that don't ship a real DAP adapter (the JVM/JDWP placeholder): the runtime
     *  must not try to launch them, or the `initialize` request just hangs until it times out. */
    val dapAdapter: Boolean = true,
)

enum class DebugEngineAction(val label: String) {
    Install("Install"),
    Uninstall("Remove"),
}

data class DebugEngineCatalogState(
    val entries: List<DebugEngineEntry> = emptyList(),
    val installedEntryIds: Set<String> = emptySet(),
    val updatableEntryIds: Set<String> = emptySet(),
    val checking: Boolean = false,
    val runningEntryId: String? = null,
    val runningAction: DebugEngineAction? = null,
    val executionLabel: String? = null,
    val logLines: List<String> = emptyList(),
    val selectedDistroId: String? = null,
    val errorMessage: String? = null,
)

/**
 * The debug adapters the Debug Engine manager offers: the ones JCode ships, plus the ones installed
 * extensions bring.
 *
 * An adapter for one language belongs to that language's Dev Pack rather than to the IDE — JCode is
 * generic, and a Python-only user has no use for a JVM debugger. [BUILT_IN] is what is left after
 * that: adapters that serve languages with no pack of their own, or that several packs share.
 */
object DebugEngineCatalog {
    val BUILT_IN: List<DebugEngineEntry> = listOf(
        DebugEngineEntry(
            id = "debugpy",
            category = "Scripting",
            name = "debugpy (Python)",
            description = "Python debug adapter. Breakpoints, stepping, variables, and evaluate for Python.",
            installCommand = "jcode_apt 0 100 'Installing debugpy' python3-debugpy",
            verifyCommand = "python3 -c 'import debugpy, sys; print(debugpy.__version__)'",
            uninstallCommand = "sudo apt-get remove -y python3-debugpy",
            adapterCommand = "python3 -m debugpy.adapter",
            transport = "stdio",
            debugType = "python",
            updateCheckCommand = "apt list --upgradable 2>/dev/null | grep -q '^python3-debugpy/'",
            languageIds = listOf("python"),
            extensions = listOf(".py", ".pyw"),
        ),
        DebugEngineEntry(
            id = "lldb-dap",
            category = "Systems",
            name = "lldb-dap (C / C++ / Rust)",
            description = "LLVM's native debug adapter for C, C++, and Rust. Installed with the LLDB package.",
            installCommand = "set -e; jcode_apt 0 90 'Installing LLDB' lldb; " +
                "TARGET=\$(command -v lldb-dap || command -v lldb-dap-18 || ls /usr/bin/lldb-dap-* 2>/dev/null | head -1); " +
                "[ -n \"\$TARGET\" ] && sudo ln -sf \"\$TARGET\" /usr/local/bin/lldb-dap || true; " +
                "jcode_progress 100 'lldb-dap ready'",
            // Never launch lldb-dap to verify: LLVM 18's lldb-dap ignores --version and falls through
            // into its DAP stdin loop, blocking until the verify times out (120s) and misreporting the
            // install as failed. A presence check is faithful to install success and can't hang.
            verifyCommand = "command -v lldb-dap >/dev/null 2>&1 || command -v lldb-dap-18 >/dev/null 2>&1 || " +
                "{ set -- /usr/bin/lldb-dap-*; [ -x \"\$1\" ]; }",
            uninstallCommand = "sudo rm -f /usr/local/bin/lldb-dap; sudo apt-get remove -y lldb; sudo apt-get autoremove -y",
            adapterCommand = "sh -c 'command -v lldb-dap >/dev/null 2>&1 && exec lldb-dap; command -v lldb-dap-18 >/dev/null 2>&1 && exec lldb-dap-18; set -- /usr/bin/lldb-dap-*; exec \"\$1\"'",
            transport = "stdio",
            debugType = "lldb",
            updateCheckCommand = "apt list --upgradable 2>/dev/null | grep -qE '^lldb'",
            languageIds = listOf("c", "cpp", "rust"),
            extensions = listOf(".c", ".h", ".cpp", ".hpp", ".cc", ".cxx", ".rs"),
        ),
        DebugEngineEntry(
            id = "netcoredbg",
            category = ".NET",
            name = "netcoredbg (.NET / C#)",
            description = "Samsung's DAP debugger for .NET. Download the ARM64 release; needs the .NET runtime.",
            // curl (not wget) for the download: the required dotnet SDK guarantees curl; wget is
            // absent from the minimal base rootfs and no SDK in the chain installs it.
            installCommand = "set -e; sudo mkdir -p /opt/netcoredbg; " +
                "jcode_fetch https://github.com/Samsung/netcoredbg/releases/latest/download/netcoredbg-linux-arm64.tar.gz " +
                "/tmp/netcoredbg.tar.gz 5 85 'Downloading netcoredbg'; " +
                "jcode_progress 90 'Unpacking netcoredbg'; " +
                "sudo tar xzf /tmp/netcoredbg.tar.gz -C /opt && rm -f /tmp/netcoredbg.tar.gz && " +
                "sudo ln -sf /opt/netcoredbg/netcoredbg /usr/local/bin/netcoredbg; " +
                "jcode_progress 100 'netcoredbg ready'",
            verifyCommand = "netcoredbg --version",
            uninstallCommand = "sudo rm -f /usr/local/bin/netcoredbg; sudo rm -rf /opt/netcoredbg",
            adapterCommand = "netcoredbg --interpreter=vscode",
            transport = "stdio",
            debugType = "coreclr",
            languageIds = listOf("csharp"),
            extensions = listOf(".cs"),
            requiredSdks = listOf("dotnet"),
        ),
        DebugEngineEntry(
            id = "js-debug",
            category = "Web",
            name = "js-debug (Node.js / JS / TS)",
            description = "VS Code's JavaScript/Node debug adapter (DAP over TCP), newest release resolved at install time. Needs Node.js.",
            // Not every vscode-js-debug release attaches the js-debug-dap tarball; take the newest
            // release that has one (the API lists releases newest-first).
            installCommand = "set -e; " +
                "URL=\$(curl -fsSL 'https://api.github.com/repos/microsoft/vscode-js-debug/releases?per_page=10' | grep -oE 'https://[^\"]*js-debug-dap-v[0-9.]+\\.tar\\.gz' | head -n1); " +
                "[ -n \"\$URL\" ] || { echo 'Could not find a js-debug release to download.'; exit 1; }; " +
                "jcode_fetch \"\$URL\" /tmp/js-debug.tar.gz 5 85 'Downloading js-debug'; " +
                "jcode_progress 90 'Unpacking js-debug'; " +
                "rm -rf \"\$HOME/js-debug\" && mkdir -p \"\$HOME/js-debug\" && tar xzf /tmp/js-debug.tar.gz -C \"\$HOME/js-debug\" --strip-components=1 && rm -f /tmp/js-debug.tar.gz && " +
                "basename \"\$URL\" > \"\$HOME/js-debug/.release\"; jcode_progress 100 'js-debug ready'",
            verifyCommand = "test -f \"\$HOME/js-debug/src/dapDebugServer.js\" && node -e \"process.exit(0)\"",
            uninstallCommand = "rm -rf \"\$HOME/js-debug\"",
            updateCheckCommand = "U=\$(curl -fsSL 'https://api.github.com/repos/microsoft/vscode-js-debug/releases?per_page=10' | grep -oE 'https://[^\"]*js-debug-dap-v[0-9.]+\\.tar\\.gz' | head -n1); " +
                "[ -n \"\$U\" ] && [ \"\$(basename \"\$U\")\" != \"\$(cat \"\$HOME/js-debug/.release\" 2>/dev/null)\" ]",
            adapterCommand = "node \"\$HOME/js-debug/src/dapDebugServer.js\" {{port}} 127.0.0.1",
            transport = "tcp",
            debugType = "pwa-node",
            languageIds = listOf("javascript", "typescript", "javascriptreact", "typescriptreact"),
            extensions = listOf(".js", ".jsx", ".ts", ".tsx", ".mjs", ".cjs"),
            requiredSdks = listOf("nodejs"),
        ),
    )

    /**
     * Adapters contributed by the currently-installed extensions, republished by the host whenever
     * that set changes.
     *
     * Held here rather than passed around because the consumers are spread across modules that must
     * not depend on the marketplace — the install machinery in `:core:distro`, the launcher in
     * `:core:debug` — and all of them already reach for this catalog.
     */
    @Volatile
    var contributed: List<DebugEngineEntry> = emptyList()
        private set

    /** Called by the host when the installed-extension set changes. */
    fun setContributed(entries: List<DebugEngineEntry>) {
        // An extension must not shadow a shipped adapter: ids are the install key, and two entries
        // claiming one id would race over the same directory.
        val builtInIds = BUILT_IN.mapTo(mutableSetOf()) { it.id }
        contributed = entries.filterNot { it.id in builtInIds }.distinctBy { it.id }
    }

    /** Everything on offer right now. */
    val all: List<DebugEngineEntry> get() = BUILT_IN + contributed

    fun findById(id: String): DebugEngineEntry? = all.firstOrNull { it.id == id }
}
