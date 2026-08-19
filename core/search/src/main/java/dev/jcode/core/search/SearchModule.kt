package dev.jcode.core.search

/**
 * Text search engine for project-wide find and replace.
 *
 * Provides:
 * - [SearchEngine] - in-process Java search with regex, glob filtering, case sensitivity
 * - [SearchMatch] - a single search result (file, line, column, text)
 * - [SearchOptions] - query, regex flag, include/exclude patterns, limits
 * - Ripgrep integration via external command execution (PTY/distro)
 * - Replace-all with file modification tracking
 */
object SearchModule {
    val engine = SearchEngine()
}

