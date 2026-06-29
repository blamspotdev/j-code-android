package dev.jcode.core.resource

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Generic object pool for reusable resources.
 * Reduces allocation overhead for frequently created/destroyed objects.
 */
class ResourcePool<T : Any>(
    val name: String,
    private val maxSize: Int,
    private val factory: () -> T,
    private val reset: (T) -> Unit = {},
    private val destroy: (T) -> Unit = {},
) {
    private val pool = ConcurrentLinkedQueue<T>()
    private val currentSize = AtomicInteger(0)

    /**
     * Acquire an object from the pool, creating a new one if necessary.
     */
    fun acquire(): T {
        val obj = pool.poll()
        return if (obj != null) {
            reset(obj)
            obj
        } else {
            factory()
        }
    }

    /**
     * Release an object back to the pool.
     * If the pool is full, the object is destroyed instead.
     */
    fun release(obj: T) {
        if (currentSize.get() < maxSize) {
            reset(obj)
            pool.offer(obj)
            currentSize.incrementAndGet()
        } else {
            destroy(obj)
        }
    }

    /**
     * Drain the pool, destroying all pooled objects.
     */
    fun drain() {
        while (true) {
            val obj = pool.poll() ?: break
            destroy(obj)
            currentSize.decrementAndGet()
        }
    }

    /**
     * Trim the pool by destroying a ratio of pooled objects.
     */
    fun trim(ratio: Float) {
        val targetToRemove = (currentSize.get() * ratio).toInt()
        var removed = 0
        while (removed < targetToRemove) {
            val obj = pool.poll() ?: break
            destroy(obj)
            currentSize.decrementAndGet()
            removed++
        }
    }

    val size: Int
        get() = currentSize.get()
}
