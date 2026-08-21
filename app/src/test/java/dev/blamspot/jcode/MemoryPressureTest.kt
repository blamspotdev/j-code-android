package dev.blamspot.jcode

import android.content.ComponentCallbacks2
import dev.blamspot.jcode.core.resource.MemoryPressure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trim-level mapping, which decides how hard `ResourceManager` trims its caches and pools.
 *
 * Worth pinning because Android's constants are not ordered by severity: `RUNNING_CRITICAL` is 15
 * while `BACKGROUND` is 40, so any comparison chain that tests `level < 40` first swallows all
 * three `RUNNING_*` levels. That is what this code did until 1.6.2 — the most urgent signal the
 * system sends trimmed 30%, and `LOW` was unreachable behind a `level < 10` branch that anything
 * under 10 had already matched.
 */
@Suppress("DEPRECATION") // same reason as MemoryPressure.fromTrimLevel
class MemoryPressureTest {

    @Test
    fun eachTrimLevelMapsToItsOwnPressure() {
        assertEquals(MemoryPressure.MODERATE, MemoryPressure.fromTrimLevel(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        assertEquals(MemoryPressure.LOW, MemoryPressure.fromTrimLevel(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertEquals(MemoryPressure.CRITICAL, MemoryPressure.fromTrimLevel(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        assertEquals(MemoryPressure.BACKGROUND, MemoryPressure.fromTrimLevel(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertEquals(MemoryPressure.BACKGROUND, MemoryPressure.fromTrimLevel(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
        assertEquals(MemoryPressure.MODERATE, MemoryPressure.fromTrimLevel(ComponentCallbacks2.TRIM_MEMORY_MODERATE))
        assertEquals(MemoryPressure.CRITICAL, MemoryPressure.fromTrimLevel(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
    }

    /** The regression itself: the urgent foreground signal must not be read as a mild one. */
    @Test
    fun runningCriticalTrimsHardNotGently() {
        val critical = MemoryPressure.fromTrimLevel(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals(MemoryPressure.CRITICAL, critical)
        assertTrue(
            "RUNNING_CRITICAL must trim harder than a backgrounded app",
            critical.trimRatio > MemoryPressure.BACKGROUND.trimRatio,
        )
    }

    /** `LOW` is only reachable at all if the mapping matches exactly rather than by range. */
    @Test
    fun lowIsReachable() {
        assertEquals(MemoryPressure.LOW, MemoryPressure.fromTrimLevel(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
    }

    @Test
    fun unknownLevelsFailSafe() {
        assertEquals(MemoryPressure.NORMAL, MemoryPressure.fromTrimLevel(0))
        assertEquals(MemoryPressure.NORMAL, MemoryPressure.fromTrimLevel(-1))
        // Something past COMPLETE is an emergency this build has not heard of yet.
        assertEquals(MemoryPressure.CRITICAL, MemoryPressure.fromTrimLevel(100))
    }
}
