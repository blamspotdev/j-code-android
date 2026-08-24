package dev.blamspot.jcode

import dev.blamspot.jcode.core.resource.LruManagedCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cache behind memory-pressure trimming.
 *
 * The invariant worth guarding is that the reported `size` matches what is actually still in the
 * cache. It did not until 1.6.2: `removeEldestEntry` was overridden to evict at `maxSize`, and the
 * entries it dropped went out behind `currentSize`'s back, while `put` *also* called `trimToSize`
 * and did do the arithmetic. Two eviction paths, one of them silent, so the counter drifted above
 * the real total and the cache then evicted far more than it was holding.
 */
class ManagedCacheTest {

    private fun cache(maxSize: Int) = LruManagedCache<String, String>("test", maxSize)

    @Test
    fun sizeMatchesWhatIsActuallyRetrievable() {
        val c = cache(maxSize = 3)
        repeat(10) { c.put("k$it", "v$it") }

        assertTrue("size ${c.size} must not exceed maxSize", c.size <= 3)
        val actuallyPresent = (0 until 10).count { c.get("k$it") != null }
        assertEquals("reported size must equal what is really there", actuallyPresent, c.size)
    }

    @Test
    fun evictsTheLeastRecentlyUsed() {
        val c = cache(maxSize = 2)
        c.put("a", "1")
        c.put("b", "2")
        c.get("a")          // "a" is now the most recently used, so "b" should go first
        c.put("c", "3")

        assertNotNull("a was touched most recently", c.get("a"))
        assertNotNull("c was just added", c.get("c"))
        assertNull("b was least recently used", c.get("b"))
    }

    @Test
    fun replacingAKeyDoesNotInflateTheCount() {
        val c = cache(maxSize = 4)
        repeat(5) { c.put("same", "v$it") }

        assertEquals("one key means one entry", 1, c.size)
        assertEquals("v4", c.get("same"))
    }

    @Test
    fun trimIsRelativeToMaxSizeNotCurrentSize() {
        val c = cache(maxSize = 10)
        repeat(10) { c.put("k$it", "v$it") }

        c.trim(0.9f)        // CRITICAL: keep a tenth of the budget
        assertEquals(1, c.size)

        val stillThere = (0 until 10).count { c.get("k$it") != null }
        assertEquals(stillThere, c.size)
    }

    /** NORMAL pressure has a 0.0 ratio, and must not throw a full cache away for nothing. */
    @Test
    fun trimAtZeroKeepsEverything() {
        val c = cache(maxSize = 5)
        repeat(5) { c.put("k$it", "v$it") }

        c.trim(0.0f)
        assertEquals(5, c.size)
    }

    @Test
    fun clearEmptiesAndResetsTheCount() {
        val c = cache(maxSize = 5)
        repeat(5) { c.put("k$it", "v$it") }
        c.clear()

        assertEquals(0, c.size)
        assertNull(c.get("k0"))
        // The counter has to come back too, or the next put would evict against a stale total.
        c.put("fresh", "v")
        assertEquals(1, c.size)
    }
}
