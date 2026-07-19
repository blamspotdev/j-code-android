package dev.jcode.debug

import dev.jcode.core.debug.DapStackFrame
import dev.jcode.core.debug.DapTransport
import dev.jcode.core.debug.DebugSession
import dev.jcode.core.debug.DebugState
import dev.jcode.core.debug.StoppedInfo
import dev.jcode.core.distro.DebugEngineCatalog
import dev.jcode.core.distro.DebugEngineEntry
import dev.jcode.core.distro.DistroService
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
    private val scope: CoroutineScope,
) {
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
    )

    /** Start debugging [hostPath] (its language picks the engine) with the current [bps] breakpoints. */
    fun startDebug(hostPath: String, projectDir: String, bps: Map<String, Set<Int>>) {
        stop()
        currentBps = bps
        ended = false
        val engine = engineForFile(hostPath)
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
            val proc = distroService.spawnDapProcess(
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
            runCatching { s.start(plan.adapterCommand, "launch", plan.config, distroBreakpoints) }
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

    /** Per-language launch preparation: interpreted langs run the source directly; compiled/served
     *  langs need a build or a TCP adapter. Runs off the main thread (a .NET build blocks). */
    private suspend fun prepareLaunch(engine: DebugEngineEntry, hostPath: String, projectDir: String): LaunchPlan {
        val distroCwd = hostToDistro(projectDir)
        return when (engine.debugType) {
            "python" -> LaunchPlan(
                distroCwd = distroCwd,
                config = baseConfig("python", distroCwd).apply {
                    put("program", hostToDistro(hostPath)); put("justMyCode", false); put("redirectOutput", true)
                },
                adapterCommand = engine.adapterCommand,
                tcpPort = null,
            )
            "coreclr" -> prepareDotnet(engine, hostPath, projectDir)
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

    /** Build the enclosing .csproj and point netcoredbg at the produced DLL (source alone won't launch). */
    private suspend fun prepareDotnet(engine: DebugEngineEntry, hostPath: String, projectDir: String): LaunchPlan {
        val csproj = findCsproj(hostPath, projectDir)
            ?: throw dev.jcode.core.debug.DebugException("No .csproj found near ${hostPath.substringAfterLast('/')}.")
        val csprojDir = csproj.parentFile ?: throw dev.jcode.core.debug.DebugException("Bad project path.")
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
            throw dev.jcode.core.debug.DebugException("dotnet build failed (exit ${build.exitCode ?: build.internalError}).")
        }
        val dll = findBuiltDll(csprojDir, build.stdout)
            ?: throw dev.jcode.core.debug.DebugException("Build succeeded but no output DLL was found.")
        pushOutput("Launching $dll under netcoredbg…\n")
        val config = baseConfig("coreclr", distroDir).apply {
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
        // hosting of the debuggee fails.
        val adapterCommand = "export DOTNET_ROOT=/root/.dotnet; exec ${engine.adapterCommand}"
        return LaunchPlan(distroDir, config, adapterCommand, tcpPort = null, user = "root", adapterPath = "/root/.dotnet")
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

    private fun baseConfig(type: String, cwd: String): JSONObject = JSONObject().apply {
        put("type", type)
        put("request", "launch")
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
    fun stepOver() = withThread { s, t -> s.next(t) }
    fun stepInto() = withThread { s, t -> s.stepIn(t) }
    fun stepOut() = withThread { s, t -> s.stepOut(t) }

    /**
     * Evaluate [expression] in the top stopped frame (DAP `evaluate`, context "hover") and deliver the
     * result — or null if there is no stopped frame or the expression has no value — on the main thread.
     * Backs the editor's long-press variable inspection.
     */
    fun evaluate(expression: String, onResult: (String?) -> Unit) {
        val s = activeSession ?: session
        val frameId = _callStack.value.firstOrNull()?.id
        if (s == null || frameId == null || _state.value != DebugState.STOPPED) {
            onResult(null)
            return
        }
        scope.launch {
            val value = runCatching { s.evaluate(expression, frameId, "hover") }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
            withContext(Dispatchers.Main) { onResult(value) }
        }
    }

    fun stop() {
        ended = true // a late `terminated` event must not resurrect the session as TERMINATED
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
        children.forEach { runCatching { it.close() } }
        children.clear()
        // Close the root so proot's --kill-on-exit reaps the adapter tree instead of leaking it.
        session?.let { runCatching { it.close() } }
        session = null
        activeSession = null
        _state.value = DebugState.TERMINATED
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

    private fun engineForFile(hostPath: String): DebugEngineEntry? {
        val ext = "." + hostPath.substringAfterLast('.', "")
        return DebugEngineCatalog.BUILT_IN.firstOrNull { ext in it.extensions }
    }

    /** True if [hostPath]'s language has a built-in DAP engine — the single source of truth for
     *  whether the Debug action can launch it (used by run-config entry derivation). */
    fun canDebugFile(hostPath: String): Boolean = engineForFile(hostPath) != null

    private fun hostToDistro(p: String): String =
        dev.jcode.core.distro.WorkspaceHostPaths.hostToGuest(p).replace("\\", "/")
}

/** Adapts a proot child process's stdio pipes to [DapTransport] (blocking reads, no PTY echo). */
private class ProcessTransport(private val process: Process) : DapTransport {
    private val input = process.inputStream
    private val output = process.outputStream

    init {
        // Drain the adapter's stderr so its pipe never fills and blocks the adapter.
        Thread {
            runCatching { process.errorStream.bufferedReader().forEachLine { } }
        }.apply { isDaemon = true }.start()
    }

    override fun read(buffer: ByteArray): Int = try { input.read(buffer) } catch (e: Exception) { -1 }
    override fun write(bytes: ByteArray) {
        try { output.write(bytes); output.flush() } catch (_: Exception) {}
    }
    override fun close() {
        runCatching { process.destroy() }
    }
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
        try { output.write(bytes); output.flush() } catch (_: Exception) {}
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
                val sock = runCatching {
                    java.net.Socket().apply { connect(java.net.InetSocketAddress(host, port), 1000) }
                }.getOrNull()
                if (sock != null) return TcpTransport(sock, process)
                Thread.sleep(200)
            }
            android.util.Log.e(
                "JCodeDAP-adapter",
                "adapter never became reachable on $host:$port within ${timeoutMs}ms; output:\n" +
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
        try { output.write(bytes); output.flush() } catch (_: Exception) {}
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
