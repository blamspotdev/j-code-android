package dev.jcode.editor

import dev.jcode.core.editor.completion.CompletionItem
import dev.jcode.core.editor.completion.CompletionItemKind
import dev.jcode.feature.marketplace.LanguagePack

/**
 * Build editor completion items for [prefix] from a language pack's completions, helpers, keywords and
 * types. Completions insert their text; helpers expand as snippets; keywords/types insert their word.
 * Returns empty when there's no pack or prefix. Drives the editor's as-you-type completion popup.
 */
fun languagePackCompletionItems(lang: LanguagePack?, prefix: String): List<CompletionItem> {
    if (lang == null || prefix.isEmpty()) return emptyList()
    val p = prefix.lowercase()
    val out = ArrayList<CompletionItem>()
    lang.completions.forEach { c ->
        if (c.label.lowercase().startsWith(p)) {
            // A pack's `insert` is LSP snippet syntax ($0/$1/…), so expand it as a snippet.
            out.add(
                CompletionItem(
                    label = c.label,
                    kind = CompletionItemKind.FUNCTION,
                    detail = c.detail.ifBlank { null },
                    snippetText = c.insert,
                )
            )
        }
    }
    lang.helpers.forEach { h ->
        if (h.title.lowercase().startsWith(p)) {
            out.add(
                CompletionItem(
                    label = h.title,
                    kind = CompletionItemKind.SNIPPET,
                    detail = "snippet",
                    snippetText = h.snippet,
                )
            )
        }
    }
    lang.keywords.forEach { k ->
        if (k.lowercase().startsWith(p)) out.add(CompletionItem(label = k, kind = CompletionItemKind.KEYWORD))
    }
    lang.types.forEach { t ->
        if (t.lowercase().startsWith(p)) out.add(CompletionItem(label = t, kind = CompletionItemKind.CLASS))
    }
    // A plain word identical to what's already typed adds nothing; drop it (snippets/inserts stay).
    return out.asSequence()
        .filterNot { it.label.equals(prefix, ignoreCase = true) && it.snippetText == null && it.insertText == null }
        .distinctBy { it.label to it.kind }
        .sortedBy { it.label.lowercase() }
        .take(50)
        .toList()
}
