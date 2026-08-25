package dev.blamspot.jcode.core.resource

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central resource management hub. Monitors memory pressure from the OS,
 * coordinates cache trimming, pool draining, and native handle tracking.
 *
 * All managed resources register here and respond to pressure changes.
 */
@Singleton
class ResourceManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : ComponentCallbacks2 {

    private val caches = CopyOnWriteArrayList<ManagedCache>()
    private val _pressure = MutableStateFlow(MemoryPressure.NORMAL)

    /** Current memory pressure level */
    val pressure: StateFlow<MemoryPressure> = _pressure.asStateFlow()

    init {
        context.registerComponentCallbacks(this)
    }

    /**
     * Register a cache for automatic trimming under memory pressure.
     */
    fun registerCache(cache: ManagedCache) {
        caches.add(cache)
    }

    /**
     * Create and register a new LRU cache in one call.
     */
    fun <K, V> managedCache(
        name: String,
        maxSize: Int,
        sizeOf: (K, V) -> Int = { _, _ -> 1 },
    ): LruManagedCache<K, V> {
        val cache = LruManagedCache<K, V>(name, maxSize, sizeOf)
        registerCache(cache)
        return cache
    }

    // --- ComponentCallbacks2 ---

    override fun onTrimMemory(level: Int) {
        val newPressure = MemoryPressure.fromTrimLevel(level)
        // Reported, not high-water. This used to only ever move up — `if (new.ordinal >
        // old.ordinal)` — so one CRITICAL callback pinned the flow at CRITICAL for the life of the
        // process and anything reading it stayed degraded forever. Android never sends a
        // "pressure relieved" callback, so recovery has to come from [onAppForegrounded].
        _pressure.value = newPressure
        applyTrimming(newPressure)
    }

    /**
     * The app is interactive again — clear the pressure reading.
     *
     * `ComponentCallbacks2` has no "you are fine now" callback: the system tells you it is running
     * out and then goes quiet. Without this the flow would keep reporting whatever the worst moment
     * was, long after the app was back in front of the user. Called from `MainActivity.onResume`.
     */
    fun onAppForegrounded() {
        _pressure.value = MemoryPressure.NORMAL
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // No-op; configuration changes handled by Compose
    }

    @Deprecated("Deprecated in Java. Use onTrimMemory(int) instead.")
    override fun onLowMemory() {
        _pressure.value = MemoryPressure.CRITICAL
        applyTrimming(MemoryPressure.CRITICAL)
    }

    private fun applyTrimming(pressure: MemoryPressure) {
        val ratio = pressure.trimRatio
        if (ratio <= 0f) return
        // Worth a line in logcat: an OOM report is much easier to read when you can see whether the
        // app was asked to give memory back, and how much anything actually held at the time.
        android.util.Log.d(
            "ResourceManager",
            "trim $pressure (ratio=$ratio) over ${caches.size} cache(s): " +
                caches.joinToString { "${it.name}=${it.size}" },
        )
        caches.forEach { it.trim(ratio) }
    }
}
