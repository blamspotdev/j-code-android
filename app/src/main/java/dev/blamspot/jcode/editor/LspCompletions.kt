package dev.blamspot.jcode.editor

import dev.blamspot.jcode.core.editor.completion.CompletionItem
import dev.blamspot.jcode.core.editor.completion.CompletionItemKind
import dev.blamspot.jcode.core.lsp.CompletionResult

/**
 * A language server's completion item in the editor's model.
 *
 * `insertTextFormat` 2 means the text carries LSP snippet syntax (`${1:name}`), which the editor's
 * snippet engine expands; 1 is literal text.
 */
fun CompletionResult.toEditorItem(): CompletionItem {
    val isSnippet = insertTextFormat == SNIPPET_FORMAT
    return CompletionItem(
        label = label,
        kind = kind.toCompletionItemKind(),
        detail = detail,
        documentation = documentation,
        insertText = if (isSnippet) null else insertText,
        snippetText = if (isSnippet) insertText else null,
        sortText = sortText ?: label,
        filterText = label,
        source = "lsp",
    )
}

/** LSP `CompletionItemKind` is a fixed 1..25 enumeration. */
private fun Int.toCompletionItemKind(): CompletionItemKind = when (this) {
    2 -> CompletionItemKind.METHOD
    3 -> CompletionItemKind.FUNCTION
    4 -> CompletionItemKind.CONSTRUCTOR
    5 -> CompletionItemKind.FIELD
    6 -> CompletionItemKind.VARIABLE
    7 -> CompletionItemKind.CLASS
    8 -> CompletionItemKind.INTERFACE
    9 -> CompletionItemKind.MODULE
    10 -> CompletionItemKind.PROPERTY
    11 -> CompletionItemKind.UNIT
    12 -> CompletionItemKind.VALUE
    13 -> CompletionItemKind.ENUM
    14 -> CompletionItemKind.KEYWORD
    15 -> CompletionItemKind.SNIPPET
    16 -> CompletionItemKind.COLOR
    17 -> CompletionItemKind.FILE
    18 -> CompletionItemKind.REFERENCE
    19 -> CompletionItemKind.FOLDER
    20 -> CompletionItemKind.ENUM_MEMBER
    21 -> CompletionItemKind.CONSTANT
    22 -> CompletionItemKind.STRUCT
    23 -> CompletionItemKind.EVENT
    24 -> CompletionItemKind.OPERATOR
    25 -> CompletionItemKind.TYPE_PARAMETER
    else -> CompletionItemKind.TEXT
}

private const val SNIPPET_FORMAT = 2
