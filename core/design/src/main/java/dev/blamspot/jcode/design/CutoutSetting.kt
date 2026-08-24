package dev.blamspot.jcode.design

import androidx.compose.runtime.compositionLocalOf

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
