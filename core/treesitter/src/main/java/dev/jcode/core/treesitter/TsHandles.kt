package dev.jcode.core.treesitter

import java.lang.ref.Cleaner

/**
 * Handle wrapper for a tree-sitter parser instance.
 * Backed by native JNI implementation.
 */
class TsParser : AutoCloseable {
    private var handle: Long = nativeCreate()
    private var closed = false
    private val cleanable: Cleaner.Cleanable

    init {
        val h = handle
        cleanable = cleaner.register(this) { if (h != 0L) nativeCloseByHandle(h) }
    }

    /** Parse source code and return a syntax tree. */
    fun parse(source: String, oldTree: TsTree? = null): TsTree {
        checkNotClosed()
        val treeHandle = nativeParseString(handle, oldTree?.handle ?: 0L, source)
        return TsTree(source, treeHandle)
    }

    /** Parse source bytes and return a syntax tree. */
    fun parse(source: ByteArray, oldTree: TsTree? = null): TsTree {
        checkNotClosed()
        val treeHandle = nativeParseBytes(handle, oldTree?.handle ?: 0L, source)
        return TsTree(String(source, Charsets.UTF_8), treeHandle)
    }

    /** Set the parser language. */
    fun setLanguage(language: TsLanguage): Boolean {
        checkNotClosed()
        if (language.nativeHandle == 0L) return false
        return nativeSetLanguage(handle, language.nativeHandle)
    }

    /** Reset the parser state. */
    fun reset() {
        checkNotClosed()
        nativeReset(handle)
    }

    override fun close() {
        if (!closed) {
            closed = true
            if (handle != 0L) {
                nativeClose(handle)
                handle = 0L
                cleanable.clean()
            }
        }
    }

    private fun checkNotClosed() {
        check(!closed) { "TsParser is closed" }
    }

    private external fun nativeCreate(): Long
    private external fun nativeClose(handle: Long)
    private external fun nativeSetLanguage(handle: Long, langHandle: Long): Boolean
    private external fun nativeParseString(handle: Long, oldTree: Long, source: String): Long
    private external fun nativeParseBytes(handle: Long, oldTree: Long, source: ByteArray): Long
    private external fun nativeReset(handle: Long)

    companion object {
        private val cleaner = Cleaner.create()

        init {
            runCatching { System.loadLibrary("treesitter") }
        }

        private external fun nativeCloseByHandle(handle: Long)
    }
}

/**
 * Handle wrapper for a parsed syntax tree.
 */
class TsTree internal constructor(
    val sourceText: String,
    internal var handle: Long,
) : AutoCloseable {
    private var closed = false
    private val cleanable: Cleaner.Cleanable

    init {
        val h = handle
        cleanable = cleaner.register(this) { if (h != 0L) nativeCloseByHandle(h) }
    }

    /** Get the root node of the tree. */
    val rootNode: TsNode
        get() {
            checkNotClosed()
            if (handle == 0L) return TsNode(this, "root", 0, sourceText.length, 0, 0, 0, 0L, 0L)
            val nodeHandle = nativeRootNode(handle)
            if (nodeHandle == 0L) return TsNode(this, "root", 0, sourceText.length, 0, 0, 0, 0L, 0L)
            return TsNode.fromNative(this, nodeHandle)
        }

    /** Copy the tree (for thread-safe access). */
    fun copy(): TsTree {
        checkNotClosed()
        val newHandle = nativeCopy(handle)
        return TsTree(sourceText, newHandle)
    }

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
        nativeEdit(handle, startByte, oldEndByte, newEndByte,
            startRow, startColumn, oldEndRow, oldEndColumn, newEndRow, newEndColumn)
    }

    override fun close() {
        if (!closed) {
            closed = true
            if (handle != 0L) {
                nativeClose(handle)
                handle = 0L
                cleanable.clean()
            }
        }
    }

    private fun checkNotClosed() {
        check(!closed) { "TsTree is closed" }
    }

    private external fun nativeRootNode(handle: Long): Long
    private external fun nativeCopy(handle: Long): Long
    private external fun nativeClose(handle: Long)
    private external fun nativeEdit(handle: Long, startByte: Int, oldEndByte: Int, newEndByte: Int,
        startRow: Int, startColumn: Int, oldEndRow: Int, oldEndColumn: Int, newEndRow: Int, newEndColumn: Int)

    companion object {
        private val cleaner = Cleaner.create()
        private external fun nativeCloseByHandle(handle: Long)
    }
}

/**
 * A syntax node within a tree.
 */
class TsNode internal constructor(
    val tree: TsTree,
    val type: String,
    val startByte: Int,
    val endByte: Int,
    val startRow: Int,
    val startColumn: Int,
    val endRow: Int,
    internal val handle: Long,
    internal val nodeId: Long,
) {
    val childCount: Int
        get() = if (handle != 0L) nativeChildCount(handle) else 0

    val namedChildCount: Int
        get() = if (handle != 0L) nativeNamedChildCount(handle) else 0

    val isNamed: Boolean
        get() = if (handle != 0L) nativeIsNamed(handle) else true

    val isMissing: Boolean
        get() = if (handle != 0L) nativeIsMissing(handle) else false

    val isError: Boolean
        get() = if (handle != 0L) nativeIsError(handle) else false

    fun child(index: Int): TsNode? {
        if (handle == 0L) return null
        val childHandle = nativeChild(handle, index)
        if (childHandle == 0L) return null
        return fromNative(tree, childHandle)
    }

    fun namedChild(index: Int): TsNode? {
        if (handle == 0L) return null
        val childHandle = nativeNamedChild(handle, index)
        if (childHandle == 0L) return null
        return fromNative(tree, childHandle)
    }

    fun childByFieldName(name: String): TsNode? {
        if (handle == 0L) return null
        val childHandle = nativeChildByFieldName(handle, name)
        if (childHandle == 0L) return null
        return fromNative(tree, childHandle)
    }

    val parent: TsNode?
        get() {
            if (handle == 0L) return null
            val parentHandle = nativeParent(handle)
            if (parentHandle == 0L) return null
            return fromNative(tree, parentHandle)
        }

    val nextSibling: TsNode?
        get() {
            if (handle == 0L) return null
            val sibHandle = nativeNextSibling(handle)
            if (sibHandle == 0L) return null
            return fromNative(tree, sibHandle)
        }

    val prevSibling: TsNode?
        get() {
            if (handle == 0L) return null
            val sibHandle = nativePrevSibling(handle)
            if (sibHandle == 0L) return null
            return fromNative(tree, sibHandle)
        }

    val nextNamedSibling: TsNode?
        get() {
            if (handle == 0L) return null
            val sibHandle = nativeNextNamedSibling(handle)
            if (sibHandle == 0L) return null
            return fromNative(tree, sibHandle)
        }

    val prevNamedSibling: TsNode?
        get() {
            if (handle == 0L) return null
            val sibHandle = nativePrevNamedSibling(handle)
            if (sibHandle == 0L) return null
            return fromNative(tree, sibHandle)
        }

    fun walk(): TsCursor {
        if (handle == 0L) return TsCursor(tree, 0L)
        val cursorHandle = nativeWalk(handle)
        return TsCursor(tree, cursorHandle)
    }

    fun text(): String {
        val start = startByte.coerceAtMost(tree.sourceText.length)
        val end = endByte.coerceAtMost(tree.sourceText.length)
        return tree.sourceText.substring(start, end)
    }

    fun close() {
        if (handle != 0L) {
            nativeClose(handle)
        }
    }

    private external fun nativeChildCount(handle: Long): Int
    private external fun nativeNamedChildCount(handle: Long): Int
    private external fun nativeIsNamed(handle: Long): Boolean
    private external fun nativeIsError(handle: Long): Boolean
    private external fun nativeIsMissing(handle: Long): Boolean
    private external fun nativeChild(handle: Long, index: Int): Long
    private external fun nativeNamedChild(handle: Long, index: Int): Long
    private external fun nativeChildByFieldName(handle: Long, name: String): Long
    private external fun nativeParent(handle: Long): Long
    private external fun nativeNextSibling(handle: Long): Long
    private external fun nativePrevSibling(handle: Long): Long
    private external fun nativeNextNamedSibling(handle: Long): Long
    private external fun nativePrevNamedSibling(handle: Long): Long
    private external fun nativeWalk(handle: Long): Long
    private external fun nativeClose(handle: Long)

    companion object {
        /** Create a TsNode from a native handle, reading its properties via JNI. */
        internal fun fromNative(tree: TsTree, handle: Long): TsNode {
            return TsNode(
                tree = tree,
                type = nativeType(handle),
                startByte = nativeStartByte(handle),
                endByte = nativeEndByte(handle),
                startRow = nativeStartRow(handle),
                startColumn = nativeStartColumn(handle),
                endRow = nativeEndRow(handle),
                handle = handle,
                nodeId = handle,
            )
        }

        private external fun nativeType(handle: Long): String
        private external fun nativeStartByte(handle: Long): Int
        private external fun nativeEndByte(handle: Long): Int
        private external fun nativeStartRow(handle: Long): Int
        private external fun nativeStartColumn(handle: Long): Int
        private external fun nativeEndRow(handle: Long): Int
        private external fun nativeEndColumn(handle: Long): Int
    }
}

/**
 * Tree cursor for efficient tree traversal.
 */
class TsCursor internal constructor(
    private val tree: TsTree,
    private var handle: Long,
) : AutoCloseable {
    private var closed = false

    fun currentNode(): TsNode? {
        checkNotClosed()
        if (handle == 0L) return null
        val nodeHandle = nativeCurrentNode(handle)
        if (nodeHandle == 0L) return null
        return TsNode.fromNative(tree, nodeHandle)
    }

    fun currentFieldName(): String? {
        checkNotClosed()
        if (handle == 0L) return null
        return nativeCurrentFieldName(handle)
    }

    fun goToFirstChild(): Boolean {
        checkNotClosed()
        return handle != 0L && nativeGoToFirstChild(handle)
    }

    fun goToNextSibling(): Boolean {
        checkNotClosed()
        return handle != 0L && nativeGoToNextSibling(handle)
    }

    fun goToParent(): Boolean {
        checkNotClosed()
        return handle != 0L && nativeGoToParent(handle)
    }

    fun resetTo(node: TsNode) {
        checkNotClosed()
        if (handle != 0L && node.handle != 0L) {
            nativeReset(handle, node.handle)
        }
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
        check(!closed) { "TsCursor is closed" }
    }

    private external fun nativeGoToFirstChild(handle: Long): Boolean
    private external fun nativeGoToNextSibling(handle: Long): Boolean
    private external fun nativeGoToParent(handle: Long): Boolean
    private external fun nativeCurrentNode(handle: Long): Long
    private external fun nativeCurrentFieldName(handle: Long): String?
    private external fun nativeReset(handle: Long, nodeHandle: Long)
    private external fun nativeClose(handle: Long)
}

/**
 * Compiled tree-sitter query.
 */
class TsQuery private constructor(
    val source: String,
    private var handle: Long,
) : AutoCloseable {
    private var closed = false
    private var cursorHandle: Long = 0L

    val patternCount: Int
        get() = if (handle != 0L) nativePatternCount(handle) else 0

    val captureCount: Int
        get() = if (handle != 0L) nativeCaptureCount(handle) else 0

    fun captureNameForId(id: Int): String? {
        if (handle == 0L) return null
        return nativeCaptureNameForId(handle, id)
    }

    /** Execute the query against a tree and return matches. */
    fun matches(tree: TsTree, rangeStart: Int = 0, rangeEnd: Int = tree.sourceText.length): List<TsQueryMatch> {
        if (handle == 0L || tree.handle == 0L) return emptyList()

        // Create cursor if needed
        if (cursorHandle == 0L) {
            cursorHandle = nativeCreateCursor()
        }
        if (cursorHandle == 0L) return emptyList()

        val rootNode = tree.rootNode
        if (rootNode.handle == 0L) return emptyList()

        nativeSetByteRange(cursorHandle, rangeStart, rangeEnd)
        nativeExec(cursorHandle, handle, rootNode.handle)

        val results = mutableListOf<TsQueryMatch>()
        while (true) {
            val matchData = nativeNextMatch(cursorHandle, handle) ?: break
            if (matchData.size < 2) continue

            val patternIndex = matchData[0]
            val captureCount = matchData[1]
            val captures = mutableListOf<TsQueryCapture>()

            for (i in 0 until captureCount) {
                val base = 2 + i * 6
                if (base + 5 >= matchData.size) break

                val captureIndex = matchData[base]
                val nodeStartByte = matchData[base + 1]
                val nodeEndByte = matchData[base + 2]
                val nodeStartRow = matchData[base + 3]
                val nodeStartCol = matchData[base + 4]
                val nodeEndRow = matchData[base + 5]

                val captureName = captureNameForId(captureIndex) ?: continue
                val node = TsNode(
                    tree = tree,
                    type = "",
                    startByte = nodeStartByte,
                    endByte = nodeEndByte,
                    startRow = nodeStartRow,
                    startColumn = nodeStartCol,
                    endRow = nodeEndRow,
                    handle = 0L,
                    nodeId = 0L,
                )
                captures.add(TsQueryCapture(node, captureIndex, captureName))
            }

            results.add(TsQueryMatch(patternIndex, captures))
        }

        rootNode.close()
        return results
    }

    override fun close() {
        if (!closed) {
            closed = true
            if (cursorHandle != 0L) {
                nativeDeleteCursor(cursorHandle)
                cursorHandle = 0L
            }
            if (handle != 0L) {
                nativeClose(handle)
                handle = 0L
            }
        }
    }

    private external fun nativePatternCount(handle: Long): Int
    private external fun nativeCaptureCount(handle: Long): Int
    private external fun nativeCaptureNameForId(handle: Long, id: Int): String?
    private external fun nativeCreateCursor(): Long
    private external fun nativeDeleteCursor(cursorHandle: Long)
    private external fun nativeExec(cursorHandle: Long, queryHandle: Long, nodeHandle: Long)
    private external fun nativeSetByteRange(cursorHandle: Long, start: Int, end: Int)
    private external fun nativeNextMatch(cursorHandle: Long, queryHandle: Long): IntArray?
    private external fun nativeClose(handle: Long)

    companion object {
        /** Create a query from source text for a given language. */
        fun create(language: TsLanguage, source: String): TsQuery? {
            if (language.nativeHandle == 0L) return null
            val handle = nativeCreate(language.nativeHandle, source)
            if (handle == 0L) return null
            return TsQuery(source, handle)
        }

        private external fun nativeCreate(langHandle: Long, source: String): Long
    }
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
 * Loaded dynamically from grammar .so files.
 */
class TsLanguage private constructor(
    val name: String,
    val extensions: List<String>,
    internal val nativeHandle: Long,
) {
    val version: Int
        get() = if (nativeHandle != 0L) nativeVersion(nativeHandle) else 0

    val fieldCount: Int
        get() = if (nativeHandle != 0L) nativeFieldCount(nativeHandle) else 0

    private external fun nativeVersion(handle: Long): Int
    private external fun nativeFieldCount(handle: Long): Int

    companion object {
        /**
         * Create a TsLanguage from metadata only (no native grammar loaded).
         * Used for registry entries before grammar .so is loaded.
         */
        fun create(name: String, extensions: List<String>): TsLanguage {
            return TsLanguage(name, extensions, 0L)
        }

        /**
         * Load a grammar from a native library.
         * @param libName Full path or name of the .so file (e.g., "tree-sitter-c")
         * @param funcName Exported function name (e.g., "tree_sitter_c")
         */
        fun load(libName: String, funcName: String, name: String, extensions: List<String>): TsLanguage? {
            val handle = nativeLoad(libName, funcName)
            if (handle == 0L) return null
            return TsLanguage(name, extensions, handle)
        }

        private external fun nativeLoad(libName: String, funcName: String): Long
    }
}
