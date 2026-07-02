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

/**
 * Per-extension Extension-API capability grants (declared in the manifest's `api.capabilities`,
 * granted by default, revocable per capability on the Extension Permissions page). Same
 * CompositionLocal convention as [LocalExtensionActivation].
 */
class ExtensionCapabilitySetting(
    val grantedFor: (extensionId: String, capability: String) -> Boolean = { _, _ -> true },
    val onSetGranted: (extensionId: String, capability: String, granted: Boolean) -> Unit = { _, _, _ -> },
)

val LocalExtensionCapabilities = compositionLocalOf { ExtensionCapabilitySetting() }

/**
 * Per-extension "keep running in background": whether an extension's chat/app WebView survives its
 * panel closing (enabled by default). Same CompositionLocal convention as [LocalExtensionActivation].
 */
class ExtensionKeepAliveSetting(
    val enabledFor: (extensionId: String) -> Boolean = { true },
    val onSetEnabled: (extensionId: String, enabled: Boolean) -> Unit = { _, _ -> },
)

val LocalExtensionKeepAlive = compositionLocalOf { ExtensionKeepAliveSetting() }
