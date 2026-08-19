package dev.blamspot.jcode.core.editor.completion

/**
 * Editor completion framework — completion window, providers, snippet engine, ghost text.
 *
 * Provides:
 * - [CompletionItem] model with kind, label, detail, snippet
 * - [CompletionSource] the caret-position query the host answers (language pack + language server)
 * - [CompletionWindow] composable popup anchored to caret
 * - [SnippetEngine] for LSP snippet syntax ($0, ${1:placeholder}, etc.)
 * - [GhostTextProvider] for AI inline completion previews
 */
object EditorCompletionModule {
    val snippetEngine = SnippetEngine()
}
