package dev.jcode.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf

/**
 * Whether the right drawer docks beside the editor instead of sliding over it.
 *
 * Only landscape honours [enabled]: portrait has no width to give away, so the drawer stays the
 * modal sheet it has always been there. Docking splits the screen at
 * [SettingsDefaults.RIGHT_DRAWER_PERSISTENT_FRACTION] and drops the scrim, so the editor stays
 * usable while a panel is open — which is the point of turning it on.
 */
@Immutable
class RightDrawerSetting(
    val enabled: Boolean = SettingsDefaults.RIGHT_DRAWER_PERSISTENT,
    val onSetEnabled: (Boolean) -> Unit = {},
)

val LocalRightDrawerSetting = compositionLocalOf { RightDrawerSetting() }
