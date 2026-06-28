package dev.jcode

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.jcode.backend.BackendSessionHandle
import dev.jcode.backend.BackendSessionKind
import dev.jcode.backend.SessionRegistry
import dev.jcode.core.distro.ProotManager
import dev.jcode.core.distro.RootfsDownloader
import dev.jcode.core.distro.RootfsManager
import dev.jcode.core.term.TerminalSessionManager
import java.io.File

/**
 * Process-lifetime owner of the single [TerminalSessionManager].
 *
 * Terminal sessions are native PTY child processes (proot -> bash). Previously the manager was
 * created with Compose `remember`, so it was scoped to the composition and lost whenever the
 * Activity was recreated — orphaning the running shells. Hosting it here (process scope) keeps the
 * same manager instance across Activity recreation/backgrounding so sessions survive.
 *
 * It also keeps the [dev.jcode.BackendService] foreground service alive while any terminal session
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

    // Optional UI hook so a guest `code`/`jcode <path>` command can open + focus a file in the editor.
    // Set via a DisposableEffect and cleared on dispose, so it never outlives its composition.
    @Volatile
    private var uiOpenFileListener: ((String) -> Unit)? = null

    fun setUiOpenFileListener(listener: ((String) -> Unit)?) {
        uiOpenFileListener = listener
    }

    // Optional UI hook so the terminal tab can be named after the running foreground process.
    // Set via a DisposableEffect and cleared on dispose, so it never outlives its composition.
    @Volatile
    private var uiTitleListener: ((String, String) -> Unit)? = null

    fun setUiTitleListener(listener: ((String, String) -> Unit)?) {
        uiTitleListener = listener
    }

    fun manager(context: Context): TerminalSessionManager {
        manager?.let { return it }
        return synchronized(this) {
            manager ?: TerminalSessionManager(
                prootManager = ProotManager(context.applicationContext),
                rootfsManager = RootfsManager(
                    context.applicationContext,
                    RootfsDownloader(tmpDir = File(context.applicationContext.filesDir, "tmp")),
                ),
                maxSessions = 4,
            ).also { mgr ->
                manager = mgr
                // When a shell exits on its own (e.g. a finished build, or `exit`), the manager reaps
                // the session off-thread: release its foreground-service hold, then notify the UI (on
                // the main thread) so it can drop the tab and free the parser.
                mgr.onSessionExit = { sessionId ->
                    onSessionStopped(sessionId)
                    uiExitListener?.let { listener -> mainHandler.post { listener(sessionId) } }
                }
                // A guest `code`/`jcode <path>` command (OSC 7711) fires this off the reader thread;
                // hop to the main thread and hand the path token to the active UI listener.
                mgr.onOpenFileRequest = { token ->
                    uiOpenFileListener?.let { listener -> mainHandler.post { listener(token) } }
                }
                // The shell reports the running program via OSC 7712 (off the reader thread); hop to
                // the main thread so the UI can rename the session's tab.
                mgr.onTitleChange = { sessionId, title ->
                    uiTitleListener?.let { listener -> mainHandler.post { listener(sessionId, title) } }
                }
            }
        }
    }

    /** Acquire a foreground-service hold for a newly started terminal session (idempotent per id). */
    fun onSessionStarted(context: Context, sessionId: String) {
        synchronized(this) {
            if (fgsHandles.containsKey(sessionId)) return
            runCatching {
                SessionRegistry.registerSession(
                    context.applicationContext,
                    BackendSessionKind.TERMINAL,
                    "terminal",
                )
            }.onSuccess { fgsHandles[sessionId] = it }
                .onFailure { error ->
                    android.util.Log.w("TerminalSessionHost", "foreground-service register failed", error)
                }
        }
    }

    /** Release the foreground-service hold for a closed terminal session. */
    fun onSessionStopped(sessionId: String) {
        synchronized(this) { fgsHandles.remove(sessionId)?.close() }
    }
}
