package dev.blamspot.jcode.design

import androidx.compose.runtime.compositionLocalOf

/**
 * User-defined environment variables surfaced on the Settings "Env Var" tab, applied to every
 * terminal / Build & Run session. [vars] is name→value; the callbacks persist edits. Shared via
 * [LocalEnvVarSettings] like [PerformanceSettings] so the settings screen needs no threaded params.
 */
class EnvVarSettings(
    val vars: Map<String, String> = emptyMap(),
    // onSet(name, value, oldName): oldName non-null when renaming an existing variable in place.
    val onSet: (name: String, value: String, oldName: String?) -> Unit = { _, _, _ -> },
    val onRemove: (name: String) -> Unit = {},
)

val LocalEnvVarSettings = compositionLocalOf { EnvVarSettings() }
