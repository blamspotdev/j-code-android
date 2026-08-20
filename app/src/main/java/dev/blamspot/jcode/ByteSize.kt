package dev.blamspot.jcode

import java.util.Locale

/**
 * A byte count as a person reads it.
 *
 * One implementation because a file's size should not be written two different ways depending on
 * which screen is showing it — the image viewer's header and the virtual device's app details both
 * report the size of something on disk, and they now agree.
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
