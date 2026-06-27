package dev.jcode.core.editor.completion

/**
 * Types of completion items.
 */
enum class CompletionItemKind(val displayName: String) {
    TEXT("Text"),
    METHOD("Method"),
    FUNCTION("Function"),
    CONSTRUCTOR("Constructor"),
    FIELD("Field"),
    VARIABLE("Variable"),
    CLASS("Class"),
    STRUCT("Struct"),
    INTERFACE("Interface"),
    MODULE("Module"),
    PROPERTY("Property"),
    EVENT("Event"),
    OPERATOR("Operator"),
    UNIT("Unit"),
    VALUE("Value"),
    CONSTANT("Constant"),
    ENUM("Enum"),
    ENUM_MEMBER("EnumMember"),
    KEYWORD("Keyword"),
    SNIPPET("Snippet"),
    COLOR("Color"),
    FILE("File"),
    REFERENCE("Reference"),
    FOLDER("Folder"),
    TYPE_PARAMETER("TypeParameter"),
}

/**
 * A single completion item.
 */
data class CompletionItem(
    /** Display label shown in the completion list */
    val label: String,
    /** Kind of completion item */
    val kind: CompletionItemKind = CompletionItemKind.TEXT,
    /** Additional detail text (e.g., type signature) */
    val detail: String? = null,
    /** Documentation string */
    val documentation: String? = null,
    /** Text to insert (defaults to label if null) */
    val insertText: String? = null,
    /** Snippet text (LSP snippet syntax, takes precedence over insertText) */
    val snippetText: String? = null,
    /** Whether this item is deprecated */
    val deprecated: Boolean = false,
    /** Sort text for ordering (defaults to label) */
    val sortText: String = label,
    /** Filter text for matching (defaults to label) */
    val filterText: String = label,
    /** Source provider identifier */
    val source: String? = null,
)

/**
 * Interface for completion providers.
 */
interface CompletionProvider {
    /** Unique identifier for this provider */
    val id: String

    /**
     * Provide completion items for the given context.
     * @param text The full document text
     * @param offset The cursor byte offset
     * @param triggerChar The character that triggered completion, or null
     * @return List of completion items
     */
    suspend fun provide(text: String, offset: Int, triggerChar: Char?): List<CompletionItem>
}

/**
 * Context for the current completion session.
 */
data class CompletionContext(
    val items: List<CompletionItem>,
    val triggerOffset: Int,
    val triggerChar: Char?,
)
