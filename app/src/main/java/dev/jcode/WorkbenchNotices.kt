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

    /**
     * One problem: a line to show, and the output it came out of.
     *
     * [message] is whatever the failing tool said first, which is routinely the least useful thing
     * it said — "Install failed." names the outcome and not one reason for it. [detail] is the run's
     * own log, kept so the pane can be opened up to what actually happened instead of sending the
     * user back to re-run the thing that just failed.
     */
    data class Notice(val message: String, val detail: List<String> = emptyList())

    private val bySource = MutableStateFlow<Map<String, List<Notice>>>(emptyMap())

    val notices: StateFlow<Map<String, List<Notice>>> = bySource.asStateFlow()

    /**
     * Replace what [source] is reporting. Whole-set rather than add/remove because a panel knows its
     * current problems, not which of yesterday's have since been fixed — an append-only list would
     * keep showing an install failure after the retry succeeded.
     */
    fun set(source: String, notices: List<Notice>) {
        val cleaned = notices.filter { it.message.isNotBlank() }.distinctBy { it.message }
        val current = bySource.value
        if (current[source].orEmpty() == cleaned) return
        bySource.value = if (cleaned.isEmpty()) current - source else current + (source to cleaned)
    }
}
