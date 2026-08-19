package dev.jcode.core.editor.decor

/**
 * Immutable collection of decorations sorted by z-index.
 * Thread-safe via immutable snapshots.
 *
 * The renderer queries [atLayer] several times per frame and the highlighter replaces the
 * GLYPH_COLOR layer on every keystroke, so layer access is a precomputed map lookup and layer
 * replacement is an ordered merge — no O(n) filter per frame, no full re-sort per edit.
 */
class DecorationSet private constructor(
    private val decorations: List<Decoration>,
) {
    val size: Int get() = decorations.size
    val isEmpty: Boolean get() = decorations.isEmpty()

    // Grouped once per instance (instances are immutable); SYNCHRONIZED lazy keeps this safe for
    // cross-thread readers.
    private val byLayer: Map<Int, List<Decoration>> by lazy { decorations.groupBy { it.zIndex() } }

    /** Get all decorations sorted by z-index (lowest first). */
    fun all(): List<Decoration> = decorations

    /** Get decorations at a specific z-index layer. The returned list is stable per instance. */
    fun atLayer(zIndex: Int): List<Decoration> = byLayer[zIndex] ?: emptyList()

    /** Get decorations in a z-index range (inclusive). */
    fun inRange(minZ: Int, maxZ: Int): List<Decoration> =
        decorations.filter { it.zIndex() in minZ..maxZ }

    /** Add a decoration, returning a new set. */
    fun add(decoration: Decoration): DecorationSet =
        DecorationSet(mergeSorted(decorations, listOf(decoration)))

    /** Add multiple decorations, returning a new set. */
    fun addAll(newDecorations: Collection<Decoration>): DecorationSet =
        DecorationSet(mergeSorted(decorations, newDecorations))

    /** Remove a decoration by ID, returning a new set. */
    fun remove(id: String): DecorationSet {
        val newList = decorations.filter { it.id != id }
        return DecorationSet(newList)
    }

    /** Remove all decorations at a specific layer, returning a new set. */
    fun removeLayer(zIndex: Int): DecorationSet {
        val newList = decorations.filter { it.zIndex() != zIndex }
        return DecorationSet(newList)
    }

    /** Replace all decorations at a specific layer with new ones. */
    fun replaceLayer(zIndex: Int, newDecorations: Collection<Decoration>): DecorationSet {
        val kept = decorations.filter { it.zIndex() != zIndex }
        return DecorationSet(mergeSorted(kept, newDecorations))
    }

    /** Replace decorations matching a predicate with new ones. */
    fun replaceWhere(predicate: (Decoration) -> Boolean, newDecorations: Collection<Decoration>): DecorationSet {
        val kept = decorations.filterNot(predicate)
        return DecorationSet(mergeSorted(kept, newDecorations))
    }

    /** Clear all decorations. */
    fun clear(): DecorationSet = EMPTY

    operator fun iterator(): Iterator<Decoration> = decorations.iterator()

    companion object {
        val EMPTY = DecorationSet(emptyList())

        fun of(vararg decorations: Decoration): DecorationSet {
            return DecorationSet(decorations.sortedBy { it.zIndex() })
        }

        /**
         * Merge [added] into the z-sorted [sorted] list, preserving insertion order within equal
         * z-indexes (what the previous stable sortedBy produced) without re-sorting everything.
         */
        private fun mergeSorted(sorted: List<Decoration>, added: Collection<Decoration>): List<Decoration> {
            if (added.isEmpty()) return sorted
            val addedSorted = added.sortedBy { it.zIndex() }
            val out = ArrayList<Decoration>(sorted.size + addedSorted.size)
            var i = 0
            for (dec in addedSorted) {
                val z = dec.zIndex()
                while (i < sorted.size && sorted[i].zIndex() <= z) out.add(sorted[i++])
                out.add(dec)
            }
            while (i < sorted.size) out.add(sorted[i++])
            return out
        }
    }
}
