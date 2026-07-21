package dev.jcode

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
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

    private data class Entry(val id: String, val name: String, val resId: Int)

    private val entries = listOf(
        Entry("jetbrains-mono", "JetBrains Mono", dev.jcode.core.editor.R.font.jetbrains_mono_regular),
    )

    /** id + display name for each built-in font, for the settings dropdowns. */
    val options: List<FontOption> = entries.map { FontOption(it.id, it.name) }

    /**
     * Load the [Typeface] for [id]. [envFontPaths] maps environment-font ids (discovered from the Linux
     * distro's installed fonts) to host file paths, loaded via [Typeface.createFromFile]; a built-in id
     * resolves from the catalog. Falls back to the first built-in for an unknown id, and to
     * [Typeface.MONOSPACE] for an env id whose file is missing (e.g. before discovery has run).
     *
     * When [systemFallback] is set, the primary font is wrapped with the platform's system fallback
     * chain so codepoints it lacks resolve from Noto/system fonts instead of rendering as tofu — used by
     * the terminal, where TUIs (e.g. Claude Code) emit symbol glyphs like U+23F5 that JetBrains Mono
     * doesn't ship. Wrapping is best-effort: it degrades to the plain (non-fallback) typeface on any
     * failure, so a font resource the fallback builder can't consume never breaks rendering.
     */
    fun resolve(
        context: Context,
        id: String,
        envFontPaths: Map<String, String> = emptyMap(),
        systemFallback: Boolean = false,
    ): Typeface {
        if (id.startsWith(ENV_PREFIX)) {
            val path = envFontPaths[id] ?: return Typeface.MONOSPACE
            if (systemFallback) withSystemFallback { Font.Builder(File(path)).build() }?.let { return it }
            return runCatching { Typeface.createFromFile(File(path)) }.getOrNull() ?: Typeface.MONOSPACE
        }
        val entry = entries.firstOrNull { it.id == id } ?: entries.first()
        if (systemFallback) {
            withSystemFallback { Font.Builder(context.resources, entry.resId).build() }?.let { return it }
        }
        return runCatching { ResourcesCompat.getFont(context, entry.resId) }.getOrNull() ?: Typeface.MONOSPACE
    }

    /**
     * Build a [Typeface] whose primary family is [buildFont] but that falls back to the system monospace
     * chain for any missing glyph. Returns null on failure so callers keep the plain typeface.
     */
    private fun withSystemFallback(buildFont: () -> Font): Typeface? = runCatching {
        val family = FontFamily.Builder(buildFont()).build()
        Typeface.CustomFallbackBuilder(family)
            .setSystemFallback("monospace")
            .build()
    }.getOrNull()

    /** Id prefix marking a font discovered from the Linux environment (vs a built-in). */
    const val ENV_PREFIX = "env:"
}
