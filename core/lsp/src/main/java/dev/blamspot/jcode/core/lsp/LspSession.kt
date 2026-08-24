package dev.blamspot.jcode.core.lsp

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
 * LSP client session. The language server runs inside the distro and speaks JSON-RPC over its stdio
 * pipes ([LspTransport]).
 *
 * The [transportFactory] indirection keeps this module free of any knowledge of proot: the caller
 * turns a command string into a running guest process.
 */
class LspSession(
    val descriptor: LspServerDescriptor,
    val projectRoot: String,
    /** Spawns the server (given a shell command) and returns its stdio as an [LspTransport], or null. */
    private val transportFactory: (command: String) -> LspTransport?,
) : Closeable {

    private var transport: LspTransport? = null
    private var readJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestId = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Any?>>()
    private val writeMutex = Mutex()
    @Volatile private var closed = false

    private val _state = MutableStateFlow(LspState.DISCONNECTED)
    val state: StateFlow<LspState> = _state.asStateFlow()

    private val _diagnostics = MutableStateFlow<Map<String, List<Diagnostic>>>(emptyMap())
    val diagnostics: StateFlow<Map<String, List<Diagnostic>>> = _diagnostics.asStateFlow()

    /** The server's advertised capabilities, available once the handshake completes. */
    @Volatile var serverCapabilities: JSONObject? = null
        private set

    /** Notification handler for server-pushed events other than diagnostics. */
    var onNotification: ((String, JSONObject) -> Unit)? = null

    /** Handles a server-initiated `workspace/applyEdit`; returns whether the edit was applied. */
    var onApplyEdit: ((JSONObject) -> Boolean)? = null

    /** Human-readable reason the session failed, when [state] is [LspState.ERROR]. */
    @Volatile var errorMessage: String? = null
        private set

    // ---- lifecycle ------------------------------------------------------------------------------

    /**
     * Spawn the server and run the LSP handshake: `initialize` -> `initialized`.
     *
     * [rootUri] is a `file://` URI in the GUEST path space, since the server sees the project through
     * proot's binds.
     */
    suspend fun start(rootUri: String) {
        if (_state.value != LspState.DISCONNECTED || closed) return
        _state.value = LspState.STARTING
        try {
            // --noprofile --norc so a user's shell configuration cannot inject output into the stream.
            val command = "exec bash --noprofile --norc -c '${descriptor.runCommand}'"
            transport = transportFactory(command) ?: throw LspException("runtime is not ready")
            _state.value = LspState.RUNNING
            readJob = scope.launch { readLoop() }

            val result = sendRequest("initialize", initializeParams(rootUri), INITIALIZE_TIMEOUT_MS)
            serverCapabilities = result.asObject()?.optJSONObject("capabilities")
            sendNotification("initialized", JSONObject())
            _state.value = LspState.READY
        } catch (e: Exception) {
            errorMessage = e.message ?: e::class.java.simpleName
            _state.value = LspState.ERROR
            close()
        }
    }

    private fun initializeParams(rootUri: String): JSONObject = JSONObject().apply {
        put("processId", android.os.Process.myPid())
        put("rootUri", rootUri)
        put("workspaceFolders", JSONArray().put(workspaceFolder(rootUri)))
        put("capabilities", JSONObject().apply {
            put("textDocument", JSONObject().apply {
                put("synchronization", JSONObject().apply {
                    put("didSave", true)
                    put("willSave", false)
                    put("dynamicRegistration", false)
                })
                put("completion", JSONObject().apply {
                    put("contextSupport", true)
                    put("completionItem", JSONObject().apply {
                        put("snippetSupport", true)
                        put("documentationFormat", JSONArray().put("markdown").put("plaintext"))
                    })
                })
                put("hover", JSONObject().apply {
                    put("contentFormat", JSONArray().put("markdown").put("plaintext"))
                })
                // linkSupport off: servers then answer with plain Location[] rather than LocationLink[],
                // which keeps one parse path for definition results.
                put("definition", JSONObject().apply { put("linkSupport", false) })
                put("references", JSONObject())
                put("rename", JSONObject().apply { put("prepareSupport", false) })
                put("formatting", JSONObject())
                put("publishDiagnostics", JSONObject().apply { put("relatedInformation", true) })
            })
            put("workspace", JSONObject().apply {
                put("applyEdit", true)
                put("configuration", true)
                put("workspaceFolders", true)
            })
            put("window", JSONObject().apply { put("workDoneProgress", true) })
            // Positions are exchanged in UTF-16 code units; the caller converts at the buffer boundary.
            put("general", JSONObject().apply {
                put("positionEncodings", JSONArray().put("utf-16"))
            })
        })
    }

    private fun workspaceFolder(rootUri: String): JSONObject = JSONObject().apply {
        put("uri", rootUri)
        put("name", rootUri.substringAfterLast('/').ifBlank { "workspace" })
    }

    override fun close() {
        if (closed) return
        closed = true
        val t = transport
        readJob?.cancel()
        // Graceful stop written straight to the transport: the read loop is already cancelled, and
        // routing through the suspend writer would need a scope this method is about to cancel.
        runCatching {
            t?.write(frame(JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", requestId.incrementAndGet())
                put("method", "shutdown")
                put("params", JSONObject())
            }))
            t?.write(frame(JSONObject().apply {
                put("jsonrpc", "2.0")
                put("method", "exit")
                put("params", JSONObject())
            }))
        }
        runCatching { t?.close() }
        transport = null
        if (_state.value != LspState.ERROR) _state.value = LspState.DISCONNECTED
        pending.values.forEach { it.cancel() }
        pending.clear()
        scope.cancel()
    }

    // ---- capability gating ----------------------------------------------------------------------

    val supportsCompletion: Boolean get() = providerEnabled("completionProvider")
    val supportsHover: Boolean get() = providerEnabled("hoverProvider")
    val supportsDefinition: Boolean get() = providerEnabled("definitionProvider")
    val supportsReferences: Boolean get() = providerEnabled("referencesProvider")
    val supportsRename: Boolean get() = providerEnabled("renameProvider")
    val supportsFormatting: Boolean get() = providerEnabled("documentFormattingProvider")

    /** A provider field is either `true` or an options object; absent or `false` means unsupported. */
    private fun providerEnabled(key: String): Boolean {
        val caps = serverCapabilities ?: return false
        val value = caps.opt(key) ?: return false
        return value != false && value != JSONObject.NULL
    }

    // ---- requests -------------------------------------------------------------------------------

    /** Sends a JSON-RPC request. The result is a [JSONObject], a [JSONArray], or null. */
    suspend fun sendRequest(
        method: String,
        params: JSONObject,
        timeoutMs: Long = REQUEST_TIMEOUT_MS,
    ): Any? {
        val id = requestId.incrementAndGet()
        val deferred = CompletableDeferred<Any?>()
        pending[id] = deferred
        return try {
            writeMessage(JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            })
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    suspend fun sendNotification(method: String, params: JSONObject) {
        writeMessage(JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        })
    }

    // ---- document synchronisation ---------------------------------------------------------------

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
     * Full-document sync. Servers that advertise incremental sync still accept a change object with
     * no `range` as a whole-document replacement, so one path covers the whole catalog without
     * plumbing edit deltas out of the editor.
     */
    suspend fun didChange(uri: String, version: Int, text: String) {
        sendNotification("textDocument/didChange", JSONObject().apply {
            put("textDocument", JSONObject().apply {
                put("uri", uri)
                put("version", version)
            })
            put("contentChanges", JSONArray().put(JSONObject().apply { put("text", text) }))
        })
    }

    suspend fun didSave(uri: String, text: String) {
        sendNotification("textDocument/didSave", JSONObject().apply {
            put("textDocument", JSONObject().apply { put("uri", uri) })
            put("text", text)
        })
    }

    suspend fun didClose(uri: String) {
        sendNotification("textDocument/didClose", JSONObject().apply {
            put("textDocument", JSONObject().apply { put("uri", uri) })
        })
        // The server stops publishing for a closed document but never retracts what it already sent.
        _diagnostics.value = _diagnostics.value - distroToHostPath(uri)
    }

    // ---- language features ----------------------------------------------------------------------

    suspend fun completion(uri: String, line: Int, character: Int): List<CompletionResult> {
        if (!ready() || !supportsCompletion) return emptyList()
        val result = sendRequest("textDocument/completion", positionParams(uri, line, character))
        return parseCompletions(result)
    }

    suspend fun hover(uri: String, line: Int, character: Int): String? {
        if (!ready() || !supportsHover) return null
        val result = sendRequest("textDocument/hover", positionParams(uri, line, character))
        return parseHover(result.asObject()?.opt("contents"))
    }

    suspend fun definition(uri: String, line: Int, character: Int): List<LocationResult> {
        if (!ready() || !supportsDefinition) return emptyList()
        val result = sendRequest("textDocument/definition", positionParams(uri, line, character))
        return parseLocations(result)
    }

    suspend fun references(
        uri: String,
        line: Int,
        character: Int,
        includeDeclaration: Boolean = true,
    ): List<LocationResult> {
        if (!ready() || !supportsReferences) return emptyList()
        val params = positionParams(uri, line, character).apply {
            put("context", JSONObject().apply { put("includeDeclaration", includeDeclaration) })
        }
        return parseLocations(sendRequest("textDocument/references", params))
    }

    /** Returns the edits a rename implies, keyed by host path, or null when the server declines. */
    suspend fun rename(uri: String, line: Int, character: Int, newName: String): WorkspaceEditResult? {
        if (!ready() || !supportsRename) return null
        val params = positionParams(uri, line, character).apply { put("newName", newName) }
        val edit = sendRequest("textDocument/rename", params).asObject() ?: return null
        return parseWorkspaceEdit(edit)
    }

    suspend fun formatting(uri: String, tabSize: Int, insertSpaces: Boolean): List<TextEditResult> {
        if (!ready() || !supportsFormatting) return emptyList()
        val params = JSONObject().apply {
            put("textDocument", JSONObject().apply { put("uri", uri) })
            put("options", JSONObject().apply {
                put("tabSize", tabSize)
                put("insertSpaces", insertSpaces)
                put("trimTrailingWhitespace", true)
                put("insertFinalNewline", true)
            })
        }
        return parseTextEdits(sendRequest("textDocument/formatting", params).asArray())
    }

    private fun ready(): Boolean = _state.value == LspState.READY && !closed

    private fun positionParams(uri: String, line: Int, character: Int): JSONObject = JSONObject().apply {
        put("textDocument", JSONObject().apply { put("uri", uri) })
        put("position", JSONObject().apply {
            put("line", line)
            put("character", character)
        })
    }

    // ---- path translation -----------------------------------------------------------------------

    /** Host path -> the `file://` URI the server sees through proot's binds. */
    fun hostToDistroUri(hostPath: String): String {
        val distroPath = dev.blamspot.jcode.core.distro.WorkspaceHostPaths.hostToGuest(hostPath).replace("\\", "/")
        return "file://$distroPath"
    }

    fun distroToHostPath(distroUri: String): String {
        val path = java.net.URLDecoder.decode(distroUri.removePrefix("file://"), "UTF-8")
        return dev.blamspot.jcode.core.distro.WorkspaceHostPaths.guestToHost(path)
            .replace("/", java.io.File.separator)
    }

    // ---- transport ------------------------------------------------------------------------------

    private fun frame(message: JSONObject): ByteArray {
        val content = message.toString().toByteArray(Charsets.UTF_8)
        val header = "Content-Length: ${content.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
        return header + content
    }

    private suspend fun writeMessage(message: JSONObject) {
        val bytes = frame(message)
        writeMutex.withLock { transport?.write(bytes) }
    }

    private suspend fun readLoop() {
        val buffer = ByteArray(READ_CHUNK)
        while (scope.isActive && !closed) {
            val t = transport ?: break
            val n = try {
                t.read(buffer)
            } catch (e: Exception) {
                -1
            }
            when {
                n > 0 -> {
                    append(buffer, n)
                    drainMessages()
                }
                n < 0 -> break
                else -> delay(10)
            }
        }
    }

    // Accumulator for partial messages. Grown by doubling and compacted in place rather than
    // reallocated per chunk: a large completion response arrives as hundreds of chunks, and
    // `acc += chunk` would copy the whole buffer for each one.
    private var acc = ByteArray(READ_CHUNK * 2)
    private var accLen = 0

    private fun append(data: ByteArray, n: Int) {
        if (accLen + n > acc.size) {
            var capacity = acc.size
            while (capacity < accLen + n) capacity *= 2
            acc = acc.copyOf(capacity)
        }
        System.arraycopy(data, 0, acc, accLen, n)
        accLen += n
    }

    /**
     * Frame LSP messages out of the raw byte stream. `Content-Length` is a BYTE count, so the header
     * scan and the body slice must both work on bytes: decoding to a String first mis-slices any
     * message containing multi-byte UTF-8 (a hover body with typographic quotes is enough) and every
     * later message on the stream is then misframed.
     */
    private fun drainMessages() {
        var consumed = 0
        while (true) {
            val headerEnd = indexOfHeaderEnd(consumed)
            if (headerEnd < 0) break
            val header = String(acc, consumed, headerEnd - consumed, Charsets.US_ASCII)
            val length = CONTENT_LENGTH.find(header)?.groupValues?.get(1)?.toIntOrNull() ?: break
            val bodyStart = headerEnd + 4
            if (accLen - bodyStart < length) break
            val content = String(acc, bodyStart, length, Charsets.UTF_8)
            consumed = bodyStart + length
            runCatching { handleMessage(JSONObject(content)) }
        }
        if (consumed > 0) {
            System.arraycopy(acc, consumed, acc, 0, accLen - consumed)
            accLen -= consumed
        }
    }

    /** Index of the first `\r\n\r\n` at or after [from], or -1. */
    private fun indexOfHeaderEnd(from: Int): Int {
        for (i in from..accLen - 4) {
            if (acc[i] == 0x0D.toByte() && acc[i + 1] == 0x0A.toByte() &&
                acc[i + 2] == 0x0D.toByte() && acc[i + 3] == 0x0A.toByte()
            ) return i
        }
        return -1
    }

    /**
     * A message carrying `method` is server-initiated (a request when it also carries `id`, else a
     * notification); anything else is a response to one of ours. Checking `id` first would mistake
     * `workspace/configuration` for a response and leave the server waiting forever, which is exactly
     * how jdtls and typescript-language-server stall.
     */
    private fun handleMessage(json: JSONObject) {
        val method = if (json.has("method")) json.optString("method") else null
        if (method != null) {
            val params = json.optJSONObject("params") ?: JSONObject()
            val id = if (json.has("id")) json.opt("id") else null
            if (id != null) handleServerRequest(id, method, params) else handleServerNotification(method, params)
            return
        }
        val deferred = pending.remove(json.optInt("id", -1)) ?: return
        val error = json.optJSONObject("error")
        if (error != null) {
            deferred.completeExceptionally(LspException(error.optString("message", "unknown error")))
        } else {
            deferred.complete(json.opt("result").takeIf { it != JSONObject.NULL })
        }
    }

    private fun handleServerNotification(method: String, params: JSONObject) {
        when (method) {
            "textDocument/publishDiagnostics" -> handleDiagnostics(params)
            else -> onNotification?.invoke(method, params)
        }
    }

    private fun handleServerRequest(id: Any, method: String, params: JSONObject) {
        when (method) {
            // No per-server settings are stored, so every requested section resolves to defaults.
            // An empty object rather than null: serde-based servers fail to deserialise null.
            "workspace/configuration" -> {
                val count = params.optJSONArray("items")?.length() ?: 0
                respond(id, JSONArray().apply { repeat(count) { put(JSONObject()) } })
            }
            "workspace/workspaceFolders" ->
                respond(id, JSONArray().put(workspaceFolder(hostToDistroUri(projectRoot))))
            "workspace/applyEdit" -> {
                val applied = onApplyEdit?.invoke(params.optJSONObject("edit") ?: JSONObject()) ?: false
                respond(id, JSONObject().apply { put("applied", applied) })
            }
            "client/registerCapability", "client/unregisterCapability",
            "window/workDoneProgress/create", "window/showMessageRequest",
            -> respond(id, JSONObject.NULL)
            else -> respondError(id, METHOD_NOT_FOUND, "Method not found: $method")
        }
    }

    private fun respond(id: Any, result: Any) {
        scope.launch {
            writeMessage(JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            })
        }
    }

    private fun respondError(id: Any, code: Int, message: String) {
        scope.launch {
            writeMessage(JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", id)
                put("error", JSONObject().apply {
                    put("code", code)
                    put("message", message)
                })
            })
        }
    }

    private fun handleDiagnostics(params: JSONObject) {
        val uri = params.optString("uri", "")
        val array = params.optJSONArray("diagnostics") ?: return
        val hostPath = distroToHostPath(uri)
        val parsed = (0 until array.length()).mapNotNull { i ->
            val diagnostic = array.optJSONObject(i) ?: return@mapNotNull null
            val range = diagnostic.optJSONObject("range") ?: return@mapNotNull null
            val start = range.optJSONObject("start") ?: return@mapNotNull null
            val end = range.optJSONObject("end") ?: start
            Diagnostic(
                startLine = start.optInt("line", 0),
                startCol = start.optInt("character", 0),
                endLine = end.optInt("line", 0),
                endCol = end.optInt("character", 0),
                severity = DiagnosticSeverity.fromLsp(diagnostic.optInt("severity", 1)),
                message = diagnostic.optString("message", ""),
                source = diagnostic.optString("source", descriptor.id),
                code = if (diagnostic.has("code")) diagnostic.optString("code") else null,
            )
        }
        _diagnostics.value = _diagnostics.value + (hostPath to parsed)
    }

    // ---- result parsing -------------------------------------------------------------------------

    private fun Any?.asObject(): JSONObject? = this as? JSONObject

    private fun Any?.asArray(): JSONArray? = this as? JSONArray

    /** `CompletionItem[]` or `CompletionList { items }`. */
    private fun parseCompletions(result: Any?): List<CompletionResult> {
        val items = result.asArray() ?: result.asObject()?.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            items.optJSONObject(i)?.let { item ->
                CompletionResult(
                    label = item.optString("label", ""),
                    kind = item.optInt("kind", 1),
                    detail = item.optStringOrNull("detail"),
                    documentation = item.optJSONObject("documentation")?.optStringOrNull("value")
                        ?: item.optStringOrNull("documentation"),
                    insertText = item.optJSONObject("textEdit")?.optStringOrNull("newText")
                        ?: item.optStringOrNull("insertText")
                        ?: item.optString("label", ""),
                    insertTextFormat = item.optInt("insertTextFormat", 1),
                    sortText = item.optStringOrNull("sortText"),
                )
            }
        }
    }

    /** `MarkupContent`, `MarkedString`, or an array of either. */
    private fun parseHover(contents: Any?): String? = when (contents) {
        null, JSONObject.NULL -> null
        is String -> contents.ifBlank { null }
        is JSONObject -> contents.optStringOrNull("value")
        is JSONArray -> (0 until contents.length())
            .mapNotNull { parseHover(contents.opt(it)) }
            .joinToString("\n\n")
            .ifBlank { null }
        else -> null
    }

    /** `Location`, `Location[]`, or `LocationLink[]`. */
    private fun parseLocations(result: Any?): List<LocationResult> {
        result.asObject()?.let { return listOfNotNull(parseLocation(it)) }
        val array = result.asArray() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::parseLocation) }
    }

    private fun parseLocation(json: JSONObject): LocationResult? {
        val uri = json.optStringOrNull("uri") ?: json.optStringOrNull("targetUri") ?: return null
        val range = json.optJSONObject("range")
            ?: json.optJSONObject("targetSelectionRange")
            ?: json.optJSONObject("targetRange")
            ?: return null
        val start = range.optJSONObject("start") ?: return null
        return LocationResult(
            path = distroToHostPath(uri),
            line = start.optInt("line", 0),
            character = start.optInt("character", 0),
        )
    }

    private fun parseTextEdits(array: JSONArray?): List<TextEditResult> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val edit = array.optJSONObject(i) ?: return@mapNotNull null
            val range = edit.optJSONObject("range") ?: return@mapNotNull null
            val start = range.optJSONObject("start") ?: return@mapNotNull null
            val end = range.optJSONObject("end") ?: return@mapNotNull null
            TextEditResult(
                startLine = start.optInt("line", 0),
                startChar = start.optInt("character", 0),
                endLine = end.optInt("line", 0),
                endChar = end.optInt("character", 0),
                newText = edit.optString("newText", ""),
            )
        }
    }

    /**
     * A `WorkspaceEdit` carries edits either in `changes` (uri -> edits) or in `documentChanges`
     * (which may also hold create/rename/delete file operations, ignored here).
     */
    fun parseWorkspaceEdit(edit: JSONObject): WorkspaceEditResult {
        val byPath = LinkedHashMap<String, List<TextEditResult>>()
        edit.optJSONObject("changes")?.let { changes ->
            changes.keys().forEach { uri ->
                val edits = parseTextEdits(changes.optJSONArray(uri))
                if (edits.isNotEmpty()) byPath[distroToHostPath(uri)] = edits
            }
        }
        edit.optJSONArray("documentChanges")?.let { changes ->
            for (i in 0 until changes.length()) {
                val change = changes.optJSONObject(i) ?: continue
                val uri = change.optJSONObject("textDocument")?.optStringOrNull("uri") ?: continue
                val edits = parseTextEdits(change.optJSONArray("edits"))
                if (edits.isEmpty()) continue
                val path = distroToHostPath(uri)
                byPath[path] = byPath[path].orEmpty() + edits
            }
        }
        return WorkspaceEditResult(byPath)
    }

    /** `optString` returns "" for both a missing key and an explicit null; this distinguishes them. */
    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

    private companion object {
        const val READ_CHUNK = 8192
        const val REQUEST_TIMEOUT_MS = 15_000L

        // jdtls builds a workspace index before answering `initialize`, which on a cold project on a
        // phone is comfortably past any ordinary request timeout.
        const val INITIALIZE_TIMEOUT_MS = 120_000L
        const val METHOD_NOT_FOUND = -32601
        val CONTENT_LENGTH = Regex("Content-Length: (\\d+)")
    }
}

/** LSP session states. */
enum class LspState {
    DISCONNECTED,
    STARTING,
    RUNNING,
    READY,
    ERROR,
}

/** A diagnostic from an LSP server. */
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

/** Diagnostic severity levels (matching LSP spec). */
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

/** A completion result from an LSP server. */
data class CompletionResult(
    val label: String,
    val kind: Int,
    val detail: String?,
    val documentation: String?,
    val insertText: String,
    /** 1 = plain text, 2 = snippet. */
    val insertTextFormat: Int,
    val sortText: String?,
)

/** A resolved location (go-to-definition, references), in HOST path space. */
data class LocationResult(
    val path: String,
    val line: Int,
    val character: Int,
)

/** A single text edit, in LSP coordinates (0-based line, UTF-16 character). */
data class TextEditResult(
    val startLine: Int,
    val startChar: Int,
    val endLine: Int,
    val endChar: Int,
    val newText: String,
)

/** The edits a workspace-wide operation implies, keyed by HOST path. */
data class WorkspaceEditResult(val editsByPath: Map<String, List<TextEditResult>>) {
    val isEmpty: Boolean get() = editsByPath.values.all { it.isEmpty() }
    val fileCount: Int get() = editsByPath.count { it.value.isNotEmpty() }
    val editCount: Int get() = editsByPath.values.sumOf { it.size }
}

class LspException(message: String) : Exception(message)
