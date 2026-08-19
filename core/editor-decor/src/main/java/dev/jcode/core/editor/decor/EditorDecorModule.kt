package dev.jcode.core.editor.decor

/**
 * Editor decoration framework — layers, dirty-region tracking, inlays, squigglies, colored spans.
 *
 * Provides:
 * - [Decoration] interface and [Layer] z-order constants
 * - [DecorationSet] immutable collection with subscribe/notify
 * - [ColoredSpan] for syntax highlighting
 * - [SquiggleDecoration] for diagnostics (errors, warnings)
 * - [InlineDecoration] for inline widgets and ghost text
 * - [BackgroundDecoration] for line highlights
 * - [DirtyTracker] for incremental rendering
 */
object EditorDecorModule {
    const val VERSION = "1.0.0"
}
