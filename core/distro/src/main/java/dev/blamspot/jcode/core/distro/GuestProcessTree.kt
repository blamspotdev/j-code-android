package dev.blamspot.jcode.core.distro

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Tearing down one guest process tree — a proot launcher and everything it started.
 *
 * The obvious `Process.destroy()` does not work here, and neither does the obvious escalation:
 *
 * - `destroy()` sends `SIGTERM` **to proot**, which proot survives while it is tracing. Nothing in
 *   the tree dies; the extension's `node`, and the `claude` or `opencode` it started, keep running.
 * - `destroyForcibly()` sends `SIGKILL`, which proot cannot handle, so it dies without running the
 *   cleanup that `--kill-on-exit` is. The tracees are detached by the kernel, reparented to init,
 *   and carry on — the same leak, only now with no launcher left to aim at.
 *
 * What works is signalling proot's **direct child**, the top-level tracee: its exit is the event
 * `--kill-on-exit` waits for, and proot then reaps the tree and exits itself. Both `SIGTERM` and
 * `SIGKILL` to that pid were measured to take a tree down completely.
 *
 * Finding that pid is the other half. `java.lang.Process` has no `pid()` on Android and the field
 * behind it is not reachable, so a tree is identified by what it carries instead: every process
 * spawned for an owner gets [OWNER_ENV] and [INSTANCE_ENV] in its environment, which every
 * descendant inherits however deeply it forks. The top-level tracee is then the tagged process whose
 * parent is not tagged, and a process that double-forked its way out of the tree — reparented to
 * init, invisible in the parent links — is still found by its tag.
 */
object GuestProcessTree {

    /** Which extension a guest process belongs to; inherited by everything it forks. */
    const val OWNER_ENV = "JCODE_EXT_ID"

    /** Which spawn it came from, so one service can be stopped without touching its siblings. */
    const val INSTANCE_ENV = "JCODE_PROC_ID"

    private val nextInstance = AtomicLong()

    /** Instance id per live process. Weak: a process nobody holds is a tree nobody can stop. */
    private val instances = Collections.synchronizedMap(WeakHashMap<Process, String>())

    /** A fresh instance id for [owner], to be exported into the spawn's guest environment. */
    internal fun mintInstance(owner: String): String = "$owner:${nextInstance.incrementAndGet()}"

    /** The guest environment that stamps a spawn, ready to merge into the shell's exports. */
    internal fun instanceEnv(owner: String, instance: String): Map<String, String> =
        mapOf(OWNER_ENV to owner, INSTANCE_ENV to instance)

    /** Remember which spawn [process] is, so [destroy] can find its tree later. */
    internal fun register(process: Process, instance: String) {
        instances[process] = instance
    }

    /**
     * End [process] and everything it started, off the calling thread.
     *
     * Asynchronous because the escalation has to wait for proot to finish reaping, and the callers
     * are the main thread (the Task Manager's Stop) or a UI coroutine on it.
     */
    fun destroy(process: Process) {
        Thread { destroyBlocking(process) }
            .apply { isDaemon = true; name = "guest-teardown" }
            .start()
    }

    /** [destroy], on this thread. Takes up to [GRACE_MS] twice over. */
    fun destroyBlocking(process: Process) {
        val instance = instances.remove(process)
        if (instance == null) {
            // Not one of ours to trace — nothing to aim at but the launcher itself.
            runCatching { process.destroyForcibly() }
            return
        }
        val tagged = pidsCarrying(INSTANCE_ENV, instance)
        val parents = parentByPid()
        // proot is not tagged (the stamp is exported inside the guest shell), so the tracee is the
        // tagged process whose parent is not. One per tree, and an owner may have several.
        val roots = tagged.filter { parents[it] !in tagged }

        roots.forEach { runCatching { Os.kill(it, OsConstants.SIGTERM) } }
        if (!awaitExit(process)) {
            roots.forEach { runCatching { Os.kill(it, OsConstants.SIGKILL) } }
            if (!awaitExit(process)) runCatching { process.destroyForcibly() }
        }
        // Whatever forked its way out of the tree before proot could reap it.
        pidsCarrying(INSTANCE_ENV, instance).forEach { runCatching { Os.kill(it, OsConstants.SIGKILL) } }
    }

    /** Every app-owned process still running on [owner]'s behalf, whatever it is parented to now. */
    fun pidsOwnedBy(owner: String): List<Int> = pidsCarrying(OWNER_ENV, owner)

    /**
     * How many processes each extension is running, from a single walk of `/proc`.
     *
     * The Task Manager's answer to "what is this extension actually running": the `claude` an
     * extension started is one of these, and before the tags existed it was an anonymous row in the
     * process list with nothing connecting it to the extension that owns it.
     */
    fun ownerCounts(): Map<String, Int> = AppProcesses.ownPids()
        .mapNotNull { ownerOf(it) }
        .groupingBy { it }
        .eachCount()

    private fun ownerOf(pid: Int): String? = runCatching {
        environOf(pid).firstOrNull { it.startsWith("$OWNER_ENV=") }?.substringAfter('=')
    }.getOrNull()

    private fun pidsCarrying(name: String, value: String): List<Int> {
        val marker = "$name=$value"
        return AppProcesses.ownPids().filter { pid ->
            runCatching { environOf(pid).any { it == marker } }.getOrDefault(false)
        }
    }

    private fun environOf(pid: Int): List<String> =
        File("/proc/$pid/environ").readBytes().toString(Charsets.UTF_8).split(Char(0))

    private fun awaitExit(process: Process): Boolean =
        runCatching { process.waitFor(GRACE_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)

    /** Parent pid of every process this app owns. `/proc/<pid>/task/<pid>/children` is not built in. */
    private fun parentByPid(): Map<Int, Int> = AppProcesses.ownPids().mapNotNull { pid ->
        runCatching {
            // "<pid> (<comm>) <state> <ppid> ...", and comm may hold spaces and parentheses.
            val fields = File("/proc/$pid/stat").readText().substringAfterLast(')').trim().split(' ')
            fields.getOrNull(1)?.toIntOrNull()?.let { pid to it }
        }.getOrNull()
    }.toMap()

    private const val GRACE_MS = 1_500L
}
