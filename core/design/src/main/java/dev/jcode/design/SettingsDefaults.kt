package dev.jcode.design

/** Factory defaults for the app-level (Global) settings. Single source of truth shared by the
 *  ViewModel's DataStore fallbacks and the Settings screen's modified/reset-to-default logic. */
object SettingsDefaults {
    const val HARDWARE_ACCELERATION = true
    const val CONFIRM_CLOSE_RUNNING = true
    const val EXIT_ON_SWIPE_AWAY = true
    const val AUTO_CLOSE_IDLE_TERMINALS = false
    const val IDLE_TIMEOUT_MINUTES = 30
    const val MAX_TERMINAL_SESSIONS = 12
    const val HIDE_STATUS_BAR_WITH_KEYBOARD = false
    const val HIDE_TAB_CLOSE_BUTTON = false
    const val EDITOR_DRAG_MOVES_CURSOR = false
    const val CURSOR_DRAG_LEVEL = 2
    const val RESTORE_LAST_SESSION = true
    val EXTRA_KEYS_PORTRAIT = ExtraKeysVisibility.WithKeyboard
    val EXTRA_KEYS_LANDSCAPE = ExtraKeysVisibility.Hidden
    val BOTTOM_STATUS_BAR = BottomBarVisibility.HideOnKeyboard
    val HIDDEN_ROOT_MODE = ExplorerHiddenMode.HideSpecifiedAndInjected
    const val HIDDEN_ROOT_PATTERNS = ".jcode"
}
