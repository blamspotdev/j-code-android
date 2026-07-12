package dev.jcode.workbench

import android.content.Context
import dev.jcode.TerminalSessionHost
import dev.jcode.core.distro.DistroBind
import dev.jcode.core.distro.DistroService
import dev.jcode.core.distro.ExecResult
import dev.jcode.core.term.TerminalSessionManager
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs toolchain installs and project scaffolds inside one shared "Setup" terminal session, so the
 * user can watch (and scroll) the real output in the right drawer instead of a silent in-process
 * exec. The session is created on first use, reused afterwards, and never focused automatically —
 * the terminal panel's unseen-badge is the only attention cue.
 *
 * Completion is detected via OSC 7713 (`ESC ] 7713 ; <token> ; <exitCode> BEL`), emitted by a
 * `printf` appended to each task. Tasks are serialized: the session is one shell.
 */
class SetupTerminalRunner(
    private val appContext: Context,
    private val distroService: DistroService,
) {
    private val manager: TerminalSessionManager
        get() = TerminalSessionHost.manager(appContext)

    private val mutex = Mutex()
    private val tokenCounter = AtomicInteger()

    private val _sessionId = MutableStateFlow<String?>(null)

    /** The live Setup session id, for the UI to surface as a (non-focused) terminal tab. */
    val sessionId: StateFlow<String?> = _sessionId.asStateFlow()

    private class Pending(val token: String, val sessionId: String) {
        val exit = CompletableDeferred<Int>()
    }

    @Volatile
    private var pending: Pending? = null

    /** Routed from [TerminalSessionManager.onTaskComplete]; payload is `<token>;<exitCode>`. */
    fun handleTaskComplete(sessionId: String, payload: String) {
        val current = pending ?: return
        if (current.sessionId != sessionId) return
        val token = payload.substringBefore(';')
        if (token != current.token) return
        current.exit.complete(payload.substringAfter(';').trim().toIntOrNull() ?: -1)
    }

    /**
     * Run [script] in the Setup terminal and suspend until it reports completion. [asUser] follows
     * catalog semantics: "root" runs privileged with HOME/USER pointing at the jcode user; any other
     * user goes through a `su -` login shell. Returns null when no session could be started (caller
     * falls back to the quiet in-process exec).
     */
    suspend fun run(
        label: String,
        script: String,
        workdir: String?,
        asUser: String,
        timeoutMs: Long,
    ): ExecResult? = mutex.withLock {
        val session = ensureSession() ?: return@withLock null
        val token = "jc${tokenCounter.incrementAndGet()}"
        val task = Pending(token, session.id)
        pending = task
        try {
            manager.sendInput(session.id, buildTaskInput(label, script, workdir, asUser, token))
            awaitCompletion(task, session.id, label, timeoutMs)
        } finally {
            pending = null
        }
    }

    /** One shell-input block: heredoc the script into a guest file, run it, report OSC 7713. */
    private fun buildTaskInput(
        label: String,
        script: String,
        workdir: String?,
        asUser: String,
        token: String,
    ): String {
        val guestScript = "/tmp/.jcode-task-$token.sh"
        val heredocEnd = "JCODE_EOF_$token"
        val runtimeUser = distroService.environmentState.value.runtime.user
        val body = buildString {
            append("echo '== ").append(label.replace("'", "")).append(" =='\n")
            if (!workdir.isNullOrBlank()) append("cd \"").append(workdir).append("\" || exit 1\n")
            append(script)
            if (!script.endsWith("\n")) append('\n')
        }
        val runCommand = if (asUser == "root") {
            "env HOME=/home/$runtimeUser USER=$runtimeUser /bin/sh $guestScript"
        } else {
            "su - $asUser -c '/bin/sh $guestScript'"
        }
        return buildString {
            append("cat > ").append(guestScript).append(" <<'").append(heredocEnd).append("'\n")
            append(body)
            append(heredocEnd).append('\n')
            append(runCommand)
            append("; printf '\\033]7713;").append(token).append(";%s\\007' \"$?\"")
            append("; rm -f ").append(guestScript).append('\n')
        }
    }

    private suspend fun awaitCompletion(
        task: Pending,
        sessionId: String,
        label: String,
        timeoutMs: Long,
    ): ExecResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val exit = withTimeoutOrNull(1_000L) { task.exit.await() }
            when {
                exit != null -> return ExecResult(exitCode = exit)
                manager.getSession(sessionId) == null -> return ExecResult(
                    internalError = "The Setup terminal closed before \"$label\" finished.",
                    exitCode = null,
                )
                System.currentTimeMillis() >= deadline -> {
                    manager.sendInput(sessionId, "\u0003")
                    return ExecResult(
                        internalError = "$label timed out after ${timeoutMs / 1000}s — see the Setup terminal.",
                        exitCode = null,
                    )
                }
            }
        }
    }

    /** Reuse the live Setup session or start a fresh one (root shell, /workspace mounted). */
    private fun ensureSession(): TerminalSessionManager.Session? {
        _sessionId.value?.let { id -> manager.getSession(id)?.let { return it } }
        val environment = distroService.environmentState.value
        if (environment.distroInstalled != true) return null
        val runtime = environment.runtime
        val session = manager.createSession(
            distroId = runtime.selectedDistro.id,
            binds = listOf(DistroBind(host = dev.jcode.core.distro.WorkspaceHostPaths.projectsRoot, target = "/workspace")),
            workdir = "/workspace",
            user = "root",
            rootfsArch = runtime.selectedDistro.arch,
            label = "Setup",
        ) ?: return null
        TerminalSessionHost.onSessionStarted(appContext, session.id)
        _sessionId.value = session.id
        return session
    }
}
