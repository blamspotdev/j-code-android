package dev.jcode.core.resource

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
         * Convert Android ComponentCallbacks2 trim level to MemoryPressure.
         */
        fun fromTrimLevel(level: Int): MemoryPressure = when {
            level <= 0 -> NORMAL
            level < TRIM_MEMORY_BACKGROUND -> BACKGROUND
            level < TRIM_MEMORY_MODERATE -> MODERATE
            level < TRIM_MEMORY_RUNNING_LOW -> LOW
            else -> CRITICAL
        }

        private const val TRIM_MEMORY_BACKGROUND = 40
        private const val TRIM_MEMORY_MODERATE = 60
        private const val TRIM_MEMORY_RUNNING_LOW = 10
    }
}
