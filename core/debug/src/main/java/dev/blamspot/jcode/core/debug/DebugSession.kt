package dev.blamspot.jcode.core.debug

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Debug Adapter Protocol (DAP) client session. The transport mirrors
 * [dev.blamspot.jcode.core.lsp.LspSession] exactly — JSON messages framed with `Content-Length` headers over
 * a PTY (the adapter runs inside the distro). Only the message envelope differs: DAP uses
 * `{seq, type: request|response|event, command|event, arguments|body}` instead of LSP's JSON-RPC.
 *
 * Handshake follows the VS Code order: initialize → (launch|attach) → `initialized` event →
 * setBreakpoints → configurationDone → launch response, then `stopped`/`continued`/`output`/
 * `terminated` events drive the UI.
 */
class DebugSession(
    /** DAP `adapterID` / config `type` (e.g. "python", "lldb", "coreclr"). */
    val debugType: String,
    val projectRoot: String,
    /** Spawns the adapter (given a shell command) and returns its stdio as a [DapTransport], or null. */
    private val transportFactory: (command: String) -> DapTransport?,
) : Closeable {

    private var transport: DapTransport? = null
    private var readJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seq = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val writeMutex = Mutex()

    private val _state = MutableStateFlow(DebugState.DISCONNECTED)
    val state: StateFlow<DebugState> = _state.asStateFlow()

    private val _threads = MutableStateFlow<List<DapThread>>(emptyList())
    val threads: StateFlow<List<DapThread>> = _threads.asStateFlow()

    /** The last `stopped` event (breakpoint / step / pause), or null while running. */
    private val _stopped = MutableStateFlow<StoppedInfo?>(null)
    val stopped: StateFlow<StoppedInfo?> = _stopped.asStateFlow()

    /** Console/program output: (category, text). category is "stdout"/"stderr"/"console". */
    var onOutput: ((String, String) -> Unit)? = null
    var onTerminated: (() -> Unit)? = null

    /**
     * The adapter asked us (a DAP reverse request) to open a CHILD debug session — js-debug's
     * multi-session model, where the debuggee actually runs and breakpoints bind. Args are
     * (request = "launch"|"attach", configuration = the child session's launch/attach config, which
     * carries `__jsDebugChildServer` = the port to connect the child to). The handler spawns the child.
     */
    var onStartDebugging: ((request: String, configuration: JSONObject) -> Unit)? = null

    private var onInitialized: (suspend () -> Unit)? = null
    var capabilities: JSONObject = JSONObject(); private set

    /**
     * Start the adapter and begin a debug session.
     * @param request "launch" or "attach"
     * @param configuration adapter-specific launch/attach arguments (program, cwd, args, …)
     * @param breakpoints distro-path -> 1-based line numbers, applied on the `initialized` event
     */
    suspend fun start(
        adapterCommand: String,
        request: String,
        configuration: JSONObject,
        breakpoints: Map<String, List<Int>> = emptyMap(),
    ) {
        if (_state.value != DebugState.DISCONNECTED) return
        _state.value = DebugState.STARTING
        try {
            val escaped = adapterCommand.replace("'", "'\\''")
            transport = transportFactory("exec bash --noprofile --norc -c '$escaped'")
                ?: throw DebugException("Could not start debug adapter")
            readJob = scope.launch { readLoop() }
            _state.value = DebugState.INITIALIZING

            onInitialized = {
                for ((path, lines) in breakpoints) runCatching { setBreakpoints(path, lines) }
                runCatching { sendRequest("configurationDone", JSONObject()) }
                if (_state.value == DebugState.INITIALIZING) _state.value = DebugState.RUNNING
            }

            capabilities = sendRequest("initialize", JSONObject().apply {
                put("clientID", "jcode")
                put("clientName", "JCode")
                put("adapterID", debugType)
                put("locale", "en")
                put("linesStartAt1", true)
                put("columnsStartAt1", true)
                put("pathFormat", "path")
                put("supportsRunInTerminalRequest", false)
                // js-debug (multi-session) asks us to open child sessions via `startDebugging`; advertise
                // it so the adapter delegates the debuggee to a child instead of stalling on launch.
                put("supportsStartDebuggingRequest", true)
                put("supportsVariableType", true)
            })

            // Fire launch/attach; its response only completes after configurationDone, so don't block.
            scope.launch {
                runCatching { sendRequest(request, configuration) }
                    .onFailure { _state.value = DebugState.ERROR; onOutput?.invoke("stderr", (it.message ?: "launch failed") + "\n") }
            }
        } catch (e: Exception) {
            _state.value = DebugState.ERROR
            close()
        }
    }

    suspend fun setBreakpoints(distroPath: String, lines: List<Int>): List<DapBreakpoint> {
        val body = sendRequest("setBreakpoints", JSONObject().apply {
            put("source", JSONObject().apply {
                put("path", distroPath)
                put("name", distroPath.substringAfterLast('/'))
            })
            put("breakpoints", JSONArray().apply { lines.forEach { put(JSONObject().put("line", it)) } })
            put("lines", JSONArray().apply { lines.forEach { put(it) } })
        })
        return parseBreakpoints(body)
    }

    suspend fun continueThread(threadId: Int) { sendRequest("continue", JSONObject().put("threadId", threadId)); clearStopped() }
    suspend fun next(threadId: Int) { sendRequest("next", JSONObject().put("threadId", threadId)); clearStopped() }
    suspend fun stepIn(threadId: Int) { sendRequest("stepIn", JSONObject().put("threadId", threadId)); clearStopped() }
    suspend fun stepOut(threadId: Int) { sendRequest("stepOut", JSONObject().put("threadId", threadId)); clearStopped() }
    suspend fun pause(threadId: Int) { runCatching { sendRequest("pause", JSONObject().put("threadId", threadId)) } }

    suspend fun stackTrace(threadId: Int): List<DapStackFrame> {
        val body = sendRequest("stackTrace", JSONObject().apply {
            put("threadId", threadId); put("startFrame", 0); put("levels", 50)
        })
        return parseStackFrames(body)
    }

    suspend fun scopes(frameId: Int): List<DapScope> =
        parseScopes(sendRequest("scopes", JSONObject().put("frameId", frameId)))

    suspend fun variables(variablesReference: Int): List<DapVariable> =
        parseVariables(sendRequest("variables", JSONObject().put("variablesReference", variablesReference)))

    /**
     * Evaluate [expression] and return its rendered value *and* the reference the adapter hands back
     * for structured results. Discarding that reference is what made an inspected object a dead
     * string: `variablesReference > 0` is the adapter saying "this has children, ask me for them"
     * — the same handle [variables] takes.
     */
    suspend fun evaluate(expression: String, frameId: Int?, context: String = "repl"): DapEvaluation {
        val body = sendRequest("evaluate", JSONObject().apply {
            put("expression", expression)
            if (frameId != null) put("frameId", frameId)
            put("context", context)
        })
        return DapEvaluation(
            result = body.optString("result", ""),
            type = body.optString("type").takeIf { it.isNotBlank() },
            variablesReference = body.optInt("variablesReference", 0),
        )
    }

    private fun clearStopped() { _stopped.value = null; _state.value = DebugState.RUNNING }

    // ---- transport (mirrors LspSession) ----

    private suspend fun sendRequest(command: String, arguments: JSONObject): JSONObject {
        val id = seq.incrementAndGet()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        writeMessage(JSONObject().apply {
            put("seq", id); put("type", "request"); put("command", command); put("arguments", arguments)
        })
        return withTimeout(30_000) { deferred.await() }
    }

    private suspend fun writeMessage(message: JSONObject) {
        val content = message.toString()
        val full = "Content-Length: ${content.toByteArray().size}\r\n\r\n$content"
        writeMutex.withLock { transport?.write(full.toByteArray()) }
    }

    private suspend fun readLoop() {
        val buffer = ByteArray(8192)
        var acc = ByteArray(0)
        while (scope.isActive && _state.value != DebugState.DISCONNECTED) {
            val t = transport ?: break
            val n = t.read(buffer)
            when {
                n > 0 -> { acc += buffer.copyOf(n); acc = process(acc) }
                // EOF: the adapter is gone. Fail everything in flight now — otherwise a crashed
                // adapter looks exactly like a slow one and surfaces as "Timed out waiting for
                // 30000 ms", which says nothing about what actually happened.
                n < 0 -> { onAdapterGone(t.terminationDetail()); break }
                else -> delay(10)
            }
        }
    }

    /**
     * The adapter's output closed. Anything in flight can never be answered, and — just as important —
     * a session whose adapter is gone is over, however healthy it looked a moment ago. Without this a
     * mid-session crash left the UI sitting on "Running" indefinitely with nothing in the console.
     * A normal exit is already TERMINATED by the `terminated` event, so this only reports real deaths.
     */
    private fun onAdapterGone(detail: String?) {
        val why = detail?.let { " ($it)" } ?: ""
        val message = "The debug adapter exited before answering$why."
        val state = _state.value
        val live = state != DebugState.TERMINATED && state != DebugState.DISCONNECTED &&
            state != DebugState.ERROR
        if (pending.isNotEmpty()) {
            // Whoever is awaiting a reply reports it; don't say the same thing twice.
            val error = DebugException(message)
            pending.values.forEach { it.completeExceptionally(error) }
            pending.clear()
        } else if (live) {
            onOutput?.invoke("stderr", message + "\n")
        }
        if (live) _state.value = DebugState.ERROR
    }

    /**
     * Frame DAP messages from the raw byte stream. `Content-Length` is a BYTE count, so the header
     * scan and body slice must operate on bytes — decoding to a String first would mis-slice any
     * response containing multi-byte UTF-8 (e.g. a large `variables` body), stalling every later request.
     */
    private fun process(data: ByteArray): ByteArray {
        var remaining = data
        while (true) {
            val headerEnd = indexOfHeaderEnd(remaining)
            if (headerEnd < 0) break
            val header = String(remaining, 0, headerEnd, Charsets.US_ASCII)
            val len = Regex("Content-Length: (\\d+)").find(header)?.groupValues?.get(1)?.toIntOrNull() ?: break
            val start = headerEnd + 4
            if (remaining.size < start + len) break
            val content = String(remaining, start, len, Charsets.UTF_8)
            remaining = remaining.copyOfRange(start + len, remaining.size)
            runCatching { handleMessage(JSONObject(content)) }
        }
        return remaining
    }

    /** Index of the first `\r\n\r\n` in [data], or -1. */
    private fun indexOfHeaderEnd(data: ByteArray): Int {
        for (i in 0..data.size - 4) {
            if (data[i] == 0x0D.toByte() && data[i + 1] == 0x0A.toByte() &&
                data[i + 2] == 0x0D.toByte() && data[i + 3] == 0x0A.toByte()
            ) return i
        }
        return -1
    }

    private fun handleMessage(json: JSONObject) {
        when (json.optString("type")) {
            "response" -> {
                val deferred = pending.remove(json.optInt("request_seq", -1))
                if (json.optBoolean("success", false)) {
                    deferred?.complete(json.optJSONObject("body") ?: JSONObject())
                } else {
                    deferred?.completeExceptionally(DebugException(json.optString("message", "DAP request failed")))
                }
            }
            "event" -> handleEvent(json.optString("event"), json.optJSONObject("body") ?: JSONObject())
            "request" -> handleReverseRequest(json)
        }
    }

    /**
     * Handle a reverse request (the adapter calling us). js-debug uses `startDebugging` to ask the client
     * to open the CHILD session that runs the debuggee + binds breakpoints — hand it to [onStartDebugging]
     * and ack success so the adapter proceeds. Anything else (e.g. `runInTerminal`, which we don't
     * advertise) is rejected so the adapter never blocks waiting on us.
     */
    private fun handleReverseRequest(json: JSONObject) {
        val command = json.optString("command")
        val requestSeq = json.optInt("seq", -1)
        val args = json.optJSONObject("arguments") ?: JSONObject()
        when (command) {
            "startDebugging" -> {
                runCatching {
                    onStartDebugging?.invoke(
                        args.optString("request", "launch"),
                        args.optJSONObject("configuration") ?: JSONObject(),
                    )
                }
                scope.launch { runCatching { sendResponse(requestSeq, command, success = true) } }
            }
            else -> scope.launch {
                runCatching { sendResponse(requestSeq, command, success = false, message = "$command is not supported") }
            }
        }
    }

    private suspend fun sendResponse(requestSeq: Int, command: String, success: Boolean, message: String? = null) {
        writeMessage(JSONObject().apply {
            put("seq", seq.incrementAndGet())
            put("type", "response")
            put("request_seq", requestSeq)
            put("success", success)
            put("command", command)
            if (message != null) put("message", message)
        })
    }

    private fun handleEvent(event: String, body: JSONObject) {
        when (event) {
            "initialized" -> scope.launch { runCatching { onInitialized?.invoke() } }
            "stopped" -> {
                _stopped.value = StoppedInfo(
                    reason = body.optString("reason", ""),
                    threadId = body.optInt("threadId", 0),
                    description = body.optString("description", ""),
                    text = body.optString("text", ""),
                )
                _state.value = DebugState.STOPPED
                scope.launch { runCatching { _threads.value = threadsNow() } }
            }
            "continued" -> { _stopped.value = null; _state.value = DebugState.RUNNING }
            "output" -> {
                // "telemetry" is adapter analytics, not program output — js-debug emits a stream of
                // js-debug/dap/operation and js-debug/cdp/operation events that otherwise bury the
                // debuggee's own console in the Debug panel. VS Code doesn't show these either.
                val category = body.optString("category", "console")
                if (category != "telemetry") onOutput?.invoke(category, body.optString("output", ""))
            }
            "terminated", "exited" -> { _state.value = DebugState.TERMINATED; onTerminated?.invoke() }
            "thread" -> scope.launch { runCatching { _threads.value = threadsNow() } }
        }
    }

    private suspend fun threadsNow(): List<DapThread> {
        val arr = sendRequest("threads", JSONObject()).optJSONArray("threads") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val t = arr.getJSONObject(i); DapThread(t.optInt("id"), t.optString("name", "thread"))
        }
    }

    /** Host project path -> distro path (mirrors LspSession's translation). */
    fun hostToDistroPath(hostPath: String): String =
        dev.blamspot.jcode.core.distro.WorkspaceHostPaths.hostToGuest(hostPath).replace("\\", "/")

    fun distroToHostPath(distroPath: String): String =
        dev.blamspot.jcode.core.distro.WorkspaceHostPaths.guestToHost(distroPath).replace("/", java.io.File.separator)

    override fun close() {
        scope.launch { runCatching { sendRequest("disconnect", JSONObject().put("terminateDebuggee", true)) } }
        readJob?.cancel()
        transport?.close(); transport = null
        _state.value = DebugState.DISCONNECTED
        pending.values.forEach { it.cancel() }
        pending.clear()
        scope.cancel()
    }

    // ---- parsers ----

    private fun parseBreakpoints(body: JSONObject): List<DapBreakpoint> {
        val arr = body.optJSONArray("breakpoints") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val b = arr.getJSONObject(i)
            DapBreakpoint(id = b.optInt("id", -1), verified = b.optBoolean("verified", false), line = b.optInt("line", 0))
        }
    }

    private fun parseStackFrames(body: JSONObject): List<DapStackFrame> {
        val arr = body.optJSONArray("stackFrames") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val f = arr.getJSONObject(i)
            DapStackFrame(
                id = f.optInt("id", 0),
                name = f.optString("name", ""),
                sourcePath = f.optJSONObject("source")?.optString("path")?.let { distroToHostPath(it) },
                line = f.optInt("line", 0),
                column = f.optInt("column", 0),
            )
        }
    }

    private fun parseScopes(body: JSONObject): List<DapScope> {
        val arr = body.optJSONArray("scopes") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            DapScope(s.optString("name", ""), s.optInt("variablesReference", 0), s.optBoolean("expensive", false))
        }
    }

    private fun parseVariables(body: JSONObject): List<DapVariable> {
        val arr = body.optJSONArray("variables") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val v = arr.getJSONObject(i)
            DapVariable(
                name = v.optString("name", ""),
                value = v.optString("value", ""),
                type = if (v.has("type")) v.optString("type") else null,
                variablesReference = v.optInt("variablesReference", 0),
            )
        }
    }
}

enum class DebugState { DISCONNECTED, STARTING, INITIALIZING, RUNNING, STOPPED, TERMINATED, ERROR }

data class DapThread(val id: Int, val name: String)
data class DapStackFrame(val id: Int, val name: String, val sourcePath: String?, val line: Int, val column: Int)
data class DapScope(val name: String, val variablesReference: Int, val expensive: Boolean)
data class DapVariable(val name: String, val value: String, val type: String?, val variablesReference: Int) {
    val expandable: Boolean get() = variablesReference > 0
}
/** An `evaluate` response: what to show, and the handle to its children when it has any. */
data class DapEvaluation(val result: String, val type: String?, val variablesReference: Int) {
    val expandable: Boolean get() = variablesReference > 0
}
data class DapBreakpoint(val id: Int, val verified: Boolean, val line: Int)
data class StoppedInfo(val reason: String, val threadId: Int, val description: String, val text: String)

class DebugException(message: String) : Exception(message)

/**
 * Bidirectional byte transport for a debug adapter. Backed by a child process's stdio pipes (preferred
 * for DAP — no PTY echo) or a PTY. [read] blocks; returns bytes read, 0 when idle, <0 at EOF.
 */
interface DapTransport {
    fun read(buffer: ByteArray): Int
    fun write(bytes: ByteArray)
    fun close()

    /**
     * Why the adapter is gone, if it is — e.g. "crashed with signal 11". Used to explain an EOF
     * instead of letting the in-flight request sit until its 30s timeout and report nothing useful.
     */
    fun terminationDetail(): String? = null
}
