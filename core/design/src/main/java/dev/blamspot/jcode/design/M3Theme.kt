package dev.blamspot.jcode.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

private val JCodeTypography = Typography(
    bodyMedium = TextStyle(
        fontSize = 13.sp,
        lineHeight = 16.9.sp,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 15.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 15.6.sp,
    ),
)

@Composable
fun M3Theme(
    themeMode: ThemeMode = ThemeMode.System,
    densityMode: DensityMode = DensityMode.Comfortable,
    themeBundle: ThemeBundle = ThemeBundleRegistry.default,
    uiIconSet: UiIconSet = UiIconSetRegistry.default,
    fileIconSet: FileIconSet? = null,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }
    CompositionLocalProvider(
        LocalDensityMode provides densityMode,
        LocalSemanticColors provides themeBundle.semanticColors(darkTheme),
        LocalUiIconSet provides uiIconSet,
        LocalFileIconSet provides fileIconSet,
    ) {
        MaterialTheme(
            colorScheme = themeBundle.colorScheme(darkTheme),
            typography = JCodeTypography,
            content = content,
        )
    }
}
