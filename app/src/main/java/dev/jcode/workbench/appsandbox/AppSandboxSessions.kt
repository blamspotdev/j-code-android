package dev.jcode.workbench.appsandbox

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Process-scoped holder for live sandbox sessions (mirrors [dev.jcode.workbench.ScmWebViewHolder]).
 *
 * The guest app lives on a display the server owns, so a session has to outlive the composition that
 * shows it: switching to another editor tab, or away from JCode entirely, must not restart the app.
 * Keyed by editor tab id. [generation] bumps on every change so composables re-read their entry.
 */
internal object AppSandboxSessions {
    private val entries = HashMap<String, AppSandboxSession>()
    val generation = MutableStateFlow(0)

    @Synchronized
    fun get(tabId: String): AppSandboxSession? = entries[tabId]

    @Synchronized
    fun put(tabId: String, session: AppSandboxSession) {
        entries.put(tabId, session)?.close("Replaced by a new session.")
        generation.value++
    }

    @Synchronized
    fun destroy(tabId: String, reason: String = "Stopped.") {
        entries.remove(tabId)?.let { session ->
            session.close(reason)
            generation.value++
        }
    }

    @Synchronized
    fun destroyAll(reason: String = "Stopped.") {
        if (entries.isEmpty()) return
        entries.values.forEach { it.close(reason) }
        entries.clear()
        generation.value++
    }

    /** Drops every session whose editor tab is gone — page tabs have no close callback of their own. */
    @Synchronized
    fun retainOnly(tabIds: Set<String>) {
        entries.keys.filterNot { it in tabIds }.forEach { destroy(it, "The sandbox tab was closed.") }
    }
}

/**
 * Shared state for JCode's single app sandbox tab, observed by the page and written by whatever asks
 * for one (the Run panel today). A process singleton because at most one sandbox tab exists at a time
 * — the server owns one virtual display.
 */
internal object AppSandbox {
    /** Bumped on each open request so the shell can surface the sandbox editor tab. */
    val revealSignal = mutableStateOf(0)

    /** Package the next sandbox should launch, consumed by the page. */
    val pendingPackage = mutableStateOf<String?>(null)

    fun requestOpen(packageName: String?) {
        pendingPackage.value = packageName?.trim()?.takeIf { it.isNotEmpty() }
        revealSignal.value += 1
    }
}
