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
import dev.jcode.feature.marketplace.RunPresetKind
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

    /** Append all of [configs] to the project's run list in a single load → append → save cycle (so a
     *  file-pick that creates several configs at once doesn't race per-append). No-op when empty. */
    fun upsertRuns(project: Project, configs: List<RunConfig>) {
        if (configs.isEmpty()) return
        val existing = loadProjectConfigs(project)
        saveProjectConfigs(project, existing.copy(runs = existing.runs + configs))
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

    /**
     * One bounded walk of [project]'s files, shared by every suggestion pass so a large repository is
     * scanned once per picker rather than once per kind. Directories that only ever hold dependencies
     * or build output are skipped, and the walk is capped at [SCAN_FILE_CAP] files.
     */
    private class ProjectScan(val root: File, val guestDir: String, val files: List<File>) {
        fun rel(f: File): String = f.relativeTo(root).invariantSeparatorsPath
        fun guest(f: File): String = if (f == root) guestDir else "$guestDir/${rel(f)}"
    }

    private fun scanProject(project: Project): ProjectScan? {
        val root = (project.fsPath as? FsPath.Local)?.file?.takeIf(File::isDirectory) ?: return null
        return ProjectScan(
            root = root,
            guestDir = project.distroBindTarget.trimEnd('/'),
            files = root.walkTopDown()
                .maxDepth(SCAN_DEPTH)
                .onEnter { dir -> dir == root || (dir.name !in SCAN_SKIP_DIRS && !dir.name.startsWith(".")) }
                .filter { it.isFile }
                .take(SCAN_FILE_CAP)
                .toList(),
        )
    }

    /** A preset whose every required glob matched: the first one's file (its anchor, used as the
     *  trigger's detail line) and the substitution its commands go through. */
    private class PresetMatch(val anchor: File, val substitute: (String) -> String)

    /** Resolve [preset] against this scan, or null when any required glob matched nothing — which is
     *  what gates a preset to the projects it applies to. */
    private fun ProjectScan.matchPreset(preset: RunConfigPreset): PresetMatch? {
        val matched = preset.requires.map { glob ->
            val regex = runCatching { globToRegex(glob) }.getOrNull() ?: return null
            val byName = '/' !in glob
            files.firstOrNull { f -> regex.matches(if (byName) f.name else rel(f)) } ?: return null
        }
        return PresetMatch(anchor = matched.first()) { cmd ->
            var out = cmd.replace("{{projectDir}}", guestDir)
            matched.forEachIndexed { i, f ->
                val g = guest(f)
                val d = g.substringBeforeLast('/')
                out = out.replace("{{file${i + 1}}}", g).replace("{{dir${i + 1}}}", d)
                if (i == 0) out = out.replace("{{file}}", g).replace("{{dir}}", d)
            }
            out
        }
    }

    /** A runnable trigger file — a `.csproj`, a `package.json`, a Gradle module, or an extension
     *  preset's anchor — and the run configs it offers. The "Add run config" picker groups these by
     *  [kind] into frameworks; picking a file then creates all of its [configs] at once, which is why
     *  they need no per-config label of their own. */
    data class RunTrigger(
        val label: String,
        val kind: String,
        val detail: String,
        val configs: List<RunConfig>,
    )

    /**
     * Scan [project]'s files (to [SCAN_DEPTH] levels, skipping dependency/VCS dirs) and group runnable
     * entry points by their trigger file: one [RunTrigger] per `.csproj` (a debug + release build
     * option, each a single server invocation that lets the project build & serve its own client;
     * port read from launchSettings), per `package.json` (one option per `scripts` entry), a Gradle
     * build when `gradlew` is present, and every applicable [extensionPresets]. Blocking filesystem
     * work — call from a background dispatcher.
     */
    fun suggestRunTriggers(project: Project, extensionPresets: List<ExtensionRunPreset>): List<RunTrigger> {
        val scan = scanProject(project) ?: return emptyList()
        val root = scan.root
        val guestDir = scan.guestDir
        val files = scan.files
        fun rel(f: File) = scan.rel(f)
        fun guest(f: File) = scan.guest(f)
        fun stage(suffix: String) = sanitizeStageName("${project.name}-$suffix")

        val csprojs = files.filter { it.extension.equals("csproj", ignoreCase = true) }
            .map { it to runCatching { it.readText() }.getOrDefault("").contains("Microsoft.NET.Sdk.Web") }
        val packageJsons = files.filter { it.name == "package.json" }
            .map { it to runCatching { it.readText() }.getOrDefault("") }
        val viteJsons = packageJsons.filter { (_, text) -> text.contains("\"vite\"") }
        // A web .csproj serves the client its own MSBuild SPA target builds into wwwroot; the client is
        // resolved PER csproj (its <SpaRoot>, else a Vite package nested under its own dir) so a
        // monorepo's services — or an unrelated standalone Vite folder — don't cross-wire.
        val viteDirs = viteJsons.mapNotNull { it.first.parentFile }

        val triggers = mutableListOf<RunTrigger>()

        // Extension presets are curated — each becomes a trigger with a single "Run" option, and they
        // go first so a busy monorepo's generic probes can't crowd them past SCAN_TOTAL_CAP. Build-kind
        // presets are the Build segment's ([suggestBuildChoices]) and are skipped here.
        extensionPresets.forEach { (source, preset) ->
            if (preset.kind != RunPresetKind.Run) return@forEach
            val match = scan.matchPreset(preset) ?: return@forEach
            triggers += RunTrigger(
                label = preset.label,
                kind = source,
                detail = rel(match.anchor),
                configs = listOf(
                    RunConfig(
                        name = preset.label,
                        readyPort = preset.readyPort,
                        terminals = preset.terminals.map { RunConfigTerminal(it.label, match.substitute(it.command)) },
                    ),
                ),
            )
        }

        // .NET: one trigger per .csproj, each offering a debug and a release build (both build to
        // ext4 and launch the managed DLL via the dotnet host — /workspace is noexec). A web project
        // with a detected client uses the single-endpoint recipe (client built into the server's
        // wwwroot, served on one port); the port comes from launchSettings.
        csprojs.take(SCAN_PER_KIND_CAP).forEach { (csproj, web) ->
            val port = launchProfilePort(csproj, web)
            val name = csproj.nameWithoutExtension
            val client = spaClientDir(csproj, web, viteDirs)
            val clientGuest = client?.let { if (it == root) guestDir else guest(it) }
            fun serverCmd(config: String, suffix: String): String =
                if (clientGuest != null) {
                    aspnetSinglePortCommand(guest(csproj), clientGuest, stage("$name-$suffix-server"), stage("$name-$suffix-client"), port, config)
                } else {
                    dotnetProjectCommand(guest(csproj), stage("$name-$suffix"), web, port, config)
                }
            fun cfg(cfgName: String, config: String, suffix: String) = RunConfig(
                name = cfgName,
                readyPort = port,
                terminals = listOf(RunConfigTerminal("Server", serverCmd(config, suffix))),
            )
            triggers += RunTrigger(
                label = csproj.name,
                kind = if (web) "C# · ASP.NET Core" else "C# · .NET",
                detail = rel(csproj),
                configs = listOf(
                    cfg("$name (debug)", "Debug", "debug"),
                    cfg(name, "Release", "release"),
                ),
            )
        }

        // Node: one trigger per package.json, one option per script (a Vite "dev" script binds the dev
        // server to 0.0.0.0 so the in-app browser can reach it).
        packageJsons.take(SCAN_PER_KIND_CAP).forEach { (json, text) ->
            val scripts = packageScripts(text)
            if (scripts.isEmpty()) return@forEach
            val dir = json.parentFile ?: return@forEach
            val dirGuest = if (dir == root) guestDir else guest(dir)
            val isVite = viteJsons.any { it.first == json }
            val scriptConfigs = scripts.map { s ->
                val serveDev = isVite && s == "dev"
                val port = if (serveDev) VITE_PORT else 0
                val cmd = if (serveDev) viteClientCommand(guestDir, stage(s), VITE_PORT, dirGuest)
                    else npmScriptCommand(dirGuest, stage("npm-$s"), s)
                RunConfig(name = "npm run $s", readyPort = port, terminals = listOf(RunConfigTerminal("Run", cmd)))
            }
            triggers += RunTrigger(
                label = if (dir == root) "package.json" else "${dir.name}/package.json",
                kind = "Node",
                detail = rel(json),
                configs = scriptConfigs,
            )
        }

        // Android: one trigger per APPLICATION module, so a clone with several of them (an app plus a
        // wear or automotive variant) offers each by its Gradle path instead of one lumped "gradlew"
        // row that could only ever have meant the first. Only what actually launches the app is a run;
        // Gradle's build tasks belong to the Build segment ([suggestBuildChoices]).
        if (isGradleProject(root)) {
            val gradle = gradleInvoker(root)
            val modules = androidAppModules(root, files)
            modules.take(SCAN_PER_KIND_CAP).forEach { module ->
                val modulePath = module.relativeTo(root).invariantSeparatorsPath
                // One module needs no disambiguation, and "Android app" is the name already in use.
                val qualifier = if (modules.size > 1) " ${androidGradlePath(modulePath)}" else ""
                triggers += RunTrigger(
                    label = if (modulePath.isEmpty()) project.name else androidGradlePath(modulePath),
                    kind = "Android",
                    detail = "build, install & launch",
                    configs = listOf(
                        RunConfig(
                            name = "Android app$qualifier",
                            readyPort = 0,
                            terminals = listOf(RunConfigTerminal("Run", androidRunCommand(guestDir, modulePath, gradle))),
                        ),
                        RunConfig(
                            name = "Android app$qualifier (virtual device)",
                            readyPort = 0,
                            terminals = listOf(RunConfigTerminal("Build", androidVirtualDeviceCommand(guestDir, modulePath, gradle))),
                        ),
                    ),
                )
            }
        }

        return triggers.filter { it.configs.isNotEmpty() }.take(SCAN_TOTAL_CAP)
    }

    /** One selectable entry in the Build segment's Add picker: a build config and what offered it
     *  ("Detected" for a built-in probe, or the contributing extension's name). */
    data class BuildSuggestion(
        val source: String,
        val config: BuildConfig,
    )

    /**
     * Build tasks to offer for [project]: every applicable `kind: build` preset from [extensionPresets]
     * first (curated beats generic), then the built-in probes from [detectBuildConfigs] minus any whose
     * Gradle tasks a preset already covers — otherwise installing the Android Dev Pack would show the
     * same `assembleDebug` twice. Blocking filesystem work — call from a background dispatcher.
     */
    fun suggestBuildChoices(project: Project, extensionPresets: List<ExtensionRunPreset>): List<BuildSuggestion> {
        val scan = scanProject(project) ?: return emptyList()
        val fromExtensions = extensionPresets.mapNotNull { (source, preset) ->
            if (preset.kind != RunPresetKind.Build) return@mapNotNull null
            val match = scan.matchPreset(preset) ?: return@mapNotNull null
            // A build task is a single command, so a multi-terminal build preset keeps only its first.
            val command = preset.terminals.firstOrNull()?.command ?: return@mapNotNull null
            BuildSuggestion(source, BuildConfig(preset.label, match.substitute(command)))
        }
        val covered = fromExtensions.flatMapTo(mutableSetOf()) { gradleTasksIn(it.config.command) }
        val builtIn = detectBuildConfigs(project)
            .filterNot { built ->
                val tasks = gradleTasksIn(built.command)
                tasks.isNotEmpty() && tasks.all { it in covered }
            }
            .map { BuildSuggestion("Detected", it) }
        return (fromExtensions + builtIn).take(SCAN_TOTAL_CAP)
    }

    /** The Gradle tasks a build command invokes, for the de-duplication in [suggestBuildChoices].
     *  Module-qualified paths are kept qualified: `:wear:assembleDebug` is not the same work as a
     *  project-wide `assembleDebug`, and collapsing them would drop the more precise one. Empty for a
     *  command that is not a Gradle invocation, which then never de-duplicates anything. */
    private fun gradleTasksIn(command: String): Set<String> =
        GRADLE_TASK_RE.findAll(command)
            .flatMap { it.groupValues[1].trim().split(' ') }
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("-") }
            .toSet()

    /** The http(s) port from the .csproj's `Properties/launchSettings.json` first `applicationUrl`
     *  (so the run serves — and the browser opens — the project's own port); [ASPNET_PORT] as a
     *  fallback for a web project with no profile, 0 for a non-web project. */
    private fun launchProfilePort(csproj: File, web: Boolean): Int {
        if (!web) return 0
        val dir = csproj.parentFile ?: return ASPNET_PORT
        val ls = sequenceOf("Properties/launchSettings.json", "properties/launchSettings.json")
            .map { File(dir, it) }.firstOrNull { it.isFile } ?: return ASPNET_PORT
        // The first `commandName: "Project"` (Kestrel) profile's applicationUrl — NOT the file's first
        // applicationUrl, which for stock templates is the iisSettings/IIS Express one on a different port.
        val appUrl = runCatching {
            val profiles = org.json.JSONObject(ls.readText()).optJSONObject("profiles") ?: return@runCatching null
            val keys = profiles.keys()
            while (keys.hasNext()) {
                val p = profiles.optJSONObject(keys.next()) ?: continue
                if (p.optString("commandName") == "Project") {
                    p.optString("applicationUrl").takeIf { it.isNotBlank() }?.let { return@runCatching it }
                }
            }
            null
        }.getOrNull() ?: return ASPNET_PORT
        // Prefer the http URL's port (the recipe serves plain http), else the first URL with a port.
        val urls = appUrl.split(';').map(String::trim).filter { it.isNotEmpty() }
        fun portOf(u: String) = Regex(":(\\d{2,5})").find(u.substringAfter("://").substringBefore('/'))?.groupValues?.get(1)?.toIntOrNull()
        return urls.firstOrNull { it.startsWith("http://") }?.let(::portOf)
            ?: urls.firstNotNullOfOrNull(::portOf)
            ?: ASPNET_PORT
    }

    /** The Vite client dir a web [csproj] bundles: its own `<SpaRoot>` (the .NET SPA-template link,
     *  resolved relative to the csproj), else a Vite package nested under the csproj's own directory;
     *  null when the project ships no client — then a plain web run (no client build) is used. */
    private fun spaClientDir(csproj: File, web: Boolean, viteDirs: List<File>): File? {
        if (!web) return null
        val dir = csproj.parentFile ?: return null
        val text = runCatching { csproj.readText() }.getOrDefault("")
        Regex("<SpaRoot>\\s*([^<]+?)\\s*</SpaRoot>", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim()?.let { raw ->
            val resolved = File(dir, raw.replace('\\', '/').trimEnd('/')).normalize()
            if (resolved.isDirectory && File(resolved, "package.json").isFile) return resolved
        }
        return viteDirs.firstOrNull { it != dir && it.absolutePath.startsWith(dir.absolutePath + File.separator) }
    }

    /** The keys of the `scripts` object in a package.json's text, in file order ([] when absent). */
    private fun packageScripts(text: String): List<String> = runCatching {
        val scripts = org.json.JSONObject(text).optJSONObject("scripts") ?: return emptyList()
        buildList { val keys = scripts.keys(); while (keys.hasNext()) add(keys.next()) }
    }.getOrDefault(emptyList())

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

    /** Detected build presets: `dotnet publish`, `npm run build`, and a Gradle assemble — scoped to the
     *  app module when a clone has more than one, since building them all is rarely what was meant. */
    fun detectBuildConfigs(project: Project): List<BuildConfig> {
        val scan = scanProject(project) ?: return emptyList()
        val root = scan.root
        val guestDir = scan.guestDir
        fun rel(f: File) = scan.rel(f)
        fun guest(f: File) = scan.guest(f)
        fun stage(s: String) = sanitizeStageName("${project.name}-$s")
        val out = mutableListOf<BuildConfig>()

        scan.files.firstOrNull { it.extension.equals("csproj", ignoreCase = true) }?.let { csproj ->
            out += BuildConfig("Publish (Release) — ${rel(csproj)}", dotnetPublishCommand(guest(csproj), stage("publish")))
        }
        scan.files.firstOrNull { it.name == "package.json" && runCatching { it.readText() }.getOrNull()?.contains("\"build\"") == true }
            ?.let { pkg ->
                val dir = pkg.parentFile ?: root
                out += BuildConfig("npm build — ${rel(pkg)}", npmBuildCommand(scan.guest(dir), stage("npm-build")))
            }
        if (isGradleProject(root)) {
            val gradle = gradleInvoker(root)
            val modules = androidAppModules(root, scan.files)
            fun gradleBuild(name: String, task: String) =
                BuildConfig(name, "clear\nset -e\ncd \"$guestDir\"\n$gradle --console=plain $task")
            when {
                // A single app module's plain `assembleDebug` is the same work as its qualified task,
                // and staying unqualified lets an extension's project-wide preset de-duplicate it.
                modules.size == 1 -> out += gradleBuild("Assemble debug APK", "assembleDebug")
                modules.size > 1 -> modules.take(SCAN_PER_KIND_CAP).forEach { module ->
                    val modulePath = module.relativeTo(root).invariantSeparatorsPath
                    out += gradleBuild(
                        "Assemble debug APK — ${androidGradlePath(modulePath)}",
                        androidAssembleTask(modulePath),
                    )
                }
                else -> out += gradleBuild("Gradle — build", "build")
            }
        }
        return out
    }

    /** Whether [root] is a Gradle build. The **wrapper is not the test**: a repository whose
     *  `gradle-wrapper.jar` was gitignored still has a settings or build script, and is exactly the
     *  clone that most needs a build task offering — not the one that should be told it isn't a
     *  Gradle project at all. */
    private fun isGradleProject(root: File): Boolean =
        GRADLE_MARKER_FILES.any { File(root, it).isFile }

    /** How to invoke Gradle for [root]: its own wrapper when it has one (pinning the version the
     *  project expects), otherwise the runtime's Gradle. `bash gradlew` rather than `./gradlew`
     *  because a checkout can arrive without the exec bit, and reading the script needs no exec
     *  permission on a `noexec` mount either. */
    private fun gradleInvoker(root: File): String =
        if (File(root, "gradlew").isFile) "bash gradlew" else "gradle"

    private val AGP_ALIAS_RE = Regex("""alias\s*\([^)]*[Aa]ndroid[^)]*[Aa]pplication[^)]*\)""")

    /** Every Android *application* module directory in the project, in scan order (empty when this
     *  isn't an Android project). `com.android.application` in a module's build script is the primary
     *  signal; a version-catalog alias (`alias(libs.plugins.android.application)`) never names the
     *  plugin id, so the catalog is consulted for those, and manifests declaring a LAUNCHER activity
     *  are the last resort — used only when no build script matched, since a library module can carry
     *  such a manifest too and would otherwise be offered as something to launch. */
    private fun androidAppModules(root: File, files: List<File>): List<File> {
        val catalogNamesAgp = File(root, "gradle/libs.versions.toml").takeIf(File::isFile)
            ?.let { runCatching { it.readText() }.getOrNull() }
            ?.contains("com.android.application") == true

        val byBuildScript = files.mapNotNull { buildFile ->
            if (buildFile.name != "build.gradle" && buildFile.name != "build.gradle.kts") return@mapNotNull null
            val text = runCatching { buildFile.readText() }.getOrNull() ?: return@mapNotNull null
            // The ROOT build script of a conventional Android project also names the plugin, as
            // `id("com.android.application") version "..." apply false` — declaring the version for
            // subprojects without applying it. Matching the file as a whole would pick the root as
            // an app module, so the `apply false` suffix has to be excluded line by line.
            val appliesAgp = text.lineSequence().any { line ->
                !line.contains("apply false") &&
                    (line.contains("com.android.application") || (catalogNamesAgp && AGP_ALIAS_RE.containsMatchIn(line)))
            }
            buildFile.parentFile.takeIf { appliesAgp }
        }
        if (byBuildScript.isNotEmpty()) return byBuildScript.distinct()

        // <module>/src/main/AndroidManifest.xml -> <module>
        return files.filter { f ->
            f.name == "AndroidManifest.xml" &&
                runCatching { f.readText() }.getOrNull()?.contains("android.intent.category.LAUNCHER") == true
        }.mapNotNull { it.parentFile?.parentFile?.parentFile }.distinct()
    }

    /** Build the debug APK, then install and launch it on the device behind the ADB bridge. The
     *  package and launcher activity are read back out of the built APK with `aapt dump badging`
     *  instead of parsed from Gradle: flavors, `applicationIdSuffix` and build types all rewrite the
     *  final id, so only the APK knows what it actually is. */
    private fun androidRunCommand(guestDir: String, modulePath: String, gradle: String): String = buildString {
        val task = androidAssembleTask(modulePath)
        val apkDir = androidApkDir(modulePath)
        appendLine("clear"); appendLine("set -e")
        appendLine("cd \"$guestDir\"")
        appendLine("echo '== JCode: building =='")
        appendLine("$gradle --console=plain $task")
        appendLine("APK=\$(find \"$apkDir\" -name '*.apk' -type f 2>/dev/null | xargs -r ls -t | head -1)")
        appendLine("[ -n \"\$APK\" ] || { echo \"No APK under $apkDir\"; exit 1; }")
        appendLine("BADGE=\$(aapt dump badging \"\$APK\")")
        appendLine("PKG=\$(printf '%s\\n' \"\$BADGE\" | sed -n \"s/^package: name='\\([^']*\\)'.*/\\1/p\")")
        appendLine("ACT=\$(printf '%s\\n' \"\$BADGE\" | sed -n \"s/^launchable-activity: name='\\([^']*\\)'.*/\\1/p\")")
        appendLine("[ -n \"\$PKG\" ] || { echo \"Could not read a package name from \$APK\"; exit 1; }")
        appendLine("echo \"== JCode: installing \$PKG ==\"")
        appendLine("adb install -r -t \"\$APK\"")
        appendLine("if [ -n \"\$ACT\" ]; then adb shell am start -n \"\$PKG/\$ACT\"; else adb shell monkey -p \"\$PKG\" -c android.intent.category.LAUNCHER 1; fi")
        appendLine("echo \"Launched \$PKG\"")
    }

    /** Build the debug APK and stop there: the app is started by the container, in JCode's own
     *  process, once the workbench sees the run finish. Nothing is installed and adb is never used, so
     *  this recipe works on a device that was never paired. The APK directory is stamped into the
     *  script as [VDEVICE_MARKER] — that, not the terminal output, is how the workbench finds the
     *  build's APK afterwards ([virtualDeviceApkDir]). */
    private fun androidVirtualDeviceCommand(guestDir: String, modulePath: String, gradle: String): String = buildString {
        appendLine("clear"); appendLine("set -e")
        appendLine("# $VDEVICE_MARKER ${androidApkDir(modulePath)}")
        appendLine("cd \"$guestDir\"")
        appendLine("echo '== JCode: building for the virtual device =='")
        appendLine("$gradle --console=plain ${androidAssembleTask(modulePath)}")
        appendLine("echo 'Build finished.'")
    }

    private fun androidAssembleTask(modulePath: String): String =
        if (modulePath.isEmpty()) "assembleDebug" else "${androidGradlePath(modulePath)}:assembleDebug"

    /** Gradle's own name for a module directory — `app` → `:app`, `features/home` → `:features:home`.
     *  The root project itself has no path, and is named after the project instead. */
    private fun androidGradlePath(modulePath: String): String = ":${modulePath.replace('/', ':')}"

    private fun androidApkDir(modulePath: String): String =
        if (modulePath.isEmpty()) "build/outputs/apk/debug" else "$modulePath/build/outputs/apk/debug"

    /** The host directory a virtual-device run config builds its APK into, or null when [config] is
     *  not one (i.e. its script carries no [VDEVICE_MARKER]). */
    fun virtualDeviceApkDir(project: Project, config: RunConfig): File? {
        val root = (project.fsPath as? FsPath.Local)?.file ?: return null
        val relative = config.terminals
            .firstNotNullOfOrNull { VDEVICE_MARKER_RE.find(it.command)?.groupValues?.get(1)?.trim() }
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return File(root, relative)
    }

    /** The most recently written `.apk` under [apkDir] (searched recursively, since a flavoured build
     *  nests its output), or null when the build produced none. */
    fun freshestApk(apkDir: File): File? =
        apkDir.takeIf(File::isDirectory)
            ?.walkTopDown()
            ?.filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
            ?.maxByOrNull(File::lastModified)

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
     *
     * A non-blank [androidSerial] is exported for this one command, which is how a picked device
     * overrides the session-wide `ANDROID_SERIAL`. It goes on the invocation rather than into the
     * script so the saved script stays portable — the device is a property of this launch, not of the
     * run config.
     */
    fun runInvocation(project: Project, terminal: RunTerminal, androidSerial: String = ""): String {
        val prefix = if (androidSerial.isBlank()) "" else "ANDROID_SERIAL=\"$androidSerial\" "
        val hostDir = (project.fsPath as? FsPath.Local)?.file
        val scriptName = "run-${sanitizeStageName(terminal.label)}.sh"
        if (hostDir != null) {
            val written = runCatching {
                val dir = File(hostDir, ".jcode").apply { if (!exists()) mkdirs() }
                File(dir, scriptName).writeText(terminal.command)
            }.isSuccess
            if (written) {
                val guestDir = project.distroBindTarget.trimEnd('/')
                return "${prefix}bash \"$guestDir/.jcode/$scriptName\""
            }
        }
        return "${prefix}bash <<'JCRUN'\n${terminal.command}\nJCRUN"
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
    private fun aspnetSinglePortCommand(csprojGuest: String, clientDir: String, serverStage: String, clientStage: String, port: Int, config: String = "Debug"): String =
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
            appendLine("echo '[2/3] Publishing client -> server wwwroot + building server (dotnet build, $config)...'")
            appendLine("mkdir -p \"\$WWWROOT\" && cp -a \"\$STAGE/dist/.\" \"\$WWWROOT/\"")
            appendLine("rm -rf \"\$SRV\"")
            appendLine("dotnet build \"\$CSPROJ\" -c $config -o \"\$SRV\" --nologo -p:SkipSpaBuild=true")
            appendLine("echo '[3/3] Serving SPA + API on http://localhost:$port ...'")
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
    private fun dotnetProjectCommand(csprojGuest: String, stageName: String, web: Boolean, port: Int, config: String = "Debug"): String =
        buildString {
            appendLine("clear")
            appendLine("set -e")
            appendLine("CSPROJ=\"$csprojGuest\"")
            appendLine("OUT=\"\$HOME/.jcode-run/$stageName\"")
            appendLine("echo '== JCode: .NET (dotnet build + run) =='")
            appendLine("echo '[1/2] Building (dotnet build, $config)...'")
            appendLine("rm -rf \"\$OUT\"")
            appendLine("dotnet build \"\$CSPROJ\" -c $config -o \"\$OUT\" --nologo")
            appendLine("cd \"\$OUT\"")
            if (web) {
                appendLine("echo '[2/2] Starting server on http://localhost:$port (Development)...'")
                appendLine("ASPNETCORE_ENVIRONMENT=Development ASPNETCORE_URLS='http://0.0.0.0:$port' dotnet \"\$(basename \"\$CSPROJ\" .csproj).dll\"")
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

    const val VDEVICE_MARKER = "jcode-virtual-device:"
    private val VDEVICE_MARKER_RE = Regex("^# $VDEVICE_MARKER (.+)$", RegexOption.MULTILINE)

    /** Everything after a `gradlew`/`gradle` invocation up to the end of its line — the task list, as
     *  written. Both the built-in recipes and an extension preset spell it this way. */
    private val GRADLE_TASK_RE = Regex("""\bgradlew?\b((?:\s+[^\s;&|]+)*)""")

    /** Any one of these at the project root makes it a Gradle build. */
    private val GRADLE_MARKER_FILES =
        listOf("gradlew", "settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts")

    /** Boilerplate every recipe opens with, which says nothing about what the command does. */
    private val COMMAND_PREAMBLE = setOf("clear", "set -e", "set -eu", "set -euo pipefail", "set -u")

    /** A shell variable assignment opening a line — scaffolding, not the work. */
    private val ASSIGNMENT_RE = Regex("""^[A-Za-z_][A-Za-z0-9_]*=""")

    /**
     * A one-line summary of [command] for a config row: the first line that is actually the work.
     * Recipes are scripts, so the literal first line is never the interesting one — it is `clear`,
     * then `set -e`, then a `cd`, then an `echo` announcing what is about to happen. Skip all of
     * that and the answer is the compiler or task invocation, which is what the row should say.
     */
    fun commandPreview(command: String, max: Int = 40): String {
        fun isScaffolding(line: String) = line in COMMAND_PREAMBLE ||
            line.startsWith("#") ||
            line.startsWith("cd ") ||
            line.startsWith("echo ") ||
            line.startsWith("export ") ||
            ASSIGNMENT_RE.containsMatchIn(line)
        val lines = command.lineSequence().map(String::trim).filter(String::isNotEmpty)
        // Fall back to the first non-empty line: a one-liner that IS an assignment or a `cd` still
        // has to show something.
        return (lines.firstOrNull { !isScaffolding(it) } ?: lines.firstOrNull().orEmpty()).take(max)
    }

    private const val ASPNET_PORT = 5080
    private const val VITE_PORT = 5173
    private const val SCAN_DEPTH = 8
    private const val SCAN_FILE_CAP = 8_000
    private const val SCAN_PER_KIND_CAP = 64
    private const val SCAN_TOTAL_CAP = 128
    private val SCAN_SKIP_DIRS = setOf("node_modules", "bin", "obj", "build", "dist", "out", "target")
    private const val SERVER_WAIT_MS = 240_000L
    private const val POLL_INTERVAL_MS = 1_000L
    private const val CONNECT_TIMEOUT_MS = 800
}
