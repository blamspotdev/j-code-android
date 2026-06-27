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
    val author: String = "J Code",
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
        nordBundle,
        gruvboxBundle,
        oneDarkBundle,
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

// Nord — cool arctic palette.
private val nordBundle = ThemeBundle(
    id = "nord",
    name = "Nord",
    description = "Cool, muted arctic palette.",
    dark = darkColorScheme(
        primary = Color(0xFF88C0D0),
        onPrimary = Color(0xFF2E3440),
        primaryContainer = Color(0xFF434C5E),
        onPrimaryContainer = Color(0xFFECEFF4),
        secondary = Color(0xFF81A1C1),
        onSecondary = Color(0xFF2E3440),
        tertiary = Color(0xFFA3BE8C),
        onTertiary = Color(0xFF2E3440),
        background = Color(0xFF2E3440),
        onBackground = Color(0xFFECEFF4),
        surface = Color(0xFF272C36),
        onSurface = Color(0xFFE5E9F0),
        surfaceVariant = Color(0xFF3B4252),
        onSurfaceVariant = Color(0xFFD8DEE9),
    ),
    light = lightColorScheme(
        primary = Color(0xFF5E81AC),
        onPrimary = Color(0xFFECEFF4),
        primaryContainer = Color(0xFFD8DEE9),
        onPrimaryContainer = Color(0xFF2E3440),
        secondary = Color(0xFF81A1C1),
        onSecondary = Color(0xFFECEFF4),
        tertiary = Color(0xFFA3BE8C),
        onTertiary = Color(0xFF2E3440),
        background = Color(0xFFECEFF4),
        onBackground = Color(0xFF2E3440),
        surface = Color(0xFFE5E9F0),
        onSurface = Color(0xFF2E3440),
        surfaceVariant = Color(0xFFD8DEE9),
        onSurfaceVariant = Color(0xFF4C566A),
    ),
    darkSemantic = JCodeSemanticColors(
        success = Color(0xFFA3BE8C), onSuccess = Color(0xFF2E3440),
        warning = Color(0xFFEBCB8B), onWarning = Color(0xFF2E3440),
        info = Color(0xFF8FBCBB), onInfo = Color(0xFF2E3440),
    ),
    lightSemantic = JCodeSemanticColors(
        success = Color(0xFF5E8C3F), onSuccess = Color(0xFFECEFF4),
        warning = Color(0xFFB58900), onWarning = Color(0xFF2E3440),
        info = Color(0xFF4C8C8A), onInfo = Color(0xFFECEFF4),
    ),
)

// Gruvbox — warm, retro palette.
private val gruvboxBundle = ThemeBundle(
    id = "gruvbox",
    name = "Gruvbox",
    description = "Warm retro palette with earthy tones.",
    dark = darkColorScheme(
        primary = Color(0xFF83A598),
        onPrimary = Color(0xFF282828),
        primaryContainer = Color(0xFF3C3836),
        onPrimaryContainer = Color(0xFFEBDBB2),
        secondary = Color(0xFFD3869B),
        onSecondary = Color(0xFF282828),
        tertiary = Color(0xFFB8BB26),
        onTertiary = Color(0xFF282828),
        background = Color(0xFF282828),
        onBackground = Color(0xFFEBDBB2),
        surface = Color(0xFF1D2021),
        onSurface = Color(0xFFEBDBB2),
        surfaceVariant = Color(0xFF3C3836),
        onSurfaceVariant = Color(0xFFD5C4A1),
    ),
    light = lightColorScheme(
        primary = Color(0xFF076678),
        onPrimary = Color(0xFFFBF1C7),
        primaryContainer = Color(0xFFEBDBB2),
        onPrimaryContainer = Color(0xFF3C3836),
        secondary = Color(0xFF8F3F71),
        onSecondary = Color(0xFFFBF1C7),
        tertiary = Color(0xFF79740E),
        onTertiary = Color(0xFFFBF1C7),
        background = Color(0xFFFBF1C7),
        onBackground = Color(0xFF3C3836),
        surface = Color(0xFFF2E5BC),
        onSurface = Color(0xFF3C3836),
        surfaceVariant = Color(0xFFEBDBB2),
        onSurfaceVariant = Color(0xFF665C54),
    ),
    darkSemantic = JCodeSemanticColors(
        success = Color(0xFFB8BB26), onSuccess = Color(0xFF282828),
        warning = Color(0xFFFABD2F), onWarning = Color(0xFF282828),
        info = Color(0xFF83A598), onInfo = Color(0xFF282828),
    ),
    lightSemantic = JCodeSemanticColors(
        success = Color(0xFF79740E), onSuccess = Color(0xFFFBF1C7),
        warning = Color(0xFFB57614), onWarning = Color(0xFFFBF1C7),
        info = Color(0xFF076678), onInfo = Color(0xFFFBF1C7),
    ),
)

// One Dark — Atom's signature palette; paired with One Light.
private val oneDarkBundle = ThemeBundle(
    id = "one-dark",
    name = "One Dark",
    description = "Balanced editor palette (One Dark / Light).",
    dark = darkColorScheme(
        primary = Color(0xFF61AFEF),
        onPrimary = Color(0xFF282C34),
        primaryContainer = Color(0xFF3B4048),
        onPrimaryContainer = Color(0xFFABB2BF),
        secondary = Color(0xFFC678DD),
        onSecondary = Color(0xFF282C34),
        tertiary = Color(0xFF98C379),
        onTertiary = Color(0xFF282C34),
        background = Color(0xFF282C34),
        onBackground = Color(0xFFABB2BF),
        surface = Color(0xFF21252B),
        onSurface = Color(0xFFABB2BF),
        surfaceVariant = Color(0xFF3B4048),
        onSurfaceVariant = Color(0xFF9DA5B4),
    ),
    light = lightColorScheme(
        primary = Color(0xFF4078F2),
        onPrimary = Color(0xFFFAFAFA),
        primaryContainer = Color(0xFFE5E5E6),
        onPrimaryContainer = Color(0xFF383A42),
        secondary = Color(0xFFA626A4),
        onSecondary = Color(0xFFFAFAFA),
        tertiary = Color(0xFF50A14F),
        onTertiary = Color(0xFFFAFAFA),
        background = Color(0xFFFAFAFA),
        onBackground = Color(0xFF383A42),
        surface = Color(0xFFEAEAEB),
        onSurface = Color(0xFF383A42),
        surfaceVariant = Color(0xFFE5E5E6),
        onSurfaceVariant = Color(0xFF696C77),
    ),
    darkSemantic = JCodeSemanticColors(
        success = Color(0xFF98C379), onSuccess = Color(0xFF282C34),
        warning = Color(0xFFE5C07B), onWarning = Color(0xFF282C34),
        info = Color(0xFF56B6C2), onInfo = Color(0xFF282C34),
    ),
    lightSemantic = JCodeSemanticColors(
        success = Color(0xFF50A14F), onSuccess = Color(0xFFFAFAFA),
        warning = Color(0xFFC18401), onWarning = Color(0xFFFAFAFA),
        info = Color(0xFF0184BC), onInfo = Color(0xFFFAFAFA),
    ),
)
