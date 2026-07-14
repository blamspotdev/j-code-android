package dev.jcode.core.distro

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream

/**
 * Manages the bundled proot binary and builds proot command lines
 * for spawning Linux environment sessions.
 */
class ProotManager(private val context: Context) {
    companion object {
        private const val REPORTED_KERNEL_RELEASE = "6.1.0"

        // Bump when the bundled support-lib assets (libtalloc, libandroid-shmem) change, so
        // existing installs re-extract them on the next runtime prep. v2 = memfd libandroid-shmem
        // (see native/proot/libandroid-shmem/README.md).
        private const val SUPPORT_LIBS_VERSION = 2
    }

    
    private val appContext = context.applicationContext
    
    /** Directory where proot's support libraries are extracted */
    private val prootDir: File
        get() = File(appContext.filesDir, "bin/proot")

    /** Directory where libtalloc.so is extracted */
    private val libtallocDir: File
        get() = File(appContext.filesDir, "bin/proot/lib")

    /** Directory where qemu user-mode emulators are extracted */
    private val qemuDir: File
        get() = File(appContext.filesDir, "bin/proot/qemu")

    /** Directory where proot creates temporary files (glue rootfs, etc.) */
    private val prootTmpDir: File
        get() = File(appContext.filesDir, "tmp/proot")

    /** Directory holding synthetic /proc files bound over the (SELinux-blocked) real ones. */
    private val fakeProcDir: File
        get() = File(appContext.filesDir, "tmp/proot-fakeproc")

    /** Absolute path to the lib directory (for LD_LIBRARY_PATH) */
    val libtallocPath: String
        get() = libtallocDir.absolutePath

    /** Absolute path to the proot temp directory (for PROOT_TMP_DIR) */
    val prootTmpPath: String
        get() = prootTmpDir.absolutePath

    @Volatile
    private var runtimePrepared = false

    @Volatile
    private var transferDirReady = false

    /**
     * Ensure the proot temp directory exists and is writable. Called before every proot
     * invocation; after the first successful prep it costs a single stat (recovers with a full
     * re-prep if the directory was cleared). The one-time prep also deletes proot/loader
     * binaries extracted by pre-jniLibs app versions — dead weight that W^X forbids exec'ing —
     * and re-extracts the support libs when an app update shipped newer ones.
     */
    fun ensureProotTmpDir(): Boolean {
        if (runtimePrepared && prootTmpDir.isDirectory) return true
        return try {
            listOf("proot", "loader", "loader32").forEach { File(prootDir, it).delete() }
            if (!supportLibsFresh()) extractSupportLibs()
            prootTmpDir.mkdirs()
            val ok = prootTmpDir.exists() && prootTmpDir.canWrite()
            if (!ok) {
                android.util.Log.e("ProotManager", "ensureProotTmpDir: ${prootTmpDir.absolutePath} missing or not writable")
            }
            runtimePrepared = ok
            ok
        } catch (e: Exception) {
            android.util.Log.e("ProotManager", "ensureProotTmpDir: failed", e)
            false
        }
    }

    /**
     * Android's SELinux policy denies app processes read access to several system-wide procfs files
     * (/proc/stat, /proc/loadavg, /proc/uptime, /proc/version); proot's fake-root can't lift a
     * kernel-level denial, so tools that need them — htop, top, uptime, vmstat — abort with
     * "Cannot open /proc/stat: Permission denied". Mirror proot-distro/Termux: generate small
     * synthetic versions on the host and bind them over the real entries (see [buildProotCommand]).
     * Memory (/proc/meminfo) and per-process files stay real (they ARE readable), so htop shows real
     * RAM + the real process tree; only the CPU/load figures are static placeholders — the live tick
     * data is exactly what Android withholds. Distro-agnostic (the denial is host-side, not per-rootfs).
     * Idempotent; regenerated only when a file is missing. Returns the dir, or null on failure.
     */
    fun ensureFakeProcFiles(): File? {
        val dir = fakeProcDir
        return try {
            val cores = Runtime.getRuntime().availableProcessors().coerceIn(1, 4096)
            val stat = buildString {
                append("cpu  0 0 0 0 0 0 0 0 0 0\n")
                for (i in 0 until cores) append("cpu$i 0 0 0 0 0 0 0 0 0 0\n")
                append("intr 0\nctxt 0\nbtime 1893456000\nprocesses 1\n")
                append("procs_running 1\nprocs_blocked 0\n")
                append("softirq 0 0 0 0 0 0 0 0 0 0 0\n")
            }
            val files = mapOf(
                "stat" to stat,
                "loadavg" to "0.00 0.00 0.00 1/1 1\n",
                "uptime" to "0.00 0.00\n",
                "version" to "Linux version $REPORTED_KERNEL_RELEASE (jcode@localhost) " +
                    "(gcc) #1 SMP PREEMPT\n",
            )
            if (files.keys.any { !File(dir, it).exists() }) {
                dir.mkdirs()
                files.forEach { (name, content) ->
                    File(dir, name).apply {
                        writeText(content)
                        setReadable(true, false)
                    }
                }
            }
            // Take /proc/stat + /proc/loadavg live: the (process-wide) sampler rewrites them from real
            // per-core cpuidle deltas so htop/top show true utilization instead of the static baseline.
            CpuStatSampler.shared(dir).ensureRunning()
            dir.takeIf { File(it, "stat").exists() }
        } catch (e: Exception) {
            android.util.Log.e("ProotManager", "ensureFakeProcFiles: failed", e)
            null
        }
    }

    /** Path to the extracted libtalloc shared library (soname) */
    private val libtallocBinary: File
        get() = File(libtallocDir, "libtalloc.so.2")

    /** Path to libandroid-shmem (NEEDED by the proot binary; backs the --sysvipc extension). */
    private val shmemBinary: File
        get() = File(libtallocDir, "libandroid-shmem.so")

    /** Marker recording which SUPPORT_LIBS_VERSION the extracted libs came from. */
    private val supportLibsMarker: File
        get() = File(libtallocDir, ".version")

    private fun supportLibsFresh(): Boolean =
        libtallocBinary.exists() && shmemBinary.exists() &&
            runCatching { supportLibsMarker.readText().trim() }.getOrNull() == SUPPORT_LIBS_VERSION.toString()

    private fun extractSupportLibs(): Boolean = try {
        val abi = detectAbi()
        libtallocDir.mkdirs()
        listOf(
            "bin/libtalloc-$abi.so" to libtallocBinary,
            "bin/libandroid-shmem-$abi.so" to shmemBinary,
        ).forEach { (asset, target) ->
            android.util.Log.d("ProotManager", "extractSupportLibs: extracting $asset")
            appContext.assets.open(asset).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            target.setReadable(true, false)
            target.setExecutable(true, false)
        }
        supportLibsMarker.writeText(SUPPORT_LIBS_VERSION.toString())
        true
    } catch (e: Exception) {
        android.util.Log.e("ProotManager", "extractSupportLibs: failed", e)
        false
    }

    /**
     * proot and its loaders ship as jniLibs and are exec'd from the app's native library dir:
     * at targetSdk >= 29 SELinux (W^X) denies execve() on anything under filesDir, and the
     * native library dir is the only app-owned location exec is still allowed from. The
     * mmap-only libraries (libtalloc, libandroid-shmem) stay asset-extracted under filesDir,
     * which W^X permits.
     */
    private val nativeLibDir: File
        get() = File(appContext.applicationInfo.nativeLibraryDir)

    /** Path to the proot binary (a jniLib, exec'd in place). */
    val prootBinary: File
        get() = File(nativeLibDir, "libproot.so")

    /** proot's helper loader (maps guest ELFs); referenced via the PROOT_LOADER env var. */
    val loaderBinary: File
        get() = File(nativeLibDir, "libproot-loader.so")

    /** proot's 32-bit helper loader; referenced via PROOT_LOADER_32. */
    val loader32Binary: File
        get() = File(nativeLibDir, "libproot-loader32.so")

    /**
     * Whether the proot runtime is usable: jniLib binaries present + support libs extracted.
     * Deliberately NOT gated on [supportLibsFresh]: outdated-but-present support libs still mean
     * "installed" (otherwise upgraded installs would bounce back to setup); they are refreshed
     * by the pre-spawn prep in [ensureProotTmpDir].
     */
    val isProotInstalled: Boolean
        get() = prootBinary.exists() && prootBinary.canExecute() &&
            loaderBinary.exists() && loaderBinary.canExecute() &&
            libtallocBinary.exists() && shmemBinary.exists()
    
    /**
     * Detect the current device's primary ABI.
     */
    private fun detectAbi(): String {
        val supportedAbis = Build.SUPPORTED_ABIS
        return when {
            supportedAbis.any { it.contains("arm64") || it.contains("aarch64") } -> "arm64-v8a"
            supportedAbis.any { it.contains("x86_64") } -> "x86_64"
            supportedAbis.any { it.contains("armeabi") } -> "armeabi-v7a"
            else -> supportedAbis.firstOrNull() ?: "arm64-v8a"
        }
    }

    /** The architecture of the host device. */
    fun hostArch(): Arch = Arch.host()

    /**
     * Whether running a rootfs of [rootfsArch] requires QEMU user-mode emulation
     * (i.e. the rootfs targets a different CPU arch than the host device).
     */
    fun needsQemu(rootfsArch: Arch): Boolean = rootfsArch != hostArch()

    /** Path to the extracted qemu user-mode emulator that emulates [rootfsArch]. */
    fun qemuBinaryFor(rootfsArch: Arch): File = File(qemuDir, rootfsArch.qemuUserBinary)

    /** Whether the qemu emulator for [rootfsArch] has been extracted and is runnable. */
    fun isQemuInstalled(rootfsArch: Arch): Boolean =
        qemuBinaryFor(rootfsArch).let { it.exists() && it.canExecute() }

    /**
     * Extract the qemu user-mode emulator for [rootfsArch] from assets to app-private storage.
     *
     * The binary is shipped at `assets/bin/<qemuUserBinary>` (e.g. `bin/qemu-x86_64`). It must be a
     * STATIC build so it has no host library dependencies; the only LD_LIBRARY_PATH in play is
     * proot's libtalloc. Safe to call repeatedly. Returns true if the emulator is present afterwards.
     *
     * NOTE: shipping/sourcing a working static `qemu-x86_64` for Android arm64 is an external task —
     * see the migration plan. Until the asset exists this returns false and emulated environments
     * cannot start; native (same-arch) environments are unaffected.
     *
     * W^X WARNING (targetSdk >= 29): proot execve()s the `--qemu` binary on the host, so an
     * asset extracted to filesDir can no longer be exec'd. At qemu bring-up, ship it as a jniLib
     * (e.g. libqemu-x86_64.so) and point this at nativeLibraryDir, like proot itself.
     */
    suspend fun ensureQemuInstalled(rootfsArch: Arch): Boolean {
        if (isQemuInstalled(rootfsArch)) return true
        val asset = "bin/${rootfsArch.qemuUserBinary}"
        return try {
            qemuDir.mkdirs()
            val target = qemuBinaryFor(rootfsArch)
            appContext.assets.open(asset).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            target.setExecutable(true, false)
            target.setReadable(true, false)
            isQemuInstalled(rootfsArch)
        } catch (e: Exception) {
            android.util.Log.e("ProotManager", "ensureQemuInstalled($rootfsArch): failed", e)
            false
        }
    }

    /**
     * Build the process environment (`KEY=VALUE` list) for invoking proot against [rootfsArch].
     * Callers (DistroService, TerminalSessionManager) should use this so the QEMU/seccomp env stays
     * in one place.
     */
    fun runtimeEnv(rootfsArch: Arch): List<String> {
        val env = mutableListOf(
            "LD_LIBRARY_PATH=$libtallocPath",
            "PROOT_TMP_DIR=$prootTmpPath",
            "PROOT_LOADER=${loaderBinary.absolutePath}",
            "PROOT_LOADER_32=${loader32Binary.absolutePath}",
        )
        if (needsQemu(rootfsArch)) {
            // proot's seccomp acceleration can conflict with qemu emulation on some kernels
            // (hangs / "bad system call"). Fall back to pure ptrace under emulation.
            env.add("PROOT_NO_SECCOMP=1")
        }
        return env
    }
    
    /**
     * Extract proot's support libraries (libtalloc, libandroid-shmem) from assets to
     * app-private storage. The exec'able binaries (proot + loaders) need no extraction —
     * they ship as jniLibs. Safe to call multiple times - skips if already extracted and valid.
     */
    suspend fun ensureProotInstalled(): Boolean {
        if (isProotInstalled) return true
        extractSupportLibs()
        // Create proot temp directory (for glue rootfs, f2fs probe, etc.)
        prootTmpDir.mkdirs()
        android.util.Log.d("ProotManager", "ensureProotInstalled: prootBinary.exists=${prootBinary.exists()}, canExecute=${prootBinary.canExecute()}")
        return isProotInstalled
    }
    
    /**
     * Build a proot command line for spawning a shell or running a command.
     */
    fun buildProotCommand(
        rootfsPath: File,
        command: List<String>,
        binds: List<DistroBind> = emptyList(),
        workdir: String = DEFAULT_DISTRO_WORKDIR,
        rootfsArch: Arch = Arch.ARM64,
    ): List<String> {
        // Ensure temp directory exists before building command
        ensureProotTmpDir()

        val args = mutableListOf<String>()

        // proot binary path
        args.add(prootBinary.absolutePath)

        // Foreign-arch rootfs: emulate guest binaries with qemu user-mode.
        // When rootfsArch == host this is skipped and binaries run natively (fast path).
        //
        // NOTE: the bundled proot fork uses a bare `-q` for *quiet* (see below), so the qemu flag
        // here is the long form `--qemu=<path>`. The exact spelling must be confirmed on-device via
        // `proot --help`; finalize during qemu bring-up. This branch is inert until a qemu binary is
        // shipped, so it cannot affect the existing native arm64 path.
        if (needsQemu(rootfsArch)) {
            args.add("--qemu=${qemuBinaryFor(rootfsArch).absolutePath}")
        }

        // Root directory
        args.addAll(listOf("-r", rootfsPath.absolutePath))
        
        // Bind mounts
        for (bind in binds) {
            args.addAll(listOf("-b", "${bind.host}:${bind.target}"))
        }
        
        // Common bind mounts for Android compatibility
        args.addAll(listOf("-b", "/dev"))
        args.addAll(listOf("-b", "/proc"))
        args.addAll(listOf("-b", "/sys"))

        // Overlay synthetic /proc/{stat,loadavg,uptime,version} — Android's SELinux sandbox denies the
        // real ones to app processes, which makes htop/top/uptime abort ("Cannot open /proc/stat").
        // Declared AFTER `-b /proc` so the more-specific file bindings win. Real /proc/meminfo and the
        // per-process files stay live, so only CPU/load readings are placeholders. See ensureFakeProcFiles.
        ensureFakeProcFiles()?.let { fp ->
            for (name in listOf("stat", "loadavg", "uptime", "version")) {
                args.addAll(listOf("-b", "${File(fp, name).absolutePath}:/proc/$name"))
            }
        }

        // Shared transfer dir for the extension `file.import` bridge: SAF-picked files are stream-copied
        // to this host dir by the app so extensions can reach them by a runtime path (/jcode-transfer)
        // and stream them onward (e.g. scp a .bak into a DB VM) without a base64 round-trip. Bound only
        // when it exists/creatable on the host so this stays a no-op on devices without the folder
        // (e.g. All-files access not granted). Once available it can't silently vanish mid-run, so a
        // positive result is cached to skip the per-spawn stat on (slow) emulated storage.
        val transferDir = File("/storage/emulated/0/JCode/.jcode-transfer")
        if (transferDirReady || transferDir.exists() || transferDir.mkdirs()) {
            transferDirReady = true
            args.addAll(listOf("-b", "${transferDir.absolutePath}:/jcode-transfer"))
        }

        // Working directory
        args.addAll(listOf("-w", workdir))
        
        // Run as root (UID 0) inside proot - needed for apt, etc.
        args.add("-0")

        // Emulate hard-links as symlinks. dpkg/apt atomically back up their database by hard-linking
        // (/var/lib/dpkg/status -> status-old); many Android kernels/filesystems reject link() in the
        // app data dir (EPERM/EACCES), so without this dpkg dies with "error creating new backup file
        // '/var/lib/dpkg/status-old': Permission denied" — and every later apt/dpkg (incl. the
        // `dpkg --configure -a` self-heal) then fails too. Device-dependent: seen on some Android 13
        // devices, not others. Standard proot option (Termux/proot-distro enable it by default);
        // required for apt/dpkg to work uniformly across devices.
        args.add("--link2symlink")

        // Emulate SysV IPC (shmget/shmat/...) for guests. Android kernels ship with
        // CONFIG_SYSVIPC off, so without this extension those syscalls return ENOSYS; with it,
        // proot backs segments via the bundled memfd libandroid-shmem
        // (native/proot/libandroid-shmem/README.md). Device-verified: single- and
        // cross-process shm attach both work.
        args.add("--sysvipc")

        // Report a modern kernel release to reduce false "unknown syscall" warnings.
        args.addAll(listOf("-k", REPORTED_KERNEL_RELEASE))

        // Quiet proot's own diagnostics. NOTE: this proot build uses -q / --qemu for QEMU
        // emulation (the flag takes an argument), so a bare -q would swallow the next token and
        // break the command. Use --verbose=-1 to silence instead.
        args.add("--verbose=-1")

        // Kill the whole guest process tree when the top-level command exits or proot is terminated.
        // Without this, tearing down only the proot launcher (PtyProcess.close / Process.destroy)
        // orphans its descendants (e.g. a debug adapter's python3), leaking proot trees on every
        // close. Requires a graceful signal (SIGTERM) so proot can run its cleanup.
        args.add("--kill-on-exit")

        // This proot build does NOT accept `--` as an option/command terminator (it reports
        // "unknown option '--'"). proot stops parsing options at the first non-option token, so the
        // command (a path like /bin/sh) is passed directly; its own args follow.
        if (command.isNotEmpty()) {
            args.addAll(command)
        }
        
        return args
    }
    
    /**
     * Build a proot command for running a shell command as the jcode user.
     */
    fun buildShellCommand(
        rootfsPath: File,
        shellCommand: String,
        binds: List<DistroBind> = emptyList(),
        env: Map<String, String> = emptyMap(),
        workdir: String = DEFAULT_DISTRO_WORKDIR,
        user: String = DEFAULT_DISTRO_USER,
        rootfsArch: Arch = Arch.ARM64,
    ): List<String> {
        val defaultEnv = if (user == "root") {
            mapOf(
                "TERM" to "xterm-256color",
                "COLORTERM" to "truecolor",
                "HOME" to "/root",
                "USER" to "root",
                "JCODE" to "1",
                "LANG" to "en_US.UTF-8",
                // Override the Android app's inherited TMPDIR (an app-private host path like
                // /data/user/0/<pkg>/cache) which does not exist inside the guest — leaking it breaks
                // any tool that creates temp files (e.g. .NET/MSBuild fails GetTempPath with ENOENT).
                "TMPDIR" to "/tmp",
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            )
        } else {
            mapOf(
                "TERM" to "xterm-256color",
                "COLORTERM" to "truecolor",
                "HOME" to "/home/$user",
                "USER" to user,
                "JCODE" to "1",
                "LANG" to "en_US.UTF-8",
                "TMPDIR" to "/tmp",
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            )
        }
        val mergedEnv = defaultEnv + env
        val envExports = mergedEnv.toSortedMap().entries.joinToString("; ") { (key, value) ->
            "export $key=${shellQuote(value)}"
        }
        val execution = if (user == "root") {
            "exec /bin/sh -c ${shellQuote(shellCommand)}"
        } else {
            "exec su - ${shellQuote(user)} -c ${shellQuote(shellCommand)}"
        }
        return buildProotCommand(
            rootfsPath = rootfsPath,
            command = listOf("/bin/sh", "-c", "$envExports; $execution"),
            binds = binds,
            workdir = workdir,
            rootfsArch = rootfsArch,
        )
    }
    
    /**
     * Build a proot command for an interactive login shell.
     */
    fun buildInteractiveShell(
        rootfsPath: File,
        binds: List<DistroBind> = emptyList(),
        env: Map<String, String> = emptyMap(),
        workdir: String = DEFAULT_DISTRO_WORKDIR,
        user: String = DEFAULT_DISTRO_USER,
        rootfsArch: Arch = Arch.ARM64,
    ): List<String> {
        return buildProotCommand(
            rootfsPath = rootfsPath,
            command = if (user == "root") listOf("/bin/sh", "-l") else listOf("su", "-", user),
            binds = binds,
            workdir = workdir,
            rootfsArch = rootfsArch,
        )
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
