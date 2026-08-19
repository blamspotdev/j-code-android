package dev.jcode.design

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.random.Random

/**
 * How editor file tabs are auto-colored. The app-level (Global) value is the default; a project
 * .jcode may override it. Stored by `enum.name`.
 *
 * - [RandomRemember]: on first open, pick a random color and remember it per file.
 * - [Random]: a random color each session, remembered for nothing.
 * - [DirectoryBased]: all files in a folder share one remembered random color.
 * - [Disabled]: no tab colors, and the "Change Tab Color" menu item is hidden.
 *
 * A manually-set color (via the tab menu) always takes precedence in every mode except [Disabled].
 */
enum class TabColoring {
    RandomRemember,
    Random,
    DirectoryBased,
    Disabled,
}

/** App-level (Global) tab-coloring default, backed by DataStore. A project .jcode can override it. */
class TabColoringSetting(
    val mode: TabColoring = SettingsDefaults.TAB_COLORING,
    val onChange: (TabColoring) -> Unit = {},
)

val LocalTabColoringSetting = compositionLocalOf { TabColoringSetting() }

/**
 * Resolves the accent color of an editor tab from its absolute file path. Backed by a plain map so
 * the (per-frame) tab strip lookup is cheap; returns null for tabs with no color (page tabs, or the
 * Disabled mode). Shared with the tab strip via [LocalEditorTabColors] to dodge JCodeShell params.
 */
class EditorTabColors(
    val colorFor: (String) -> Color? = { null },
    /** False when the effective mode is [TabColoring.Disabled] — hides the "Change Tab Color" item. */
    val pickerEnabled: Boolean = true,
)

val LocalEditorTabColors = compositionLocalOf { EditorTabColors() }

/**
 * The 25-color (5x5) tab accent palette shown in the "Change Tab Color" picker and drawn from for the
 * random/auto modes. Medium-saturation hues that read clearly as a thin accent in light and dark.
 */
val TabColorPalette: List<Color> = listOf(
    Color(0xFFEF5350), Color(0xFFEC407A), Color(0xFFAB47BC), Color(0xFF7E57C2), Color(0xFF5C6BC0),
    Color(0xFF42A5F5), Color(0xFF29B6F6), Color(0xFF26C6DA), Color(0xFF26A69A), Color(0xFF66BB6A),
    Color(0xFF9CCC65), Color(0xFFD4E157), Color(0xFFFFEE58), Color(0xFFFFCA28), Color(0xFFFFA726),
    Color(0xFFFF7043), Color(0xFF8D6E63), Color(0xFFBDBDBD), Color(0xFF78909C), Color(0xFFEF9A9A),
    Color(0xFFF48FB1), Color(0xFFCE93D8), Color(0xFF90CAF9), Color(0xFFA5D6A7), Color(0xFFFFF59D),
)

/** A random palette color, for the auto modes and the picker's "Random" button. */
fun randomTabColor(): Color = TabColorPalette[Random.nextInt(TabColorPalette.size)]

/** Serialize a color as `#RRGGBB` for YAML storage. */
fun tabColorToHex(color: Color): String = String.format("#%06X", 0xFFFFFF and color.toArgb())

/** Parse a `#RRGGBB` (or `RRGGBB`) hex string back to a color; null if malformed. */
fun tabColorFromHex(hex: String): Color? =
    runCatching { Color(("FF" + hex.trim().removePrefix("#")).toLong(16)) }.getOrNull()
