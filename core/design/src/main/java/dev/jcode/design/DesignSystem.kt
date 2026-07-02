package dev.jcode.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
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

/** User-selectable theme preference. [System] follows the OS dark-mode setting. */
enum class ThemeMode(val configId: String) {
    System("system"),
    Light("light"),
    Dark("dark");

    companion object {
        fun fromConfigId(id: String?): ThemeMode = when (id?.lowercase()) {
            "light" -> Light
            "system", "auto" -> System
            else -> Dark
        }
    }
}

val LocalDensityMode = compositionLocalOf { DensityMode.Comfortable }
val LocalIconSize = compositionLocalOf { 18.dp }

/**
 * Tab close-button preference, shared (via [LocalTabCloseButtonSetting]) with the deep tab UIs and the
 * settings screen so it doesn't have to be threaded as params. [hidden] hides the "×" on editor and
 * terminal tabs to avoid accidental closes (close via the tab's long-press menu); [onChange] toggles it.
 */
class TabCloseButtonSetting(
    val hidden: Boolean = false,
    val onChange: (Boolean) -> Unit = {},
)

val LocalTabCloseButtonSetting = compositionLocalOf { TabCloseButtonSetting() }

/**
 * Editor drag-gesture preference, shared (via [LocalEditorDragMovesCursor]) with the editor view host and
 * the settings screen. When [enabled], a one-finger drag on the editor moves the text cursor (the view
 * scrolls to follow) instead of scrolling the content; long-press text selection is unaffected.
 */
class EditorDragSetting(
    val enabled: Boolean = false,
    val onChange: (Boolean) -> Unit = {},
    /** Drag-to-cursor sensitivity, 1 (slow/precise) … 5 (fast), independent per axis. */
    val verticalLevel: Int = 2,
    val horizontalLevel: Int = 2,
    val onVerticalLevelChange: (Int) -> Unit = {},
    val onHorizontalLevelChange: (Int) -> Unit = {},
)

val LocalEditorDragMovesCursor = compositionLocalOf { EditorDragSetting() }

/**
 * Editor save-related actions, shared (via [LocalEditorSaveActions]) with the top bar's Save button so
 * its long-press menu can offer them without threading callbacks as params (JCodeShell is at the ART
 * verifier's register limit). Each defaults to a no-op.
 */
class EditorSaveActions(
    val onUndo: () -> Unit = {},
    val onRedo: () -> Unit = {},
    val onDiscard: () -> Unit = {},
    val onSaveAll: () -> Unit = {},
    val onFormat: () -> Unit = {},
)

val LocalEditorSaveActions = compositionLocalOf { EditorSaveActions() }

/**
 * "Restore last session" preference, shared (via [LocalRestoreSession]) with the settings screen without
 * threading a param through JCodeShell (which is at the ART verifier's register limit). When [enabled]
 * (the default), the last open workspace/project and editor tabs (incl. unsaved changes) are reopened on
 * launch; [onChange] toggles it.
 */
class RestoreSessionSetting(
    val enabled: Boolean = true,
    val onChange: (Boolean) -> Unit = {},
)

val LocalRestoreSession = compositionLocalOf { RestoreSessionSetting() }

/**
 * Performance / resource-management preferences, shared (via [LocalPerformanceSettings]) with both the
 * settings screen and JCodeShell without threading params through the latter (ART register limit).
 * [confirmCloseRunning] warns before closing a project/workspace that still has a running terminal
 * program, an active Build & Run, or a live debug session; [autoCloseIdleTerminals] auto-closes
 * terminals idle at the prompt past [idleTimeoutMinutes] to free their proot trees + memory.
 */
class PerformanceSettings(
    val confirmCloseRunning: Boolean = true,
    val autoCloseIdleTerminals: Boolean = false,
    val idleTimeoutMinutes: Int = 30,
    val onSetConfirmCloseRunning: (Boolean) -> Unit = {},
    val onSetAutoCloseIdleTerminals: (Boolean) -> Unit = {},
    val onSetIdleTimeoutMinutes: (Int) -> Unit = {},
)

val LocalPerformanceSettings = compositionLocalOf { PerformanceSettings() }

/** An installed app that can open http(s) URLs. */
data class BrowserApp(val packageName: String, val label: String)

/**
 * "Open web previews in" preferences, shared (via [LocalWebPreviewBrowsers]) with the settings screen
 * (global default) and the Build & Run panel (per-project override). A choice is [SYSTEM] (the device
 * default browser), [ASK] (the Android chooser), a browser package name, or [INHERIT] (per-project only:
 * fall back to the global default). [available] is the installed-browser list for the picker.
 */
class WebPreviewBrowsers(
    val available: List<BrowserApp> = emptyList(),
    val globalChoice: String = SYSTEM,
    /** Per-project raw choice (may be [INHERIT]); keyed by a stable project key. */
    val projectChoice: (projectKey: String) -> String = { INHERIT },
    val onSetGlobal: (String) -> Unit = {},
    val onSetProject: (projectKey: String, choice: String) -> Unit = { _, _ -> },
) {
    /** The choice actually used for [projectKey]: its override, or the global default when inheriting. */
    fun effective(projectKey: String): String =
        projectChoice(projectKey).let { if (it.isBlank() || it == INHERIT) globalChoice else it }

    /** Human label for a stored choice value. */
    fun label(choice: String): String = when (choice) {
        INHERIT -> "Use global default"
        SYSTEM -> "System default"
        ASK -> "Always ask"
        else -> available.firstOrNull { it.packageName == choice }?.label ?: choice
    }

    companion object {
        const val SYSTEM = "SYSTEM"
        const val ASK = "ASK"
        const val INHERIT = ""
    }
}

val LocalWebPreviewBrowsers = compositionLocalOf { WebPreviewBrowsers() }

val JetBrainsMonoFontFamily: FontFamily
    @Composable get() = FontFamily.Monospace

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

/** Convenience accessors for J Code design tokens that sit alongside [MaterialTheme]. */
object JCodeTheme {
    val semanticColors: JCodeSemanticColors
        @Composable get() = LocalSemanticColors.current
    val spacing: JCodeSpacing
        @Composable get() = LocalSpacing.current
}

@Composable
fun M3Theme(
    themeMode: ThemeMode = ThemeMode.System,
    densityMode: DensityMode = DensityMode.Comfortable,
    themeBundle: ThemeBundle = ThemeBundleRegistry.default,
    iconBundle: IconBundle = IconBundleRegistry.default,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }
    CompositionLocalProvider(
        LocalDensityMode provides densityMode,
        LocalIconSize provides 18.dp,
        LocalSpacing provides JCodeSpacing(),
        LocalSemanticColors provides themeBundle.semanticColors(darkTheme),
        LocalIconBundle provides iconBundle,
    ) {
        MaterialTheme(
            colorScheme = themeBundle.colorScheme(darkTheme),
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
