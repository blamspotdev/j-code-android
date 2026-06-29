package dev.jcode.core.editor.completion

import androidx.compose.runtime.compositionLocalOf

/**
 * Supplies completion items for the identifier prefix currently being typed. The app provides this
 * (built from the focused file's language pack) so the editor pane can show completions without
 * depending on the marketplace/language-pack types directly. Defaults to no completions.
 */
val LocalCompletionSource = compositionLocalOf<(String) -> List<CompletionItem>> { { emptyList() } }
