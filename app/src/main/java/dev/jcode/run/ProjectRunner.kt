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
 * A [RunPlan] is a single bash command sequence written into a visible terminal session (the right
 * drawer) so the user watches the compile/run stream live. Recipes are **self-healing**: if the
 * project is only partially scaffolded (e.g. an ASP.NET server with no React client), the run
 * (re)creates and builds the missing pieces in that same terminal instead of failing. Front-end
 * builds happen in the runtime's ext4 home (`$HOME/.jcode-run/<name>`) because the FUSE-backed
 * `/workspace` mount has no symlinks, so `node_modules` cannot live there — only the build output is
 * published back into the project.
 *
 * proot shares the app process's network stack, so a server bound to `0.0.0.0:<port>` in the guest
 * is reachable from the device browser at `http://localhost:<port>`.
 */
object ProjectRunner {

    /** A runnable recipe for a detected project type. */
    data class RunPlan(
        /** Short human label, e.g. "ASP.NET Core + Vite React". */
        val kindLabel: String,
        /** Localhost port the server listens on once started. */
        val port: Int,
        /** The full bash command sequence to send into the run terminal. */
        val command: String,
    ) {
        val url: String get() = "http://localhost:$port"
    }

    /**
     * Detect how to build & run [project], or null if no recipe matches. Detection prefers the
     * project's scaffold [Project.templateId] (authoritative, and cheap — no disk access), then falls
     * back to a filesystem probe for projects opened without a J Code template. Because the recipes
     * self-heal, detection deliberately does NOT require a complete on-disk layout.
     */
    fun detectRunPlan(project: Project): RunPlan? {
        val root = (project.fsPath as? FsPath.Local)?.file ?: return null
        val guestDir = project.distroBindTarget.trimEnd('/')
        val stageName = sanitizeStageName(project.name)

        when (project.templateId) {
            "aspnet-vite-react-ts" -> return aspnetVitePlan(guestDir, stageName)
            "react-app" -> return vitePlan(guestDir, stageName)
            // Angular/empty have no one-tap recipe this round; fall back to the filesystem probe only
            // when the project was opened without a recognized J Code template.
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

    private fun aspnetVitePlan(guestDir: String, stageName: String) = RunPlan(
        kindLabel = "ASP.NET Core + Vite React",
        port = ASPNET_PORT,
        command = aspnetViteCommand(guestDir, stageName, ASPNET_PORT),
    )

    private fun vitePlan(guestDir: String, stageName: String) = RunPlan(
        kindLabel = "Vite / React dev server",
        port = VITE_PORT,
        command = viteDevCommand(guestDir, stageName, VITE_PORT),
    )

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

    // Recipes run as a single `bash <<'JCRUN'` heredoc so `set -e` aborts on the first failing step
    // and multi-line file writes stay clean. $PROJ/$STAGE/$TFM are expanded by the guest bash at
    // runtime (the heredoc is quoted, so they stay literal here).

    /** Pick the newest installed .NET SDK's major version → e.g. `net10.0`, falling back to net8.0. */
    private const val SELECT_TFM =
        "TFM=\$(dotnet --list-sdks 2>/dev/null | awk '{print \$1}' | sort -V | tail -1 | cut -d. -f1); " +
            "[ -n \"\$TFM\" ] && TFM=\"net\${TFM}.0\" || TFM=net8.0"

    private fun aspnetViteCommand(projectDir: String, stageName: String, port: Int): String =
        buildString {
            appendLine("clear")
            appendLine("bash <<'JCRUN'")
            appendLine("set -e")
            // npm's animated progress bar (redrawn with carriage returns) renders as a wall of garbage
            // on the VT terminal; disable it and the fund/audit chatter for clean line-by-line output.
            appendLine("export npm_config_progress=false npm_config_fund=false npm_config_audit=false")
            appendLine("PROJ=\"$projectDir\"")
            appendLine("STAGE=\"\$HOME/.jcode-run/$stageName\"")
            appendLine("echo '== J Code: Build & Run (ASP.NET Core + Vite React) =='")
            appendLine(SELECT_TFM)
            appendLine("echo \"[setup] Target framework: \$TFM\"")
            // 1. Ensure the ASP.NET server exists (self-heal a project with no Server/).
            appendLine("if ! ls \"\$PROJ/Server\"/*.csproj >/dev/null 2>&1; then")
            appendLine("  echo '[setup] Creating ASP.NET Core server...'")
            appendLine("  dotnet new web -o \"\$PROJ/Server\"")
            appendLine("fi")
            // Retarget the server's TFM to the newest installed SDK (safe single-line rewrite).
            appendLine("for cs in \"\$PROJ/Server\"/*.csproj; do")
            appendLine("  sed -i \"s#<TargetFramework>[^<]*</TargetFramework>#<TargetFramework>\$TFM</TargetFramework>#\" \"\$cs\" 2>/dev/null || true")
            appendLine("done")
            // 2. Ensure Program.cs serves the built SPA (never clobbers an already-configured server).
            appendLine("if ! grep -q UseStaticFiles \"\$PROJ/Server/Program.cs\" 2>/dev/null; then")
            appendLine("  echo '[setup] Configuring the server to serve the built client...'")
            appendLine("  cat > \"\$PROJ/Server/Program.cs\" <<'PROG'")
            appendLine("var builder = WebApplication.CreateBuilder(args);")
            appendLine("var app = builder.Build();")
            appendLine("app.UseDefaultFiles();")
            appendLine("app.UseStaticFiles();")
            appendLine("app.MapGet(\"/api/hello\", () => \"Hello from ASP.NET Core\");")
            appendLine("app.MapFallbackToFile(\"index.html\");")
            appendLine("app.Run();")
            appendLine("PROG")
            appendLine("fi")
            // 3. Ensure the React client exists; scaffold it into the project if the partial project has none.
            appendLine("if [ ! -f \"\$PROJ/client/package.json\" ]; then")
            appendLine("  echo '[1/5] No client found — scaffolding Vite React (TypeScript)...'")
            appendLine("  TMP=\"\$HOME/.jcode-run/.new-$stageName\"")
            appendLine("  rm -rf \"\$TMP\" && mkdir -p \"\$TMP\" && cd \"\$TMP\"")
            appendLine("  CI=1 npm create vite@latest client -- --template react-ts </dev/null")
            // Persist the scaffolded source into the project (minus node_modules/dist) so it is editable.
            appendLine("  ( cd \"\$TMP/client\" && tar --exclude=node_modules --exclude=dist -cf - . ) | ( mkdir -p \"\$PROJ/client\" && cd \"\$PROJ/client\" && tar -xf - )")
            appendLine("  rm -rf \"\$TMP\"")
            appendLine("fi")
            appendLine("echo '[2/5] Staging client for build...'")
            appendLine("rm -rf \"\$STAGE\" && mkdir -p \"\$STAGE\" && cp -a \"\$PROJ/client/.\" \"\$STAGE/\"")
            appendLine("cd \"\$STAGE\"")
            appendLine("echo '[3/5] Building frontend (npm install + build)...'")
            appendLine("npm install")
            appendLine("npm run build")
            appendLine("echo '[4/5] Publishing client into Server/wwwroot...'")
            appendLine("rm -rf \"\$PROJ/Server/wwwroot\" && mkdir -p \"\$PROJ/Server/wwwroot\" && cp -a \"\$STAGE/dist/.\" \"\$PROJ/Server/wwwroot/\"")
            appendLine("echo '[5/5] Publishing & starting backend on http://localhost:$port ...'")
            // /workspace is a noexec FUSE mount, so the compiled apphost can't be executed there.
            // Publish to the ext4 home and launch the framework-dependent DLL via the dotnet host
            // (running a managed .dll needs no exec bit, unlike the native apphost `dotnet run` spawns).
            appendLine("CSPROJ=\$(ls \"\$PROJ/Server\"/*.csproj | head -1)")
            appendLine("SRV=\"\$STAGE-server\" && rm -rf \"\$SRV\"")
            appendLine("dotnet publish \"\$CSPROJ\" -c Release -o \"\$SRV\" --nologo")
            appendLine("cd \"\$SRV\"")
            appendLine("ASPNETCORE_URLS='http://0.0.0.0:$port' dotnet \"\$(basename \"\$CSPROJ\" .csproj).dll\"")
            appendLine("JCRUN")
        }

    private fun viteDevCommand(projectDir: String, stageName: String, port: Int): String =
        buildString {
            appendLine("clear")
            appendLine("bash <<'JCRUN'")
            appendLine("set -e")
            // Disable npm's animated progress bar (renders as garbage on the VT terminal) + chatter.
            appendLine("export npm_config_progress=false npm_config_fund=false npm_config_audit=false")
            appendLine("PROJ=\"$projectDir\"")
            appendLine("STAGE=\"\$HOME/.jcode-run/$stageName\"")
            appendLine("echo '== J Code: Run (Vite dev server) =='")
            // Self-heal: scaffold a Vite React app in place if the project has no package.json yet.
            appendLine("if [ ! -f \"\$PROJ/package.json\" ]; then")
            appendLine("  echo '[setup] No package.json — scaffolding Vite React (TypeScript)...'")
            appendLine("  TMP=\"\$HOME/.jcode-run/.new-$stageName\"")
            appendLine("  rm -rf \"\$TMP\" && mkdir -p \"\$TMP\" && cd \"\$TMP\"")
            appendLine("  CI=1 npm create vite@latest app -- --template react-ts </dev/null")
            appendLine("  ( cd \"\$TMP/app\" && tar --exclude=node_modules --exclude=dist -cf - . ) | ( cd \"\$PROJ\" && tar -xf - )")
            appendLine("  rm -rf \"\$TMP\"")
            appendLine("fi")
            appendLine("echo '[1/3] Preparing build area...'")
            appendLine("rm -rf \"\$STAGE\" && mkdir -p \"\$STAGE\" && cp -a \"\$PROJ/.\" \"\$STAGE/\"")
            appendLine("cd \"\$STAGE\"")
            appendLine("echo '[2/3] Installing dependencies (npm install)...'")
            appendLine("npm install")
            appendLine("echo '[3/3] Starting Vite dev server on http://localhost:$port ...'")
            appendLine("npm run dev -- --host 0.0.0.0 --port $port")
            appendLine("JCRUN")
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
