package dev.jcode.core.distro

import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * The app's own Linux processes, as Android counts them.
 *
 * Android mounts `/proc` with hidepid for apps, so listing it yields exactly this app's tree: the app
 * process plus every proot/distro process it forked. Everything past the first is what the platform
 * calls a *phantom* process, and ActivityManager kills them once the total passes
 * `max_phantom_processes` — 32 unless the device owner raised it. That trim takes proot and the whole
 * distro with it while the app itself survives, which is why a long-running toolchain, build or agent
 * session can have its terminal die out from under it.
 */
object AppProcesses {
    /** One app-owned process. [rssKb] is resident memory, which is what makes a tree worth killing. */
    data class Process(val pid: Int, val name: String, val rssKb: Long)

    /** The default `activity_manager/max_phantom_processes`, i.e. how many forked processes fit. */
    const val DEFAULT_PHANTOM_LIMIT = 32

    /**
     * Shell commands that lift the limit. The app cannot apply these itself — `max_phantom_processes`
     * lives in DeviceConfig, writable only with a signature permission — so they are surfaced for the
     * user to run over adb. `set_sync_disabled_for_tests` keeps a config sync from resetting it.
     */
    const val RAISE_LIMIT_COMMANDS =
        "adb shell device_config set_sync_disabled_for_tests persistent\n" +
            "adb shell device_config put activity_manager max_phantom_processes 2147483647"

    /** How many processes this app currently owns, or null when `/proc` cannot be read. */
    fun count(): Int? = runCatching { ownProcDirs().size }.getOrNull()?.takeIf { it > 0 }

    /** Every app-owned process with its name and memory, heaviest first. */
    fun list(): List<Process> {
        val pageKb = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) / 1024 }.getOrDefault(4L)
        return ownProcDirs().mapNotNull { dir ->
            runCatching {
                val pid = dir.name.toInt()
                // cmdline is NUL-separated argv; the first token is the executable path.
                val cmdline = File(dir, "cmdline").readBytes()
                    .toString(Charsets.UTF_8)
                    .split(Char(0))
                    .firstOrNull { it.isNotBlank() }
                val comm = File(dir, "comm").readText().trim()
                val name = (cmdline?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: comm)
                    .ifBlank { "pid $pid" }
                val rssPages = File(dir, "statm").readText().split(' ').getOrNull(1)?.toLongOrNull() ?: 0L
                Process(pid = pid, name = name, rssKb = rssPages * pageKb)
            }.getOrNull()
        }.sortedByDescending { it.rssKb }
    }

    private fun ownProcDirs(): List<File> {
        val myUid = android.os.Process.myUid()
        return File("/proc").listFiles().orEmpty().filter { dir ->
            dir.name.toIntOrNull() != null &&
                runCatching { Os.stat(dir.path).st_uid }.getOrNull() == myUid
        }
    }
}
