package dev.jcode.workbench.marketplace

import androidx.compose.runtime.compositionLocalOf
import dev.jcode.feature.marketplace.ExtensionActivation

/**
 * Per-extension activation mode, shared (via [LocalExtensionActivation]) with the extension detail page
 * so the mode selector can read the current mode + change it without threading params through JCodeShell
 * (which is at the ART verifier's register limit). [modeFor] returns the mode for an extension id
 * ([ExtensionActivation.Default] when unset); [onChange] persists a new mode.
 */
class ExtensionActivationSetting(
    val modeFor: (String) -> ExtensionActivation = { ExtensionActivation.Default },
    val onChange: (String, ExtensionActivation) -> Unit = { _, _ -> },
)

val LocalExtensionActivation = compositionLocalOf { ExtensionActivationSetting() }
