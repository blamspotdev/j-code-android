package dev.jcode.core.term

import dev.jcode.core.distro.Arch
import dev.jcode.core.distro.DistroBind
import dev.jcode.core.distro.ProotManager
import dev.jcode.core.distro.RootfsManager
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages terminal sessions backed by real PTY processes.
 * Sessions survive app backgrounding via the PTY file descriptors.
 */
class TerminalSessionManager(
    private val prootManager: ProotManager,
    private val rootfsManager: RootfsManager,
    maxSessions: Int = 4,
) {
    class Session(
        val id: String,
        val label: String,
        val pty: PtyProcess,
        var cols: Int = 80,
        var rows: Int = 24,
    ) {
        /** This session's own screen + scrollback. The manager feeds it continuously (see
         *  [startReader]) so the session keeps running and retains its content even when no view is
         *  attached — e.g. while the terminal panel is hidden, a CLI like `claude`/`opencode` keeps
         *  producing output (the PTY is drained) instead of blocking on a full buffer. */
        val parser: VtParser = VtParser(rows, cols)
        internal var readerJob: Job? = null

        /** Invoked off the main thread after new output is parsed, so a bound view can repaint. */
        @Volatile var onUpdate: (() -> Unit)? = null

        /** Resize both the PTY and the parser together (parser realloc is synchronized against the
         *  reader's feed). Safe to call from the UI thread. */
        fun resize(newCols: Int, newRows: Int) {
            if (newCols <= 0 || newRows <= 0) return
            if (cols == newCols && rows == newRows) return
            cols = newCols
            rows = newRows
            synchronized(this) { parser.resize(newRows, newCols) }
            runCatching { pty.resize(newCols, newRows) }
        }
    }

    // Process-lifetime scope that keeps every session's PTY drained and parsed regardless of the UI.
    private val readerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Guards _sessions: the reader/reaper runs on the IO scope while create/close run on the UI thread.
    private val sessionsLock = Any()
    private val _sessions = mutableMapOf<String, Session>()
    val sessions: Map<String, Session>
        get() = synchronized(sessionsLock) { _sessions.toMap() }

    /** Invoked (off the main thread) when a session's shell exits on its own and it is auto-reaped,
     *  so the host can release the session's foreground-service hold and the UI can drop its tab. */
    var onSessionExit: ((String) -> Unit)? = null

    @Volatile
    var activeSessionId: String? = null
        private set

    var maxSessions: Int = maxSessions
        set(value) {
            field = value.coerceIn(1, 8)
        }

    val sessionCount: Int
        get() = synchronized(sessionsLock) { _sessions.size }

    /**
     * Create a new terminal session spawning the configured shell via proot as the distro user.
     * Returns null if max sessions reached or proot/rootfs are not available.
     */
    fun createSession(
        distroId: String,
        binds: List<DistroBind>,
        workdir: String,
        user: String,
        shellCommand: String = "/bin/bash --login",
        rootfsArch: Arch = Arch.ARM64,
    ): Session? {
        if (sessionCount >= maxSessions) return null
        if (!prootManager.isProotInstalled) return null

        val rootfsPath = rootfsManager.getRootfsPath(distroId)
        if (!rootfsManager.isDistroInstalled(distroId)) return null

        // Suppress Ubuntu/Debian's /etc/bash.bashrc "sudo hint", which runs `$(groups)` (printing
        // "groups: cannot find name for group ID N" for the inherited Android gids) unless
        // ~/.hushlogin or ~/.sudo_as_admin_successful exists. .hushlogin also quiets login MOTD.
        runCatching {
            File(rootfsPath, "root/.hushlogin").createNewFile()
            if (user != "root") {
                File(rootfsPath, "home/$user").mkdirs()
                File(rootfsPath, "home/$user/.hushlogin").createNewFile()
            }
        }

        // Foreign-arch environment: ensure the QEMU emulator is extracted before spawning.
        if (prootManager.needsQemu(rootfsArch) && !prootManager.isQemuInstalled(rootfsArch)) {
            val qemuOk = kotlinx.coroutines.runBlocking { prootManager.ensureQemuInstalled(rootfsArch) }
            if (!qemuOk) {
                android.util.Log.e(
                    "TerminalSessionManager",
                    "qemu ${rootfsArch.qemuUserBinary} unavailable for $distroId; cannot start emulated session",
                )
                return null
            }
        }

        val sessionId = "terminal-${UUID.randomUUID().toString().take(8)}"
        val label = "bash ${sessionCount + 1}"

        val home = if (user == "root") "/root" else "/home/$user"
        val envVars = mapOf(
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
            "HOME" to home,
            "USER" to user,
            "LANG" to "en_US.UTF-8",
            // Replace the inherited Android TMPDIR (an app-private host path that doesn't exist in the
            // guest) with the rootfs /tmp, so guest tools that use temp dirs work (.NET, gcc, etc.).
            "TMPDIR" to "/tmp",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        )

        val prootArgs = prootManager.buildShellCommand(
            rootfsPath = rootfsPath,
            shellCommand = shellCommand,
            binds = binds,
            env = envVars,
            workdir = workdir,
            user = user,
            rootfsArch = rootfsArch,
        )

        return try {
            // Ensure temp directory exists before creating session
            prootManager.ensureProotTmpDir()

            val pty = PtyProcess.create(
                exe = prootArgs.first(),
                argv = prootArgs,
                envp = prootManager.runtimeEnv(rootfsArch),
                cwd = null,
                cols = 80,
                rows = 24,
            )
            val session = Session(sessionId, label, pty)
            synchronized(sessionsLock) { _sessions[sessionId] = session }
            activeSessionId = sessionId
            startReader(session)
            session
        } catch (e: Exception) {
            android.util.Log.e("TerminalSessionManager", "Failed to create session", e)
            null
        }
    }

    /** Continuously drain a session's PTY into its parser, for the lifetime of the session — not tied
     *  to any view. This is what makes background terminals keep running while the panel is hidden. */
    private fun startReader(session: Session) {
        session.readerJob?.cancel()
        session.readerJob = readerScope.launch {
            val buffer = ByteArray(8192)
            var exited = false
            while (isActive) {
                val n = try {
                    session.pty.read(buffer)
                } catch (e: Exception) {
                    -1
                }
                when {
                    n > 0 -> {
                        synchronized(session) { session.parser.feed(buffer.copyOf(n)) }
                        session.onUpdate?.invoke()
                    }
                    n < 0 -> { exited = true; break }   // EOF: the shell/process exited
                    else -> delay(8)                    // no data available yet
                }
            }
            // Reap a session that ended on its own (EOF) so its PTY fd + parser are freed and the
            // foreground hold released. A manual closeSession() cancels this job before EOF, so the
            // CancellationException unwinds past here and reapExitedSession only runs for real exits.
            if (exited) reapExitedSession(session.id)
        }
    }

    /** Tear down a session whose shell exited by itself (see [startReader]). Idempotent. */
    private fun reapExitedSession(id: String) {
        val session = synchronized(sessionsLock) { _sessions.remove(id) } ?: return
        session.onUpdate = null
        // Close only the PTY (already at EOF). Do NOT close the VtParser here: a bound TerminalView may
        // still be drawing it on the main thread, and closing native parser state from this IO thread
        // would race onDraw. The parser is freed on the main thread by closeSession(), or by its
        // Cleaner once the view unbinds and the Session becomes unreachable.
        runCatching { session.pty.close() }
        if (activeSessionId == id) {
            activeSessionId = synchronized(sessionsLock) { _sessions.keys.firstOrNull() }
        }
        onSessionExit?.invoke(id)
    }

    /**
     * Close a terminal session, killing the PTY process and its reader.
     */
    fun closeSession(id: String) {
        val session = synchronized(sessionsLock) { _sessions.remove(id) }
        session?.readerJob?.cancel()
        session?.onUpdate = null
        session?.parser?.close()
        session?.pty?.close()
        if (activeSessionId == id) {
            activeSessionId = synchronized(sessionsLock) { _sessions.keys.firstOrNull() }
        }
    }

    /**
     * Switch the active session.
     */
    fun switchSession(id: String) {
        if (synchronized(sessionsLock) { _sessions.containsKey(id) }) {
            activeSessionId = id
        }
    }

    /**
     * Resize a terminal session.
     */
    fun resizeSession(id: String, cols: Int, rows: Int) {
        getSession(id)?.resize(cols, rows)
    }

    /**
     * Send input to a specific session.
     */
    fun sendInput(id: String, text: String) {
        getSession(id)?.pty?.write(text)
    }

    /**
     * Send input bytes to a specific session.
     */
    fun sendInput(id: String, data: ByteArray) {
        getSession(id)?.pty?.write(data)
    }

    /**
     * Get a session by ID.
     */
    fun getSession(id: String): Session? = synchronized(sessionsLock) { _sessions[id] }

    /**
     * Get the active session.
     */
    fun getActiveSession(): Session? = activeSessionId?.let { getSession(it) }

    /**
     * Close all sessions.
     */
    fun closeAll() {
        val all = synchronized(sessionsLock) {
            val copy = _sessions.values.toList()
            _sessions.clear()
            copy
        }
        all.forEach {
            it.readerJob?.cancel()
            it.onUpdate = null
            it.parser.close()
            it.pty.close()
        }
        activeSessionId = null
    }
}
