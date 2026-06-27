package dev.jcode

import android.content.Context
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
            ).also { manager = it }
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
