package dev.jcode.core.lsp

import dev.jcode.core.term.PtyProcess
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * LSP client session that communicates with a language server over a PTY.
 *
 * The language server runs inside the distro via `proot-distro login`,
 * and we communicate via JSON-RPC over stdio (the PTY's stdin/stdout).
 */
class LspSession(
    val descriptor: LspServerDescriptor,
    val projectRoot: String,
    private val ptyFactory: (command: String) -> PtyProcess,
) : Closeable {

    private var pty: PtyProcess? = null
    private var readJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestId = AtomicInteger(0)
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val writeMutex = Mutex()

    private val _state = MutableStateFlow(LspState.DISCONNECTED)
    val state: StateFlow<LspState> = _state.asStateFlow()

    private val _diagnostics = MutableStateFlow<Map<String, List<Diagnostic>>>(emptyMap())
    val diagnostics: StateFlow<Map<String, List<Diagnostic>>> = _diagnostics.asStateFlow()

    /** Notification handler for server-pushed events. */
    var onNotification: ((String, JSONObject) -> Unit)? = null

    /**
     * Start the language server session.
     */
    suspend fun start(rootUri: String) {
        if (_state.value != LspState.DISCONNECTED) return

        _state.value = LspState.STARTING

        try {
            val command = "exec bash --noprofile --norc -c '${descriptor.runCommand}'"
            pty = ptyFactory(command)
            _state.value = LspState.RUNNING

            // Start reading responses
            readJob = scope.launch { readLoop() }

            // Send initialize request
            val initResult = sendRequest("initialize", JSONObject().apply {
                put("processId", android.os.Process.myPid())
                put("rootUri", rootUri)
                put("capabilities", JSONObject().apply {
                    put("textDocument", JSONObject().apply {
                        put("completion", JSONObject().apply {
                            put("completionItem", JSONObject().apply {
                                put("snippetSupport", true)
                                put("documentationFormat", listOf("markdown", "plaintext"))
                            })
                        })
                        put("hover", JSONObject().apply {
                            put("contentFormat", listOf("markdown", "plaintext"))
                        })
                        put("publishDiagnostics", JSONObject().apply {
                            put("relatedInformation", true)
                        })
                    })
                })
            })

            // Send initialized notification
            sendNotification("initialized", JSONObject())

            _state.value = LspState.READY
        } catch (e: Exception) {
            _state.value = LspState.ERROR
            close()
        }
    }

    /**
     * Send a JSON-RPC request and wait for response.
     */
    suspend fun sendRequest(method: String, params: JSONObject): JSONObject {
        val id = requestId.incrementAndGet()
        val deferred = CompletableDeferred<JSONObject>()
        pendingRequests[id] = deferred

        val message = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }

        writeMessage(message)

        return withTimeout(30_000) { deferred.await() }
    }

    /**
     * Send a JSON-RPC notification (no response expected).
     */
    suspend fun sendNotification(method: String, params: JSONObject) {
        val message = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }
        writeMessage(message)
    }

    /**
     * Notify the server that a text document was opened.
     */
    suspend fun didOpen(uri: String, languageId: String, version: Int, text: String) {
        sendNotification("textDocument/didOpen", JSONObject().apply {
            put("textDocument", JSONObject().apply {
                put("uri", uri)
                put("languageId", languageId)
                put("version", version)
                put("text", text)
            })
        })
    }

    /**
     * Notify the server that a text document was changed.
     */
    suspend fun didChange(uri: String, version: Int, text: String) {
        sendNotification("textDocument/didChange", JSONObject().apply {
            put("textDocument", JSONObject().apply {
                put("uri", uri)
                put("version", version)
            })
            put("contentChanges", listOf(JSONObject().apply {
                put("text", text)
            }))
        })
    }

    /**
     * Request completions at a position.
     */
    suspend fun completion(uri: String, line: Int, character: Int): List<CompletionResult> {
        if (_state.value != LspState.READY) return emptyList()

        val result = sendRequest("textDocument/completion", JSONObject().apply {
            put("textDocument", JSONObject().apply { put("uri", uri) })
            put("position", JSONObject().apply {
                put("line", line)
                put("character", character)
            })
        })

        return parseCompletionResults(result)
    }

    /**
     * Request hover information.
     */
    suspend fun hover(uri: String, line: Int, character: Int): String? {
        if (_state.value != LspState.READY) return null

        val result = sendRequest("textDocument/hover", JSONObject().apply {
            put("textDocument", JSONObject().apply { put("uri", uri) })
            put("position", JSONObject().apply {
                put("line", line)
                put("character", character)
            })
        })

        return result.optJSONObject("contents")?.optString("value")
            ?: result.optString("contents")
    }

    /**
     * Request go-to-definition.
     */
    suspend fun definition(uri: String, line: Int, character: Int): LocationResult? {
        if (_state.value != LspState.READY) return null

        val result = sendRequest("textDocument/definition", JSONObject().apply {
            put("textDocument", JSONObject().apply { put("uri", uri) })
            put("position", JSONObject().apply {
                put("line", line)
                put("character", character)
            })
        })

        return parseLocation(result)
    }

    /**
     * Translate a host path to a distro URI.
     */
    fun hostToDistroUri(hostPath: String): String {
        val distroPath = hostPath
            .replace("/storage/emulated/0/JCode/projects/", "/workspace/")
            .replace("\\", "/")
        return "file://$distroPath"
    }

    /**
     * Translate a distro URI to a host path.
     */
    fun distroToHostPath(distroUri: String): String {
        val path = distroUri.removePrefix("file://")
        return path
            .replace("/workspace/", "/storage/emulated/0/JCode/projects/")
            .replace("/", java.io.File.separator)
    }

    private suspend fun writeMessage(message: JSONObject) {
        val content = message.toString()
        val header = "Content-Length: ${content.toByteArray().size}\r\n\r\n"
        val fullMessage = header + content

        writeMutex.withLock {
            pty?.write(fullMessage.toByteArray())
        }
    }

    private suspend fun readLoop() {
        val buffer = ByteArray(8192)
        var accumulated = ""

        while (scope.isActive && _state.value != LspState.DISCONNECTED) {
            val pty = this.pty ?: break
            val n = pty.read(buffer)
            if (n > 0) {
                accumulated += String(buffer, 0, n)
                accumulated = processAccumulated(accumulated)
            } else if (n < 0) {
                break
            } else {
                delay(10)
            }
        }
    }

    private fun processAccumulated(data: String): String {
        var remaining = data

        while (true) {
            val headerEnd = remaining.indexOf("\r\n\r\n")
            if (headerEnd < 0) break

            val header = remaining.substring(0, headerEnd)
            val contentLengthMatch = Regex("Content-Length: (\\d+)").find(header)
            val contentLength = contentLengthMatch?.groupValues?.get(1)?.toIntOrNull() ?: break

            val contentStart = headerEnd + 4
            if (remaining.length < contentStart + contentLength) break

            val content = remaining.substring(contentStart, contentStart + contentLength)
            remaining = remaining.substring(contentStart + contentLength)

            try {
                val json = JSONObject(content)
                handleMessage(json)
            } catch (e: Exception) {
                // Invalid JSON, skip
            }
        }

        return remaining
    }

    private fun handleMessage(json: JSONObject) {
        val id = json.optInt("id", -1)
        if (id >= 0) {
            // Response to a request
            val deferred = pendingRequests.remove(id)
            if (json.has("result")) {
                deferred?.complete(json.getJSONObject("result"))
            } else if (json.has("error")) {
                deferred?.completeExceptionally(
                    LspException(json.getJSONObject("error").optString("message", "Unknown error"))
                )
            }
        } else {
            // Notification from server
            val method = json.optString("method", "")
            val params = json.optJSONObject("params") ?: JSONObject()

            when (method) {
                "textDocument/publishDiagnostics" -> handleDiagnostics(params)
                else -> onNotification?.invoke(method, params)
            }
        }
    }

    private fun handleDiagnostics(params: JSONObject) {
        val uri = params.optString("uri", "")
        val diags = params.optJSONArray("diagnostics") ?: return

        val hostUri = distroToHostPath(uri)
        val diagnostics = mutableListOf<Diagnostic>()

        for (i in 0 until diags.length()) {
            val diag = diags.getJSONObject(i)
            val range = diag.optJSONObject("range") ?: continue
            val start = range.optJSONObject("start") ?: continue
            val end = range.optJSONObject("end") ?: continue

            diagnostics.add(Diagnostic(
                startLine = start.optInt("line", 0),
                startCol = start.optInt("character", 0),
                endLine = end.optInt("line", 0),
                endCol = end.optInt("character", 0),
                severity = DiagnosticSeverity.fromLsp(diag.optInt("severity", 1)),
                message = diag.optString("message", ""),
                source = diag.optString("source", descriptor.id),
                code = if (diag.has("code")) diag.optString("code") else null,
            ))
        }

        val current = _diagnostics.value.toMutableMap()
        current[hostUri] = diagnostics
        _diagnostics.value = current
    }

    private fun parseCompletionResults(result: JSONObject): List<CompletionResult> {
        val items = result.optJSONArray("items")
            ?: return if (result.has("label")) listOf(parseCompletionItem(result)) else emptyList()

        return (0 until items.length()).map { i ->
            parseCompletionItem(items.getJSONObject(i))
        }
    }

    private fun parseCompletionItem(item: JSONObject): CompletionResult {
        return CompletionResult(
            label = item.optString("label", ""),
            kind = item.optInt("kind", 1),
            detail = if (item.has("detail")) item.optString("detail") else null,
            documentation = item.optJSONObject("documentation")?.optString("value")
                ?: if (item.has("documentation")) item.optString("documentation") else null,
            insertText = if (item.has("insertText")) item.optString("insertText") else item.optString("label"),
            insertTextFormat = item.optInt("insertTextFormat", 1),
        )
    }

    private fun parseLocation(result: JSONObject): LocationResult? {
        // Could be a single location or an array
        if (result.has("uri")) {
            val range = result.optJSONObject("range") ?: return null
            val start = range.optJSONObject("start") ?: return null
            return LocationResult(
                uri = distroToHostPath(result.optString("uri", "")),
                line = start.optInt("line", 0),
                character = start.optInt("character", 0),
            )
        }
        val array = result.optJSONArray("items") ?: return null
        if (array.length() == 0) return null
        val first = array.getJSONObject(0)
        val range = first.optJSONObject("range") ?: return null
        val start = range.optJSONObject("start") ?: return null
        return LocationResult(
            uri = distroToHostPath(first.optString("targetUri", first.optString("uri", ""))),
            line = start.optInt("line", 0),
            character = start.optInt("character", 0),
        )
    }

    override fun close() {
        scope.launch {
            try {
                sendNotification("exit", JSONObject())
            } catch (_: Exception) {}
        }

        readJob?.cancel()
        pty?.close()
        pty = null
        _state.value = LspState.DISCONNECTED
        pendingRequests.values.forEach { it.cancel() }
        pendingRequests.clear()
        scope.cancel()
    }
}

/**
 * LSP session states.
 */
enum class LspState {
    DISCONNECTED,
    STARTING,
    RUNNING,
    READY,
    ERROR,
}

/**
 * A diagnostic from an LSP server.
 */
data class Diagnostic(
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
    val severity: DiagnosticSeverity,
    val message: String,
    val source: String,
    val code: String?,
)

/**
 * Diagnostic severity levels (matching LSP spec).
 */
enum class DiagnosticSeverity(val value: Int) {
    ERROR(1),
    WARNING(2),
    INFORMATION(3),
    HINT(4);

    companion object {
        fun fromLsp(value: Int): DiagnosticSeverity = when (value) {
            1 -> ERROR
            2 -> WARNING
            3 -> INFORMATION
            4 -> HINT
            else -> ERROR
        }
    }
}

/**
 * A completion result from an LSP server.
 */
data class CompletionResult(
    val label: String,
    val kind: Int,
    val detail: String?,
    val documentation: String?,
    val insertText: String,
    val insertTextFormat: Int,  // 1 = plain text, 2 = snippet
)

/**
 * A location result (go-to-definition, etc.).
 */
data class LocationResult(
    val uri: String,
    val line: Int,
    val character: Int,
)

class LspException(message: String) : Exception(message)
