package dev.blamspot.jcode.feature.editor.pane

import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

/** Trades an opaque [InspectedValue.reference] for that value's children. */
internal typealias ChildExpander = (Int, (List<InspectedValue>) -> Unit) -> Unit

/**
 * How far down the object graph a copy walks. Deep enough for the data people actually copy,
 * shallow enough that a cyclic graph (parent -> child -> parent) cannot run away.
 */
private const val JSON_MAX_DEPTH = 4

/** Ceiling on how many values one copy fetches, so copying the wrong thing can't flood the adapter. */
private const val JSON_MAX_NODES = 500

private const val INDENT = "  "

private val NUMBER = Regex("""-?\d+(\.\d+)?([eE][-+]?\d+)?""")

/** `{int[4]}` — an array's rendering carries its length in brackets. */
private val ARRAY_VALUE = Regex(""".*\[\d+]""")

/**
 * What a container calls how many elements it holds. `capacity` is deliberately absent: that is the
 * buffer it allocated, not what is in it.
 */
private val SIZE_NAMES = setOf("count", "length", "size", "_size", "_count")

/**
 * How many elements a container holds, read from its children: the number of indexed entries
 * (`[0]`, `[1]`, ...) if the adapter lists them, else a size field. 0 when the children say nothing
 * about a size, which is the right answer for a plain struct.
 */
fun elementCountOf(children: List<InspectedValue>): Int {
    children.indexedElements()?.let { return it.size }
    return children.firstOrNull { it.name.lowercase() in SIZE_NAMES }?.value?.trim()?.toIntOrNull() ?: 0
}

/**
 * An inspected value as pretty-printed JSON: the list the adapter would only describe as
 * `{System.Collections.Generic.List<int>}` comes back as `[10, 20, 30]`, and an object as its fields.
 *
 * Containers are walked by asking [expand] for each level's children — one adapter round-trip per
 * container actually reached — so this suspends and is not for the UI's critical path. A scalar needs
 * no walk and comes back as its own text, untouched: copying `30` pastes `30`, not `"30"`.
 */
internal suspend fun InspectedValue.toJson(expand: ChildExpander?): String =
    if (!expandable || expand == null) value
    else encode(this, expand, depth = 0, budget = intArrayOf(JSON_MAX_NODES))

private suspend fun encode(value: InspectedValue, expand: ChildExpander, depth: Int, budget: IntArray): String {
    if (!value.expandable) return leaf(value)
    // Out of depth or budget: the adapter's own rendering, as a string. It says what was left
    // unexpanded, which a bare `{}` would not.
    if (depth >= JSON_MAX_DEPTH || budget[0] <= 0) return quote(value.value)
    val children = fetchChildren(expand, value.reference).filterNot { it.isPrototypeChain }
    budget[0] -= children.size
    if (children.isEmpty()) return "{}"

    val pad = INDENT.repeat(depth + 1)
    val closePad = INDENT.repeat(depth)
    val elements = children.indexedElements()
        ?: children.elementStorage(expand)?.also { budget[0] -= it.size }
    val out = StringBuilder()
    if (elements != null) {
        out.append("[\n")
        elements.forEachIndexed { i, child ->
            if (i > 0) out.append(",\n")
            out.append(pad).append(encode(child, expand, depth + 1, budget))
        }
        out.append("\n").append(closePad).append("]")
    } else {
        out.append("{\n")
        children.forEachIndexed { i, child ->
            if (i > 0) out.append(",\n")
            out.append(pad).append(quote(child.name)).append(": ")
                .append(encode(child, expand, depth + 1, budget))
        }
        out.append("\n").append(closePad).append("}")
    }
    return out.toString()
}

/**
 * A collection's elements when the adapter exposes only the machinery holding them.
 *
 * netcoredbg hands back a `List<int>` as its private fields — `_items` (the backing array, sized to
 * the capacity), `_size`, `Count`, the explicit interface implementations — and no `[0]`, `[1]`, ...
 * entries at all, so copying it verbatim yields the plumbing instead of the data. A value shaped
 * like a collection — exactly one array-valued field, plus a size that isn't the capacity — copies
 * as the elements that size says are live. Null when the children aren't that shape, leaving the
 * value an object like any other.
 */
private suspend fun List<InspectedValue>.elementStorage(expand: ChildExpander): List<InspectedValue>? {
    val size = firstOrNull { it.name.lowercase() in SIZE_NAMES }?.value?.trim()?.toIntOrNull() ?: return null
    if (size < 0) return null
    val storage = filter { it.expandable && it.looksLikeArray }.singleOrNull() ?: return null
    val rows = fetchChildren(expand, storage.reference)
    // An empty backing array lists no children at all, and an empty collection is still a collection.
    val elements = rows.indexedElements() ?: if (rows.isEmpty()) emptyList() else return null
    return elements.take(size)
}

private val InspectedValue.looksLikeArray: Boolean
    get() = type?.endsWith("[]") == true || ARRAY_VALUE.matches(displayValue)

/**
 * The prototype chain js-debug hangs off every object and array. It is the language's machinery, not
 * the value, and walking it turns copying a four-field object into two hundred lines of `toString`
 * and `hasOwnProperty`.
 */
private val InspectedValue.isPrototypeChain: Boolean
    get() = name == "[[Prototype]]" || name == "__proto__"

/**
 * The `[0]`, `[1]`, ... entries in index order, or null when there are none.
 *
 * Their presence is what makes a container an array rather than an object, and the rest of what the
 * adapter lists alongside them — a size, a capacity, `Non-Public members` — is machinery about the
 * container, not contents, so an array copies as its elements only.
 */
private fun List<InspectedValue>.indexedElements(): List<InspectedValue>? {
    val bracketed = mapNotNull { row ->
        row.name.takeIf { it.length > 2 && it.startsWith("[") && it.endsWith("]") }
            ?.drop(1)?.dropLast(1)?.toIntOrNull()
            ?.let { index -> index to row }
    }
    if (bracketed.isNotEmpty()) return bracketed.sortedBy { it.first }.map { it.second }
    return numberedElements()
}

/**
 * The same elements where the adapter drops the brackets: js-debug names an array's entries `0`,
 * `1`, `2` and lists `length` beside them.
 *
 * A bare number is a weaker signal than `[0]` — an object can have numeric keys — so this insists on
 * a complete `0..n-1` run whose only named siblings are the length, which an array always satisfies
 * and a dictionary that merely counts from zero does not.
 */
private fun List<InspectedValue>.numberedElements(): List<InspectedValue>? {
    val numbered = mapNotNull { row -> row.name.toIntOrNull()?.let { index -> index to row } }
    if (numbered.isEmpty()) return null
    val sorted = numbered.sortedBy { it.first }
    if (sorted.withIndex().any { (position, entry) -> entry.first != position }) return null
    if (any { it.name.toIntOrNull() == null && it.name.lowercase() !in SIZE_NAMES }) return null
    return sorted.map { it.second }
}

private suspend fun fetchChildren(expand: ChildExpander, reference: Int): List<InspectedValue> =
    suspendCancellableCoroutine { cont ->
        expand(reference) { rows -> if (cont.isActive) cont.resume(rows) }
    }

/**
 * One leaf value as JSON. Adapters render values for people, not machines, so the text is read back
 * into a JSON type where it plainly is one and quoted where it isn't.
 */
private fun leaf(value: InspectedValue): String {
    val raw = value.value.trim()
    return when {
        raw.isEmpty() -> "\"\""
        raw == "null" || raw == "nil" || raw == "None" -> "null"
        raw == "true" || raw == "false" -> raw
        raw == "True" || raw == "False" -> raw.lowercase()
        NUMBER.matches(raw) -> raw
        // A double-quoted value is already an escaped string: netcoredbg, lldb, js-debug and debugpy
        // all use C-style escapes, which JSON shares, so it passes through. Python's single quotes do
        // not, so that text is re-encoded.
        raw.length > 1 && raw.startsWith("\"") && raw.endsWith("\"") -> raw
        raw.length > 1 && raw.startsWith("'") && raw.endsWith("'") -> quote(raw.substring(1, raw.length - 1))
        else -> quote(raw)
    }
}

private fun quote(text: String): String = JSONObject.quote(text)
