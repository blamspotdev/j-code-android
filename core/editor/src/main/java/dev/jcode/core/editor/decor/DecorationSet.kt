package dev.jcode.core.editor.decor

/**
 * Immutable collection of decorations sorted by z-index.
 * Thread-safe via immutable snapshots.
 */
class DecorationSet private constructor(
    private val decorations: List<Decoration>,
) {
    val size: Int get() = decorations.size
    val isEmpty: Boolean get() = decorations.isEmpty()

    /** Get all decorations sorted by z-index (lowest first). */
    fun all(): List<Decoration> = decorations

    /** Get decorations at a specific z-index layer. */
    fun atLayer(zIndex: Int): List<Decoration> = decorations.filter { it.zIndex() == zIndex }

    /** Get decorations in a z-index range (inclusive). */
    fun inRange(minZ: Int, maxZ: Int): List<Decoration> =
        decorations.filter { it.zIndex() in minZ..maxZ }

    /** Add a decoration, returning a new set. */
    fun add(decoration: Decoration): DecorationSet {
        val newList = (decorations + decoration).sortedBy { it.zIndex() }
        return DecorationSet(newList)
    }

    /** Add multiple decorations, returning a new set. */
    fun addAll(newDecorations: Collection<Decoration>): DecorationSet {
        val newList = (decorations + newDecorations).sortedBy { it.zIndex() }
        return DecorationSet(newList)
    }

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
        val newList = (kept + newDecorations).sortedBy { it.zIndex() }
        return DecorationSet(newList)
    }

    /** Replace decorations matching a predicate with new ones. */
    fun replaceWhere(predicate: (Decoration) -> Boolean, newDecorations: Collection<Decoration>): DecorationSet {
        val kept = decorations.filterNot(predicate)
        val newList = (kept + newDecorations).sortedBy { it.zIndex() }
        return DecorationSet(newList)
    }

    /** Clear all decorations. */
    fun clear(): DecorationSet = EMPTY

    operator fun iterator(): Iterator<Decoration> = decorations.iterator()

    companion object {
        val EMPTY = DecorationSet(emptyList())

        fun of(vararg decorations: Decoration): DecorationSet {
            return DecorationSet(decorations.sortedBy { it.zIndex() })
        }
    }
}
