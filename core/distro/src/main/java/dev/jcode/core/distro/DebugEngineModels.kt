package dev.jcode.core.distro

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
)

enum class DebugEngineAction(val label: String) {
    Install("Install"),
    Verify("Verify"),
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

/** Built-in debug adapters offered by the Debug Engine Manager. */
object DebugEngineCatalog {
    val BUILT_IN: List<DebugEngineEntry> = listOf(
        DebugEngineEntry(
            id = "debugpy",
            category = "Scripting",
            name = "debugpy (Python)",
            description = "Python debug adapter. Breakpoints, stepping, variables, and evaluate for Python.",
            installCommand = "sudo DEBIAN_FRONTEND=noninteractive apt-get install -y python3-debugpy || " +
                "(sudo apt-get update && sudo DEBIAN_FRONTEND=noninteractive apt-get install -y python3-debugpy)",
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
            installCommand = "sudo apt-get update && sudo apt-get install -y lldb && " +
                "sudo ln -sf \"\$(command -v lldb-dap || command -v lldb-dap-18 || command -v lldb-vscode-18 || command -v lldb-vscode)\" /usr/local/bin/lldb-dap 2>/dev/null || true",
            verifyCommand = "lldb-dap --version 2>/dev/null || lldb-dap-18 --version 2>/dev/null || lldb-vscode-18 -h",
            uninstallCommand = "sudo rm -f /usr/local/bin/lldb-dap; sudo apt-get remove -y lldb",
            adapterCommand = "sh -c 'command -v lldb-dap >/dev/null 2>&1 && exec lldb-dap || (command -v lldb-dap-18 >/dev/null 2>&1 && exec lldb-dap-18 || exec lldb-vscode-18)'",
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
            installCommand = "set -e; sudo mkdir -p /opt/netcoredbg && " +
                "wget -qO /tmp/netcoredbg.tar.gz https://github.com/Samsung/netcoredbg/releases/latest/download/netcoredbg-linux-arm64.tar.gz && " +
                "sudo tar xzf /tmp/netcoredbg.tar.gz -C /opt && rm -f /tmp/netcoredbg.tar.gz && " +
                "sudo ln -sf /opt/netcoredbg/netcoredbg /usr/local/bin/netcoredbg",
            verifyCommand = "netcoredbg --version",
            uninstallCommand = "sudo rm -f /usr/local/bin/netcoredbg; sudo rm -rf /opt/netcoredbg",
            adapterCommand = "netcoredbg --interpreter=vscode",
            transport = "stdio",
            debugType = "coreclr",
            languageIds = listOf("csharp"),
            extensions = listOf(".cs"),
        ),
        DebugEngineEntry(
            id = "js-debug",
            category = "Web",
            name = "js-debug (Node.js / JS / TS)",
            description = "VS Code's JavaScript/Node debug adapter (DAP over TCP). Needs Node.js.",
            installCommand = "set -e; V=v1.100.0; " +
                "wget -qO /tmp/js-debug.tar.gz https://github.com/microsoft/vscode-js-debug/releases/download/\$V/js-debug-dap-\$V.tar.gz && " +
                "rm -rf \"\$HOME/js-debug\" && mkdir -p \"\$HOME/js-debug\" && tar xzf /tmp/js-debug.tar.gz -C \"\$HOME/js-debug\" --strip-components=1 && rm -f /tmp/js-debug.tar.gz",
            verifyCommand = "test -f \"\$HOME/js-debug/src/dapDebugServer.js\" && node -e \"process.exit(0)\"",
            uninstallCommand = "rm -rf \"\$HOME/js-debug\"",
            adapterCommand = "node \"\$HOME/js-debug/src/dapDebugServer.js\" {{port}} 127.0.0.1",
            transport = "tcp",
            debugType = "pwa-node",
            languageIds = listOf("javascript", "typescript", "javascriptreact", "typescriptreact"),
            extensions = listOf(".js", ".jsx", ".ts", ".tsx", ".mjs", ".cjs"),
        ),
        DebugEngineEntry(
            id = "java-debug",
            category = "JVM",
            name = "Java / Kotlin (JDWP)",
            description = "JVM debugging via JDWP. Advanced: launch your app with " +
                "-agentlib:jdwp and attach. A full DAP adapter (java-debug) is installed manually.",
            installCommand = "echo 'JVM debug uses JDWP. Run your app with " +
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 and attach. " +
                "For a DAP adapter, install microsoft java-debug manually, then re-run Verify.'",
            verifyCommand = "command -v java >/dev/null 2>&1 && java -version",
            uninstallCommand = "echo 'Nothing to remove for JDWP-based debugging.'",
            adapterCommand = "echo 'java-debug adapter not installed; use JDWP attach'",
            transport = "stdio",
            debugType = "java",
            languageIds = listOf("java", "kotlin"),
            extensions = listOf(".java", ".kt", ".kts"),
        ),
    )

    fun findById(id: String): DebugEngineEntry? = BUILT_IN.firstOrNull { it.id == id }
}
