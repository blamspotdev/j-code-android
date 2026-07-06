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
    }

    
    private val appContext = context.applicationContext
    
    /** Directory where proot and its libraries are extracted */
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

    /** Absolute path to the lib directory (for LD_LIBRARY_PATH) */
    val libtallocPath: String
        get() = libtallocDir.absolutePath

    /** Absolute path to the proot temp directory (for PROOT_TMP_DIR) */
    val prootTmpPath: String
        get() = prootTmpDir.absolutePath

    /**
     * Ensure the proot temp directory exists and is writable.
     * Called before every proot invocation to handle cases where
     * the directory was cleared or never created.
     */
    fun ensureProotTmpDir(): Boolean {
        return try {
            // Use app's own filesDir which is always writable
            val created = prootTmpDir.mkdirs()
            
            // Verify it exists and is writable
            val exists = prootTmpDir.exists()
            val canWrite = prootTmpDir.canWrite()
            
            android.util.Log.d("ProotManager", "ensureProotTmpDir: path=${prootTmpDir.absolutePath}, created=$created, exists=$exists, canWrite=$canWrite")
            
            exists && canWrite
        } catch (e: Exception) {
            android.util.Log.e("ProotManager", "ensureProotTmpDir: failed", e)
            false
        }
    }

    /** Path to the extracted libtalloc shared library (soname) */
    private val libtallocBinary: File
        get() = File(libtallocDir, "libtalloc.so.2")

    /** Path to libandroid-shmem (NEEDED by the proot binary for SysV shared memory). */
    private val shmemBinary: File
        get() = File(libtallocDir, "libandroid-shmem.so")

    /** Path to the extracted proot binary */
    val prootBinary: File
        get() = File(prootDir, "proot")

    /** proot's helper loader (maps guest ELFs); referenced via the PROOT_LOADER env var. */
    val loaderBinary: File
        get() = File(prootDir, "loader")

    /** proot's 32-bit helper loader; referenced via PROOT_LOADER_32. */
    val loader32Binary: File
        get() = File(prootDir, "loader32")

    /**
     * Whether proot has been fully extracted. Requires the loader too: an older install that only
     * has proot+libtalloc (no loader) is treated as not-installed so it re-extracts the full set.
     */
    val isProotInstalled: Boolean
        get() = prootBinary.exists() && prootBinary.canExecute() &&
            libtallocBinary.exists() && loaderBinary.exists() && loaderBinary.canExecute()
    
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
     * Extract the proot binary and libtalloc from assets to app-private storage.
     * Safe to call multiple times - skips if already extracted and valid.
     */
    suspend fun ensureProotInstalled(): Boolean {
        // Check if already installed
        if (isProotInstalled) return true
        
        val abi = detectAbi()
        android.util.Log.d("ProotManager", "ensureProotInstalled: abi=$abi")
        
        return try {
            prootDir.mkdirs()

            // Extract proot binary (dynamically linked, requires libtalloc)
            val prootAsset = "bin/proot-$abi"
            android.util.Log.d("ProotManager", "ensureProotInstalled: extracting $prootAsset")
            appContext.assets.open(prootAsset).use { input ->
                FileOutputStream(prootBinary).use { output ->
                    input.copyTo(output)
                }
            }
            prootBinary.setExecutable(true, false)
            prootBinary.setReadable(true, false)

            // Extract libtalloc shared library
            libtallocDir.mkdirs()
            val libtallocAsset = "bin/libtalloc-$abi.so"
            android.util.Log.d("ProotManager", "ensureProotInstalled: extracting $libtallocAsset")
            appContext.assets.open(libtallocAsset).use { input ->
                FileOutputStream(libtallocBinary).use { output ->
                    input.copyTo(output)
                }
            }
            libtallocBinary.setReadable(true, false)
            libtallocBinary.setExecutable(true, false)

            // Extract libandroid-shmem (NEEDED by the proot binary for SysV shared memory).
            val shmemAsset = "bin/libandroid-shmem-$abi.so"
            android.util.Log.d("ProotManager", "ensureProotInstalled: extracting $shmemAsset")
            appContext.assets.open(shmemAsset).use { input ->
                FileOutputStream(shmemBinary).use { output -> input.copyTo(output) }
            }
            shmemBinary.setReadable(true, false)
            shmemBinary.setExecutable(true, false)

            // Extract proot's helper loaders. proot cannot map any guest ELF without these
            // (referenced via PROOT_LOADER / PROOT_LOADER_32); without them every guest execve
            // fails with ENOENT.
            val loaderAsset = "bin/loader-$abi"
            android.util.Log.d("ProotManager", "ensureProotInstalled: extracting $loaderAsset")
            appContext.assets.open(loaderAsset).use { input ->
                FileOutputStream(loaderBinary).use { output -> input.copyTo(output) }
            }
            loaderBinary.setExecutable(true, false)
            loaderBinary.setReadable(true, false)

            val loader32Asset = "bin/loader32-$abi"
            appContext.assets.open(loader32Asset).use { input ->
                FileOutputStream(loader32Binary).use { output -> input.copyTo(output) }
            }
            loader32Binary.setExecutable(true, false)
            loader32Binary.setReadable(true, false)

            // Create proot temp directory (for glue rootfs, f2fs probe, etc.)
            prootTmpDir.mkdirs()

            android.util.Log.d("ProotManager", "ensureProotInstalled: prootBinary.exists=${prootBinary.exists()}, canExecute=${prootBinary.canExecute()}")
            isProotInstalled
        } catch (e: Exception) {
            android.util.Log.e("ProotManager", "ensureProotInstalled: failed", e)
            false
        }
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
        noSeccomp: Boolean = false,
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

        // Shared transfer dir for the extension `file.import` bridge: SAF-picked files are stream-copied
        // to this host dir by the app so extensions can reach them by a runtime path (/jcode-transfer)
        // and stream them onward (e.g. scp a .bak into a DB VM) without a base64 round-trip. Bound only
        // when it exists/creatable on the host so this stays a no-op on devices without the folder.
        val transferDir = File("/storage/emulated/0/JCode/.jcode-transfer")
        if (transferDir.exists() || transferDir.mkdirs()) {
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
        noSeccomp: Boolean = false,
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
            noSeccomp = noSeccomp,
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
        noSeccomp: Boolean = false,
    ): List<String> {
        return buildProotCommand(
            rootfsPath = rootfsPath,
            command = if (user == "root") listOf("/bin/sh", "-l") else listOf("su", "-", user),
            binds = binds,
            workdir = workdir,
            rootfsArch = rootfsArch,
            noSeccomp = noSeccomp,
        )
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
