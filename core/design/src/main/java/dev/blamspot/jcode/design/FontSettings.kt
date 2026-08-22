package dev.blamspot.jcode.design

import android.graphics.Typeface
import androidx.compose.runtime.compositionLocalOf

/** One selectable monospace font: a stable [id] and a display [name]. The id→Typeface resolution
 *  (and the built-in catalog) lives in the app; extensions can contribute more fonts later. */
data class FontOption(val id: String, val name: String)

/**
 * Editor + terminal font-family selection, shared (via [LocalFontSettings]) with the settings screen
 * so the dropdowns need no JCodeShell param (ART register limit). Ids are opaque; [options] maps them
 * to display names. The resolved [Typeface]s reach the views via [LocalEditorTypeface] /
 * [LocalTerminalTypeface].
 */
class FontSettings(
    val options: List<FontOption> = emptyList(),
    val editorFontId: String = "",
    val terminalFontId: String = "",
    val editorDefaultId: String = "",
    val terminalDefaultId: String = "",
    val onSelectEditorFont: (String) -> Unit = {},
    val onSelectTerminalFont: (String) -> Unit = {},
    /** Re-scan the Linux environment's installed fonts (called when the font settings open). */
    val onScanFonts: () -> Unit = {},
)

val LocalFontSettings = compositionLocalOf { FontSettings() }

/** The resolved editor font, pushed to the EditorView; defaults to the system monospace. */
val LocalEditorTypeface = compositionLocalOf { Typeface.MONOSPACE }

/** The resolved terminal font, pushed to the TerminalView; defaults to the system monospace. */
val LocalTerminalTypeface = compositionLocalOf { Typeface.MONOSPACE }
