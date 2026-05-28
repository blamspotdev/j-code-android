package dev.jcode.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class DensityMode {
    Compact,
    Comfortable,
}

val LocalDensityMode = compositionLocalOf { DensityMode.Comfortable }
val LocalIconSize = compositionLocalOf { 18.dp }

val JetBrainsMonoFontFamily: FontFamily
    @Composable get() = FontFamily.Monospace

private val JCodeDarkColorScheme = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF89B4FA),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF1E1E2E),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF313244),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFCDD6F4),
    secondary = androidx.compose.ui.graphics.Color(0xFFCBA6F7),
    onSecondary = androidx.compose.ui.graphics.Color(0xFF1E1E2E),
    tertiary = androidx.compose.ui.graphics.Color(0xFFA6E3A1),
    onTertiary = androidx.compose.ui.graphics.Color(0xFF1E1E2E),
    background = androidx.compose.ui.graphics.Color(0xFF1E1E2E),
    onBackground = androidx.compose.ui.graphics.Color(0xFFCDD6F4),
    surface = androidx.compose.ui.graphics.Color(0xFF181825),
    onSurface = androidx.compose.ui.graphics.Color(0xFFCDD6F4),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF313244),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFBAC2DE),
)

private val JCodeLightColorScheme = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF6750A4),
    onPrimary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFEADDFF),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF21005D),
    secondary = androidx.compose.ui.graphics.Color(0xFF625B71),
    onSecondary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    tertiary = androidx.compose.ui.graphics.Color(0xFF7D5260),
    onTertiary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    background = androidx.compose.ui.graphics.Color(0xFFFAFAFA),
    onBackground = androidx.compose.ui.graphics.Color(0xFF1C1B1F),
    surface = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    onSurface = androidx.compose.ui.graphics.Color(0xFF1C1B1F),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE7E0EC),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF49454F),
)

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
    darkTheme: Boolean = true,
    densityMode: DensityMode = DensityMode.Comfortable,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalDensityMode provides densityMode,
        LocalIconSize provides 18.dp,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) JCodeDarkColorScheme else JCodeLightColorScheme,
            typography = JCodeTypography,
            content = content,
        )
    }
}

@Composable
fun DenseRow(
    modifier: Modifier = Modifier,
    height: Dp = when (LocalDensityMode.current) {
        DensityMode.Compact -> 28.dp
        DensityMode.Comfortable -> 40.dp
    },
    leading: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = height)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (leading != null) {
            Box(contentAlignment = Alignment.Center) { leading() }
        }

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            content()
        }

        if (trailing != null) {
            Box(contentAlignment = Alignment.CenterEnd) { trailing() }
        }
    }
}

@Immutable
data class CommandSpec(
    val id: String,
    val title: String,
    val group: String,
    val action: () -> Unit,
    val isEnabled: () -> Boolean = { true },
)

object CommandRegistry {
    private val commands = linkedMapOf<String, CommandSpec>()

    fun register(
        id: String,
        title: String,
        group: String,
        action: () -> Unit,
        whenPredicate: () -> Boolean = { true },
    ) {
        commands[id] = CommandSpec(
            id = id,
            title = title,
            group = group,
            action = action,
            isEnabled = whenPredicate,
        )
    }

    fun all(): List<CommandSpec> = commands.values.toList()

    fun clear() {
        commands.clear()
    }
}
