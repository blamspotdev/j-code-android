package dev.blamspot.jcode.core.resource

import android.content.ComponentCallbacks2

/**
 * Memory pressure levels reported by the system via ComponentCallbacks2.
 * Resources should reduce their footprint when pressure increases.
 */
enum class MemoryPressure(val trimRatio: Float) {
    /** Normal operation, no memory pressure */
    NORMAL(0.0f),

    /** Background, reduce memory usage */
    BACKGROUND(0.3f),

    /** Moderate pressure, trim caches */
    MODERATE(0.5f),

    /** Low memory, aggressive trimming required */
    LOW(0.7f),

    /** Critical memory, release all non-essential resources */
    CRITICAL(0.9f);

    companion object {
        /**
         * Convert an Android `ComponentCallbacks2` trim level to a pressure level.
         *
         * Matched exactly, never by range, because **the constants are not ordered by severity**.
         * They are two independent families: `RUNNING_*` (5, 10, 15) is the app in the foreground
         * with the *system* running out, and `UI_HIDDEN`/`BACKGROUND`/`MODERATE`/`COMPLETE`
         * (20, 40, 60, 80) is the app in the background moving down the LRU list. So
         * `RUNNING_CRITICAL` is 15 — numerically below `BACKGROUND`'s 40 while being the far more
         * urgent signal.
         *
         * A comparison chain gets this wrong in a way that is invisible until it matters: until
         * 1.6.2 this read `level < 40 -> BACKGROUND` first, which swallowed all three `RUNNING_*`
         * levels, so `RUNNING_CRITICAL` trimmed 30% at the exact moment the app was closest to
         * being killed — and `LOW` sat behind `level < 10`, unreachable, since anything under 10 had
         * already matched.
         */
        // The RUNNING_* family and TRIM_MEMORY_COMPLETE/MODERATE are deprecated as of API 34,
        // which stops delivering them. This app is minSdk/targetSdk 33 and runs on devices that
        // still send them, so the mapping has to keep handling them — deleting these arms would
        // silently reintroduce the very under-trimming this function exists to prevent.
        @Suppress("DEPRECATION")
        fun fromTrimLevel(level: Int): MemoryPressure = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> CRITICAL
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> MODERATE
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> BACKGROUND
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> BACKGROUND
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> CRITICAL
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> LOW
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> MODERATE
            // A level this build has never heard of. Treat anything at or past COMPLETE as the
            // emergency it numerically resembles, and everything else as no pressure — the safe
            // end for an unknown signal is the one that does not throw caches away for nothing.
            else -> if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) CRITICAL else NORMAL
        }
    }
}
