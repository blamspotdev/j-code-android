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
            if (systemFallback) withSystemFallback(context) { Font.Builder(File(path)).build() }?.let { return it }
            return runCatching { Typeface.createFromFile(File(path)) }.getOrNull() ?: Typeface.MONOSPACE
        }
        val entry = entries.firstOrNull { it.id == id } ?: entries.first()
        if (systemFallback) {
            withSystemFallback(context) { Font.Builder(context.resources, entry.resId).build() }?.let { return it }
        }
        return runCatching { ResourcesCompat.getFont(context, entry.resId) }.getOrNull() ?: Typeface.MONOSPACE
    }

    /**
     * Build a [Typeface] whose primary family is [buildPrimary], falling back first to the bundled
     * Noto Sans Symbols 2 (media/technical/geometric glyphs that TUIs like Claude Code emit — e.g. the
     * "auto-accept" indicator U+23F5 — which the device's *subsetted* system symbol fonts don't ship),
     * then to the system monospace chain (CJK, other symbols). Returns null on failure so callers keep
     * the plain typeface. The bundled fallback is best-effort: skipped if its resource can't load.
     */
    private fun withSystemFallback(context: Context, buildPrimary: () -> Font): Typeface? = runCatching {
        val builder = Typeface.CustomFallbackBuilder(FontFamily.Builder(buildPrimary()).build())
        runCatching {
            val symbols = Font.Builder(context.resources, dev.jcode.R.font.noto_sans_symbols2).build()
            builder.addCustomFallback(FontFamily.Builder(symbols).build())
        }
        builder.setSystemFallback("monospace").build()
    }.getOrNull()

    /** Id prefix marking a font discovered from the Linux environment (vs a built-in). */
    const val ENV_PREFIX = "env:"
}
