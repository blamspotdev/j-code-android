package dev.blamspot.jcode.design

import androidx.compose.runtime.compositionLocalOf

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
