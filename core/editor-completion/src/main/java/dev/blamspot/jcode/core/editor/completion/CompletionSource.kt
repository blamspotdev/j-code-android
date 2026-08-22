package dev.blamspot.jcode.core.editor.completion

import androidx.compose.runtime.compositionLocalOf

/**
 * Where the caret is when the editor asks for completions.
 *
 * A prefix alone is enough for keyword completion, but a language server resolves a *position*: the
 * same prefix means different things at different points in a file.
 */
data class CompletionQuery(
    /** The identifier prefix typed so far. */
    val prefix: String,
    /** Host path of the document, blank for a buffer with no file behind it. */
    val path: String,
    /** 0-based line. */
    val line: Int,
    /** 0-based offset within the line, in UTF-16 code units (LSP's `character`). */
    val character: Int,
)

/**
 * Supplies completion items at a caret position. The app provides this (merging the focused file's
 * language pack with its language server, if one is running) so the editor pane can show completions
 * without depending on the marketplace or LSP types directly.
 *
 * Suspending because a language server answers over IPC; the pane collects the result and is free to
 * render language-pack items in the meantime.
 */
fun interface CompletionSource {
    suspend fun completions(query: CompletionQuery): List<CompletionItem>
}

val LocalCompletionSource = compositionLocalOf { CompletionSource { emptyList() } }
