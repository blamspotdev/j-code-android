package dev.blamspot.jcode.ext

import dev.blamspot.jcode.ext.api.NativeContextAction
import dev.blamspot.jcode.ext.api.NativeDecoration
import dev.blamspot.jcode.ext.api.NativeExecResult
import dev.blamspot.jcode.ext.api.NativeHost
import dev.blamspot.jcode.ext.api.NativeProjectInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where a fire-and-forget host call runs.
 *
 * The process's lifetime rather than the calling page's, because several of these calls *end* that
 * page: opening a project clears the editor, closing your own view closes your tab. Launched on the
 * page's scope, such a call cancels itself halfway and leaves the workbench holding a job it began
 * and will never finish -- which is how a scaffolded project came to be created and never opened.
 * One job is one envelope and its reply, so nothing accumulates here.
 */
private val hostCalls = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/**
 * [NativeHost] over the same request dispatcher the WebView bridge uses.
 *
 * The methods are typed because a plugin author should get a signature and a return type rather than
 * a JSON envelope and a guess. What is behind them is deliberately *not* a second implementation:
 * every call becomes the same `{type, payload}` envelope the web extensions send, so the workbench
 * has one place where an API is implemented, versioned and permission-checked. A typed method that
 * reimplemented its own half of `exec.run` would be the copy that drifts.
 */
internal class NativeHostBridge(
    /** The page's own. Event collectors belong to it: a closed page stops listening. */
    private val pageScope: CoroutineScope,
    /** Sends one envelope and returns the reply, already scoped to the calling extension. */
    private val request: suspend (String) -> String,
    private val events: Flow<Pair<String, String>>,
    private val readFileText: (String) -> String?,
    private val writeFileText: (String, String) -> Unit,
    private val projectDirPath: () -> String?,
    private val onSnackbar: (String, String?, (() -> Unit)?) -> Unit,
    private val onIssues: (List<String>) -> Unit,
    private val onShowSource: () -> Unit,
) : NativeHost {

    // --- the original six ------------------------------------------------------------------------

    override fun readFile(path: String): String? = readFileText(path)
    override fun writeFile(path: String, text: String) = writeFileText(path, text)
    override fun projectDir(): String? = projectDirPath()
    override fun snackbar(message: String) = onSnackbar(message, null, null)

    override fun snackbar(message: String, actionLabel: String, action: () -> Unit) =
        onSnackbar(message, actionLabel, action)
    override fun reportIssues(messages: List<String>) = onIssues(messages)
    override fun showSource() = onShowSource()

    // --- envelope plumbing -----------------------------------------------------------------------

    private suspend fun call(type: String, payload: JSONObject = JSONObject()): JSONObject? {
        val envelope = JSONObject().put("type", type).put("payload", payload).toString()
        val reply = runCatching { request(envelope) }.getOrNull() ?: return null
        val parsed = runCatching { JSONObject(reply) }.getOrNull() ?: return null
        if (!parsed.optBoolean("ok")) return null
        return parsed.optJSONObject("data") ?: JSONObject()
    }

    /** For a call whose answer nobody waits on — the plugin said something, the workbench does it. */
    private fun send(type: String, payload: JSONObject = JSONObject()) {
        hostCalls.launch { call(type, payload) }
    }

    // --- the runtime -------------------------------------------------------------------------------

    override suspend fun serviceStart(id: String, command: String): Boolean =
        call("service.start", JSONObject().put("id", id).put("command", command)) != null

    override suspend fun serviceRunning(id: String): Boolean =
        call("service.status", JSONObject().put("id", id))?.optBoolean("running") == true

    override suspend fun serviceStop(id: String) {
        call("service.stop", JSONObject().put("id", id))
    }

    override suspend fun exec(
        command: String,
        workdir: String?,
        timeoutMs: Long,
        env: Map<String, String>,
    ): NativeExecResult {
        val payload = JSONObject()
            .put("command", command)
            .put("timeoutMs", timeoutMs)
        if (workdir != null) payload.put("workdir", workdir)
        if (env.isNotEmpty()) payload.put("env", JSONObject(env.toMap<String, Any>()))
        val data = call("exec.run", payload)
            ?: return NativeExecResult(error = "the workbench could not run the command")
        return NativeExecResult(
            stdout = data.optString("stdout"),
            stderr = data.optString("stderr"),
            exitCode = data.optInt("exitCode", -1),
            error = data.optString("error").ifBlank { null },
        )
    }

    // --- the workbench -----------------------------------------------------------------------------

    override suspend fun projectInfo(): NativeProjectInfo? {
        val data = call("workbench.projectInfo") ?: return null
        val name = data.optString("name").ifBlank { return null }
        return NativeProjectInfo(
            name = name,
            path = data.optString("path").ifBlank { null },
            workspace = data.optString("workspace").ifBlank { null },
        )
    }

    override suspend fun workspaceFolders(): List<String> {
        val data = call("workbench.workspaceFolders") ?: return emptyList()
        val arr = data.optJSONArray("folders") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            when (val item = arr.opt(i)) {
                is String -> item.takeIf { it.isNotBlank() }
                is JSONObject -> item.optString("path").ifBlank { null }
                else -> null
            }
        }
    }

    override fun openFile(path: String, line: Int?) =
        send("workbench.openFile", JSONObject().put("path", path).apply { line?.let { put("line", it) } })

    // The route registers a folder the extension has already created under the guest workspace mount,
    // and it names that folder rather than pathing to it -- a top-level name is the only thing it can
    // register. Native plugins hold a path, which is what they have just made, so the name is taken
    // from it here instead of in every plugin.
    override fun openFolder(path: String) =
        send("workbench.openFolder", JSONObject().put("name", path.trimEnd('/').substringAfterLast('/')))
    override fun addFolder(path: String) = send("workbench.addFolder", JSONObject().put("path", path))
    override fun openUrl(url: String) = send("workbench.openUrl", JSONObject().put("url", url))
    override fun openView(id: String, title: String?) = send(
        "workbench.openView",
        JSONObject().put("view", id).apply { title?.let { put("title", it) } },
    )
    override fun closeView(id: String) = send("workbench.closeView", JSONObject().put("view", id))

    override fun setExplorerDecorations(root: String, decorations: List<NativeDecoration>) {
        val entries = JSONArray()
        decorations.forEach { d ->
            entries.put(JSONObject().put("path", d.path).put("status", d.status))
        }
        send("workbench.setExplorerDecorations", JSONObject().put("path", root).put("decorations", entries))
    }

    override fun setHiddenInjected(paths: List<String>) =
        send("workbench.setHiddenInjected", JSONObject().put("paths", JSONArray(paths)))

    override suspend fun trash(paths: List<String>): Int =
        call("workbench.trash", JSONObject().put("paths", JSONArray(paths)))?.optInt("moved") ?: 0

    override suspend fun pendingContextAction(): NativeContextAction? {
        val action = call("workbench.pendingContextAction")?.optJSONObject("action") ?: return null
        val id = action.optString("actionId").ifBlank { return null }
        return NativeContextAction(
            actionId = id,
            path = action.optString("path"),
            isDirectory = action.optBoolean("isDirectory"),
        )
    }

    // --- this extension's settings ------------------------------------------------------------------

    override suspend fun config(): Map<String, String> {
        val data = call("config.all") ?: return emptyMap()
        return buildMap {
            data.keys().forEach { key -> put(key, data.optString(key)) }
        }
    }

    override suspend fun setConfig(key: String, value: String) {
        call("config.set", JSONObject().put("key", key).put("value", value))
    }

    override fun onEvent(listener: (name: String, json: String) -> Unit): AutoCloseable {
        val job = pageScope.launch {
            events.collect { (name, json) -> listener(name, json) }
        }
        return AutoCloseable { job.cancel() }
    }
}
