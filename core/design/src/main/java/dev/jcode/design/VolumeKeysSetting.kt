package dev.jcode.design

import androidx.compose.runtime.compositionLocalOf

/**
 * An action a hardware volume button can be remapped to. [SystemDefault] leaves the OS volume
 * behavior untouched; every other value makes the app consume the key and perform the action instead.
 * [repeatable] governs auto-repeat while the button is held: caret/scroll actions repeat, one-shot
 * actions (undo/redo/palette) fire once per press. [SystemDefault] is first so it is the default and
 * the reset target. Persisted by `.name`, never ordinal.
 */
enum class VolumeKeyAction(val repeatable: Boolean) {
    SystemDefault(false),
    Undo(false),
    Redo(false),
    KeyLeft(true),
    KeyRight(true),
    KeyUp(true),
    KeyDown(true),
    ScrollUp(true),
    ScrollDown(true),
    CommandPalette(false),
}

/** App setting: the action bound to each hardware volume button. Shared with the settings screen and
 *  the key handler via [LocalVolumeKeysSetting] without threading params through JCodeShell. */
class VolumeKeysSetting(
    val up: VolumeKeyAction = SettingsDefaults.VOLUME_UP_ACTION,
    val down: VolumeKeyAction = SettingsDefaults.VOLUME_DOWN_ACTION,
    val onChangeUp: (VolumeKeyAction) -> Unit = {},
    val onChangeDown: (VolumeKeyAction) -> Unit = {},
)

val LocalVolumeKeysSetting = compositionLocalOf { VolumeKeysSetting() }
