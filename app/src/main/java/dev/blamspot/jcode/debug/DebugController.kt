package dev.blamspot.jcode.debug

import dev.blamspot.jcode.core.debug.DapEvaluation
import dev.blamspot.jcode.core.debug.DapStackFrame
import dev.blamspot.jcode.core.debug.DapVariable
import dev.blamspot.jcode.core.debug.DapTransport
import dev.blamspot.jcode.core.debug.DebugSession
import dev.blamspot.jcode.core.debug.DebugState
import dev.blamspot.jcode.core.debug.StoppedInfo
import dev.blamspot.jcode.core.distro.DebugEngineCatalog
import dev.blamspot.jcode.core.distro.DebugEngineEntry
import dev.blamspot.jcode.core.distro.DistroService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A source location the debugger is stopped at. [line] is 0-based (editor convention). */
data class DebugLocation(val hostPath: String, val line: Int)

/** A flattened variables/scopes row for the Variables panel. depth 0 = scope, 1 = variable. */
data class VariableRow(val name: String, val value: String, val type: String?, val depth: Int)

/**
 * Orchestrates a single debug session: resolves the engine for a file, launches the adapter (via the
 * distro), pushes breakpoints, and exposes UI state (call stack, variables, output, stopped location).
 * Mirrors the LSP client's shape but for DAP. The transport is a proot child process's stdio pipes.
 */
class DebugController(
    private val distroService: DistroService,
    parentScope: CoroutineScope,
) {
    // Every DAP request writes to the adapter's transport, and for a TCP adapter (js-debug) that is a
    // socket write — illegal on Android's main thread. The caller passes viewModelScope, which is
    // Dispatchers.Main, so requests raised from here (stackTrace/scopes/variables after a `stopped`
    // event, step/continue) threw NetworkOnMainThreadException inside the transport's catch-all and
    // were silently dropped: js-debug paused correctly but the call stack and variables stayed empty.
    // Keep the caller's lifecycle (cancellation still cascades) but do the work off the main thread.
    private val scope = CoroutineScope(parentScope.coroutineContext + Dispatchers.IO)

    private var session: DebugSession? = null
    /** The session that owns the current stopped thread (root, or a js-debug child) — step/continue target. */
    private var activeSession: DebugSession? = null
    /** js-debug child sessions (the debuggee runs here); empty for single-session adapters. */
    private val children = java.util.concurrent.CopyOnWriteArrayList<DebugSession>()
    /** Current breakpoints (host path -> 0-based lines), so freshly-spawned child sessions get them too. */
    private var currentBps: Map<String, Set<Int>> = emptyMap()
    /** Distro cwd of the active launch, reused as the child sessions' project root. */
    private var sessionCwd: String = ""
    /** The root js-debug adapter's TCP port, used as a fallback child-server port. */
    private var rootTcpPort: Int? = null
    /** Guards [endSession] so a terminated event + child cleanup can't tear down twice. */
    private var ended = false
    /** Device-side state of an Android attach, undone when the session ends. */
    private var androidAttach: AndroidDebugAttach? = null

    private val _state = MutableStateFlow(DebugState.DISCONNECTED)
    val state: StateFlow<DebugState> = _state.asStateFlow()
    private val _callStack = MutableStateFlow<List<DapStackFrame>>(emptyList())
    val callStack: StateFlow<List<DapStackFrame>> = _callStack.asStateFlow()
    private val _variables = MutableStateFlow<List<VariableRow>>(emptyList())
    val variables: StateFlow<List<VariableRow>> = _variables.asStateFlow()
    private val _output = MutableStateFlow<List<String>>(emptyList())
    val output: StateFlow<List<String>> = _output.asStateFlow()
    private val _location = MutableStateFlow<DebugLocation?>(null)
    val currentLocation: StateFlow<DebugLocation?> = _location.asStateFlow()

    /**
     * How a session should be launched, after any per-language preparation (e.g. building a .NET
     * project). [tcpPort] non-null selects the TCP transport (js-debug); null uses the adapter's stdio.
     */
    private data class LaunchPlan(
        val distroCwd: String,
        val config: JSONObject,
        val adapterCommand: String,
        val tcpPort: Int?,
        /** Adapter process user override (netcoredbg needs root for /root/.dotnet); null = runtime user. */
        val user: String? = null,
        /** Prepended to the adapter's PATH (e.g. /root/.dotnet). */
        val adapterPath: String = "",
        /** DAP request: "launch" spawns the debuggee, "attach" joins one that is already running. */
        val request: String = "launch",
    )

    /**
     * Why a C# session dies, in the user's words. Established on-device rather than guessed: under proot
     * a process may only ptrace its OWN children — PTRACE_ATTACH to any other pid returns ESRCH even
     * though the pid exists. That kills both of netcoredbg's routes. Attach reports
     * CORDBG_E_DEBUG_COMPONENT_MISSING because dbgshim cannot read the target at all, and launch, which
     * does spawn its own child, still exits without answering (its own --interpreter=cli segfaults the
     * same way, with JCode out of the picture) on net8.0, net9.0 and net10.0 alike. Building and RUNNING
     * .NET works fine, so point there instead of leaving a bare adapter error on screen.
     */
    /** Start debugging [hostPath] (its language picks the engine) with the current [bps] breakpoints. */
    fun startDebug(hostPath: String, projectDir: String, bps: Map<String, Set<Int>>) {
        stop()
        currentBps = bps
        ended = false
        val engine = engineForFile(hostPath, projectDir)
        _output.value = emptyList()
        if (engine == null) {
            pushOutput("No debug engine is installed for ${hostPath.substringAfterLast('/')}.\n")
            _state.value = DebugState.ERROR
            return
        }
        // The JVM entry is a JDWP placeholder with no DAP adapter — launching it would just hang on
        // the `initialize` request until it times out. Fail fast with an actionable message instead.
        if (!engine.dapAdapter) {
            pushOutput(
                "Debugging ${hostPath.substringAfterLast('/')} isn't available yet: JCode has no " +
                    "built-in ${engine.name} adapter.\nRun the program with " +
                    "`-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005` in a terminal " +
                    "and attach an external debugger.\n",
            )
            _state.value = DebugState.ERROR
            return
        }
        // STARTING covers the prepare phase (a .NET build can take a while) so the UI shows progress
        // before any adapter process exists.
        _state.value = DebugState.STARTING
        pushOutput("Preparing ${engine.name}…\n")
        scope.launch {
            val plan = runCatching { prepareLaunch(engine, hostPath, projectDir) }
                .onFailure { pushOutput("Debug setup failed: ${it.message}\n") }
                .getOrNull()
            if (plan == null) {
                _state.value = DebugState.ERROR
                return@launch
            }
            beginSession(engine, plan, hostPath)
        }
    }

    private fun beginSession(engine: DebugEngineEntry, plan: LaunchPlan, hostPath: String) {
        val transportFactory: (String) -> DapTransport? = { command ->
            android.util.Log.d(
                "JCodeDAP-adapter",
                "spawn user=${plan.user ?: "<default>"} cwd=${plan.distroCwd} cmd=$command",
            )
            val proc = distroService.spawnStdioProcess(
                command, workdir = plan.distroCwd, userOverride = plan.user, extraPath = plan.adapterPath,
            )
            when {
                proc == null -> null
                // TCP adapters (js-debug) listen on a port; connect a socket to it once it's up.
                plan.tcpPort != null -> TcpTransport.connect("127.0.0.1", plan.tcpPort, proc, 12_000L)
                else -> ProcessTransport(proc)
            }
        }
        sessionCwd = plan.distroCwd
        rootTcpPort = plan.tcpPort
        val s = DebugSession(engine.debugType, plan.distroCwd, transportFactory)
        session = s
        activeSession = s
        s.onOutput = { _, text -> pushOutput(text) }
        s.onTerminated = { endSession() }
        // js-debug (multi-session): the root adapter asks us — via a `startDebugging` reverse request —
        // to open the CHILD session where the debuggee runs and breakpoints bind. Single-session adapters
        // (python/coreclr/lldb) never fire this, so this is inert for them.
        s.onStartDebugging = { request, config -> spawnChild(request, config) }
        // While preparing we hold STARTING; ignore the fresh session's initial DISCONNECTED so the
        // panel doesn't flicker back to the launch row between build and adapter start.
        scope.launch { s.state.collect { if (!(it == DebugState.DISCONNECTED && _state.value == DebugState.STARTING)) _state.value = it } }
        scope.launch { s.stopped.collect { st -> if (st != null) onStopped(s, st) else clearStoppedView() } }

        val distroBreakpoints = distroBps() // DAP lines are 1-based; applied on `initialized`
        pushOutput("Starting ${engine.name} on ${hostPath.substringAfterLast('/')}…\n")
        scope.launch {
            runCatching { s.start(plan.adapterCommand, plan.request, plan.config, distroBreakpoints) }
                .onFailure { pushOutput("Debug failed: ${it.message}\n"); _state.value = DebugState.ERROR }
            // start() swallows a failed adapter launch (bad transport / handshake) into DISCONNECTED
            // without rethrowing, and the STARTING guard on the state collector can eat that final
            // DISCONNECTED — leaving the panel stuck on "Starting…" forever. If we never advanced past
            // STARTING, the adapter never became reachable: surface it as an error instead of hanging.
            if (_state.value == DebugState.STARTING) {
                pushOutput(
                    "Couldn't reach the ${engine.name} debug adapter — it started but the connection " +
                        "timed out. See the log (tag JCodeDAP-adapter) for details.\n",
                )
                _state.value = DebugState.ERROR
            }
        }
    }

    /**
     * The `site-packages` of the virtualenv a Run config built for this project, or null when there
     * is none yet.
     *
     * A project's dependencies are almost never importable from the system interpreter: `/workspace`
     * is a noexec FUSE mount, so a venv cannot live beside the source and every run recipe stages to
     * `$HOME/.jcode-run/<name>…` on ext4 and builds the venv *there* (see `ProjectRunner`). Debugging
     * against the bare system interpreter therefore ran the right file in the wrong environment and
     * died on the first third-party import — `ModuleNotFoundError: No module named 'flask'` on a
     * project whose Run worked seconds earlier.
     *
     * This returns the package directory rather than the venv's `bin/python` because the venv is
     * built without system site-packages and so has no `debugpy` of its own; handing debugpy that
     * interpreter makes the debuggee die with "No module named debugpy" and the session times out
     * waiting for it. Keeping the system interpreter (which has debugpy) and putting these packages
     * on `PYTHONPATH` gives both halves. The venv is created by that same `python3`, so the ABI
     * matches. The glob covers the per-terminal suffixes recipes append (`-web`, `-server`, …).
     */
    private suspend fun stagedVenvSitePackages(distroProjectDir: String): String? {
        val name = distroProjectDir.trimEnd('/').substringAfterLast('/')
        if (name.isBlank()) return null
        val result = runCatching {
            distroService.exec(
                // Both homes explicitly, as root: `exec` defaults to the `jcode` user but run
                // terminals are root, so a venv a Run built sits in /root/.jcode-run — which is
                // mode 700 and unreadable as `jcode`. This only ever lists paths.
                command = "ls -d /root/.jcode-run/$name*/.venv/lib/python*/site-packages " +
                    "/home/*/.jcode-run/$name*/.venv/lib/python*/site-packages 2>/dev/null | head -1",
                workdir = distroProjectDir,
                timeoutMs = 10_000L,
                user = "root",
            )
        }.getOrNull() ?: return null
        return result.stdout.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
    }

    /** Per-language launch preparation: interpreted langs run the source directly; compiled/served
     *  langs need a build or a TCP adapter. Runs off the main thread (a .NET build blocks). */
    private suspend fun prepareLaunch(engine: DebugEngineEntry, hostPath: String, projectDir: String): LaunchPlan {
        val distroCwd = hostToDistro(projectDir)
        return when (engine.debugType) {
            "python" -> {
                // Debug the project against the dependencies Run installed for it, or every
                // third-party import fails here. See [stagedVenvSitePackages].
                val venvPackages = stagedVenvSitePackages(distroCwd)
                LaunchPlan(
                    distroCwd = distroCwd,
                    config = baseConfig("python", distroCwd).apply {
                        put("program", hostToDistro(hostPath)); put("justMyCode", false); put("redirectOutput", true)
                        venvPackages?.let { put("env", JSONObject().put("PYTHONPATH", it)) }
                    },
                    adapterCommand = engine.adapterCommand,
                    tcpPort = null,
                    // Those packages sit in the run user's home, which is mode 700, so the debuggee
                    // has to be that user to read them at all. Run already executes the app as root,
                    // so this is parity with Run rather than a new privilege.
                    user = if (venvPackages != null) "root" else null,
                )
            }
            "coreclr" -> prepareDotnet(engine, hostPath, projectDir)
            "java" -> prepareJvm(engine, hostPath, projectDir)
            "lldb" -> prepareNative(engine, hostPath, projectDir)
            "pwa-node" -> {
                val port = randomDebugPort()
                LaunchPlan(
                    distroCwd = distroCwd,
                    config = baseConfig("pwa-node", distroCwd).apply { put("program", hostToDistro(hostPath)) },
                    adapterCommand = engine.adapterCommand.replace("{{port}}", port.toString()),
                    tcpPort = port,
                )
            }
            "pwa-chrome" -> {
                val port = randomDebugPort()
                LaunchPlan(
                    distroCwd = distroCwd,
                    config = baseConfig("pwa-chrome", distroCwd).apply {
                        put("url", "http://127.0.0.1:5173"); put("webRoot", distroCwd)
                    },
                    adapterCommand = engine.adapterCommand.replace("{{port}}", port.toString()),
                    tcpPort = port,
                )
            }
            else -> LaunchPlan(
                distroCwd = distroCwd,
                config = baseConfig(engine.debugType, distroCwd).apply {
                    put("program", hostToDistro(hostPath)); put("args", JSONArray())
                },
                adapterCommand = engine.adapterCommand,
                tcpPort = null,
            )
        }
    }

    /**
     * Compile the source and point lldb at the binary it produced.
     *
     * lldb debugs a *program*; handing it a `.c` gets "doesn't contain any 'objfile'", which is what
     * every C/C++/Rust session did before this — the generic launch path passed the source through as
     * `program`, an assumption that only holds for Python, where the source really is the program.
     * Java and .NET already build first ([prepareJava], [prepareDotnet]); this is the same step for
     * native code.
     *
     * `-g` is the point: without DWARF there is nothing to map a breakpoint back to a line with. `-O0`
     * keeps the mapping honest, since optimised code reorders and folds away the statements you set
     * breakpoints on.
     */
    private suspend fun prepareNative(engine: DebugEngineEntry, hostPath: String, projectDir: String): LaunchPlan {
        val srcFile = java.io.File(hostPath)
        val srcDistro = hostToDistro(hostPath)
        val outDir = "/tmp/jcode-native"
        val outBin = "$outDir/${srcFile.nameWithoutExtension}"
        // A header is not a translation unit; compiling one produces no program to run. Say so rather
        // than letting the compiler emit something obscure about precompiled headers.
        val compile = when (val ext = srcFile.extension.lowercase()) {
            "c" -> "cc -g -O0 -o '$outBin' '$srcDistro'"
            "cpp", "cc", "cxx" -> "c++ -g -O0 -o '$outBin' '$srcDistro'"
            "rs" -> "rustc -g -o '$outBin' '$srcDistro'"
            else -> throw dev.blamspot.jcode.core.debug.DebugException(
                "Can't debug a .$ext on its own — open the .c/.cpp/.rs that defines main().",
            )
        }
        pushOutput("Compiling ${srcFile.name}…\n")
        val build = distroService.exec(
            command = "mkdir -p '$outDir' && rm -f '$outBin' && $compile",
            workdir = hostToDistro(projectDir),
            timeoutMs = 300_000L,
            onLine = { pushOutput(it + "\n") },
        )
        if (!build.succeeded) {
            // The compiler's own diagnostics are already in the console via onLine; this is the summary.
            throw dev.blamspot.jcode.core.debug.DebugException(
                build.stderr.lineSequence().firstOrNull { it.isNotBlank() }
                    ?: "Compile failed (exit ${build.exitCode ?: build.internalError}).",
            )
        }
        pushOutput("Launching ${srcFile.nameWithoutExtension} under ${engine.name}…\n")
        val distroCwd = hostToDistro(projectDir)
        return LaunchPlan(
            distroCwd = distroCwd,
            config = baseConfig(engine.debugType, distroCwd).apply {
                put("program", outBin)
                put("args", JSONArray())
                // lldb resolves breakpoints back to the source through DWARF, which records the path
                // the compiler saw — the guest path, which is what the editor's breakpoints use too.
                put("stopOnEntry", false)
            },
            adapterCommand = engine.adapterCommand,
            tcpPort = null,
            // lldb-dap must run as root. It debugs through ptrace, and proot only grants that to its
            // fake-root (-0) user: spawned as the unprivileged runtime user, lldb-server starts but
            // never attaches to the inferior, so `launch` never answers and the session times out
            // after 30s with no output at all from the adapter.
            user = "root",
        )
    }

    /** Build the enclosing .csproj and point netcoredbg at the produced DLL (source alone won't launch). */
    private suspend fun prepareDotnet(engine: DebugEngineEntry, hostPath: String, projectDir: String): LaunchPlan {
        val csproj = findCsproj(hostPath, projectDir)
            ?: throw dev.blamspot.jcode.core.debug.DebugException("No .csproj found near ${hostPath.substringAfterLast('/')}.")
        val csprojDir = csproj.parentFile ?: throw dev.blamspot.jcode.core.debug.DebugException("Bad project path.")
        val distroDir = hostToDistro(csprojDir.path)
        // .NET lives under /root/.dotnet (installed as root); build with that HOME/PATH.
        val dotnetPath = "/root/.dotnet:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        // Under proot the .NET GC otherwise tries to reserve 256 GiB of address space and CoreCLR fails
        // to start (0x8007000E) — for the Roslyn compiler AND the debuggee. Cap the heap to 1 GiB.
        val gcHeapLimit = "0x40000000"
        val env = mapOf(
            "HOME" to "/root", "DOTNET_ROOT" to "/root/.dotnet", "DOTNET_CLI_TELEMETRY_OPTOUT" to "1",
            "DOTNET_GCHeapHardLimit" to gcHeapLimit, "PATH" to dotnetPath,
        )
        pushOutput("Building ${csproj.name} (dotnet build)…\n")
        val build = distroService.exec(
            command = "cd '$distroDir' && dotnet build -c Debug -v m",
            workdir = distroDir,
            env = env,
            timeoutMs = 600_000L,
            onLine = { pushOutput(it + "\n") },
            user = "root",
        )
        if (!build.succeeded) {
            throw dev.blamspot.jcode.core.debug.DebugException("dotnet build failed (exit ${build.exitCode ?: build.internalError}).")
        }
        val builtDll = findBuiltDll(csprojDir, build.stdout)
            ?: throw dev.blamspot.jcode.core.debug.DebugException("Build succeeded but no output DLL was found.")
        // Stage the build output onto ext4 before debugging. /workspace is a noexec FUSE mount and
        // netcoredbg cannot debug a program launched from it — the identical binary, project and
        // breakpoints hit from ext4 and fail from /workspace. The PDB still records the original
        // /workspace source path, so breakpoints keep binding against the files the editor shows.
        // Same reasoning, and the same destination filesystem, as ProjectRunner's run staging.
        val stageDir = "/root/.jcode-debug/" + csprojDir.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val outDir = builtDll.substringBeforeLast('/')
        val dll = stageDir + "/" + builtDll.substringAfterLast('/')
        val staged = distroService.exec(
            command = "rm -rf '$stageDir' && mkdir -p '$stageDir' && cp -r '$outDir'/. '$stageDir'/",
            workdir = distroDir,
            env = env,
            timeoutMs = 120_000L,
            user = "root",
        )
        if (!staged.succeeded) {
            throw dev.blamspot.jcode.core.debug.DebugException(
                "Couldn't stage the build output for debugging (exit ${staged.exitCode ?: staged.internalError}).",
            )
        }
        pushOutput("Launching $dll under netcoredbg…\n")
        val config = baseConfig("coreclr", stageDir).apply {
            put("program", dll)
            put("stopAtEntry", false)
            put("justMyCode", false)
            put("env", JSONObject()
                .put("DOTNET_ROOT", "/root/.dotnet")
                .put("DOTNET_GCHeapHardLimit", gcHeapLimit)
                .put("ASPNETCORE_ENVIRONMENT", "Development")
                .put("PATH", dotnetPath))
        }
        // netcoredbg must run as root (where /root/.dotnet lives) with DOTNET_ROOT set, or CoreCLR
        // hosting of the debuggee fails. It also needs the GC cap for ITSELF, not just for the build
        // and the debuggee: netcoredbg hosts its own CoreCLR (to run ManagedPart.dll), so without the
        // cap it hits the same 256 GiB reservation under proot and dies — leaving no adapter process
        // at all, no debuggee, and a session stuck on "Running" with nothing in the console.
        val adapterCommand = "export DOTNET_ROOT=/root/.dotnet DOTNET_GCHeapHardLimit=$gcHeapLimit; " +
            "exec ${engine.adapterCommand}"
        // netcoredbg itself also runs from the staged directory, not the project: the adapter
        // inherits its working directory and the debuggee is launched relative to it, and the
        // whole session only works when that side sits on ext4 too.
        return LaunchPlan(stageDir, config, adapterCommand, tcpPort = null, user = "root", adapterPath = "/root/.dotnet")
    }

    /** Walk up from the source file to [projectDir] for a .csproj, else search a few levels down. */
    private fun findCsproj(hostPath: String, projectDir: String): java.io.File? {
        val root = java.io.File(projectDir)
        var dir = java.io.File(hostPath).parentFile
        while (dir != null && dir.path.length >= root.path.length) {
            dir.listFiles { f -> f.extension == "csproj" }?.firstOrNull()?.let { return it }
            dir = dir.parentFile
        }
        return root.walkTopDown().maxDepth(4).firstOrNull { it.extension == "csproj" }
    }

    /** The main output DLL: prefer dotnet's "Name -> /path/Name.dll" log line, else glob bin/Debug. */
    private fun findBuiltDll(csprojDir: java.io.File, buildStdout: String): String? {
        Regex("""->\s+(\S+\.dll)""").findAll(buildStdout).map { it.groupValues[1] }.lastOrNull()?.let { return it }
        val name = csprojDir.listFiles { f -> f.extension == "csproj" }?.firstOrNull()?.nameWithoutExtension
        val dll = java.io.File(csprojDir, "bin/Debug").walkTopDown()
            .firstOrNull { it.extension == "dll" && (name == null || it.nameWithoutExtension == name) }
        return dll?.let { hostToDistro(it.path) }
    }

    /** JVM sources take one of two shapes: an Android app (already built, installed and running on a
     *  device — the debugger attaches to its ART process) or a plain source tree javac can compile and
     *  launch here in the distro. */
    private suspend fun prepareJvm(engine: DebugEngineEntry, hostPath: String, projectDir: String): LaunchPlan {
        val module = withContext(Dispatchers.IO) { AndroidAppProject.appModuleFor(hostPath, projectDir) }
        return if (module != null) prepareAndroidAttach(engine, projectDir, module)
        else prepareJava(engine, hostPath, projectDir)
    }

    /**
     * Attach to the Android app built from [module], running on the device behind the ADB bridge.
     *
     * There is nothing to launch: an APK's process is started by the system, and ART exposes JDWP only
     * through adb's `jdwp:<pid>` service. [AndroidDebugAttach] marks the app debuggable-on-launch,
     * resolves its pid and forwards that channel to a local TCP port; the adapter — which runs inside
     * proot, sharing the app's network namespace — then attaches to 127.0.0.1:port like any socket JVM.
     */
    private suspend fun prepareAndroidAttach(
        engine: DebugEngineEntry,
        projectDir: String,
        module: java.io.File,
    ): LaunchPlan {
        pushOutput("Android app module: ${module.name}\n")
        val attach = AndroidDebugAttach(distroService, ::hostToDistro, ::pushOutput)
        androidAttach = attach
        val port = attach.attach(module)

        val distroCwd = hostToDistro(projectDir)
        val sourcePaths = AndroidAppProject.sourceRoots(module).map { hostToDistro(it.path) }
        val config = baseConfig("java", distroCwd, request = "attach").apply {
            put("hostName", "127.0.0.1")
            put("port", port)
            put("projectName", module.name)
            put("sourcePaths", JSONArray().apply { sourcePaths.forEach { put(it) } })
        }
        // The adapter also takes source roots on its command line, which it parses itself — so
        // stack-frame -> file resolution works even if the attach arguments don't carry sourcePaths.
        val adapterCommand = engine.adapterCommand + sourcePaths.joinToString("") { " --source-path '$it'" }
        return LaunchPlan(distroCwd, config, adapterCommand, tcpPort = null, request = "attach")
    }

    /** Compile the enclosing Java sources with javac and launch the main class under the java-debug
     *  adapter (source alone won't run). The adapter speaks DAP over stdio, so there's no TCP leg. */
    private suspend fun prepareJava(engine: DebugEngineEntry, hostPath: String, projectDir: String): LaunchPlan {
        val srcFile = java.io.File(hostPath)
        val text = runCatching { srcFile.readText() }.getOrDefault("")
        if (!Regex("""static\s+void\s+main\s*\(\s*String""").containsMatchIn(text)) {
            throw dev.blamspot.jcode.core.debug.DebugException(
                "No `public static void main(String[])` in ${srcFile.name} — open the file with main().",
            )
        }
        // Java requires the public type to match the file name, so that's the main class.
        val pkg = Regex("""(?m)^\s*package\s+([\w.]+)\s*;""").find(text)?.groupValues?.get(1)
        val mainFqn = (pkg?.let { "$it." } ?: "") + srcFile.nameWithoutExtension
        // Source root = the directory above the package path (default package → the file's own dir).
        var root = srcFile.parentFile ?: srcFile
        pkg?.split('.')?.forEach { root = root.parentFile ?: root }
        val srcRootDistro = hostToDistro(root.path)
        val classesDir = "/tmp/jcode-java-classes"
        pushOutput("Compiling ${srcFile.name} (javac)…\n")
        val build = distroService.exec(
            command = "rm -rf '$classesDir' && mkdir -p '$classesDir' && cd '$srcRootDistro' && " +
                "javac -g -encoding UTF-8 -d '$classesDir' \$(find . -name '*.java')",
            workdir = srcRootDistro,
            timeoutMs = 300_000L,
            onLine = { pushOutput(it + "\n") },
        )
        if (!build.succeeded) {
            throw dev.blamspot.jcode.core.debug.DebugException("javac failed (exit ${build.exitCode ?: build.internalError}).")
        }
        pushOutput("Launching $mainFqn under ${engine.name}…\n")
        val distroCwd = hostToDistro(projectDir)
        val config = baseConfig("java", distroCwd).apply {
            put("mainClass", mainFqn)
            put("classPaths", JSONArray().put(classesDir))
            put("sourcePaths", JSONArray().put(srcRootDistro))
            put("vmArgs", "-Djava.net.preferIPv4Stack=true")
            put("args", "")
        }
        return LaunchPlan(distroCwd, config, engine.adapterCommand, tcpPort = null)
    }

    private fun baseConfig(type: String, cwd: String, request: String = "launch"): JSONObject = JSONObject().apply {
        put("type", type)
        put("request", request)
        put("name", "JCode Debug")
        put("cwd", cwd)
        put("console", "internalConsole")
        put("stopOnEntry", false)
    }

    private fun randomDebugPort(): Int = 41000 + kotlin.random.Random.nextInt(4000)

    /** Current breakpoints as distro-path -> 1-based lines (DAP convention). */
    private fun distroBps(): Map<String, List<Int>> =
        currentBps.mapKeys { hostToDistro(it.key) }.mapValues { e -> e.value.sorted().map { it + 1 } }

    /**
     * js-debug multi-session: open a CHILD DAP session by connecting to the `__jsDebugChildServer` port
     * the root adapter handed us. The debuggee runs and breakpoints bind in the child, so its stopped /
     * call-stack / variables / output feed the same UI; [activeSession] follows whichever child last
     * stopped so step/continue target the right one. Children can nest (workers / child processes).
     */
    private fun spawnChild(request: String, config: JSONObject) {
        val port = config.optString("__jsDebugChildServer", "").toIntOrNull() ?: rootTcpPort
        if (port == null) {
            pushOutput("js-debug requested a child session without a server port; cannot attach.\n")
            return
        }
        val childType = config.optString("type", session?.debugType ?: "pwa-node")
        // We advertise no runInTerminal support and reject it, so force internalConsole (program output
        // via DAP `output` events) — otherwise a propagated terminal console would strand the debuggee.
        config.put("console", "internalConsole")
        val childFactory: (String) -> DapTransport? = { _ -> SocketTransport.connect("127.0.0.1", port, 12_000L) }
        val child = DebugSession(childType, sessionCwd, childFactory)
        children.add(child)
        activeSession = child
        child.onOutput = { _, text -> pushOutput(text) }
        child.onStartDebugging = { req, cfg -> spawnChild(req, cfg) }
        child.onTerminated = { onChildTerminated(child) }
        scope.launch { child.stopped.collect { st -> if (st != null) onStopped(child, st) else clearStoppedView() } }
        // Only the child's STOPPED/RUNNING drive the UI; its start-up states would flicker the panel.
        scope.launch {
            child.state.collect { st ->
                if (st == DebugState.STOPPED || st == DebugState.RUNNING) _state.value = st
            }
        }
        scope.launch {
            runCatching { child.start(adapterCommand = "", request = request, configuration = config, breakpoints = distroBps()) }
            // start() swallows its own failures into DISCONNECTED/ERROR; if the child never reached a live
            // session (e.g. it couldn't connect to the child server), clean it up so it doesn't hang the run.
            if (child.state.value == DebugState.DISCONNECTED || child.state.value == DebugState.ERROR) {
                pushOutput("Child debug session couldn't connect to the js-debug child server.\n")
                onChildTerminated(child)
            }
        }
    }

    private fun onChildTerminated(child: DebugSession) {
        if (activeSession === child) { activeSession = session; _location.value = null }
        children.remove(child)
        runCatching { child.close() }
        // The debuggee(s) ended once no child remains — end the whole session.
        if (children.isEmpty()) endSession()
    }

    private fun onStopped(s: DebugSession, st: StoppedInfo) {
        activeSession = s
        scope.launch {
            runCatching {
                val frames = s.stackTrace(st.threadId)
                _callStack.value = frames
                val top = frames.firstOrNull()
                _location.value = top?.sourcePath?.let { DebugLocation(it, (top.line - 1).coerceAtLeast(0)) }
                if (top != null) refreshVariables(s, top.id)
            }
        }
    }

    /**
     * Fetch each non-expensive scope's variables for [frameId], publishing incrementally so a slow or
     * large scope (e.g. Globals) never hides the ones already resolved. Resilient to one scope failing.
     */
    private suspend fun refreshVariables(s: DebugSession, frameId: Int) {
        val scopes = runCatching { s.scopes(frameId) }.getOrDefault(emptyList())
        val rows = mutableListOf<VariableRow>()
        _variables.value = emptyList()
        for (sc in scopes) {
            if (sc.expensive || sc.variablesReference == 0) continue
            rows.add(VariableRow(sc.name, "", null, depth = 0))
            _variables.value = rows.toList()
            val vars = runCatching { s.variables(sc.variablesReference) }.getOrDefault(emptyList())
            for (v in vars.take(200)) rows.add(VariableRow(v.name, v.value, v.type, depth = 1))
            _variables.value = rows.toList()
        }
    }

    private fun clearStoppedView() {
        _location.value = null
        _callStack.value = emptyList()
        _variables.value = emptyList()
    }

    /** Called when the user toggles a breakpoint; remembers it and pushes to every live session. */
    fun onBreakpointsChanged(hostPath: String, lines: Set<Int>) {
        currentBps = currentBps.toMutableMap().apply { if (lines.isEmpty()) remove(hostPath) else put(hostPath, lines) }
        val distroPath = hostToDistro(hostPath)
        val distroLines = lines.sorted().map { it + 1 }
        // Breakpoints bind in the child sessions (js-debug); harmless on a single-session root.
        val targets = buildList { session?.let { add(it) }; addAll(children) }
        targets.forEach { s -> scope.launch { runCatching { s.setBreakpoints(distroPath, distroLines) } } }
    }

    fun resume() = withThread { s, t -> s.continueThread(t) }

    /** Interrupt a running debuggee (DAP `pause`). Optional in the protocol — an adapter that does
     *  not implement it simply reports an error, which [DebugSession.pause] already swallows. */
    fun pause() = withThread { s, t -> s.pause(t) }
    fun stepOver() = withThread { s, t -> s.next(t) }
    fun stepInto() = withThread { s, t -> s.stepIn(t) }
    fun stepOut() = withThread { s, t -> s.stepOut(t) }

    /**
     * Evaluate [expression] in the top stopped frame (DAP `evaluate`, context "hover") and deliver the
     * result — or null if there is no stopped frame or the expression has no value — on the main thread.
     * Backs the editor's variable inspection; the result carries the children handle when the value is
     * structured, so the inspector can expand it rather than showing a flattened `toString()`.
     */
    fun evaluate(expression: String, onResult: (DapEvaluation?) -> Unit) {
        val s = activeSession ?: session
        val frameId = _callStack.value.firstOrNull()?.id
        if (s == null || frameId == null || _state.value != DebugState.STOPPED) {
            onResult(null)
            return
        }
        scope.launch {
            val value = runCatching { s.evaluate(expression, frameId, "hover") }
                .getOrNull()
                ?.takeIf { it.result.isNotBlank() || it.expandable }
            withContext(Dispatchers.Main) { onResult(value) }
        }
    }

    /**
     * Children of an expandable value (DAP `variables`), for the inspector's tree. Empty on any
     * failure — a node that cannot be expanded should render as a leaf, not as an error.
     */
    fun variables(reference: Int, onResult: (List<DapVariable>) -> Unit) {
        val s = activeSession ?: session
        if (s == null || reference <= 0 || _state.value != DebugState.STOPPED) {
            onResult(emptyList())
            return
        }
        scope.launch {
            val rows = runCatching { s.variables(reference) }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) { onResult(rows) }
        }
    }

    fun stop() {
        ended = true // a late `terminated` event must not resurrect the session as TERMINATED
        releaseAndroidAttachment()
        children.forEach { runCatching { it.close() } }
        children.clear()
        session?.close()
        session = null
        activeSession = null
        _state.value = DebugState.DISCONNECTED
        clearStoppedView()
    }

    /** A debuggee-driven end (root or last child terminated): tear everything down, exactly once. */
    private fun endSession() {
        if (ended) return
        ended = true
        _location.value = null
        releaseAndroidAttachment()
        children.forEach { runCatching { it.close() } }
        children.clear()
        // Close the root so proot's --kill-on-exit reaps the adapter tree instead of leaking it.
        session?.let { runCatching { it.close() } }
        session = null
        activeSession = null
        _state.value = DebugState.TERMINATED
    }

    /** Clear the device-side `set-debug-app -w` flag left by an Android attach, exactly once. */
    private fun releaseAndroidAttachment() {
        val attach = androidAttach ?: return
        androidAttach = null
        scope.launch { runCatching { attach.detach() } }
    }

    private inline fun withThread(crossinline block: suspend (DebugSession, Int) -> Unit) {
        val s = activeSession ?: session ?: return
        val t = s.stopped.value?.threadId ?: s.threads.value.firstOrNull()?.id ?: 1
        scope.launch { runCatching { block(s, t) } }
    }

    private fun pushOutput(text: String) {
        val line = text.trimEnd('\n')
        if (line.isNotEmpty()) _output.value = (_output.value + line).takeLast(500)
    }

    private fun engineForFile(hostPath: String, projectDir: String? = null): DebugEngineEntry? {
        val ext = "." + hostPath.substringAfterLast('.', "")
        DebugEngineCatalog.BUILT_IN.firstOrNull { ext in it.extensions }?.let { return it }
        // Kotlin ships no engine of its own. In an Android app it compiles to JVM bytecode that the
        // java-debug adapter debugs over ART's JDWP, so .kt resolves there — and only there, since
        // outside an Android project nothing would be running to attach to.
        if (ext == KOTLIN_EXT && AndroidAppProject.appModuleFor(hostPath, projectDir) != null) {
            return DebugEngineCatalog.findById(JAVA_ENGINE_ID)
        }
        return null
    }

    /** True if [hostPath]'s language has a built-in DAP engine — the single source of truth for
     *  whether the Debug action can launch it (used by run-config entry derivation). */
    fun canDebugFile(hostPath: String): Boolean = engineForFile(hostPath) != null

    /** The catalog id of the engine that would debug [hostPath], for callers that also have to check
     *  the engine is installed. Resolves .kt in an Android app to java-debug, which a plain extension
     *  match over the catalog cannot. */
    fun debugEngineIdFor(hostPath: String): String? = engineForFile(hostPath)?.id

    private fun hostToDistro(p: String): String =
        dev.blamspot.jcode.core.distro.WorkspaceHostPaths.hostToGuest(p).replace("\\", "/")

    private companion object {
        const val KOTLIN_EXT = ".kt"
        const val JAVA_ENGINE_ID = "java-debug"
    }
}

/** Adapts a proot child process's stdio pipes to [DapTransport] (blocking reads, no PTY echo). */
private class ProcessTransport(private val process: Process) : DapTransport {
    private val input = process.inputStream
    private val output = process.outputStream

    init {
        // Drain the adapter's stderr so its pipe never fills and blocks the adapter — and log what it
        // says. Discarding it left a stdio adapter that fails at startup completely mute: the session
        // just timed out after 30s with nothing to go on, while the TCP path had logged its adapter's
        // output all along.
        Thread {
            runCatching {
                process.errorStream.bufferedReader().forEachLine { line ->
                    android.util.Log.d("JCodeDAP-adapter", "[err] $line")
                }
            }
        }.apply { isDaemon = true }.start()
    }

    override fun read(buffer: ByteArray): Int = try { input.read(buffer) } catch (e: Exception) { -1 }
    override fun write(bytes: ByteArray) {
        // Never swallow silently: a dropped write looks exactly like an adapter that stopped
        // answering, and the request then just times out 30s later with no clue why.
        try {
            output.write(bytes); output.flush()
        } catch (e: Exception) {
            android.util.Log.w("JCodeDAP-adapter", "DAP write failed", e)
        }
    }
    override fun close() {
        runCatching { process.destroy() }
    }

    // Linux reports a signal-killed child as 128 + signal, which is how a segfaulting adapter
    // (netcoredbg under proot does exactly this) becomes an actionable message instead of a timeout.
    override fun terminationDetail(): String? = runCatching {
        // EOF on the pipe races the child being reaped, so give it a moment before asking.
        if (!process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) return@runCatching null
        val code = process.exitValue()
        if (code > 128) "crashed with signal ${code - 128}" else "exit code $code"
    }.getOrNull()
}

/**
 * Adapts a TCP DAP adapter (js-debug's `dapDebugServer`, which listens on a port inside proot) to
 * [DapTransport]. proot shares the host network namespace, so the guest's 127.0.0.1:port is directly
 * reachable from the app. The listener [process] is held so closing tears down its proot tree too.
 */
private class TcpTransport private constructor(
    private val socket: java.net.Socket,
    private val process: Process,
) : DapTransport {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    // stdout/stderr are already being drained by connect()'s logging threads (started before the
    // connect loop so the adapter's pipes never fill and any startup error is captured) — the DAP
    // stream itself flows over the socket, so nothing more to drain here.

    override fun read(buffer: ByteArray): Int = try { input.read(buffer) } catch (e: Exception) { -1 }
    override fun write(bytes: ByteArray) {
        // Never swallow silently: a dropped write looks exactly like an adapter that stopped
        // answering, and the request then just times out 30s later with no clue why.
        try {
            output.write(bytes); output.flush()
        } catch (e: Exception) {
            android.util.Log.w("JCodeDAP-adapter", "DAP write failed", e)
        }
    }
    override fun close() {
        runCatching { socket.close() }
        runCatching { process.destroy() }
    }

    companion object {
        /**
         * Retry-connect until the adapter is listening or [timeoutMs] elapses / the process dies.
         * A TCP adapter (js-debug) talks DAP over the socket, but still prints startup logs/errors to
         * its stdout/stderr pipes. Drain BOTH from the moment it spawns — otherwise a full pipe blocks
         * node before it binds the port (a silent "connect timeout"). The drained lines go to logcat
         * (tag JCodeDAP-adapter) and a bounded tail so a died/never-bound adapter reports WHY.
         */
        fun connect(host: String, port: Int, process: Process, timeoutMs: Long): TcpTransport? {
            val tail = java.util.concurrent.ConcurrentLinkedQueue<String>()
            fun drain(stream: java.io.InputStream, tag: String) = Thread {
                runCatching {
                    stream.bufferedReader().forEachLine { line ->
                        android.util.Log.d("JCodeDAP-adapter", "[$tag] $line")
                        tail.add("[$tag] $line"); while (tail.size > 60) tail.poll()
                    }
                }
            }.apply { isDaemon = true }.start()
            drain(process.inputStream, "out")
            drain(process.errorStream, "err")
            val deadline = System.currentTimeMillis() + timeoutMs
            var lastConnectError: Throwable? = null
            while (System.currentTimeMillis() < deadline) {
                if (!process.isAlive) {
                    val code = runCatching { process.exitValue() }.getOrNull()
                    android.util.Log.e(
                        "JCodeDAP-adapter",
                        "adapter exited early (code=$code) before $host:$port was reachable; output:\n" +
                            tail.joinToString("\n"),
                    )
                    return null
                }
                val attempt = runCatching {
                    java.net.Socket().apply { connect(java.net.InetSocketAddress(host, port), 1000) }
                }
                attempt.getOrNull()?.let { return TcpTransport(it, process) }
                lastConnectError = attempt.exceptionOrNull()
                Thread.sleep(200)
            }
            val reason = lastConnectError?.let { "${it::class.java.simpleName}: ${it.message}" } ?: "none"
            android.util.Log.e(
                "JCodeDAP-adapter",
                "adapter never became reachable on $host:$port within ${timeoutMs}ms " +
                    "(last connect error: $reason); output:\n" +
                    tail.joinToString("\n"),
            )
            // Best-effort teardown of the still-running adapter. NOTE: a TCP adapter that we never
            // connected to (js-debug) can't be told to `disconnect`, and destroy() doesn't reliably
            // trip proot's --kill-on-exit here, so the node/proot tree may linger until the app is
            // restarted. This only happens on the js-debug transport failure (see JCodeDAP-adapter log).
            runCatching { process.destroy() }
            return null
        }
    }
}

/**
 * A plain socket [DapTransport] for a js-debug CHILD session: connects to the `__jsDebugChildServer`
 * port the root adapter provides. Unlike [TcpTransport] it owns no process — the root adapter process
 * hosts every child server, so closing the root (proot `--kill-on-exit`) reaps the children too; this
 * only closes its own socket.
 */
private class SocketTransport private constructor(private val socket: java.net.Socket) : DapTransport {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    override fun read(buffer: ByteArray): Int = try { input.read(buffer) } catch (e: Exception) { -1 }
    override fun write(bytes: ByteArray) {
        // Never swallow silently: a dropped write looks exactly like an adapter that stopped
        // answering, and the request then just times out 30s later with no clue why.
        try {
            output.write(bytes); output.flush()
        } catch (e: Exception) {
            android.util.Log.w("JCodeDAP-adapter", "DAP write failed", e)
        }
    }
    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        /** Retry-connect to the child DAP server until it accepts or [timeoutMs] elapses. */
        fun connect(host: String, port: Int, timeoutMs: Long): SocketTransport? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val sock = runCatching {
                    java.net.Socket().apply { connect(java.net.InetSocketAddress(host, port), 1000) }
                }.getOrNull()
                if (sock != null) return SocketTransport(sock)
                Thread.sleep(100)
            }
            return null
        }
    }
}
