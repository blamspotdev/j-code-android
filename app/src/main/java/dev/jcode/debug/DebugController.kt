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

    /** Start debugging [hostPath] (its language picks the engine) with the current [bps] breakpoints. */
    fun startDebug(hostPath: String, projectDir: String, bps: Map<String, Set<Int>>) {
        stop()
        val engine = engineForFile(hostPath)
        if (engine == null) {
            pushOutput("No debug engine is installed for ${hostPath.substringAfterLast('/')}.\n")
            return
        }
        val distroCwd = hostToDistro(projectDir)
        val distroProgram = hostToDistro(hostPath)
        val s = DebugSession(engine.debugType, distroCwd) { command ->
            distroService.spawnDapProcess(command, workdir = distroCwd)?.let { ProcessTransport(it) }
        }
        session = s
        s.onOutput = { _, text -> pushOutput(text) }
        s.onTerminated = { _state.value = DebugState.TERMINATED; _location.value = null }
        scope.launch { s.state.collect { _state.value = it } }
        scope.launch { s.stopped.collect { st -> if (st != null) onStopped(st) else clearStoppedView() } }

        val config = launchConfig(engine.debugType, distroProgram, distroCwd)
        val distroBreakpoints = bps.mapKeys { hostToDistro(it.key) }
            .mapValues { entry -> entry.value.map { it + 1 } } // DAP lines are 1-based
        _output.value = emptyList()
        pushOutput("Starting ${engine.name} on ${hostPath.substringAfterLast('/')}…\n")
        scope.launch {
            runCatching { s.start(engine.adapterCommand, "launch", config, distroBreakpoints) }
                .onFailure { pushOutput("Debug failed: ${it.message}\n"); _state.value = DebugState.ERROR }
        }
    }

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

    private fun launchConfig(debugType: String, program: String, cwd: String): JSONObject = JSONObject().apply {
        put("type", debugType)
        put("request", "launch")
        put("name", "JCode Debug")
        put("program", program)
        put("cwd", cwd)
        put("console", "internalConsole")
        put("stopOnEntry", false)
        when (debugType) {
            "python" -> { put("justMyCode", false); put("redirectOutput", true) }
            "lldb", "coreclr" -> put("args", org.json.JSONArray())
        }
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
