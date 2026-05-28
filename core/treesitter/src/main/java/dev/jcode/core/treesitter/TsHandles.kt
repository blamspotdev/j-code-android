package dev.jcode.core.treesitter

import java.lang.ref.Cleaner

/**
 * Handle wrapper for a tree-sitter parser instance.
 * JNI-backed in production; compile-safe stub for now.
 */
class TsParser : AutoCloseable {
    private var handle: Long = 0L
    private var closed = false

    /** Parse source code and return a syntax tree. */
    fun parse(source: String, oldTree: TsTree? = null): TsTree {
        checkNotClosed()
        // Stub: return a synthetic tree
        return TsTree(source)
    }

    /** Set the parser language. */
    fun setLanguage(language: TsLanguage) {
        checkNotClosed()
        // Stub: no-op
    }

    /** Reset the parser state. */
    fun reset() {
        checkNotClosed()
    }

    override fun close() {
        if (!closed) {
            closed = true
            if (handle != 0L) {
                nativeClose(handle)
                handle = 0L
            }
        }
    }

    private fun checkNotClosed() {
        check(!closed) { "TsParser is closed" }
    }

    private external fun nativeClose(handle: Long)

    companion object {
        init {
            runCatching { System.loadLibrary("treesitter") }
        }
    }
}

/**
 * Handle wrapper for a parsed syntax tree.
 */
class TsTree internal constructor(
    val sourceText: String,
) : AutoCloseable {
    private var handle: Long = 0L
    private var closed = false

    /** Get the root node of the tree. */
    val rootNode: TsNode
        get() = TsNode(this, "root", 0, sourceText.length, 0L)

    /** Edit the tree incrementally. */
    fun edit(
        startByte: Int,
        oldEndByte: Int,
        newEndByte: Int,
        startRow: Int,
        startColumn: Int,
        oldEndRow: Int,
        oldEndColumn: Int,
        newEndRow: Int,
        newEndColumn: Int,
    ) {
        checkNotClosed()
        // Stub: no-op for now
    }

    override fun close() {
        if (!closed) {
            closed = true
            if (handle != 0L) {
                nativeClose(handle)
                handle = 0L
            }
        }
    }

    private fun checkNotClosed() {
        check(!closed) { "TsTree is closed" }
    }

    private external fun nativeClose(handle: Long)
}

/**
 * A syntax node within a tree.
 */
data class TsNode(
    val tree: TsTree,
    val type: String,
    val startByte: Int,
    val endByte: Int,
    val nodeId: Long,
) {
    /** Child nodes. */
    val childCount: Int = 0
    val namedChildCount: Int = 0

    /** Child at index. */
    fun child(index: Int): TsNode? = null

    /** Named child at index. */
    fun namedChild(index: Int): TsNode? = null

    /** First child with the given type. */
    fun childByFieldName(name: String): TsNode? = null

    /** Parent node. */
    val parent: TsNode? = null

    /** Next sibling. */
    val nextSibling: TsNode? = null

    /** Previous sibling. */
    val prevSibling: TsNode? = null

    /** Next named sibling. */
    val nextNamedSibling: TsNode? = null

    /** Previous named sibling. */
    val prevNamedSibling: TsNode? = null

    /** Create a tree cursor starting at this node. */
    fun walk(): TsCursor = TsCursor(tree)

    /** Get the text content of this node. */
    fun text(): String = tree.sourceText.substring(startByte.coerceAtMost(tree.sourceText.length), endByte.coerceAtMost(tree.sourceText.length))

    /** Whether this node is named (significant in the grammar). */
    val isNamed: Boolean = true

    /** Whether this node is missing (expected by grammar but not present). */
    val isMissing: Boolean = false

    /** Whether this node has errors. */
    val hasError: Boolean = false
}

/**
 * Tree cursor for walking a syntax tree.
 */
class TsCursor internal constructor(
    private val tree: TsTree,
) : AutoCloseable {
    private var closed = false
    private var currentNode: TsNode? = null

    /** Current node. */
    fun currentNode(): TsNode? = currentNode

    /** Move to the next node in a depth-first walk. */
    fun goToNext(): Boolean {
        checkNotClosed()
        return false
    }

    /** Move to the previous node. */
    fun goToPrevious(): Boolean {
        checkNotClosed()
        return false
    }

    /** Move to the parent. */
    fun goToParent(): Boolean {
        checkNotClosed()
        return false
    }

    /** Move to the first child. */
    fun goToFirstChild(): Boolean {
        checkNotClosed()
        return false
    }

    /** Move to the next sibling. */
    fun goToNextSibling(): Boolean {
        checkNotClosed()
        return false
    }

    /** Move to the previous sibling. */
    fun goToPreviousSibling(): Boolean {
        checkNotClosed()
        return false
    }

    /** Reset the cursor to the root. */
    fun reset() {
        checkNotClosed()
        currentNode = tree.rootNode
    }

    /** Reset to a specific node. */
    fun resetTo(node: TsNode) {
        checkNotClosed()
        currentNode = node
    }

    override fun close() {
        if (!closed) {
            closed = true
        }
    }

    private fun checkNotClosed() {
        check(!closed) { "TsCursor is closed" }
    }
}

/**
 * Compiled tree-sitter query.
 */
class TsQuery internal constructor(
    val source: String,
    val patternCount: Int = 0,
) {
    /** Execute the query against a tree. */
    fun matches(tree: TsTree, rangeStart: Int = 0, rangeEnd: Int = tree.sourceText.length): List<TsQueryMatch> {
        return emptyList()
    }

    /** Get the pattern string for a given index. */
    fun patternForId(index: Int): String = ""
}

/** A single match from a query execution. */
data class TsQueryMatch(
    val patternIndex: Int,
    val captures: List<TsQueryCapture>,
)

/** A captured node from a query match. */
data class TsQueryCapture(
    val node: TsNode,
    val index: Int,
    val name: String,
)

/**
 * Handle wrapper for a tree-sitter language.
 */
class TsLanguage private constructor(
    val name: String,
    val extensions: List<String> = emptyList(),
) {
    companion object {
        fun create(name: String, extensions: List<String>): TsLanguage {
            return TsLanguage(name, extensions)
        }
    }
}
