package dev.jcode.design

import android.graphics.Typeface
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
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
    /** Save every dirty tab and suspend until the writes finish — used by the close-guard so a switch
     *  only proceeds once the buffers are safely on disk. Returns true only if every tab is now clean
     *  (false if any couldn't be saved, so the caller can avoid tearing down unsaved work). */
    val onSaveAllAwait: suspend () -> Boolean = { true },
)

val LocalEditorSaveActions = compositionLocalOf { EditorSaveActions() }

/**
 * Per-tab actions for the editor tab strip's long-press menu, shared (via [LocalEditorTabActions])
 * so the pin / close-others / close-to-the-right handlers reach the tab UI without threading params
 * through JCodeShell (which is at the ART verifier's register limit). Each takes the tab's id.
 */
class EditorTabActions(
    val onTogglePin: (String) -> Unit = {},
    val onCloseOthers: (String) -> Unit = {},
    val onCloseToRight: (String) -> Unit = {},
    /** Set (or, with a null hex, clear) the manual color of a file tab. Persists to the project .jcode. */
    val onSetTabColor: (String, String?) -> Unit = { _, _ -> },
)

val LocalEditorTabActions = compositionLocalOf { EditorTabActions() }

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
)

val LocalFontSettings = compositionLocalOf { FontSettings() }

/** The resolved editor font, pushed to the EditorView; defaults to the system monospace. */
val LocalEditorTypeface = compositionLocalOf { Typeface.MONOSPACE }

/** The resolved terminal font, pushed to the TerminalView; defaults to the system monospace. */
val LocalTerminalTypeface = compositionLocalOf { Typeface.MONOSPACE }

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

/** Which files/folders the Explorer hides at the PROJECT ROOT. */
enum class ExplorerHiddenMode { HideSpecifiedAndInjected, HideInjected, None }

/**
 * Explorer "hide files at the project root" preference, shared with the settings screen and the
 * Explorer (via [LocalExplorerHiddenSetting]) without threading params through JCodeShell (ART
 * register limit). [specifiedRaw] is the user's newline-separated pattern list; [hiddenPatternsFor]
 * resolves the effective hide list for a project id per [mode], merging the by-line list with the
 * injected list the SCM extension pushes from each project's .gitignore.
 */
class ExplorerHiddenSetting(
    val mode: ExplorerHiddenMode = ExplorerHiddenMode.HideSpecifiedAndInjected,
    val specifiedRaw: String = ".jcode",
    val onSetMode: (ExplorerHiddenMode) -> Unit = {},
    val onSetSpecifiedRaw: (String) -> Unit = {},
    val hiddenPatternsFor: (projectId: String?) -> List<String> = { emptyList() },
)

val LocalExplorerHiddenSetting = compositionLocalOf { ExplorerHiddenSetting() }

/**
 * "Respect device cutout" preference. When true the app keeps content out of the camera notch /
 * punch-hole (letterboxed beside it in landscape); when false it draws into the cutout for a full
 * screen. [hasCutout] is false when the current display has no cutout (desktop/external display or a
 * notchless device), where the setting is hidden. Shared with the settings screen via
 * [LocalCutoutSetting] without threading params through JCodeShell (ART register limit).
 */
class CutoutSetting(
    val respect: Boolean = false,
    val hasCutout: Boolean = false,
    val onChange: (Boolean) -> Unit = {},
)

val LocalCutoutSetting = compositionLocalOf { CutoutSetting() }

/**
 * In-app update state, shared (via [LocalAppUpdate]) with the settings screen without threading
 * params through JCodeShell (ART register limit). Populated from a GitHub-release check on startup:
 * [updateAvailable] flags a newer [latestVersion] than [currentVersion]; [onCheck] re-runs the check;
 * [onOpenRelease] opens the release page in a browser.
 */
class AppUpdateSetting(
    val currentVersion: String = "",
    val latestVersion: String? = null,
    val updateAvailable: Boolean = false,
    val checking: Boolean = false,
    val onCheck: () -> Unit = {},
    val onOpenRelease: () -> Unit = {},
)

val LocalAppUpdate = compositionLocalOf { AppUpdateSetting() }

/**
 * Performance / resource-management preferences, shared (via [LocalPerformanceSettings]) with both the
 * settings screen and JCodeShell without threading params through the latter (ART register limit).
 * [confirmCloseRunning] warns before closing a project/workspace that still has a running terminal
 * program, an active Build & Run, or a live debug session; [autoCloseIdleTerminals] auto-closes
 * terminals idle at the prompt past [idleTimeoutMinutes] to free their proot trees + memory.
 */
class PerformanceSettings(
    val hardwareAcceleration: Boolean = true,
    val confirmCloseRunning: Boolean = true,
    val autoCloseIdleTerminals: Boolean = false,
    val idleTimeoutMinutes: Int = 30,
    val maxTerminalSessions: Int = 12,
    val exitOnSwipeAway: Boolean = true,
    val onSetHardwareAcceleration: (Boolean) -> Unit = {},
    val onSetConfirmCloseRunning: (Boolean) -> Unit = {},
    val onSetAutoCloseIdleTerminals: (Boolean) -> Unit = {},
    val onSetIdleTimeoutMinutes: (Int) -> Unit = {},
    val onSetMaxTerminalSessions: (Int) -> Unit = {},
    val onSetExitOnSwipeAway: (Boolean) -> Unit = {},
)

val LocalPerformanceSettings = compositionLocalOf { PerformanceSettings() }

/**
 * A single user-configurable option an extension declares in its manifest, surfaced generically on the
 * settings screen. [type] is one of "bool" | "enum" | "int" | "str"; [options] applies to enums only.
 */
data class ExtensionSettingSpec(
    val key: String,
    val label: String,
    val type: String,
    val options: List<String> = emptyList(),
    val default: String = "",
    val description: String? = null,
)

/** One installed extension's declared settings, grouped for the settings screen. */
data class ExtensionSettingsGroup(
    val extensionId: String,
    val extensionName: String,
    val specs: List<ExtensionSettingSpec>,
)

/**
 * Generic extension-settings platform, shared (via [LocalExtensionSettingsUi]) with the settings screen.
 * Each installed extension that declares a `settings:` block contributes a [ExtensionSettingsGroup];
 * [valueOf] reads the current (or default) value for a key, [onChange] persists a new value (and
 * notifies the live extension via a `config` event so its UI can react).
 */
class ExtensionSettingsUi(
    val groups: List<ExtensionSettingsGroup> = emptyList(),
    val valueOf: (extensionId: String, key: String) -> String = { _, _ -> "" },
    val onChange: (extensionId: String, key: String, value: String) -> Unit = { _, _, _ -> },
)

val LocalExtensionSettingsUi = compositionLocalOf { ExtensionSettingsUi() }

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
        BUILTIN -> "Built-in browser"
        else -> available.firstOrNull { it.packageName == choice }?.label ?: choice
    }

    companion object {
        const val SYSTEM = "SYSTEM"
        const val ASK = "ASK"
        /** Open the preview inside J Code's own in-editor browser (with DevTools) instead of an external app. */
        const val BUILTIN = "BUILTIN"
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
    val icon: JCodeIcon? = null,
)

object CommandRegistry {
    private val commands = linkedMapOf<String, CommandSpec>()

    /** Bumped on every mutation so a composed palette (which reads it as Compose state) recomposes
     *  when the shell re-registers commands — including the first population after a process-restore
     *  that reopened the palette from saved state. */
    var version by mutableIntStateOf(0)
        private set

    fun register(
        id: String,
        title: String,
        group: String,
        action: () -> Unit,
        whenPredicate: () -> Boolean = { true },
        icon: JCodeIcon? = null,
    ) {
        commands[id] = CommandSpec(
            id = id,
            title = title,
            group = group,
            action = action,
            isEnabled = whenPredicate,
            icon = icon,
        )
        version++
    }

    fun all(): List<CommandSpec> = commands.values.toList()

    fun clear() {
        commands.clear()
        version++
    }
}
