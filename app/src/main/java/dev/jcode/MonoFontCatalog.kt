package dev.jcode

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import dev.jcode.design.FontOption

/**
 * Built-in monospace fonts selectable for the editor and terminal. The id→[Typeface] resolution lives
 * here (it references the bundled JetBrains Mono in :core:editor); the settings screen only sees the
 * opaque ids + display names via [FontOption]. Extensions can contribute more fonts here later.
 */
object MonoFontCatalog {
    const val EDITOR_DEFAULT = "jetbrains-mono"
    const val TERMINAL_DEFAULT = "system"

    private data class Entry(val id: String, val name: String, val load: (Context) -> Typeface)

    private val entries = listOf(
        Entry("jetbrains-mono", "JetBrains Mono") { ctx ->
            runCatching {
                ResourcesCompat.getFont(ctx, dev.jcode.core.editor.R.font.jetbrains_mono_regular)
            }.getOrNull() ?: Typeface.MONOSPACE
        },
        Entry("system", "System monospace") { Typeface.MONOSPACE },
    )

    /** id + display name for each built-in font, for the settings dropdowns. */
    val options: List<FontOption> = entries.map { FontOption(it.id, it.name) }

    /** Load the [Typeface] for [id], falling back to the first built-in for an unknown id. */
    fun resolve(context: Context, id: String): Typeface =
        (entries.firstOrNull { it.id == id } ?: entries.first()).load(context)
}
