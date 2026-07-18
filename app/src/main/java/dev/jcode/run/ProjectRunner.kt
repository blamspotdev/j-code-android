package dev.jcode.run

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.jcode.core.config.BuildConfig
import dev.jcode.core.config.ProjectConfigs
import dev.jcode.core.config.RunConfig
import dev.jcode.core.config.RunConfigStore
import dev.jcode.core.config.RunConfigTerminal
import dev.jcode.feature.marketplace.RunConfigPreset
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
     * probe for projects opened without a JCode template. Recipes self-heal, so detection does NOT
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

    /** All saved build/run configs for [project] (empty if none written yet). */
    fun loadProjectConfigs(project: Project): ProjectConfigs =
        (project.fsPath as? FsPath.Local)?.file?.let { RunConfigStore.load(it) } ?: ProjectConfigs.EMPTY

    fun saveProjectConfigs(project: Project, configs: ProjectConfigs) {
        (project.fsPath as? FsPath.Local)?.file?.let { RunConfigStore.save(it, configs) }
    }

    /** Run configs to show/run: the saved ones, or a single detected default when none are saved. */
    fun effectiveRuns(project: Project): List<RunConfig> {
        val saved = loadProjectConfigs(project).runs
        if (saved.isNotEmpty()) return saved
        return detectRunPlan(project)?.let { listOf(it.toRunConfig()) }.orEmpty()
    }

    /** Build configs to show/build: the saved ones, or detected publish/release presets when none are saved. */
    fun effectiveBuilds(project: Project): List<BuildConfig> {
        val saved = loadProjectConfigs(project).builds
        if (saved.isNotEmpty()) return saved
        return detectBuildConfigs(project)
    }

    fun runConfigToPlan(config: RunConfig): RunPlan =
        RunPlan(kindLabel = config.name, readyPort = config.readyPort, terminals = config.terminals.map { RunTerminal(it.label, it.command) })

    private fun RunPlan.toRunConfig(): RunConfig =
        RunConfig(name = kindLabel, readyPort = readyPort, terminals = terminals.map { RunConfigTerminal(it.label, it.command) })

    /** Upsert a run config at [index] (append when [index] is null or out of range), then persist. */
    fun upsertRun(project: Project, index: Int?, config: RunConfig) {
        val configs = loadProjectConfigs(project)
        val runs = configs.runs.toMutableList()
        if (index != null && index in runs.indices) runs[index] = config else runs.add(config)
        saveProjectConfigs(project, configs.copy(runs = runs))
    }

    fun upsertBuild(project: Project, index: Int?, config: BuildConfig) {
        val configs = loadProjectConfigs(project)
        val builds = configs.builds.toMutableList()
        if (index != null && index in builds.indices) builds[index] = config else builds.add(config)
        saveProjectConfigs(project, configs.copy(builds = builds))
    }

    fun deleteRun(project: Project, index: Int) {
        val configs = loadProjectConfigs(project)
        if (index in configs.runs.indices) {
            saveProjectConfigs(project, configs.copy(runs = configs.runs.filterIndexed { i, _ -> i != index }))
        }
    }

    fun deleteBuild(project: Project, index: Int) {
        val configs = loadProjectConfigs(project)
        if (index in configs.builds.indices) {
            saveProjectConfigs(project, configs.copy(builds = configs.builds.filterIndexed { i, _ -> i != index }))
        }
    }

    /** A [RunConfigPreset] paired with the display name of the extension that contributed it. */
    data class ExtensionRunPreset(
        val source: String,
        val preset: RunConfigPreset,
    )

    /** A run config detected from the project's files, offered on the Configure page as a one-tap
     *  form prefill (never auto-saved). [source] says what detected it — "Detected" for built-in
     *  probes, or the contributing extension's name. */
    data class RunSuggestion(
        val label: String,
        val source: String,
        val config: RunConfig,
    )

    /**
     * Scan [project]'s files (to [SCAN_DEPTH] levels, skipping dependency/VCS dirs) and propose run
     * configs: .NET projects per `.csproj` (web vs console via `Microsoft.NET.Sdk.Web`, plus a
     * combined ASP.NET + Vite recipe when both halves exist), Vite/npm apps per `package.json`, a
     * Gradle build when `gradlew` is present, and every [extensionPresets] whose required files are
     * ALL present. Blocking filesystem work — call from a background dispatcher.
     */
    fun suggestRunConfigs(project: Project, extensionPresets: List<ExtensionRunPreset>): List<RunSuggestion> {
        val root = (project.fsPath as? FsPath.Local)?.file?.takeIf(File::isDirectory) ?: return emptyList()
        val guestDir = project.distroBindTarget.trimEnd('/')
        val files = root.walkTopDown()
            .maxDepth(SCAN_DEPTH)
            .onEnter { dir -> dir == root || (dir.name !in SCAN_SKIP_DIRS && !dir.name.startsWith(".")) }
            .filter { it.isFile }
            .take(SCAN_FILE_CAP)
            .toList()
        fun rel(f: File) = f.relativeTo(root).invariantSeparatorsPath
        fun guest(f: File) = "$guestDir/${rel(f)}"
        fun stage(suffix: String) = sanitizeStageName("${project.name}-$suffix")

        // Extension presets are curated (named, multi-terminal) and go FIRST so the generic built-in
        // probes below can't crowd them out of the SCAN_TOTAL_CAP-capped list in a busy monorepo.
        val presetSuggestions = mutableListOf<RunSuggestion>()
        val suggestions = mutableListOf<RunSuggestion>()

        val csprojs = files.filter { it.extension.equals("csproj", ignoreCase = true) }
            .map { it to runCatching { it.readText() }.getOrDefault("").contains("Microsoft.NET.Sdk.Web") }
        val packageJsons = files.filter { it.name == "package.json" }
            .map { it to runCatching { it.readText() }.getOrDefault("") }
        val viteJsons = packageJsons.filter { (_, text) -> text.contains("\"vite\"") }

        val firstWebCsproj = csprojs.firstOrNull { it.second }?.first
        val firstViteDir = viteJsons.firstOrNull()?.first?.parentFile
        if (firstWebCsproj != null && firstViteDir != null) {
            val clientGuest = if (firstViteDir == root) guestDir else guest(firstViteDir)
            suggestions += RunSuggestion(
                label = "ASP.NET Core + Vite (dev) — ${rel(firstWebCsproj)}",
                source = "Detected",
                config = RunConfig(
                    name = "ASP.NET Core + Vite (dev)",
                    readyPort = VITE_PORT,
                    terminals = listOf(
                        RunConfigTerminal("Server", dotnetProjectCommand(guest(firstWebCsproj), stage("server"), web = true)),
                        RunConfigTerminal("Client", viteClientCommand(guestDir, stage("client"), VITE_PORT, clientGuest)),
                    ),
                ),
            )
            // Single-endpoint variant: one terminal that builds the client into the server's wwwroot
            // and serves everything on one port (production-style; no dev server / HMR).
            suggestions += RunSuggestion(
                label = "ASP.NET Core + Vite (single endpoint) — ${rel(firstWebCsproj)}",
                source = "Detected",
                config = RunConfig(
                    name = "ASP.NET Core + Vite (single endpoint)",
                    readyPort = ASPNET_PORT,
                    terminals = listOf(
                        RunConfigTerminal("Server", aspnetSinglePortCommand(guest(firstWebCsproj), clientGuest, stage("server"), stage("client"))),
                    ),
                ),
            )
        }
        csprojs.take(SCAN_PER_KIND_CAP).forEach { (csproj, web) ->
            val kind = if (web) "ASP.NET Core" else ".NET console"
            suggestions += RunSuggestion(
                label = "$kind — ${rel(csproj)}",
                source = "Detected",
                config = RunConfig(
                    name = "$kind (${csproj.nameWithoutExtension})",
                    readyPort = if (web) ASPNET_PORT else 0,
                    terminals = listOf(
                        RunConfigTerminal("Server", dotnetProjectCommand(guest(csproj), stage(csproj.nameWithoutExtension), web)),
                    ),
                ),
            )
        }
        viteJsons.take(SCAN_PER_KIND_CAP).forEach { (json, _) ->
            val dir = json.parentFile ?: return@forEach
            val clientGuest = if (dir == root) guestDir else guest(dir)
            suggestions += RunSuggestion(
                label = "Vite dev server — ${rel(json)}",
                source = "Detected",
                config = RunConfig(
                    name = "Vite dev server",
                    readyPort = VITE_PORT,
                    terminals = listOf(
                        RunConfigTerminal("Client", viteClientCommand(guestDir, stage("client"), VITE_PORT, clientGuest)),
                    ),
                ),
            )
        }
        (packageJsons - viteJsons.toSet()).take(SCAN_PER_KIND_CAP).forEach { (json, text) ->
            val script = if (text.contains("\"dev\"")) "dev" else if (text.contains("\"start\"")) "start" else return@forEach
            val dir = json.parentFile ?: return@forEach
            val dirGuest = if (dir == root) guestDir else guest(dir)
            suggestions += RunSuggestion(
                label = "npm run $script — ${rel(json)}",
                source = "Detected",
                config = RunConfig(
                    name = "npm run $script",
                    readyPort = 0,
                    terminals = listOf(RunConfigTerminal("Run", npmScriptCommand(dirGuest, stage("npm"), script))),
                ),
            )
        }
        if (File(root, "gradlew").isFile) {
            suggestions += RunSuggestion(
                label = "Gradle — assembleDebug",
                source = "Detected",
                config = RunConfig(
                    name = "Gradle build (assembleDebug)",
                    readyPort = 0,
                    terminals = listOf(
                        RunConfigTerminal("Build", "clear\nset -e\ncd \"$guestDir\"\nbash gradlew assembleDebug"),
                    ),
                ),
            )
        }

        // Extension presets: applicable only when EVERY `requires` glob matches some file. Each glob's
        // first match feeds {{fileN}}/{{dirN}} (1-based) — and the first also {{file}}/{{dir}} — so a
        // two-file preset (e.g. ASP.NET + Vite = a .csproj AND a package.json) can reference both.
        extensionPresets.forEach { (source, preset) ->
            fun firstMatch(glob: String): File? {
                val regex = runCatching { globToRegex(glob) }.getOrNull() ?: return null
                val byName = '/' !in glob
                return files.firstOrNull { f -> regex.matches(if (byName) f.name else rel(f)) }
            }
            val matched = preset.requires.map { firstMatch(it) }
            if (matched.any { it == null }) return@forEach // a required file is missing → not applicable
            val anchor = matched.first()!!
            fun substitute(cmd: String): String {
                var out = cmd.replace("{{projectDir}}", guestDir)
                matched.forEachIndexed { i, f ->
                    val g = guest(f!!)
                    val d = g.substringBeforeLast('/')
                    out = out.replace("{{file${i + 1}}}", g).replace("{{dir${i + 1}}}", d)
                    if (i == 0) out = out.replace("{{file}}", g).replace("{{dir}}", d)
                }
                return out
            }
            presetSuggestions += RunSuggestion(
                label = "${preset.label} — ${rel(anchor)}",
                source = source,
                config = RunConfig(
                    name = preset.label,
                    readyPort = preset.readyPort,
                    terminals = preset.terminals.map { RunConfigTerminal(it.label, substitute(it.command)) },
                ),
            )
        }

        return (presetSuggestions + suggestions).distinctBy { it.label }.take(SCAN_TOTAL_CAP)
    }

    // Glob → anchored Regex: `**` crosses directories (an optional trailing `/` is absorbed so
    // `**/*.csproj` also matches a root-level file), `*` stays within one path segment, `?` is one char.
    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        var i = 0
        while (i < glob.length) {
            when {
                glob.startsWith("**", i) -> {
                    sb.append(".*")
                    i += if (glob.getOrNull(i + 2) == '/') 3 else 2
                }
                glob[i] == '*' -> { sb.append("[^/]*"); i++ }
                glob[i] == '?' -> { sb.append("[^/]"); i++ }
                else -> { sb.append(Regex.escape(glob[i].toString())); i++ }
            }
        }
        return Regex(sb.toString())
    }

    /**
     * The run config the editor opens for [index]: the saved config at that index, else a default
     * seeded from the detected plan, else a blank single-terminal starter (for a new config).
     */
    fun editableRunConfig(project: Project, index: Int?): RunConfig {
        // Seed from the SAME list the panel shows (saved, or detected when none saved) so the row's
        // index maps to the right config; a null index (New) starts from the detected plan or blank.
        val runs = effectiveRuns(project)
        if (index != null && index in runs.indices) return runs[index]
        detectRunPlan(project)?.let { return it.toRunConfig() }
        return RunConfig(project.name, 0, terminals = listOf(RunConfigTerminal("Run", "")))
    }

    /** The build config the editor opens for [index]: the effective one at that position, else blank. */
    fun editableBuildConfig(project: Project, index: Int?): BuildConfig {
        val builds = effectiveBuilds(project)
        if (index != null && index in builds.indices) return builds[index]
        return BuildConfig("Build", "")
    }

    /** Detected publish/release build presets: `dotnet publish`, `npm run build`, `gradle assembleRelease`. */
    fun detectBuildConfigs(project: Project): List<BuildConfig> {
        val root = (project.fsPath as? FsPath.Local)?.file?.takeIf(File::isDirectory) ?: return emptyList()
        val guestDir = project.distroBindTarget.trimEnd('/')
        val files = root.walkTopDown()
            .maxDepth(SCAN_DEPTH)
            .onEnter { dir -> dir == root || (dir.name !in SCAN_SKIP_DIRS && !dir.name.startsWith(".")) }
            .filter { it.isFile }
            .take(SCAN_FILE_CAP)
            .toList()
        fun rel(f: File) = f.relativeTo(root).invariantSeparatorsPath
        fun guest(f: File) = "$guestDir/${rel(f)}"
        fun stage(s: String) = sanitizeStageName("${project.name}-$s")
        val out = mutableListOf<BuildConfig>()

        files.firstOrNull { it.extension.equals("csproj", ignoreCase = true) }?.let { csproj ->
            out += BuildConfig("Publish (Release) — ${rel(csproj)}", dotnetPublishCommand(guest(csproj), stage("publish")))
        }
        files.firstOrNull { it.name == "package.json" && runCatching { it.readText() }.getOrNull()?.contains("\"build\"") == true }
            ?.let { pkg ->
                val dir = pkg.parentFile ?: root
                val dirGuest = if (dir == root) guestDir else guest(dir)
                out += BuildConfig("npm build — ${rel(pkg)}", npmBuildCommand(dirGuest, stage("npm-build")))
            }
        if (File(root, "gradlew").isFile) {
            out += BuildConfig("Gradle — assembleRelease", "clear\nset -e\ncd \"$guestDir\"\nbash gradlew assembleRelease")
        }
        return out
    }

    private fun dotnetPublishCommand(csprojGuest: String, stageName: String): String = buildString {
        appendLine("clear"); appendLine("set -e")
        appendLine("CSPROJ=\"$csprojGuest\"")
        appendLine("OUT=\"\$HOME/.jcode-build/$stageName\"")
        appendLine("echo '== JCode: dotnet publish (Release) =='")
        appendLine("rm -rf \"\$OUT\"")
        appendLine("dotnet publish \"\$CSPROJ\" -c Release -o \"\$OUT\" --nologo")
        appendLine("echo \"Published to \$OUT\"")
    }

    private fun npmBuildCommand(dirGuest: String, stageName: String): String = buildString {
        appendLine("clear"); appendLine("set -e")
        appendLine("SRC=\"$dirGuest\"")
        appendLine("STAGE=\"\$HOME/.jcode-build/$stageName\"")
        appendLine("echo '== JCode: npm run build =='")
        appendLine("export npm_config_fund=false npm_config_audit=false")
        appendLine("rm -rf \"\$STAGE\" && mkdir -p \"\$STAGE\" && cp -a \"\$SRC/.\" \"\$STAGE/\"")
        appendLine("cd \"\$STAGE\"")
        appendLine("npm install")
        appendLine("npm run build")
        appendLine("echo \"Built in \$STAGE\"")
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

    /**
     * Open [url] for a web preview. [browser] selects the target: blank or "SYSTEM" = the device's
     * default browser (unchanged behaviour), "ASK" = the Android app chooser, otherwise a specific
     * browser package name (falling back to the default handler if that browser is gone). The
     * encoding matches [dev.jcode.design.WebPreviewBrowsers].
     */
    fun openInBrowser(context: Context, url: String, browser: String = "") {
        fun viewIntent() = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val specific = browser.isNotBlank() && browser != "SYSTEM" && browser != "ASK"
        val intent = when {
            browser == "ASK" -> Intent.createChooser(viewIntent(), "Open web preview in")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            specific -> viewIntent().setPackage(browser)
            else -> viewIntent()
        }
        val ok = runCatching { context.startActivity(intent) }.isSuccess
        if (!ok && specific) runCatching { context.startActivity(viewIntent()) } // browser uninstalled → default
    }

    /** Installed apps that can open http(s) URLs, for the "Open web previews in" picker. */
    fun installedBrowsers(context: Context): List<dev.jcode.design.BrowserApp> {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com"))
        val pm = context.packageManager
        return runCatching {
            pm.queryIntentActivities(probe, android.content.pm.PackageManager.MATCH_ALL)
                .mapNotNull { ri ->
                    val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                    dev.jcode.design.BrowserApp(pkg, ri.loadLabel(pm).toString())
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
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
            appendLine("echo '== JCode: Server (ASP.NET Core - Development) =='")
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

    // Single-endpoint ASP.NET Core: build the Vite client into the server's wwwroot and serve the API
    // + SPA from one port (production-style — no dev server / HMR). The client is built on ext4 (FUSE
    // /workspace can't host node_modules) with its output forced to `dist`, then copied into the
    // SERVER SOURCE wwwroot BEFORE `dotnet build` — so the SPA is captured in the static-web-assets
    // manifest and actually served (files dropped into the OUTPUT wwwroot after a Debug build are
    // ignored: in Development the manifest-backed web root serves only build-time assets). Copying
    // into wwwroot IS the build output for these projects (the .NET SPA template gitignores it).
    // `-p:SkipSpaBuild=true` skips a build-time SPA build the .csproj gates on that property (the .NET
    // SPA template convention — it would otherwise npm-build in the FUSE source tree and fail); a
    // harmless no-op for projects that don't use it.
    private fun aspnetSinglePortCommand(csprojGuest: String, clientDir: String, serverStage: String, clientStage: String): String =
        buildString {
            appendLine("clear")
            appendLine("set -e")
            appendLine("CSPROJ=\"$csprojGuest\"")
            appendLine("CLIENT=\"$clientDir\"")
            appendLine("SRV=\"\$HOME/.jcode-run/$serverStage\"")
            appendLine("STAGE=\"\$HOME/.jcode-run/$clientStage\"")
            appendLine("WWWROOT=\"\$(dirname \"\$CSPROJ\")/wwwroot\"")
            appendLine("echo '== JCode: ASP.NET Core (single endpoint — client built into wwwroot) =='")
            appendLine("echo '[1/3] Building client (npm run build) on ext4...'")
            appendLine("export npm_config_fund=false npm_config_audit=false")
            appendLine("rm -rf \"\$STAGE\" && mkdir -p \"\$STAGE\" && cp -a \"\$CLIENT/.\" \"\$STAGE/\"")
            appendLine("( cd \"\$STAGE\" && npm install && npm run build -- --outDir dist --emptyOutDir )")
            appendLine("echo '[2/3] Publishing client -> server wwwroot + building server (dotnet build, Debug)...'")
            appendLine("mkdir -p \"\$WWWROOT\" && cp -a \"\$STAGE/dist/.\" \"\$WWWROOT/\"")
            appendLine("rm -rf \"\$SRV\"")
            appendLine("dotnet build \"\$CSPROJ\" -c Debug -o \"\$SRV\" --nologo -p:SkipSpaBuild=true")
            appendLine("echo '[3/3] Serving SPA + API on http://localhost:$ASPNET_PORT ...'")
            appendLine("cd \"\$SRV\"")
            appendLine("ASPNETCORE_ENVIRONMENT=Development ASPNETCORE_URLS='http://0.0.0.0:$ASPNET_PORT' dotnet \"\$(basename \"\$CSPROJ\" .csproj).dll\"")
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
            appendLine("echo '== JCode: Client (Vite dev server) =='")
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

    // Generic .NET build & run for a specific .csproj (a suggested config for external projects, so
    // the project is built as-is — no TFM retargeting). Same noexec-safe shape as the template
    // recipe: build to ext4, launch the managed DLL via the dotnet host.
    private fun dotnetProjectCommand(csprojGuest: String, stageName: String, web: Boolean): String =
        buildString {
            appendLine("clear")
            appendLine("set -e")
            appendLine("CSPROJ=\"$csprojGuest\"")
            appendLine("OUT=\"\$HOME/.jcode-run/$stageName\"")
            appendLine("echo '== JCode: .NET (dotnet build + run) =='")
            appendLine("echo '[1/2] Building (dotnet build, Debug)...'")
            appendLine("rm -rf \"\$OUT\"")
            appendLine("dotnet build \"\$CSPROJ\" -c Debug -o \"\$OUT\" --nologo")
            appendLine("cd \"\$OUT\"")
            if (web) {
                appendLine("echo '[2/2] Starting server on http://localhost:$ASPNET_PORT (Development)...'")
                appendLine("ASPNETCORE_ENVIRONMENT=Development ASPNETCORE_URLS='http://0.0.0.0:$ASPNET_PORT' dotnet \"\$(basename \"\$CSPROJ\" .csproj).dll\"")
            } else {
                appendLine("echo '[2/2] Running...'")
                appendLine("dotnet \"\$(basename \"\$CSPROJ\" .csproj).dll\"")
            }
        }

    // Generic npm project: stage to ext4 (FUSE /workspace can't host node_modules), install, run the
    // detected script.
    private fun npmScriptCommand(dirGuest: String, stageName: String, script: String): String =
        buildString {
            appendLine("clear")
            appendLine("set -e")
            appendLine("SRC=\"$dirGuest\"")
            appendLine("STAGE=\"\$HOME/.jcode-run/$stageName\"")
            appendLine("echo '== JCode: npm run $script =='")
            appendLine("export npm_config_fund=false npm_config_audit=false")
            appendLine("echo '[1/2] Staging + installing deps (npm install)...'")
            appendLine("rm -rf \"\$STAGE\" && mkdir -p \"\$STAGE\" && cp -a \"\$SRC/.\" \"\$STAGE/\"")
            appendLine("cd \"\$STAGE\"")
            appendLine("npm install")
            appendLine("echo '[2/2] npm run $script ...'")
            appendLine("npm run $script")
        }

    private fun sanitizeStageName(name: String): String {
        val cleaned = name.trim().lowercase().map { ch ->
            if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '-'
        }.joinToString("")
        return cleaned.trim('-').ifBlank { "project" }
    }

    private const val ASPNET_PORT = 5080
    private const val VITE_PORT = 5173
    private const val SCAN_DEPTH = 4
    private const val SCAN_FILE_CAP = 4_000
    private const val SCAN_PER_KIND_CAP = 4
    private const val SCAN_TOTAL_CAP = 12
    private val SCAN_SKIP_DIRS = setOf("node_modules", "bin", "obj", "build", "dist", "out", "target")
    private const val SERVER_WAIT_MS = 240_000L
    private const val POLL_INTERVAL_MS = 1_000L
    private const val CONNECT_TIMEOUT_MS = 800
}
