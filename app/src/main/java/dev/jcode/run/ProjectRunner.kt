package dev.jcode.run

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.jcode.fs.FsPath
import dev.jcode.fs.Project
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Project-type-aware build & run recipes executed inside the embedded Linux runtime.
 *
 * A [RunPlan] describes one or more [RunTerminal]s, each a self-contained bash script run in its own
 * right-drawer terminal (so e.g. an ASP.NET backend and a Vite frontend run side by side in dev).
 * Recipes are **self-healing**: a partially-scaffolded project (server but no client) is repaired in
 * the run terminal rather than failing. Front-end installs/builds happen in the runtime's ext4 home
 * (`$HOME/.jcode-run/<name>`) because the FUSE `/workspace` mount has no symlinks, so `node_modules`
 * cannot live there. The .NET build output likewise goes to ext4 because `/workspace` is noexec.
 *
 * proot shares the app process's network stack, so a server bound to `0.0.0.0:<port>` in the guest
 * is reachable from the device browser at `http://localhost:<port>`.
 */
object ProjectRunner {

    /** One terminal of a run: a label (its tab name) and the bash script to execute in it. */
    data class RunTerminal(
        val label: String,
        val command: String,
    )

    /** A runnable recipe for a detected project type, across one or more terminals. */
    data class RunPlan(
        /** Short human label, e.g. "ASP.NET Core + Vite React (dev)". */
        val kindLabel: String,
        /** Localhost port to poll for readiness and open in the browser (the dev frontend). */
        val readyPort: Int,
        /** The terminals to spawn, in start order. */
        val terminals: List<RunTerminal>,
    ) {
        val url: String get() = "http://localhost:$readyPort"
    }

    /**
     * Detect how to build & run [project], or null if no recipe matches. Detection prefers the
     * project's scaffold [Project.templateId] (authoritative, cheap), then falls back to a filesystem
     * probe for projects opened without a J Code template. Recipes self-heal, so detection does NOT
     * require a complete on-disk layout.
     */
    fun detectRunPlan(project: Project): RunPlan? {
        val root = (project.fsPath as? FsPath.Local)?.file ?: return null
        val guestDir = project.distroBindTarget.trimEnd('/')
        val stageName = sanitizeStageName(project.name)

        when (project.templateId) {
            "aspnet-vite-react-ts" -> return aspnetVitePlan(guestDir, stageName)
            "react-app" -> return vitePlan(guestDir, stageName)
            "angular-aspnet", "empty" -> return null
            null -> Unit
            else -> {
                val id = project.templateId.orEmpty()
                if (id.contains("vite") || id.contains("react") || id.contains("node")) {
                    return vitePlan(guestDir, stageName)
                }
            }
        }

        if (!root.isDirectory) return null
        val serverDir = File(root, "Server")
        val hasCsproj = serverDir.isDirectory &&
            serverDir.listFiles()?.any { it.extension.equals("csproj", ignoreCase = true) } == true
        if (hasCsproj) return aspnetVitePlan(guestDir, stageName)
        if (File(root, "package.json").isFile) return vitePlan(guestDir, stageName)
        return null
    }

    // ASP.NET Core + Vite React, dev: the backend (Development, :5080) and the Vite dev server
    // (:5173) run in their own terminals, started server-first. The browser opens the Vite frontend.
    private fun aspnetVitePlan(guestDir: String, stageName: String) = RunPlan(
        kindLabel = "ASP.NET Core + Vite React (dev)",
        readyPort = VITE_PORT,
        terminals = listOf(
            RunTerminal("Server", aspnetServerCommand(guestDir, stageName, ASPNET_PORT)),
            RunTerminal("Client", viteClientCommand(guestDir, stageName, VITE_PORT, "$guestDir/client")),
        ),
    )

    // Standalone Vite/React app (the project root is the app).
    private fun vitePlan(guestDir: String, stageName: String) = RunPlan(
        kindLabel = "Vite / React dev server",
        readyPort = VITE_PORT,
        terminals = listOf(
            RunTerminal("Client", viteClientCommand(guestDir, stageName, VITE_PORT, guestDir)),
        ),
    )

    /**
     * Prepare the terminal command that runs [terminal]. Its script body is written to the project's
     * `.jcode/run-<label>.sh` and invoked with a single `bash <path>` line, so the interactive shell
     * doesn't echo the whole script back with `>` continuation prompts. The script is read, not
     * executed, so the noexec `/workspace` mount is fine. Falls back to an inline heredoc if the
     * script can't be written.
     */
    fun runInvocation(project: Project, terminal: RunTerminal): String {
        val hostDir = (project.fsPath as? FsPath.Local)?.file
        val scriptName = "run-${sanitizeStageName(terminal.label)}.sh"
        if (hostDir != null) {
            val written = runCatching {
                val dir = File(hostDir, ".jcode").apply { if (!exists()) mkdirs() }
                File(dir, scriptName).writeText(terminal.command)
            }.isSuccess
            if (written) {
                val guestDir = project.distroBindTarget.trimEnd('/')
                return "bash \"$guestDir/.jcode/$scriptName\""
            }
        }
        return "bash <<'JCRUN'\n${terminal.command}\nJCRUN"
    }

    /**
     * Poll [port] on localhost until the server accepts a connection or [timeoutMs] elapses.
     * Returns true once reachable. Runs on [Dispatchers.IO]; cancellation-safe.
     */
    suspend fun awaitServer(port: Int, timeoutMs: Long = SERVER_WAIT_MS): Boolean =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val reachable = runCatching {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS)
                        true
                    }
                }.getOrDefault(false)
                if (reachable) return@withContext true
                delay(POLL_INTERVAL_MS)
            }
            false
        }

    /** Open [url] in the device's default browser app. */
    fun openInBrowser(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    // --- command builders -------------------------------------------------

    // Each recipe is a self-contained bash script (run via `bash .jcode/run-<label>.sh`). `set -e`
    // aborts on the first failing step. $PROJ/$STAGE/$TFM are expanded by the guest bash at run time.

    /** Pick the newest installed .NET SDK's major version → e.g. `net10.0`, falling back to net8.0. */
    private const val SELECT_TFM =
        "TFM=\$(dotnet --list-sdks 2>/dev/null | awk '{print \$1}' | sort -V | tail -1 | cut -d. -f1); " +
            "[ -n \"\$TFM\" ] && TFM=\"net\${TFM}.0\" || TFM=net8.0"

    // ASP.NET Core server, Development env. /workspace is noexec, so build to ext4 and launch the
    // managed DLL via the dotnet host (a .dll needs no exec bit, unlike the apphost `dotnet run` spawns).
    private fun aspnetServerCommand(projectDir: String, stageName: String, port: Int): String =
        buildString {
            appendLine("clear")
            appendLine("set -e")
            appendLine("PROJ=\"$projectDir\"")
            appendLine("SRV=\"\$HOME/.jcode-run/$stageName-server\"")
            appendLine("echo '== J Code: Server (ASP.NET Core - Development) =='")
            appendLine(SELECT_TFM)
            appendLine("echo \"[setup] Target framework: \$TFM\"")
            appendLine("if ! ls \"\$PROJ/Server\"/*.csproj >/dev/null 2>&1; then")
            appendLine("  echo '[setup] Creating ASP.NET Core server...'")
            appendLine("  dotnet new web -o \"\$PROJ/Server\"")
            appendLine("fi")
            appendLine("for cs in \"\$PROJ/Server\"/*.csproj; do")
            appendLine("  sed -i \"s#<TargetFramework>[^<]*</TargetFramework>#<TargetFramework>\$TFM</TargetFramework>#\" \"\$cs\" 2>/dev/null || true")
            appendLine("done")
            appendLine("CSPROJ=\$(ls \"\$PROJ/Server\"/*.csproj | head -1)")
            appendLine("echo '[1/2] Building server (dotnet build, Debug)...'")
            appendLine("rm -rf \"\$SRV\"")
            appendLine("dotnet build \"\$CSPROJ\" -c Debug -o \"\$SRV\" --nologo")
            appendLine("echo '[2/2] Starting server on http://localhost:$port (Development)...'")
            appendLine("cd \"\$SRV\"")
            appendLine("ASPNETCORE_ENVIRONMENT=Development ASPNETCORE_URLS='http://0.0.0.0:$port' dotnet \"\$(basename \"\$CSPROJ\" .csproj).dll\"")
        }

    // Vite dev server (HMR). [clientDir] is the guest dir holding package.json (the project root for a
    // standalone app, or <project>/client for the ASP.NET + client layout). Staged to ext4 because the
    // FUSE /workspace can't host node_modules.
    private fun viteClientCommand(projectDir: String, stageName: String, port: Int, clientDir: String): String =
        buildString {
            appendLine("clear")
            appendLine("set -e")
            appendLine("PROJ=\"$projectDir\"")
            appendLine("CLIENT=\"$clientDir\"")
            appendLine("STAGE=\"\$HOME/.jcode-run/$stageName-client\"")
            appendLine("echo '== J Code: Client (Vite dev server) =='")
            appendLine("export npm_config_fund=false npm_config_audit=false")
            // Self-heal: scaffold a Vite React app if the client has no package.json yet.
            appendLine("if [ ! -f \"\$CLIENT/package.json\" ]; then")
            appendLine("  echo '[setup] No client found - scaffolding Vite React (TypeScript)...'")
            appendLine("  TMP=\"\$HOME/.jcode-run/.new-$stageName\"")
            appendLine("  rm -rf \"\$TMP\" && mkdir -p \"\$TMP\" && cd \"\$TMP\"")
            appendLine("  CI=1 npm create vite@latest app -- --template react-ts </dev/null")
            appendLine("  ( cd \"\$TMP/app\" && tar --exclude=node_modules --exclude=dist -cf - . ) | ( mkdir -p \"\$CLIENT\" && cd \"\$CLIENT\" && tar -xf - )")
            appendLine("  rm -rf \"\$TMP\"")
            appendLine("fi")
            appendLine("echo '[1/2] Staging client + installing deps (npm install)...'")
            appendLine("rm -rf \"\$STAGE\" && mkdir -p \"\$STAGE\" && cp -a \"\$CLIENT/.\" \"\$STAGE/\"")
            appendLine("cd \"\$STAGE\"")
            appendLine("npm install")
            appendLine("echo '[2/2] Starting Vite dev server on http://localhost:$port ...'")
            appendLine("npm run dev -- --host 0.0.0.0 --port $port")
        }

    private fun sanitizeStageName(name: String): String {
        val cleaned = name.trim().lowercase().map { ch ->
            if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '-'
        }.joinToString("")
        return cleaned.trim('-').ifBlank { "project" }
    }

    private const val ASPNET_PORT = 5080
    private const val VITE_PORT = 5173
    private const val SERVER_WAIT_MS = 240_000L
    private const val POLL_INTERVAL_MS = 1_000L
    private const val CONNECT_TIMEOUT_MS = 800
}
