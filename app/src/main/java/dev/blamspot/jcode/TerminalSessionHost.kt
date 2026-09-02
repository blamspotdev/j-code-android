package dev.blamspot.jcode

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.blamspot.jcode.backend.BackendSessionHandle
import dev.blamspot.jcode.backend.BackendSessionKind
import dev.blamspot.jcode.backend.SessionRegistry
import dev.blamspot.jcode.core.distro.ProotManager
import dev.blamspot.jcode.core.distro.RootfsDownloader
import dev.blamspot.jcode.core.distro.RootfsManager
import dev.blamspot.jcode.core.term.TerminalSessionManager
import java.io.File

/**
 * Process-lifetime owner of the single [TerminalSessionManager].
 *
 * Terminal sessions are native PTY child processes (proot -> bash). Previously the manager was
 * created with Compose `remember`, so it was scoped to the composition and lost whenever the
 * Activity was recreated — orphaning the running shells. Hosting it here (process scope) keeps the
 * same manager instance across Activity recreation/backgrounding so sessions survive.
 *
 * It also keeps the [dev.blamspot.jcode.BackendService] foreground service alive while any terminal session
 * exists (one [SessionRegistry] TERMINAL hold per session). That raises the process priority so
 * Android won't kill the cached app on Home (which would cold-start back to the initial state) and
 * exempts it from the cached-app freezer, so shells keep running in the background.
 */
object TerminalSessionHost {
    @Volatile
    private var manager: TerminalSessionManager? = null
    private val fgsHandles = HashMap<String, BackendSessionHandle>()

    private val mainHandler = Handler(Looper.getMainLooper())

    // Optional UI hook so the active screen can drop a tab when its shell exits on its own. Set via a
    // DisposableEffect and cleared on dispose, so it never outlives the composition that owns it.
    @Volatile
    private var uiExitListener: ((String) -> Unit)? = null

    fun setUiExitListener(listener: ((String) -> Unit)?) {
        uiExitListener = listener
    }

    // Optional UI hook for a session whose process tree was killed from outside the app (Android's
    // phantom-process trim), so the workbench can explain the disappearance rather than say nothing.
    @Volatile
    private var uiExternalKillListener: (() -> Unit)? = null

    fun setUiExternalKillListener(listener: (() -> Unit)?) {
        uiExternalKillListener = listener
    }

    // Optional UI hook so a guest `code`/`jcode <path>` command can open + focus a file in the editor.
    // Set via a DisposableEffect and cleared on dispose, so it never outlives its composition.
    @Volatile
    private var uiOpenFileListener: ((String) -> Unit)? = null

    fun setUiOpenFileListener(listener: ((String) -> Unit)?) {
        uiOpenFileListener = listener
    }

    // Optional UI hook so a guest tool that opens a URL (xdg-open/$BROWSER -> OSC 7714) reaches the
    // host's web preview / chosen browser. Set via a DisposableEffect and cleared on dispose.
    @Volatile
    private var uiOpenUrlListener: ((String) -> Unit)? = null

    fun setUiOpenUrlListener(listener: ((String) -> Unit)?) {
        uiOpenUrlListener = listener
    }

    // Optional UI hook so the terminal tab can be named after the running foreground process.
    // Set via a DisposableEffect and cleared on dispose, so it never outlives its composition.
    @Volatile
    private var uiTitleListener: ((String, String) -> Unit)? = null

    fun setUiTitleListener(listener: ((String, String) -> Unit)?) {
        uiTitleListener = listener
    }

    // Optional UI hook so a guest shell wrapper can relocate an interactive sub-shell into its own tab
    // (OSC 7715). Unlike the fire-and-forget hooks above, a dropped 7715 would hang the parent shell
    // (it blocks on a FIFO), so events are buffered while the listener is transiently detached (Activity
    // recreation) and replayed when it re-attaches.
    @Volatile
    private var uiNestedShellListener: ((String, String) -> Unit)? = null
    private val pendingNestedOpens = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, String>>()

    fun setUiNestedShellListener(listener: ((String, String) -> Unit)?) {
        uiNestedShellListener = listener
        if (listener != null) {
            while (true) {
                val ev = pendingNestedOpens.poll() ?: break
                mainHandler.post { listener(ev.first, ev.second) }
            }
        }
    }

    /** The manager if one has been created, without creating one. For callers that only want to look
     *  at existing sessions — no terminal has been opened yet means there is nothing to look at. */
    fun existingManager(): TerminalSessionManager? = manager

    fun manager(context: Context): TerminalSessionManager {
        manager?.let { return it }
        return synchronized(this) {
            manager ?: TerminalSessionManager(
                prootManager = ProotManager(context.applicationContext),
                rootfsManager = RootfsManager(
                    context.applicationContext,
                    RootfsDownloader(tmpDir = File(context.applicationContext.filesDir, "tmp")),
                ),
                // Matches the Settings default; JCodeShell keeps it in sync with the user's preference.
                maxSessions = 12,
            ).also { mgr ->
                manager = mgr
                // When a shell exits on its own (e.g. a finished build, or `exit`), the manager reaps
                // the session off-thread: release its foreground-service hold, then notify the UI (on
                // the main thread) so it can drop the tab and free the parser.
                mgr.onSessionExit = { sessionId ->
                    onSessionStopped(sessionId)
                    uiExitListener?.let { listener -> mainHandler.post { listener(sessionId) } }
                }
                mgr.onExternalKill = {
                    uiExternalKillListener?.let { listener -> mainHandler.post { listener() } }
                }
                // A guest `code`/`jcode <path>` command (OSC 7711) fires this off the reader thread;
                // hop to the main thread and hand the path token to the active UI listener.
                mgr.onOpenFileRequest = { token ->
                    uiOpenFileListener?.let { listener -> mainHandler.post { listener(token) } }
                }
                // A guest browser-open (xdg-open/$BROWSER -> OSC 7714) fires this off the reader thread;
                // hop to the main thread and hand the URL to the active UI listener.
                mgr.onOpenUrlRequest = { url ->
                    uiOpenUrlListener?.let { listener -> mainHandler.post { listener(url) } }
                }
                // The shell reports the running program via OSC 7712 (off the reader thread); hop to
                // the main thread so the UI can rename the session's tab.
                mgr.onTitleChange = { sessionId, title ->
                    // The same report also renames the session in the foreground notification, so the
                    // shade says "claude" or "gradle" rather than a count of anonymous sessions.
                    relabelSession(sessionId, title)
                    uiTitleListener?.let { listener -> mainHandler.post { listener(sessionId, title) } }
                }
                // Mirror run terminals' output into the Output channel (filtered to captured run
                // sessions inside OutputLog). Runs on the reader thread; OutputLog copies what it keeps.
                mgr.onOutput = { sessionId, data, length ->
                    OutputLog.appendRaw(sessionId, data, length)
                }
                // A guest OSC 52 clipboard write (Claude Code's copy-on-select, tmux `set-clipboard`)
                // fires off the reader thread; the Android clipboard requires the main thread.
                mgr.onClipboardWrite = { text ->
                    mainHandler.post {
                        runCatching {
                            val cm = context.applicationContext
                                .getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("Terminal", text))
                        }
                    }
                }
                // A guest shell wrapper requests a relocated sub-shell tab (OSC 7715), off the reader
                // thread. Hop to the main thread; buffer if the UI listener is transiently detached so a
                // dropped request can't strand the blocked parent shell.
                mgr.onNestedShellOpen = { parentId, payload ->
                    val listener = uiNestedShellListener
                    if (listener != null) mainHandler.post { listener(parentId, payload) }
                    else pendingNestedOpens.offer(parentId to payload)
                }
            }
        }
    }

    /** Acquire a foreground-service hold for a newly started terminal session (idempotent per id).
     *  [label] is the tab's name, which the notification shows until the shell reports a program. */
    fun onSessionStarted(context: Context, sessionId: String, label: String? = null) {
        synchronized(this) {
            if (fgsHandles.containsKey(sessionId)) return
            runCatching {
                SessionRegistry.registerSession(
                    context.applicationContext,
                    BackendSessionKind.TERMINAL,
                    "terminal",
                    label,
                )
            }.onSuccess { fgsHandles[sessionId] = it }
                .onFailure { error ->
                    android.util.Log.w("TerminalSessionHost", "foreground-service register failed", error)
                }
        }
    }

    /** Rename a terminal in the notification. "terminal" is the shell saying it has no program
     *  running, which is a prompt rather than a name — the tab's own label reads better there. */
    private fun relabelSession(sessionId: String, title: String) {
        val handle = synchronized(this) { fgsHandles[sessionId] } ?: return
        val program = title.trim().takeUnless { it.isEmpty() || it == "terminal" }
        SessionRegistry.relabelSession(handle.sessionId, program)
    }

    /** Release the foreground-service hold for a closed terminal session. */
    fun onSessionStopped(sessionId: String) {
        synchronized(this) { fgsHandles.remove(sessionId)?.close() }
    }
}
