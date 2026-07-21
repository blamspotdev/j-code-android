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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Upper bound for the user-configurable session limit (Settings default is 12). */
private const val MAX_SESSIONS_CAP = 24

/**
 * Manages terminal sessions backed by real PTY processes.
 * Sessions survive app backgrounding via the PTY file descriptors.
 */
class TerminalSessionManager(
    private val prootManager: ProotManager,
    private val rootfsManager: RootfsManager,
    maxSessions: Int = 12,
) {
    class Session(
        val id: String,
        var label: String,
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

        /** The foreground program reported via OSC 7712 (see GUEST_SHELL_INTEGRATION), or null while the
         *  shell sits at its prompt ("terminal"). Lets the UI warn before closing a busy terminal. */
        @Volatile var foreground: String? = null

        /** Epoch millis of this session's last I/O (output drained or input sent), for idle reaping. */
        @Volatile var lastActivityAt: Long = System.currentTimeMillis()

        /** Invoked off the main thread after new output is parsed, so a bound view can repaint. */
        @Volatile var onUpdate: (() -> Unit)? = null

        /** The parser's packed [VtParser] mode snapshot, published by the reader after each feed.
         *  The UI thread reads THIS instead of calling into the native parser, so it always sees a
         *  mutually-consistent modes + alt-screen pair and never races feed or close. */
        @Volatile var inputModesSnapshot: Int = 0

        /** For a relocated nested sub-shell tab (OSC 7715): the parent session to refocus when this
         *  child ends. Non-null marks a temporary child tab; null for a normal session. */
        @Volatile var relocationParentId: String? = null

        /** Host path of the guest FIFO the parent shell blocks on. Used to write the exit-code
         *  backstop when this child is force-closed before its launcher reports. */
        @Volatile var relocationFifoHost: File? = null

        /** Spawn parameters captured so a nested-shell child can be created cloning this session's
         *  distro/binds/user/arch/workdir (OSC 7715). Null for sessions created before this existed. */
        @Volatile var spawnSpec: SpawnSpec? = null

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

    /** Immutable spawn parameters of a session, captured on [createSession] so a relocated nested
     *  sub-shell (OSC 7715) can be created in the same distro/binds/user/arch/workdir as its parent. */
    data class SpawnSpec(
        val distroId: String,
        val binds: List<DistroBind>,
        val user: String,
        val rootfsArch: Arch,
        val workdir: String,
    )

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

    /** Invoked (off the main thread) when a guest `code`/`jcode <path>[:line[:col]]` command runs,
     *  carrying the path token so the host can open + focus it in the editor. */
    @Volatile
    var onOpenFileRequest: ((String) -> Unit)? = null

    /** Invoked (off the main thread) when a guest tool opens a URL through the xdg-open/`$BROWSER`
     *  shim (OSC 7714), so the host can route it to the in-app web preview or the chosen browser. */
    @Volatile
    var onOpenUrlRequest: ((String) -> Unit)? = null

    /** Invoked (off the main thread) with (sessionId, title) when the shell reports the running
     *  program via OSC 7712, so the UI can name the terminal tab after the foreground process. */
    @Volatile
    var onTitleChange: ((String, String) -> Unit)? = null

    /** Invoked (off the reader thread) with each session's raw PTY output (id, buffer, length), so the
     *  host can mirror run output into the Output panel. The buffer is reused after the call — the
     *  callback must consume/copy it synchronously. */
    @Volatile
    var onOutput: ((String, ByteArray, Int) -> Unit)? = null

    /** Invoked (off the reader thread) with (sessionId, payload) when a guest task reports completion
     *  via OSC 7713. The payload is `<token>;<exitCode>` — see the Setup-terminal task runner. */
    @Volatile
    var onTaskComplete: ((String, String) -> Unit)? = null

    /** Invoked (off the reader thread) with decoded text when a guest program writes the clipboard
     *  via OSC 52 (e.g. Claude Code's copy-on-select), so the host can set the Android clipboard. */
    @Volatile
    var onClipboardWrite: ((String) -> Unit)? = null

    /** Invoked (off the reader thread) with (parentSessionId, payload) when a guest shell wrapper asks
     *  to relocate an interactive sub-shell into its own tab via OSC 7715. Payload is
     *  `open;<token>;<label>`. See [createNestedShellSession] and GUEST nested-shell wrapper. */
    @Volatile
    var onNestedShellOpen: ((String, String) -> Unit)? = null

    /** When true, [createSession] installs the nested-shell PATH wrappers so typing an interactive
     *  shell (`bash`/`zsh`/…) on the app's PTY relocates it to a temporary tab. Pushed from Settings. */
    @Volatile
    var nestedShellTabs: Boolean = false

    // childId -> parentId for relocated nested-shell tabs. Outlives reapExitedSession/closeSession
    // (unlike Session) so the UI's exit path can still find the parent to refocus. Cleared by the host
    // via clearRelocation once the exit is handled, or wholesale by closeAll.
    private val relocationParents = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Volatile
    var activeSessionId: String? = null
        private set

    var maxSessions: Int = maxSessions.coerceIn(1, MAX_SESSIONS_CAP)
        set(value) {
            field = value.coerceIn(1, MAX_SESSIONS_CAP)
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
        label: String? = null,
    ): Session? {
        if (sessionCount >= maxSessions) return null
        if (!prootManager.isProotInstalled) return null

        val rootfsPath = rootfsManager.getRootfsPath(distroId)
        if (!rootfsManager.isDistroInstalled(distroId)) return null

        // Ensure DNS/host config so `apt` works from the terminal. Minimal ubuntu-base images (26.04)
        // ship no usable /etc/resolv.conf; this repairs such an install even if it was created before
        // the fix (guarded writes → cheap no-op once configured). Fresh installs get it at extraction.
        rootfsManager.ensureRootfsNetworking(rootfsPath)

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

        // Ship a phone-friendly htop layout so `htop` shows process NAMES and hides threads. htop's
        // desktop default packs so many wide columns (VIRT/RES/SHR/PRIORITY/NICE/TIME) that the
        // Command column — the actual process names — falls off a narrow phone screen, leaving only a
        // wall of "root". This compact layout (PID/CPU%/MEM%/Command) fits, and hiding threads
        // collapses the app's ~40 JVM-thread rows into one process. Written only when absent, so a
        // user's own htop settings (htop rewrites this file on exit) are never clobbered.
        runCatching {
            writeDefaultHtoprc(File(rootfsPath, "root"))
            if (user != "root") writeDefaultHtoprc(File(rootfsPath, "home/$user"))
        }

        // Install the `code`/`jcode` open-in-editor command for login shells (sourced via
        // /etc/profile -> /etc/profile.d/*.sh). It prints OSC 7711 with the file path, which the
        // session reader below detects and routes to the editor.
        runCatching {
            val profileD = File(rootfsPath, "etc/profile.d")
            profileD.mkdirs()
            File(profileD, "jcode-open.sh").writeText(GUEST_SHELL_INTEGRATION)
        }

        // Install a browser-open shim so guest CLIs (claude, etc.) that call xdg-open / $BROWSER / open
        // reach the host: it emits OSC 7714 with the URL, which the session reader routes to the web
        // preview or the chosen browser. /usr/local/bin is early on PATH so it overrides any distro one.
        runCatching {
            val localBin = File(rootfsPath, "usr/local/bin")
            localBin.mkdirs()
            for (name in listOf("xdg-open", "sensible-browser", "x-www-browser", "www-browser", "gnome-open", "open")) {
                File(localBin, name).apply {
                    writeText(GUEST_OPEN_URL_SHIM)
                    setExecutable(true, false)
                }
            }
        }

        // Nested-shell wrappers (OSC 7715): when enabled, shadow interactive shell binaries early on
        // PATH so typing `bash`/`zsh`/… on the app's own PTY relocates the sub-shell into its own tab.
        // The absolute real shell path is baked in (no runtime PATH scan on the hot `sh -c` path). When
        // disabled, only our own marker-guarded wrappers are removed, so toggling off is clean. Guarded
        // writes (content compare) keep this a cheap no-op once installed.
        runCatching {
            val localBin = File(rootfsPath, "usr/local/bin").apply { mkdirs() }
            for (name in NESTED_SHELL_NAMES) {
                val wrapper = File(localBin, name)
                if (nestedShellTabs) {
                    val real = resolveGuestShell(rootfsPath, name) ?: continue
                    val body = nestedShellWrapper(real, name)
                    if (!wrapper.exists() || wrapper.readText() != body) {
                        wrapper.writeText(body)
                        wrapper.setExecutable(true, false)
                    }
                } else if (wrapper.exists() && wrapper.readText().startsWith(NSH_MARKER)) {
                    wrapper.delete()
                }
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
        // Seed label shown until the shell reports the running program (see OSC 7712 / GUEST_SHELL_INTEGRATION).
        val sessionLabel = label ?: "terminal"

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
            // Non-interactive bash sources $BASH_ENV at startup, so the shell integration's tab-title
            // hook also fires inside run scripts (`bash run-*.sh`) — naming the tab after the actual
            // tool (npm/vite/dotnet) rather than the bash wrapper. See GUEST_SHELL_INTEGRATION.
            "BASH_ENV" to "/etc/profile.d/jcode-open.sh",
            // Browser-openers consult $BROWSER first; point it at the OSC 7714 shim (see below) so
            // guest tools open URLs through the host instead of failing on the missing X11/dbus stack.
            "BROWSER" to "/usr/local/bin/xdg-open",
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
            val session = Session(sessionId, sessionLabel, pty)
            session.spawnSpec = SpawnSpec(distroId, binds, user, rootfsArch, workdir)
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
            // Epoch millis when an open ?2026 synchronized update started suppressing repaints, or 0.
            var syncSince = 0L
            val oscHandler: (Int, String) -> Unit = { code, payload ->
                when (code) {
                    52 -> {
                        // OSC 52 clipboard write: "c;<base64>". "?" is a clipboard READ query —
                        // never answered (the guest must not see the user's clipboard uninvited).
                        val data = payload.substringAfter(';', "")
                        if (data.isNotEmpty() && data != "?") {
                            runCatching {
                                val text = String(android.util.Base64.decode(data, android.util.Base64.DEFAULT), Charsets.UTF_8)
                                if (text.isNotEmpty()) onClipboardWrite?.invoke(text)
                            }
                        }
                    }
                    7711 -> onOpenFileRequest?.invoke(payload.trim())
                    7712 -> {
                        val title = payload.trim()
                        // "terminal" (or empty) = back at the prompt with no foreground program.
                        session.foreground = title.takeUnless { it.isEmpty() || it == "terminal" }
                        onTitleChange?.invoke(session.id, title)
                    }
                    7713 -> onTaskComplete?.invoke(session.id, payload.trim())
                    7714 -> onOpenUrlRequest?.invoke(payload.trim())
                    7715 -> onNestedShellOpen?.invoke(session.id, payload.trim())
                }
            }
            while (isActive) {
                val n = try {
                    session.pty.read(buffer)
                } catch (e: Exception) {
                    -1
                }
                when {
                    n > 0 -> {
                        session.lastActivityAt = System.currentTimeMillis()
                        // Feed straight from the reused read buffer (the native feed is length-aware,
                        // so no per-chunk copy), then collect the shell-integration OSC events, the
                        // query replies, and the mode snapshot the parser holds after this chunk —
                        // all under the session lock, which closeSession also takes before closing
                        // the parser, so none of these calls can race a close. Dispatch happens
                        // outside the lock; replies (DA/DSR/CPR/DECRQM) go straight back to the PTY.
                        val parsed = synchronized(session) {
                            if (!session.parser.isOpen) return@synchronized null
                            session.parser.feed(buffer, n)
                            val oscEvents = session.parser.drainOsc()
                            val replies = session.parser.takeResponses()
                            val modes = session.parser.inputModes()
                            session.inputModesSnapshot = modes
                            Triple(oscEvents, replies, modes)
                        } ?: break
                        val (oscEvents, replies, modes) = parsed
                        replies?.let { runCatching { session.pty.write(it) } }
                        oscEvents.forEach { (code, payload) -> oscHandler(code, payload) }
                        onOutput?.invoke(session.id, buffer, n)
                        // ?2026 synchronized output: hold repaints while an update is open so a
                        // TUI's multi-write frame lands atomically — but never longer than 100ms
                        // (the idle branch below enforces the deadline even when no further output
                        // arrives). A chunk that both opens and closes the update (the common
                        // case) suppresses nothing.
                        val syncOpen = (modes and VtParser.MODE_SYNC_OUTPUT) != 0
                        val now = System.currentTimeMillis()
                        if (!syncOpen) {
                            syncSince = 0L
                            session.onUpdate?.invoke()
                        } else if (syncSince == 0L) {
                            syncSince = now
                        } else if (now - syncSince >= 100) {
                            syncSince = now
                            session.onUpdate?.invoke()
                        }
                    }
                    n < 0 -> { exited = true; break }   // EOF: the shell/process exited
                    // No data yet: park in the kernel until output arrives (readerScope is
                    // Dispatchers.IO, so blocking is fine). The 1s timeout bounds how long a
                    // cancelled/closed session's reader can linger before it re-checks isActive.
                    // With a ?2026 update still open, park only until the 100ms deadline and then
                    // force the suppressed frame out — a lost 2026l (killed TUI) must not leave
                    // the screen frozen on pre-update content.
                    else -> {
                        if (syncSince != 0L) {
                            val remaining = 100 - (System.currentTimeMillis() - syncSince)
                            if (remaining <= 0) {
                                syncSince = 0L
                                session.onUpdate?.invoke()
                            } else {
                                session.pty.awaitReadable(remaining.toInt())
                            }
                        } else {
                            session.pty.awaitReadable(1000)
                        }
                    }
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
     * Close a terminal session, killing the PTY process and its reader. The parser closes under
     * the session lock (mutually exclusive with the reader's feed/drain block), and the PTY only
     * closes once the reader coroutine has actually finished — cancellation is cooperative, so an
     * in-flight iteration may still read/write the PTY for up to its poll timeout.
     */
    fun closeSession(id: String) {
        val session = synchronized(sessionsLock) { _sessions.remove(id) } ?: return
        // If this is a relocated child being force-closed, unblock its parent with a real code before
        // the PTY dies (the parent would otherwise only get EOF → exit 0). No-op for normal sessions.
        writeRelocationBackstop(session, "E 143\n")
        val readerJob = session.readerJob
        readerJob?.cancel()
        session.onUpdate = null
        synchronized(session) { session.parser.close() }
        if (readerJob != null) {
            readerJob.invokeOnCompletion { runCatching { session.pty.close() } }
        } else {
            session.pty.close()
        }
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
        getSession(id)?.let { it.lastActivityAt = System.currentTimeMillis(); it.pty.write(text) }
    }

    /**
     * Send input bytes to a specific session.
     */
    fun sendInput(id: String, data: ByteArray) {
        getSession(id)?.let { it.lastActivityAt = System.currentTimeMillis(); it.pty.write(data) }
    }

    /** Sessions currently running a foreground program (not idle at the shell prompt): (id, program). */
    fun foregroundSessions(): List<Pair<String, String>> =
        synchronized(sessionsLock) { _sessions.values.mapNotNull { s -> s.foreground?.let { s.id to it } } }

    /** True if any session is running a foreground program (used to warn before closing). */
    fun hasForegroundProcess(): Boolean =
        synchronized(sessionsLock) { _sessions.values.any { it.foreground != null } }

    /** Parent session to refocus when the relocated child [childId] ends, or null. */
    fun relocationParentOf(childId: String): String? = relocationParents[childId]

    /** Relocated child tabs whose parent is [parentId] (for cascade-close). */
    fun childrenOf(parentId: String): List<String> =
        relocationParents.entries.filter { it.value == parentId }.map { it.key }

    /** True if [parentId] has a live relocated child — its shell is blocked waiting on the child, so
     *  it must be treated as busy (never idle-reaped, warned before close). */
    fun hasLiveRelocatedChild(parentId: String): Boolean = relocationParents.containsValue(parentId)

    /** Forget the relocation link for [childId] once the host has handled its exit. */
    fun clearRelocation(childId: String) { relocationParents.remove(childId) }

    /**
     * Spawn a temporary child tab for a relocated interactive sub-shell (OSC 7715). Clones [parentId]'s
     * distro/binds/user/arch/workdir and runs the guest launcher /tmp/.jcode-nsh-<token>.sh, linking
     * child→parent so the host refocuses the parent on the child's exit. Returns null if the token is
     * malformed, the parent is unknown, or the session cap is reached (the guest wrapper then falls
     * back to running the sub-shell inline via its watchdog timeout).
     */
    fun createNestedShellSession(parentId: String, token: String, label: String): Session? {
        if (!NSH_TOKEN_RE.matches(token)) return null
        val parent = getSession(parentId) ?: return null
        val spec = parent.spawnSpec ?: return null
        val child = createSession(
            distroId = spec.distroId,
            binds = spec.binds,
            workdir = spec.workdir,
            user = spec.user,
            shellCommand = "/bin/sh /tmp/.jcode-nsh-$token.sh",
            rootfsArch = spec.rootfsArch,
            label = label.ifBlank { "shell" }.take(24),
        ) ?: return null
        child.relocationParentId = parentId
        child.relocationFifoHost = File(rootfsManager.getRootfsPath(spec.distroId), "tmp/.jcode-nsh-$token.fifo")
        relocationParents[child.id] = parentId
        return child
    }

    /** Best-effort: unblock a relocated child's parent by writing an exit line to the shared FIFO,
     *  O_WRONLY|O_NONBLOCK so it never blocks (ENXIO = parent already gone → no-op). The parent's read
     *  also gets EOF when the child's launcher fd closes on kill; this only improves exit-code fidelity
     *  on a force-close. */
    private fun writeRelocationBackstop(session: Session, exitLine: String) {
        val fifo = session.relocationFifoHost ?: return
        runCatching {
            val fd = android.system.Os.open(
                fifo.absolutePath,
                android.system.OsConstants.O_WRONLY or android.system.OsConstants.O_NONBLOCK,
                0,
            )
            try {
                val bytes = exitLine.toByteArray()
                android.system.Os.write(fd, bytes, 0, bytes.size)
            } finally {
                android.system.Os.close(fd)
            }
        }
    }

    /**
     * Close sessions that are idle — no foreground program and no I/O for [idleMillis] — to free their
     * proot process trees and memory. Fires [onSessionExit] for each closed session so the host drops
     * its tab and releases the foreground-service hold. Returns the closed session ids.
     */
    fun reapIdle(idleMillis: Long): List<String> {
        val now = System.currentTimeMillis()
        val stale = synchronized(sessionsLock) {
            // Never reap a relocation chain: a parent blocked on its child reports foreground == null,
            // and a relocated child sitting at its own prompt does too — reaping either strands the
            // other and hangs the FIFO.
            val protected = relocationParents.keys.toSet() + relocationParents.values.toSet()
            _sessions.values
                .filter { it.foreground == null && it.id !in protected && now - it.lastActivityAt >= idleMillis }
                .map { it.id }
        }
        stale.forEach { id ->
            closeSession(id)
            onSessionExit?.invoke(id)
        }
        return stale
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
        all.forEach { session ->
            val readerJob = session.readerJob
            readerJob?.cancel()
            session.onUpdate = null
            synchronized(session) { session.parser.close() }
            if (readerJob != null) {
                readerJob.invokeOnCompletion { runCatching { session.pty.close() } }
            } else {
                session.pty.close()
            }
        }
        relocationParents.clear()
        activeSessionId = null
    }

    /** Seed the phone-friendly htop layout at [home]/.config/htop/htoprc, only when absent. */
    private fun writeDefaultHtoprc(home: File) {
        val rc = File(home, ".config/htop/htoprc")
        if (rc.exists()) return
        rc.parentFile?.mkdirs()
        rc.writeText(DEFAULT_HTOPRC)
    }
}

private val GUEST_SHELL_INTEGRATION = """# JCode shell integration (sourced via /etc/profile -> /etc/profile.d/*.sh).

# Open a file in the editor from the terminal.
# Usage: jcode <path>[:line[:col]] ...   (alias: code, when no real `code` exists)
jcode() {
  local a p
  for a in "${'$'}@"; do
    [ -z "${'$'}a" ] && continue
    case "${'$'}a" in
      /*) p="${'$'}a" ;;
      *) p="${'$'}PWD/${'$'}a" ;;
    esac
    printf '\033]7711;%s\007' "${'$'}p"
  done
}
command -v code >/dev/null 2>&1 || code() { jcode "${'$'}@"; }

# Name the terminal tab after the running program (OSC 7712); "terminal" at the prompt. Via
# BASH_ENV this also runs inside `bash run-*.sh`, so run tabs show the tool (npm/vite/dotnet),
# not the wrapper. Guarded on a tty so it stays silent when stdout is captured (e.g. $(bash -c ...)).
if [ -t 1 ]; then
  __jcode_tab() { printf '\033]7712;%s\007' "${'$'}1"; }
  __jcode_tab_cmd() {
    case "${'$'}BASH_COMMAND" in __jcode_*) return ;; esac
    local w=${'$'}{BASH_COMMAND%% *}
    __jcode_tab "${'$'}{w##*/}"
  }
  __jcode_tab_reset() { __jcode_tab terminal; }
  trap '__jcode_tab_cmd' DEBUG
  case ";${'$'}{PROMPT_COMMAND};" in
    *";__jcode_tab_reset;"*) ;;
    *) PROMPT_COMMAND="__jcode_tab_reset;${'$'}{PROMPT_COMMAND}" ;;
  esac
fi
"""

// Browser-open shim installed at /usr/local/bin/{xdg-open,open,sensible-browser,…}. Emits OSC 7714
// with the URL to the controlling terminal (so it works even if the caller redirects stdout), which
// the session reader routes to the host's web-preview / chosen browser.
private val GUEST_OPEN_URL_SHIM = """#!/bin/sh
printf '\033]7714;%s\007' "${'$'}1" >/dev/tty 2>/dev/null || printf '\033]7714;%s\007' "${'$'}1"
"""

// Shell names shadowed by the nested-shell wrapper when the feature is on. Includes `sh`: the wrapper
// inlines the heavy non-interactive `sh -c`/scripted traffic cheaply, and only relocates a bare
// interactive `sh` on the app's own PTY. A shell not present in the distro is simply skipped.
private val NESTED_SHELL_NAMES = listOf("bash", "dash", "ash", "zsh", "ksh", "mksh", "sh")

// Leading bytes of every wrapper, used to remove only our own /usr/local/bin files when the feature
// is toggled off (a distro's real shell there, if any, is left alone).
private const val NSH_MARKER = "#!/bin/sh\n# jcode-nsh-wrapper"

// Relocation tokens are interpolated into guest and host paths, so restrict them to a safe charset.
private val NSH_TOKEN_RE = Regex("[A-Za-z0-9_]+")

/** Absolute GUEST path of the real shell [name] — scans system bindirs but skips usr/local (where
 *  our wrappers live), or null when the distro ships no such shell. Baked into the wrapper at install
 *  time so the hot non-interactive path costs no runtime PATH scan. */
private fun resolveGuestShell(rootfsPath: File, name: String): String? {
    for (dir in listOf("usr/bin", "bin", "usr/sbin", "sbin")) {
        val f = File(rootfsPath, "$dir/$name")
        if (f.exists() && !f.isDirectory) return "/$dir/$name"
    }
    return null
}

/** The wrapper script for interactive shell [name] with the absolute [realPath] baked in. */
private fun nestedShellWrapper(realPath: String, name: String): String =
    NESTED_SHELL_WRAPPER_TEMPLATE.replace("__REAL__", realPath).replace("__SELF__", name)

// Nested-shell wrapper (OSC 7715). Detects a bare interactive sub-shell on the app's own PTY and asks
// the host to relocate it into its own temporary tab; anything else (pipe/redirect/`-c`/script, or a
// PTY the app doesn't own like tmux/ssh) execs the real shell inline. The parent shell then blocks on
// a FIFO until the child exits (or a 1.5s watchdog proves nobody's listening → inline fallback).
private val NESTED_SHELL_WRAPPER_TEMPLATE = """#!/bin/sh
# jcode-nsh-wrapper v1 — relocate an interactive sub-shell into its own tab (OSC 7715).
self=__SELF__
real='__REAL__'; [ -x "${'$'}real" ] || real="/bin/${'$'}self"

# One-shot guard: the app marks a tab's own top shell (JCODE_NSH_TOP) so it never relocates itself;
# clearing it here lets that shell's own interactive sub-shells still relocate.
[ -n "${'$'}{JCODE_NSH_TOP:-}" ] && { unset JCODE_NSH_TOP; exec "${'$'}real" "${'$'}@"; }
# Not a real terminal on both ends (pipe / ${'$'}(...) / redirect / cron) => run inline.
[ -t 0 ] && [ -t 1 ] || exec "${'$'}real" "${'$'}@"
# Explicit per-invocation opt-out.
[ -n "${'$'}{JCODE_NSH_INLINE:-}" ] && exec "${'$'}real" "${'$'}@"

# Relocate only a bare interactive shell: bail to inline on -c, a script operand, or an operand after --.
relocate=1; want_arg=0; ddash=0
for a in "${'$'}@"; do
  if [ "${'$'}ddash" = 1 ]; then relocate=0; break; fi
  if [ "${'$'}want_arg" = 1 ]; then want_arg=0; continue; fi
  case "${'$'}a" in
    --) ddash=1 ;;
    -c|--command) relocate=0; break ;;
    --rcfile|--init-file|-O|+O) want_arg=1 ;;
    -) : ;;
    --*) : ;;
    -*) case "${'$'}a" in *c*) relocate=0; break ;; esac ;;
    *) relocate=0; break ;;
  esac
done
[ "${'$'}relocate" = 1 ] || exec "${'$'}real" "${'$'}@"

# --- relocate: hand this interactive shell to a new tab -----------------------------------------
tok=${'$'}(tr -dc 'a-f0-9' < /proc/sys/kernel/random/uuid 2>/dev/null)
[ -n "${'$'}tok" ] || tok="${'$'}${'$'}_${'$'}(date +%s 2>/dev/null)"
d=${'$'}{TMPDIR:-/tmp}
fifo="${'$'}d/.jcode-nsh-${'$'}tok.fifo"
launcher="${'$'}d/.jcode-nsh-${'$'}tok.sh"
abort="${'$'}d/.jcode-nsh-${'$'}tok.abort"
label=${'$'}self
cwd=${'$'}(pwd)
trap 'rm -f "${'$'}fifo" "${'$'}launcher" "${'$'}abort" 2>/dev/null' EXIT

esc() { printf '%s' "${'$'}1" | sed "s/'/'\\''/g"; }
qcwd=${'$'}(esc "${'$'}cwd")
qargs=; for a in "${'$'}@"; do qargs="${'$'}qargs '${'$'}(esc "${'$'}a")'"; done

rm -f "${'$'}fifo" "${'$'}launcher" 2>/dev/null
mkfifo -m 600 "${'$'}fifo" 2>/dev/null || exec "${'$'}real" "${'$'}@"

# Generate the launcher the app runs in the NEW tab. printf keeps ${'$'}/`/% literal; the real shell
# runs with fd4 closed (won't inherit the fifo), then we report its exit code back to the parent.
{
  printf '#!/bin/sh\n'
  printf "[ -e '%s' ] && exit 0\n" "${'$'}abort"
  printf "cd '%s' 2>/dev/null || true\n" "${'$'}qcwd"
  printf "exec 4> '%s'\n" "${'$'}fifo"
  printf 'printf %s >&4\n' "'A\n'"
  printf "trap '' HUP\n"
  printf "'%s'%s 4>&-\n" "${'$'}real" "${'$'}qargs"
  printf 'printf %s "${'$'}?" >&4\n' "'E %s\n'"
  printf "rm -f '%s'\n" "${'$'}launcher"
} > "${'$'}launcher"

# Watchdog: if no ACK within 3s nobody is listening (tmux/ssh PTY, or app declined) => set the abort
# marker (so a slow-spawning launcher exits instead of blocking) and fall back to inline.
( sleep 3; : > "${'$'}abort" 2>/dev/null; printf 'T\n' > "${'$'}fifo" 2>/dev/null ) &
wd=${'$'}!
# Parent tab is now inert; a stray Ctrl-C/Ctrl-Z there must not kill us and orphan the child tab.
trap '' INT QUIT TSTP
seq='\033]7715;open;'"${'$'}tok"';'"${'$'}label"'\007'
printf "${'$'}seq" > /dev/tty 2>/dev/null || printf "${'$'}seq"

exec 3< "${'$'}fifo"
IFS= read -r verdict <&3
case "${'$'}verdict" in
  A*)
    kill "${'$'}wd" 2>/dev/null
    IFS= read -r result <&3
    exec 3<&-; trap - INT QUIT TSTP
    case "${'$'}result" in E\ *) exit "${'$'}{result#E }";; *) exit 0;; esac ;;
  *)
    : > "${'$'}abort" 2>/dev/null
    kill "${'$'}wd" 2>/dev/null
    exec 3<&-; trap - INT QUIT TSTP
    exec "${'$'}real" "${'$'}@" ;;
esac
"""

// Default htop config seeded into a fresh $HOME/.config/htop/htoprc. A compact, phone-width column
// set (PID/CPU%/MEM%/Command) so process NAMES are visible instead of being pushed off-screen by
// htop's desktop-width default, with userland threads hidden so a multi-threaded process (e.g. the
// JCode app's own JVM, ~40 threads) shows as one row. htop rewrites this file when the user changes
// settings, so it only shapes the first run and never overrides a deliberate choice.
private val DEFAULT_HTOPRC = """# Beware! This file is rewritten by htop when settings are changed in the interface.
# The parser is also very primitive, and not human-friendly.
htop_version=3.4.1
config_reader_min_version=3
fields=0 46 47 1
hide_kernel_threads=1
hide_userland_threads=1
shadow_other_users=0
show_thread_names=0
show_program_path=0
highlight_base_name=1
highlight_megabytes=1
highlight_threads=1
find_comm_in_cmdline=1
strip_exe_from_cmdline=1
show_merged_command=0
header_margin=1
screen_tabs=1
show_cpu_usage=1
show_cpu_frequency=0
show_cpu_temperature=0
show_cached_memory=1
color_scheme=0
enable_mouse=1
delay=15
hide_function_bar=0
header_layout=two_50_50
column_meters_0=LeftCPUs Memory Swap
column_meter_modes_0=1 1 1
column_meters_1=RightCPUs Tasks LoadAverage Uptime
column_meter_modes_1=1 2 2 2
tree_view=0
sort_key=46
sort_direction=-1
screen:Main=PID PERCENT_CPU PERCENT_MEM Command
.sort_key=PERCENT_CPU
.sort_direction=-1
screen:I/O=PID IO_RATE Command
.sort_key=IO_RATE
.sort_direction=-1
"""

