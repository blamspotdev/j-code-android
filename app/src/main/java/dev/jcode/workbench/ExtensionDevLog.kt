package dev.jcode.workbench

import androidx.compose.runtime.mutableStateListOf

/** One line in the Extension Dev log: an extension's API/exec call, a host→extension event, or a
 *  WebView console message. */
data class ExtensionDevLogEntry(
    val kind: Kind,
    /** The extension id this line belongs to (blank for broadcast events). */
    val extId: String,
    val message: String,
    val seq: Long,
) {
    enum class Kind { Request, Response, Event, Console, Error }
}

/**
 * Process-global sink for the Extension Dev tools' live log, observed by the right-drawer panel while
 * it's open and written from the extension bridge / WebView (which live elsewhere in the tree) — same
 * singleton rationale as [BuiltinBrowser]. Only extensions whose id is in [devIds] are recorded, so a
 * normal (signed) extension never incurs logging overhead or leaks into the dev tools.
 */
object ExtensionDevLog {
    val entries = mutableStateListOf<ExtensionDevLogEntry>()

    /** Ids of the currently-installed **dev** (unsigned sideloaded) extensions; kept in sync by the
     *  view model. Logging is gated on this so signed extensions are never instrumented. */
    @Volatile
    var devIds: Set<String> = emptySet()

    private var counter = 0L

    fun isDev(extId: String): Boolean = extId in devIds

    fun log(kind: ExtensionDevLogEntry.Kind, extId: String, message: String) {
        // Events are broadcast (no single ext id) — always record them when ANY dev extension exists,
        // so an author can watch what the host pushes out; other kinds are gated on the specific id.
        val record = when (kind) {
            ExtensionDevLogEntry.Kind.Event -> devIds.isNotEmpty()
            else -> extId in devIds
        }
        if (!record) return
        synchronized(this) {
            if (entries.size >= MAX_ENTRIES) entries.removeAt(0)
            entries.add(ExtensionDevLogEntry(kind, extId, message.take(MAX_LINE), counter++))
        }
    }

    fun clear() = synchronized(this) { entries.clear() }

    private const val MAX_ENTRIES = 800
    private const val MAX_LINE = 4000
}
