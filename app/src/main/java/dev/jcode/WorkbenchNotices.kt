package dev.jcode

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Things that went wrong away from a file.
 *
 * The Issues pane is built on [dev.jcode.core.lsp.DiagnosticsBus], which keys everything by file
 * URI and line — right for a compiler or a language server, and wrong for "the Android SDK install
 * failed", which belongs to no file and has no line to jump to. Those used to be shown as a banner
 * inside the panel that produced them, where they pushed the list down and were only visible while
 * that panel was open. They live here instead, and the panel keeps a "!" that opens the Issues pane.
 *
 * Process-scoped for the same reason [dev.jcode.workbench.VsixTerminals] is: the producer is a
 * drawer panel that is composed away the moment the user switches tools, and the notice has to
 * outlive it.
 */
object WorkbenchNotices {

    /** One problem, and the part of the workbench reporting it. */
    data class Notice(val source: String, val message: String)

    private val bySource = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    val notices: StateFlow<Map<String, List<String>>> = bySource.asStateFlow()

    /** Everything currently reported, flattened in source order. */
    fun all(): List<Notice> = bySource.value.entries
        .sortedBy { it.key }
        .flatMap { (source, messages) -> messages.map { Notice(source, it) } }

    /**
     * Replace what [source] is reporting. Whole-set rather than add/remove because a panel knows its
     * current problems, not which of yesterday's have since been fixed — an append-only list would
     * keep showing an install failure after the retry succeeded.
     */
    fun set(source: String, messages: List<String>) {
        val distinct = messages.filter { it.isNotBlank() }.distinct()
        val current = bySource.value
        if (current[source].orEmpty() == distinct) return
        bySource.value = if (distinct.isEmpty()) current - source else current + (source to distinct)
    }
}
