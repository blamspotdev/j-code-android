package dev.blamspot.jcode.design

import androidx.compose.runtime.compositionLocalOf

/**
 * A single user-configurable option an extension declares in its manifest, surfaced generically on the
 * settings screen. [type] is one of "bool" | "enum" | "int" | "autocomplete" | "str"; [options]
 * applies to enums only, and [suggestCommand] to autocompletes.
 */
data class ExtensionSettingSpec(
    val key: String,
    val label: String,
    val type: String,
    val options: List<String> = emptyList(),
    val default: String = "",
    val description: String? = null,
    /** The command behind an autocomplete's suggestions; see `ExtensionSetting.suggestCommand`. */
    val suggestCommand: String? = null,
    /** The button's own text, for a spec of type `action`. Defaults to the label. */
    val buttonLabel: String? = null,
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
    /**
     * Runs an autocomplete's `suggestCommand` and returns its output lines.
     *
     * Suspending and fallible by design: it goes out to the Linux runtime, the tool it asks may not
     * be installed, and a field whose suggestions cannot be fetched is still a field you can type
     * into. An empty list is the honest answer to "no suggestions", not an error.
     */
    val suggest: suspend (extensionId: String, key: String) -> List<String> = { _, _ -> emptyList() },
    /**
     * Runs a spec of type `action` -- a button rather than a value.
     *
     * Not everything an extension wants on its settings screen is something to store. A device
     * that has come unattached needs reattaching, and there is no value that expresses that: it
     * is a thing to *do*, and the manifest names a command JCode already has rather than
     * describing behaviour of its own.
     */
    val onAction: (extensionId: String, key: String) -> Unit = { _, _ -> },
    /** True while [onAction] for this key is still running, so its button can say so. */
    val busy: (extensionId: String, key: String) -> Boolean = { _, _ -> false },
)

val LocalExtensionSettingsUi = compositionLocalOf { ExtensionSettingsUi() }
