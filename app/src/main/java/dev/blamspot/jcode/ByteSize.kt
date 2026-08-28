package dev.blamspot.jcode

import java.util.Locale

/**
 * A byte count as a person reads it.
 *
 * One implementation because a file's size should not be written two different ways depending on
 * which screen is showing it. The virtual device used to be the other caller; it ships in the
 * Android Dev Pack now and carries its own copy, because this one is `internal` and making it
 * public would put a formatting helper into the extension ABI.
 *
 * [Locale.US] rather than the default: this is a number beside a fixed English unit, so a locale
 * that writes `16,4 MB` would produce a string that reads as a thousands separator to everyone who
 * sees the unit.
 */
internal fun humanSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
