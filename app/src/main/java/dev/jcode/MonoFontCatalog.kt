package dev.jcode

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import dev.jcode.design.FontOption
import java.io.File

/**
 * Built-in monospace fonts selectable for the editor and terminal. The id→[Typeface] resolution lives
 * here (it references the bundled JetBrains Mono in :core:editor); the settings screen only sees the
 * opaque ids + display names via [FontOption]. Extensions can contribute more fonts here later.
 */
object MonoFontCatalog {
    const val EDITOR_DEFAULT = "jetbrains-mono"
    const val TERMINAL_DEFAULT = "jetbrains-mono"

    /** Id retired from the catalog; a saved selection pointing at it falls back to the default. */
    const val RETIRED_SYSTEM = "system"

    private data class Entry(val id: String, val name: String, val load: (Context) -> Typeface)

    private val entries = listOf(
        Entry("jetbrains-mono", "JetBrains Mono") { ctx ->
            runCatching {
                ResourcesCompat.getFont(ctx, dev.jcode.core.editor.R.font.jetbrains_mono_regular)
            }.getOrNull() ?: Typeface.MONOSPACE
        },
    )

    /** id + display name for each built-in font, for the settings dropdowns. */
    val options: List<FontOption> = entries.map { FontOption(it.id, it.name) }

    /**
     * Load the [Typeface] for [id]. [envFontPaths] maps environment-font ids (discovered from the Linux
     * distro's installed fonts) to host file paths, loaded via [Typeface.createFromFile]; a built-in id
     * resolves from the catalog. Falls back to the first built-in for an unknown id, and to
     * [Typeface.MONOSPACE] for an env id whose file is missing (e.g. before discovery has run).
     */
    fun resolve(context: Context, id: String, envFontPaths: Map<String, String> = emptyMap()): Typeface {
        if (id.startsWith(ENV_PREFIX)) {
            val path = envFontPaths[id] ?: return Typeface.MONOSPACE
            return runCatching { Typeface.createFromFile(File(path)) }.getOrNull() ?: Typeface.MONOSPACE
        }
        return (entries.firstOrNull { it.id == id } ?: entries.first()).load(context)
    }

    /** Id prefix marking a font discovered from the Linux environment (vs a built-in). */
    const val ENV_PREFIX = "env:"
}
