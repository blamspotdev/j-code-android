package dev.jcode.core.resource

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
    private val pools = CopyOnWriteArrayList<ResourcePool<*>>()
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
     * Unregister a cache (e.g., when its owner is destroyed).
     */
    fun unregisterCache(cache: ManagedCache) {
        caches.remove(cache)
    }

    /**
     * Register a resource pool for automatic draining under memory pressure.
     */
    fun registerPool(pool: ResourcePool<*>) {
        pools.add(pool)
    }

    /**
     * Unregister a resource pool.
     */
    fun unregisterPool(pool: ResourcePool<*>) {
        pools.remove(pool)
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

    /**
     * Create and register a new resource pool in one call.
     */
    fun <T : Any> managedPool(
        name: String,
        maxSize: Int,
        factory: () -> T,
        reset: (T) -> Unit = {},
        destroy: (T) -> Unit = {},
    ): ResourcePool<T> {
        val pool = ResourcePool(name, maxSize, factory, reset, destroy)
        registerPool(pool)
        return pool
    }

    // --- ComponentCallbacks2 ---

    override fun onTrimMemory(level: Int) {
        val newPressure = MemoryPressure.fromTrimLevel(level)
        val oldPressure = _pressure.value

        if (newPressure.ordinal > oldPressure.ordinal) {
            _pressure.value = newPressure
        }

        applyTrimming(newPressure)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // No-op; configuration changes handled by Compose
    }

    @Deprecated("Deprecated in Java. Use onTrimMemory(int) instead.")
    override fun onLowMemory() {
        _pressure.value = MemoryPressure.CRITICAL
        applyTrimming(MemoryPressure.CRITICAL)
    }

    /**
     * Manually trigger trimming at the given pressure level.
     * Useful for testing or proactive cleanup.
     */
    fun trimToPressure(pressure: MemoryPressure) {
        _pressure.value = pressure
        applyTrimming(pressure)
    }

    private fun applyTrimming(pressure: MemoryPressure) {
        val ratio = pressure.trimRatio
        caches.forEach { it.trim(ratio) }
        pools.forEach { it.trim(ratio) }
    }

    /**
     * Release all managed resources. Called on app shutdown.
     */
    fun shutdown() {
        caches.forEach { it.clear() }
        pools.forEach { it.drain() }
        context.unregisterComponentCallbacks(this)
    }
}
