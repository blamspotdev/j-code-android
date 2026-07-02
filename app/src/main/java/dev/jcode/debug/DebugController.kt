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
        val engine = engineForFile(hostPath)
        _output.value = emptyList()
        if (engine == null) {
            pushOutput("No debug engine is installed for ${hostPath.substringAfterLast('/')}.\n")
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
            beginSession(engine, plan, hostPath, bps)
        }
    }

    private fun beginSession(engine: DebugEngineEntry, plan: LaunchPlan, hostPath: String, bps: Map<String, Set<Int>>) {
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
        val s = DebugSession(engine.debugType, plan.distroCwd, transportFactory)
        session = s
        s.onOutput = { _, text -> pushOutput(text) }
        s.onTerminated = {
            _location.value = null
            // The adapter keeps waiting for another client after the debuggee exits — close the
            // transport so proot's --kill-on-exit reaps the adapter tree instead of leaking it.
            s.close()
            if (session === s) session = null
            _state.value = DebugState.TERMINATED
        }
        // While preparing we hold STARTING; ignore the fresh session's initial DISCONNECTED so the
        // panel doesn't flicker back to the launch row between build and adapter start.
        scope.launch { s.state.collect { if (!(it == DebugState.DISCONNECTED && _state.value == DebugState.STARTING)) _state.value = it } }
        scope.launch { s.stopped.collect { st -> if (st != null) onStopped(st) else clearStoppedView() } }

        val distroBreakpoints = bps.mapKeys { hostToDistro(it.key) }
            .mapValues { entry -> entry.value.map { it + 1 } } // DAP lines are 1-based
        pushOutput("Starting ${engine.name} on ${hostPath.substringAfterLast('/')}…\n")
        scope.launch {
            runCatching { s.start(plan.adapterCommand, "launch", plan.config, distroBreakpoints) }
                .onFailure { pushOutput("Debug failed: ${it.message}\n"); _state.value = DebugState.ERROR }
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

    private fun onStopped(st: StoppedInfo) {
        val s = session ?: return
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

    /** Called when the user toggles a breakpoint; pushes to a live session. */
    fun onBreakpointsChanged(hostPath: String, lines: Set<Int>) {
        val s = session ?: return
        scope.launch { runCatching { s.setBreakpoints(hostToDistro(hostPath), lines.sorted().map { it + 1 }) } }
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
        val s = session
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
        session?.close()
        session = null
        _state.value = DebugState.DISCONNECTED
        clearStoppedView()
    }

    private inline fun withThread(crossinline block: suspend (DebugSession, Int) -> Unit) {
        val s = session ?: return
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

    private fun hostToDistro(p: String): String =
        p.replace("/storage/emulated/0/JCode/projects/", "/workspace/").replace("\\", "/")
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

    init {
        Thread { runCatching { process.errorStream.bufferedReader().forEachLine { } } }
            .apply { isDaemon = true }.start()
    }

    override fun read(buffer: ByteArray): Int = try { input.read(buffer) } catch (e: Exception) { -1 }
    override fun write(bytes: ByteArray) {
        try { output.write(bytes); output.flush() } catch (_: Exception) {}
    }
    override fun close() {
        runCatching { socket.close() }
        runCatching { process.destroy() }
    }

    companion object {
        /** Retry-connect until the adapter is listening or [timeoutMs] elapses / the process dies. */
        fun connect(host: String, port: Int, process: Process, timeoutMs: Long): TcpTransport? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (!process.isAlive) return null
                val sock = runCatching {
                    java.net.Socket().apply { connect(java.net.InetSocketAddress(host, port), 1000) }
                }.getOrNull()
                if (sock != null) return TcpTransport(sock, process)
                Thread.sleep(200)
            }
            return null
        }
    }
}
