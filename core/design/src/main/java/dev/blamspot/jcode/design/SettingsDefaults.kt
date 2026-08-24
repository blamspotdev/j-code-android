package dev.blamspot.jcode.design

/** Factory defaults for the app-level (Global) settings. Single source of truth shared by the
 *  ViewModel's DataStore fallbacks and the Settings screen's modified/reset-to-default logic. */
object SettingsDefaults {
    const val HARDWARE_ACCELERATION = true
    const val CONFIRM_CLOSE_RUNNING = true
    const val EXIT_ON_SWIPE_AWAY = true
    const val AUTO_CLOSE_IDLE_TERMINALS = false
    const val IDLE_TIMEOUT_MINUTES = 30
    const val MAX_TERMINAL_SESSIONS = 12
    const val NESTED_SHELL_TABS = false
    const val INSTALL_TIMEOUT_MINUTES = 30
    const val HIDE_STATUS_BAR_WITH_KEYBOARD = false
    const val HIDE_TAB_CLOSE_BUTTON = false
    const val EDITOR_DRAG_MOVES_CURSOR = false
    const val CURSOR_DRAG_LEVEL = 2
    const val RESTORE_LAST_SESSION = true
    val EXTRA_KEYS_PORTRAIT = ExtraKeysVisibility.WithKeyboard
    val EXTRA_KEYS_LANDSCAPE = ExtraKeysVisibility.Hidden
    const val EXTRA_KEYS_FUNCTION_KEYS = false
    val BOTTOM_STATUS_BAR = BottomBarVisibility.HideOnKeyboard
    val HIDDEN_ROOT_MODE = ExplorerHiddenMode.HideSpecifiedAndInjected
    val EXCLUDE_EFFECT = ExplorerExcludeEffect.GreyOut
    const val HIDDEN_ROOT_PATTERNS = ".jcode"

    // On by default: a delete the user cannot take back is the surprising behaviour, not the safe one.
    const val TRASH_ENABLED = true
    const val TRASH_RETENTION_DAYS = 30
    const val RESPECT_DEVICE_CUTOUT = false
    const val MARKDOWN_WRAP_PORTRAIT = true
    const val EDITOR_FONT_SIZE = 14f
    const val EDITOR_WORD_WRAP = false
    const val TERMINAL_FONT_SIZE = 13f

    /** Percent, because an extension's text size is its own choice and this scales it. */
    const val EXTENSION_FONT_SCALE = 100
    const val DEVELOPER_OPTIONS = false

    // Diagnostics is opt-in: nothing is recorded until the user turns it on to chase a problem.
    const val DIAGNOSTIC_LOGGING = false
    val DIAGNOSTIC_LEVEL = dev.blamspot.jcode.core.diag.DiagLevel.Normal
    const val DIAGNOSTIC_SYSTEM_LOG = true
    const val DIAGNOSTIC_CRASHES = true
    const val RIGHT_DRAWER_PERSISTENT = false
    const val RIGHT_DRAWER_PERSISTENT_FRACTION = 0.5f

    /** How far the docked drawer can be dragged. Both panes stay usable at either end. */
    const val RIGHT_DRAWER_MIN_FRACTION = 0.3f
    const val RIGHT_DRAWER_MAX_FRACTION = 0.7f
    const val RUN_IN_VIRTUAL_DEVICE = false
    val VOLUME_UP_ACTION = VolumeKeyAction.SystemDefault
    val VOLUME_DOWN_ACTION = VolumeKeyAction.SystemDefault
    // Default to ephemeral Random so the feature is visible out of the box WITHOUT silently writing
    // a .jcode on first file open; persistence (RandomRemember / DirectoryBased) is opt-in.
    val TAB_COLORING = TabColoring.Random
    val TAB_MAX_SIZE = TabMaxSize.Medium
}
