package dev.jcode.core.editor.completion

/**
 * Editor completion framework — completion window, providers, snippet engine, ghost text.
 *
 * Provides:
 * - [CompletionItem] model with kind, label, detail, snippet
 * - [CompletionProvider] interface for pluggable completion sources
 * - [CompletionWindow] composable popup anchored to caret
 * - [SnippetEngine] for LSP snippet syntax ($0, ${1:placeholder}, etc.)
 * - [GhostTextProvider] for AI inline completion previews
 */
object EditorCompletionModule {
    val snippetEngine = SnippetEngine()
}
