package dev.jcode.core.distro

import java.io.File

/**
 * Feeds htop/top/vmstat *real* per-core CPU utilization even though Android denies apps the real
 * /proc/stat. The trick: the per-core cpuidle C-state timers
 * (`/sys/devices/system/cpu/cpuN/cpuidle/stateX/time`, microseconds spent idle) ARE readable by an
 * app, and proot runs as the app's real uid — so this samples them on a background thread, derives
 * busy = wall − idle per core, accumulates USER_HZ jiffie counters, and rewrites the synthetic
 * /proc/stat that [ProotManager] binds over the guest's /proc/stat. Tools that delta /proc/stat then
 * show true load. (Live per-core *frequency* and *temperature* are read straight from /sys by the
 * tools themselves; only the /proc/stat load counters need this synthesis.)
 *
 * Cheap: ~2 tiny sysfs reads per core + one small file write per second, on a single daemon thread.
 * Started lazily on first proot use and left running (during doze the OS suspends it anyway).
 *
 * Process-wide singleton via [shared]: there are two [ProotManager] instances (DistroService's and
 * TerminalSessionHost's) that both bind the same synthetic /proc/stat, but only ONE sampler may own
 * it — two accumulators writing the same file would make the counters non-monotonic and glitch htop.
 */
internal class CpuStatSampler private constructor(private val fakeProcDir: File) {
    private val cores = Runtime.getRuntime().availableProcessors().coerceIn(1, 4096)

    // Cumulative jiffies per core (what /proc/stat exposes); htop reads deltas between refreshes.
    private val user = LongArray(cores)
    private val system = LongArray(cores)
    private val idle = LongArray(cores)

    private val lastIdleUs = LongArray(cores) { -1L }
    private var lastWallNs = 0L

    // Exponential moving averages of the "cores worth of work" figure, for a moving /proc/loadavg.
    private var load1 = 0.0
    private var load5 = 0.0
    private var load15 = 0.0

    @Volatile
    private var thread: Thread? = null

    fun ensureRunning() {
        if (thread != null) return
        synchronized(this) {
            if (thread != null) return
            thread = Thread({ loop() }, "jcode-cpustat").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
                start()
            }
        }
    }

    private fun loop() {
        while (true) {
            runCatching { sampleAndWrite() }
            try {
                Thread.sleep(SAMPLE_MS)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    /** Sum of every cpuidle state's `time` (microseconds) for [core], or -1 if unreadable/offline. */
    private fun readCoreIdleUs(core: Int): Long {
        val states = File("/sys/devices/system/cpu/cpu$core/cpuidle")
            .listFiles { f -> f.isDirectory && f.name.startsWith("state") } ?: return -1L
        if (states.isEmpty()) return -1L
        var sum = 0L
        for (s in states) {
            val v = runCatching { File(s, "time").readText().trim().toLong() }.getOrNull() ?: return -1L
            sum += v
        }
        return sum
    }

    private fun sampleAndWrite() {
        val nowNs = System.nanoTime()
        val wallDeltaUs = if (lastWallNs == 0L) 0L else (nowNs - lastWallNs) / 1_000L
        lastWallNs = nowNs

        var totalBusyFraction = 0.0
        for (c in 0 until cores) {
            val idleUs = readCoreIdleUs(c)
            if (idleUs < 0L) continue
            val prev = lastIdleUs[c]
            lastIdleUs[c] = idleUs
            if (prev < 0L || wallDeltaUs <= 0L) continue

            var idleDeltaUs = idleUs - prev
            if (idleDeltaUs < 0L) idleDeltaUs = 0L
            if (idleDeltaUs > wallDeltaUs) idleDeltaUs = wallDeltaUs
            val busyDeltaUs = wallDeltaUs - idleDeltaUs

            idle[c] += idleDeltaUs / US_PER_JIFFIE
            val busyJiffies = busyDeltaUs / US_PER_JIFFIE
            // Split busy into system (~1/3) and user (rest); htop sums them for the load %, so the
            // exact split is cosmetic — it just colours the meter realistically.
            system[c] += busyJiffies / 3
            user[c] += busyJiffies - busyJiffies / 3

            totalBusyFraction += busyDeltaUs.toDouble() / wallDeltaUs.toDouble()
        }

        // Moving load average ("cores worth of busy work"): EMA at ~1/5/15-min-ish weights.
        load1 += (totalBusyFraction - load1) * L1_ALPHA
        load5 += (totalBusyFraction - load5) * L5_ALPHA
        load15 += (totalBusyFraction - load15) * L15_ALPHA

        writeStat()
        writeLoadavg()
    }

    private fun writeStat() {
        val sb = StringBuilder(128 + cores * 48)
        var aU = 0L
        var aS = 0L
        var aI = 0L
        for (c in 0 until cores) {
            aU += user[c]; aS += system[c]; aI += idle[c]
        }
        sb.append("cpu  ").append(aU).append(" 0 ").append(aS).append(' ').append(aI)
            .append(" 0 0 0 0 0 0\n")
        for (c in 0 until cores) {
            sb.append("cpu").append(c).append(' ').append(user[c]).append(" 0 ")
                .append(system[c]).append(' ').append(idle[c]).append(" 0 0 0 0 0 0\n")
        }
        sb.append("intr 0\nctxt 0\nbtime 1893456000\nprocesses 1\nprocs_running 1\nprocs_blocked 0\n")
            .append("softirq 0 0 0 0 0 0 0 0 0 0 0\n")
        atomicWrite("stat", sb.toString())
    }

    private fun writeLoadavg() {
        atomicWrite(
            "loadavg",
            "%.2f %.2f %.2f 1/1 1\n".format(load1, load5, load15),
        )
    }

    /** Write via temp+rename so a concurrent guest read never sees a half-written file. */
    private fun atomicWrite(name: String, content: String) {
        runCatching {
            val target = File(fakeProcDir, name)
            val tmp = File(fakeProcDir, "$name.tmp")
            tmp.writeText(content)
            tmp.setReadable(true, false)
            if (!tmp.renameTo(target)) {
                target.writeText(content)
                target.setReadable(true, false)
                tmp.delete()
            }
        }
    }

    companion object {
        private const val SAMPLE_MS = 1_000L
        private const val US_PER_JIFFIE = 10_000L // USER_HZ = 100 → 1 jiffie = 10 ms = 10000 µs
        private const val L1_ALPHA = 0.30
        private const val L5_ALPHA = 0.10
        private const val L15_ALPHA = 0.03

        @Volatile
        private var instance: CpuStatSampler? = null

        /** The one process-wide sampler. [fakeProcDir] is filesDir-derived, identical across callers. */
        fun shared(fakeProcDir: File): CpuStatSampler {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: CpuStatSampler(fakeProcDir).also { instance = it }
            }
        }
    }
}
