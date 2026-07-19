package dev.jcode.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Extra semantic colors that Material's [ColorScheme] does not model (it only carries `error`).
 * Provided per theme bundle so success/warning/info stay consistent and themeable instead of being
 * hardcoded at call sites.
 */
@Immutable
data class JCodeSemanticColors(
    val success: Color,
    val onSuccess: Color,
    val warning: Color,
    val onWarning: Color,
    val info: Color,
    val onInfo: Color,
)

/** Single spacing scale so padding/gaps are consistent instead of ad-hoc per screen. */
@Immutable
data class JCodeSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
)

val LocalSpacing = staticCompositionLocalOf { JCodeSpacing() }

val LocalSemanticColors = staticCompositionLocalOf {
    ThemeBundleRegistry.default.darkSemantic
}

/**
 * A selectable look: a Material color scheme for dark and light, matching semantic colors, and an
 * optional UI font. Built-in bundles are defined below; the same shape is what a YAML/asset bundle
 * extension would deserialize into, so disk-loaded bundles can be added later without touching the UI.
 */
@Immutable
data class ThemeBundle(
    val id: String,
    val name: String,
    val description: String,
    val author: String = "JCode",
    val dark: ColorScheme,
    val light: ColorScheme,
    val darkSemantic: JCodeSemanticColors,
    val lightSemantic: JCodeSemanticColors,
    val fontFamily: FontFamily? = null,
) {
    fun colorScheme(darkTheme: Boolean): ColorScheme = if (darkTheme) dark else light
    fun semanticColors(darkTheme: Boolean): JCodeSemanticColors = if (darkTheme) darkSemantic else lightSemantic
}

object ThemeBundleRegistry {
    val builtIns: List<ThemeBundle> = listOf(
        catppuccinBundle,
        draculaBundle,
        midnightBundle,
    )

    val default: ThemeBundle get() = catppuccinBundle

    fun byId(id: String?): ThemeBundle = builtIns.firstOrNull { it.id == id } ?: default
}

// --- Built-in bundles ------------------------------------------------------------------------

// Catppuccin — Mocha (dark) / Latte (light). The app's original palette, kept as the default.
private val catppuccinBundle = ThemeBundle(
    id = "catppuccin",
    name = "Catppuccin",
    description = "Soft pastel theme (Mocha / Latte).",
    dark = darkColorScheme(
        primary = Color(0xFF89B4FA),
        onPrimary = Color(0xFF1E1E2E),
        primaryContainer = Color(0xFF313244),
        onPrimaryContainer = Color(0xFFCDD6F4),
        secondary = Color(0xFFCBA6F7),
        onSecondary = Color(0xFF1E1E2E),
        tertiary = Color(0xFFA6E3A1),
        onTertiary = Color(0xFF1E1E2E),
        background = Color(0xFF1E1E2E),
        onBackground = Color(0xFFCDD6F4),
        surface = Color(0xFF181825),
        onSurface = Color(0xFFCDD6F4),
        surfaceVariant = Color(0xFF313244),
        onSurfaceVariant = Color(0xFFBAC2DE),
    ),
    light = lightColorScheme(
        primary = Color(0xFF1E66F5),
        onPrimary = Color(0xFFEFF1F5),
        primaryContainer = Color(0xFFCCD0DA),
        onPrimaryContainer = Color(0xFF4C4F69),
        secondary = Color(0xFF8839EF),
        onSecondary = Color(0xFFEFF1F5),
        tertiary = Color(0xFF40A02B),
        onTertiary = Color(0xFFEFF1F5),
        background = Color(0xFFEFF1F5),
        onBackground = Color(0xFF4C4F69),
        surface = Color(0xFFE6E9EF),
        onSurface = Color(0xFF4C4F69),
        surfaceVariant = Color(0xFFCCD0DA),
        onSurfaceVariant = Color(0xFF5C5F77),
    ),
    darkSemantic = JCodeSemanticColors(
        success = Color(0xFFA6E3A1), onSuccess = Color(0xFF1E1E2E),
        warning = Color(0xFFF9E2AF), onWarning = Color(0xFF1E1E2E),
        info = Color(0xFF89DCEB), onInfo = Color(0xFF1E1E2E),
    ),
    lightSemantic = JCodeSemanticColors(
        success = Color(0xFF40A02B), onSuccess = Color(0xFFEFF1F5),
        warning = Color(0xFFDF8E1D), onWarning = Color(0xFFEFF1F5),
        info = Color(0xFF209FB5), onInfo = Color(0xFFEFF1F5),
    ),
)

// Dracula — classic dark; paired with a soft light variant.
private val draculaBundle = ThemeBundle(
    id = "dracula",
    name = "Dracula",
    description = "High-contrast dark with vivid accents.",
    dark = darkColorScheme(
        primary = Color(0xFFBD93F9),
        onPrimary = Color(0xFF282A36),
        primaryContainer = Color(0xFF44475A),
        onPrimaryContainer = Color(0xFFF8F8F2),
        secondary = Color(0xFFFF79C6),
        onSecondary = Color(0xFF282A36),
        tertiary = Color(0xFF50FA7B),
        onTertiary = Color(0xFF282A36),
        background = Color(0xFF282A36),
        onBackground = Color(0xFFF8F8F2),
        surface = Color(0xFF21222C),
        onSurface = Color(0xFFF8F8F2),
        surfaceVariant = Color(0xFF44475A),
        onSurfaceVariant = Color(0xFFBFC7D5),
    ),
    light = lightColorScheme(
        primary = Color(0xFF7C4DD6),
        onPrimary = Color(0xFFF8F8F2),
        primaryContainer = Color(0xFFE5DEF7),
        onPrimaryContainer = Color(0xFF282A36),
        secondary = Color(0xFFD6247E),
        onSecondary = Color(0xFFF8F8F2),
        tertiary = Color(0xFF1F8B3F),
        onTertiary = Color(0xFFF8F8F2),
        background = Color(0xFFF7F6FB),
        onBackground = Color(0xFF282A36),
        surface = Color(0xFFEFEDF6),
        onSurface = Color(0xFF282A36),
        surfaceVariant = Color(0xFFDDD8EC),
        onSurfaceVariant = Color(0xFF565869),
    ),
    darkSemantic = JCodeSemanticColors(
        success = Color(0xFF50FA7B), onSuccess = Color(0xFF282A36),
        warning = Color(0xFFF1FA8C), onWarning = Color(0xFF282A36),
        info = Color(0xFF8BE9FD), onInfo = Color(0xFF282A36),
    ),
    lightSemantic = JCodeSemanticColors(
        success = Color(0xFF1F8B3F), onSuccess = Color(0xFFF8F8F2),
        warning = Color(0xFFB59B00), onWarning = Color(0xFF282A36),
        info = Color(0xFF1B91A8), onInfo = Color(0xFFF8F8F2),
    ),
)

// Midnight — a true-black theme tuned for OLED displays: pure #000000 canvas so unlit pixels stay
// dark, with cool blue/violet accents that stay legible against the black.
private val midnightBundle = ThemeBundle(
    id = "midnight",
    name = "Midnight OLED",
    description = "True-black theme tuned for OLED displays.",
    dark = darkColorScheme(
        primary = Color(0xFF82AAFF),
        onPrimary = Color(0xFF00030D),
        primaryContainer = Color(0xFF1E2540),
        onPrimaryContainer = Color(0xFFC8D3F5),
        secondary = Color(0xFFC099FF),
        onSecondary = Color(0xFF11061F),
        tertiary = Color(0xFF4FD6BE),
        onTertiary = Color(0xFF00201A),
        background = Color(0xFF000000),
        onBackground = Color(0xFFC8D3F5),
        surface = Color(0xFF000000),
        onSurface = Color(0xFFC8D3F5),
        surfaceVariant = Color(0xFF262A40),
        onSurfaceVariant = Color(0xFFA9B2D0),
    ),
    light = lightColorScheme(
        primary = Color(0xFF2E7DE9),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD3DDF6),
        onPrimaryContainer = Color(0xFF17284A),
        secondary = Color(0xFF9854F1),
        onSecondary = Color(0xFFFFFFFF),
        tertiary = Color(0xFF118C74),
        onTertiary = Color(0xFFFFFFFF),
        background = Color(0xFFE1E2E7),
        onBackground = Color(0xFF3760BF),
        surface = Color(0xFFDADBE2),
        onSurface = Color(0xFF3760BF),
        surfaceVariant = Color(0xFFC7CBDB),
        onSurfaceVariant = Color(0xFF585F80),
    ),
    darkSemantic = JCodeSemanticColors(
        success = Color(0xFFC3E88D), onSuccess = Color(0xFF06210A),
        warning = Color(0xFFFFC777), onWarning = Color(0xFF201400),
        info = Color(0xFF86E1FC), onInfo = Color(0xFF00212B),
    ),
    lightSemantic = JCodeSemanticColors(
        success = Color(0xFF587539), onSuccess = Color(0xFFFFFFFF),
        warning = Color(0xFF8F5E15), onWarning = Color(0xFFFFFFFF),
        info = Color(0xFF007197), onInfo = Color(0xFFFFFFFF),
    ),
)
