package dev.jcode.workbench

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

/**
 * Terminals a `.vsix` extension opened through `vscode.window.createTerminal`.
 *
 * A real terminal rather than a stand-in, because JCode has terminals and this is what the API is
 * for: Claude Code's "run in terminal" and an extension's setup steps both arrive here, and a call
 * that quietly did nothing would look like a broken button.
 *
 * Process-scoped and observed by the workbench for the same reason [BuiltinBrowser] is: the tab
 * lives in composition state that the extension host — a background process the session outlives —
 * cannot reach directly. Requests are queued here and drained by whoever is showing the terminals.
 */
object VsixTerminals {

    /** What an extension asked of a terminal, waiting for the workbench to carry it out. */
    sealed interface Request {
        val id: String

        data class Create(override val id: String, val name: String, val cwd: String) : Request
        data class SendText(override val id: String, val text: String, val newline: Boolean) : Request
        data class Show(override val id: String) : Request
        data class Dispose(override val id: String) : Request
    }

    /** Pending requests, in order. Handed over by [drain]; never read for state. */
    private val pending = mutableStateListOf<Request>()

    /** Bumped on each request so a snapshot observer sees one even if the list is drained between. */
    val signal = mutableStateOf(0)

    /**
     * The JCode terminal session backing each extension terminal id.
     *
     * Held here rather than in the composition so text sent to a terminal still lands after the
     * drawer that opened it has been closed and recomposed away.
     */
    val sessions = HashMap<String, String>()

    fun create(id: String, name: String, cwd: String) = post(Request.Create(id, name.ifBlank { "terminal" }, cwd))

    fun sendText(id: String, text: String, newline: Boolean) = post(Request.SendText(id, text, newline))

    fun show(id: String) = post(Request.Show(id))

    fun dispose(id: String) = post(Request.Dispose(id))

    private fun post(request: Request) {
        pending += request
        signal.value += 1
    }

    /** Take everything queued so far. */
    fun drain(): List<Request> {
        if (pending.isEmpty()) return emptyList()
        val taken = pending.toList()
        pending.clear()
        return taken
    }
}
