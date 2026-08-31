package dev.blamspot.jcode.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf

/** What the workbench header's quick-action button does. Persisted by `.name`, never ordinal. */
enum class HeaderActionButton {
    /** Open the right drawer on the terminal panel, with its busy shimmer and unseen-session badge. */
    Terminal,

    /** Open the Command Palette — the same sheet Ctrl+Shift+P opens. */
    CommandPalette,

    /** Leave the slot empty; the terminal is still reachable from the right drawer. */
    Hidden,
}

/** App setting: which action the workbench header's quick-action button carries. */
@Immutable
class HeaderActionSetting(
    val button: HeaderActionButton = SettingsDefaults.HEADER_ACTION_BUTTON,
    val onChange: (HeaderActionButton) -> Unit = {},
)

val LocalHeaderActionSetting = compositionLocalOf { HeaderActionSetting() }
