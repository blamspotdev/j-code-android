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
            val oscHandler: (Int, String) -> Unit = { code, payload ->
                when (code) {
                    7711 -> onOpenFileRequest?.invoke(payload.trim())
                    7712 -> {
                        val title = payload.trim()
                        // "terminal" (or empty) = back at the prompt with no foreground program.
                        session.foreground = title.takeUnless { it.isEmpty() || it == "terminal" }
                        onTitleChange?.invoke(session.id, title)
                    }
                    7713 -> onTaskComplete?.invoke(session.id, payload.trim())
                    7714 -> onOpenUrlRequest?.invoke(payload.trim())
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
                        // so no per-chunk copy), then collect the shell-integration OSC events the
                        // parser queued from this chunk and dispatch them outside the lock.
                        val oscEvents = synchronized(session) {
                            session.parser.feed(buffer, n)
                            session.parser.drainOsc()
                        }
                        oscEvents.forEach { (code, payload) -> oscHandler(code, payload) }
                        onOutput?.invoke(session.id, buffer, n)
                        session.onUpdate?.invoke()
                    }
                    n < 0 -> { exited = true; break }   // EOF: the shell/process exited
                    // No data yet: park in the kernel until output arrives (readerScope is
                    // Dispatchers.IO, so blocking is fine). The 1s timeout bounds how long a
                    // cancelled/closed session's reader can linger before it re-checks isActive.
                    else -> session.pty.awaitReadable(1000)
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

    /**
     * Close sessions that are idle — no foreground program and no I/O for [idleMillis] — to free their
     * proot process trees and memory. Fires [onSessionExit] for each closed session so the host drops
     * its tab and releases the foreground-service hold. Returns the closed session ids.
     */
    fun reapIdle(idleMillis: Long): List<String> {
        val now = System.currentTimeMillis()
        val stale = synchronized(sessionsLock) {
            _sessions.values
                .filter { it.foreground == null && now - it.lastActivityAt >= idleMillis }
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
        all.forEach {
            it.readerJob?.cancel()
            it.onUpdate = null
            it.parser.close()
            it.pty.close()
        }
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

private val GUEST_SHELL_INTEGRATION = """# J Code shell integration (sourced via /etc/profile -> /etc/profile.d/*.sh).

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

