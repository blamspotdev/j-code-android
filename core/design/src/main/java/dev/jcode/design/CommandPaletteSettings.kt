package dev.jcode.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf

/**
 * A configurable built-in Command Palette command: [id] is the stable registry id, [label]/
 * [description] feed the Settings toggle row. The palette hides commands whose id the user disabled;
 * context predicates (active view, focused screen) further gate visibility at registration.
 */
@Immutable
data class PaletteCommandInfo(
    val id: String,
    val label: String,
    val description: String,
)

/** The user-toggleable built-in palette commands (context-dependent ones note their surface). */
val PaletteCommandCatalog: List<PaletteCommandInfo> = listOf(
    PaletteCommandInfo("view.orientationLock", "Orientation Lock/Unlock", "Pin the screen to its current orientation."),
    PaletteCommandInfo("view.hideChrome", "Hide Header and Tabs", "Distraction-free editing/preview; a floating pill restores the chrome."),
    PaletteCommandInfo("view.fullscreen", "Fullscreen", "Hide the system status and navigation bars; swipe from an edge to peek."),
    PaletteCommandInfo("view.keepAwake", "Keep Awake", "Prevent the screen from sleeping while the app is open."),
    PaletteCommandInfo("editor.goToLine", "Go to Line", "Jump the active editor to a line (or line:column)."),
    PaletteCommandInfo("tools.colorSearch", "Color Search", "Tap anywhere on screen to sample a pixel as copyable HEX/RGB(A)."),
    PaletteCommandInfo("editor.formatDocument", "Format Document", "Format the active file when its language is identified."),
    PaletteCommandInfo("editor.fontSizeIncrease", "Increase Editor Font Size", "Bump the editor font one point (8–72)."),
    PaletteCommandInfo("editor.fontSizeDecrease", "Decrease Editor Font Size", "Shrink the editor font one point (8–72)."),
)

/** Which built-in palette commands the user disabled, plus the Settings toggle writer. */
@Immutable
class CommandPaletteSetting(
    val disabledIds: Set<String> = emptySet(),
    val onSetEnabled: (String, Boolean) -> Unit = { _, _ -> },
)

val LocalCommandPaletteSetting = compositionLocalOf { CommandPaletteSetting() }

/**
 * Workbench chrome state for the palette's "Hide Header and Tabs" mode. A CompositionLocal (not
 * params) because the shell composables sit at the ART verifier register limit.
 */
@Immutable
class ChromeControls(
    val chromeHidden: Boolean = false,
    val onSetChromeHidden: (Boolean) -> Unit = {},
)

val LocalChromeControls = compositionLocalOf { ChromeControls() }
