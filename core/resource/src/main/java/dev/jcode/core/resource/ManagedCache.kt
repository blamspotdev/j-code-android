package dev.jcode.core.resource

import java.util.LinkedHashMap

/**
 * Interface for caches managed by ResourceManager.
 * Caches must respond to memory pressure by evicting entries.
 */
interface ManagedCache {
    /** Unique identifier for this cache */
    val name: String

    /** Current size (number of entries or bytes) */
    val size: Int

    /** Maximum allowed size */
    val maxSize: Int

    /** Evict entries to reduce size by the given ratio (0.0 to 1.0) */
    fun trim(ratio: Float)

    /** Clear all entries */
    fun clear()
}

/**
 * LRU cache implementation that integrates with ResourceManager.
 * Evicts least-recently-used entries when size exceeds maxSize or under memory pressure.
 */
class LruManagedCache<K, V>(
    override val name: String,
    override val maxSize: Int,
    private val sizeOf: (K, V) -> Int = { _, _ -> 1 },
) : ManagedCache {

    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
            return currentSize > maxSize
        }
    }

    @Volatile
    private var currentSize = 0

    override val size: Int
        get() = synchronized(map) { currentSize }

    fun get(key: K): V? = synchronized(map) {
        map[key]
    }

    fun put(key: K, value: V): V? = synchronized(map) {
        val old = map.put(key, value)
        if (old != null) {
            currentSize -= sizeOf(key, old)
        }
        currentSize += sizeOf(key, value)
        trimToSize(maxSize)
        old
    }

    fun remove(key: K): V? = synchronized(map) {
        val removed = map.remove(key)
        if (removed != null) {
            currentSize -= sizeOf(key, removed)
        }
        removed
    }

    private fun trimToSize(targetSize: Int) {
        while (currentSize > targetSize && map.isNotEmpty()) {
            val eldest = map.entries.firstOrNull() ?: break
            map.remove(eldest.key)
            currentSize -= sizeOf(eldest.key, eldest.value)
        }
    }

    override fun trim(ratio: Float) {
        val targetSize = (maxSize * (1.0f - ratio)).toInt().coerceAtLeast(0)
        synchronized(map) {
            trimToSize(targetSize)
        }
    }

    override fun clear() {
        synchronized(map) {
            map.clear()
            currentSize = 0
        }
    }
}
