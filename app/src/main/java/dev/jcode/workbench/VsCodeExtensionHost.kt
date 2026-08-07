package dev.jcode.workbench

import android.content.Context
import dev.jcode.core.distro.WorkspaceHostPaths
import dev.jcode.feature.marketplace.InstalledExtension
import dev.jcode.feature.marketplace.VsixPackage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs an imported `.vsix` extension's code.
 *
 * A VS Code extension is Node code that builds its UI at runtime, so there is nothing to show until
 * it has been loaded and asked for it. This starts `host.js` under the runtime's Node, loads the
 * extension's `main` inside it, and speaks newline-delimited JSON over the process's stdin/stdout —
 * the same shape a debug adapter uses, which is why it reuses that spawn path.
 *
 * The awkward part is reach: an extension installs into app-private storage, which the Linux
 * runtime cannot see. Only [WorkspaceHostPaths.transferRoot] is bind-mounted into it, so the
 * extension's files are staged there and Node loads them from the guest side. Staging is skipped
 * when the staged copy already matches the installed version, and uses hard links where the
 * filesystem allows it, so re-activating an extension costs nothing and stores nothing twice.
 */
class VsCodeExtensionHost(
    private val context: Context,
    private val extension: InstalledExtension,
    private val spawn: (command: String) -> Process?,
    private val onEvent: (method: String, params: JSONObject) -> Unit,
) {
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()

    val id: String get() = extension.id

    /** Stage, spawn, and wait for the host to announce itself. Null on success, else why not. */
    suspend fun start(): String? {
        val main = File(extension.dir, VsixPackage.VSIX_MARKER).takeIf { it.isFile }?.readText()?.trim()
        if (main.isNullOrBlank()) return "${extension.name} has no extension entry point to run."

        val guestDir = stageForRuntime() ?: return "could not stage ${extension.name} into the Linux runtime"
        val hostScript = stageHostScript() ?: return "could not stage the extension host into the Linux runtime"

        val command = buildString {
            append("node ").append(shellQuote(hostScript))
            append(" --ext-dir ").append(shellQuote(guestDir))
            append(" --main ").append(shellQuote(main))
            append(" --id ").append(shellQuote(extension.id))
        }
        val started = spawn(command) ?: return "the Linux runtime is not ready"
        process = started
        writer = started.outputStream.bufferedWriter()

        Thread { readLoop(started) }.apply { isDaemon = true; name = "vsix-host-${extension.id}" }.start()
        Thread {
            // Node's own diagnostics (a failed require, a syntax error) only appear here, and they are
            // the first thing you need when an extension will not start.
            runCatching {
                started.errorStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) onEvent("host/log", JSONObject().put("level", "error").put("text", line))
                }
            }
        }.apply { isDaemon = true }.start()

        val ready = withTimeoutOrNull(START_TIMEOUT_MS) { readyGate.await() }
        return if (ready == null) "the extension host did not start (is Node installed?)" else null
    }

    private val readyGate = CompletableDeferred<Unit>()

    /** Load the extension and run its `activate()`. Returns what it registered. */
    suspend fun activate(folders: List<Pair<String, String>>, configuration: JSONObject): JSONObject =
        request(
            "activate",
            JSONObject()
                .put("folders", JSONArray().apply {
                    folders.forEach { (name, path) -> put(JSONObject().put("name", name).put("path", path)) }
                })
                .put("configuration", configuration),
        )

    /** Ask the extension to fill in one of its webview views; returns its handle and initial HTML. */
    suspend fun resolveWebviewView(viewId: String): JSONObject =
        request("resolveWebviewView", JSONObject().put("viewId", viewId))

    /** Deliver a message the page sent through `acquireVsCodeApi().postMessage`. */
    suspend fun postToWebview(handle: String, message: String) {
        request("webview/message", JSONObject().put("handle", handle).put("message", parseLoose(message)))
    }

    suspend fun executeCommand(id: String, args: JSONArray = JSONArray()): JSONObject =
        request("command/execute", JSONObject().put("id", id).put("args", args))

    /** Push the file the user is looking at, so the extension's own view can follow along. */
    suspend fun setActiveFile(file: JSONObject?) {
        request("state/activeFile", file ?: JSONObject())
    }

    suspend fun setTheme(dark: Boolean) {
        request("state/theme", JSONObject().put("kind", if (dark) "dark" else "light"))
    }

    fun dispose() {
        runCatching { writer?.close() }
        runCatching { process?.destroy() }
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    /** Answer something the extension asked JCode for. */
    fun reply(id: Int, result: Any?, error: String? = null) {
        val frame = JSONObject().put("id", id)
        if (error != null) frame.put("error", error) else frame.put("result", result ?: JSONObject.NULL)
        writeFrame(frame)
    }

    // ---- transport ---------------------------------------------------------------------------

    private suspend fun request(method: String, params: JSONObject): JSONObject {
        val id = nextId.getAndIncrement()
        val slot = CompletableDeferred<JSONObject>()
        pending[id] = slot
        writeFrame(JSONObject().put("id", id).put("method", method).put("params", params))
        return withTimeoutOrNull(REQUEST_TIMEOUT_MS) { slot.await() }
            ?: JSONObject().put("error", "$method timed out after ${REQUEST_TIMEOUT_MS}ms")
    }

    @Synchronized
    private fun writeFrame(frame: JSONObject) {
        val out = writer ?: return
        runCatching {
            out.write(frame.toString())
            out.write("\n")
            out.flush()
        }
    }

    private fun readLoop(process: Process) {
        runCatching {
            process.inputStream.bufferedReader().forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val frame = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                val id = if (frame.has("id")) frame.optInt("id") else null
                val method = frame.optString("method").takeIf { it.isNotBlank() }

                if (method == null && id != null) {
                    // A reply to something we asked the extension.
                    pending.remove(id)?.complete(
                        if (frame.has("error")) JSONObject().put("error", frame.optString("error"))
                        else frame.optJSONObject("result") ?: JSONObject().put("value", frame.opt("result")),
                    )
                    return@forEachLine
                }
                if (method == "host/ready") {
                    readyGate.complete(Unit)
                    return@forEachLine
                }
                val params = frame.optJSONObject("params") ?: JSONObject()
                if (id != null) params.put("__requestId", id)
                onEvent(method ?: return@forEachLine, params)
            }
        }
    }

    // ---- staging -----------------------------------------------------------------------------

    /** Mirror the extension into the bind-mounted transfer dir; returns its guest path. */
    private fun stageForRuntime(): String? {
        val stageRoot = File(WorkspaceHostPaths.transferRoot(context.filesDir), "$STAGE_DIR/${safeName(extension.id)}")
        val stamp = File(stageRoot, ".staged")
        val want = extension.version ?: "0"
        if (stamp.isFile && stamp.readText().trim() == want) {
            return "${WorkspaceHostPaths.TRANSFER_GUEST}/$STAGE_DIR/${safeName(extension.id)}"
        }
        return runCatching {
            stageRoot.deleteRecursively()
            stageRoot.mkdirs()
            mirror(extension.dir, stageRoot)
            stamp.writeText(want)
            "${WorkspaceHostPaths.TRANSFER_GUEST}/$STAGE_DIR/${safeName(extension.id)}"
        }.getOrNull()
    }

    private fun stageHostScript(): String? {
        val dir = File(WorkspaceHostPaths.transferRoot(context.filesDir), HOST_DIR)
        val script = File(dir, "host.js")
        return runCatching {
            dir.mkdirs()
            context.assets.open("vscode-host/host.js").use { input ->
                script.outputStream().use { input.copyTo(it) }
            }
            "${WorkspaceHostPaths.TRANSFER_GUEST}/$HOST_DIR/host.js"
        }.getOrNull()
    }

    /** Hard-link where the filesystem allows it, copy where it does not. */
    private fun mirror(from: File, to: File) {
        from.listFiles()?.forEach { child ->
            val target = File(to, child.name)
            if (child.isDirectory) {
                target.mkdirs()
                mirror(child, target)
            } else {
                val linked = runCatching {
                    android.system.Os.link(child.absolutePath, target.absolutePath); true
                }.getOrDefault(false)
                if (!linked) child.copyTo(target, overwrite = true)
            }
        }
    }

    private fun safeName(value: String) = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun parseLoose(raw: String): Any =
        runCatching { JSONObject(raw) as Any }
            .recoverCatching { JSONArray(raw) as Any }
            .getOrDefault(raw)

    private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        const val STAGE_DIR = "vsix"
        const val HOST_DIR = "vsix-host"
        const val START_TIMEOUT_MS = 30_000L
        const val REQUEST_TIMEOUT_MS = 30_000L
    }
}
