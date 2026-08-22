package dev.blamspot.jcode.design

import androidx.compose.runtime.compositionLocalOf

/** Which files/folders the Explorer excludes at the PROJECT ROOT. (Enum names keep the legacy "Hide"
 *  prefix so persisted preferences still parse; the [ExplorerExcludeEffect] decides grey-out vs hide.) */
enum class ExplorerHiddenMode { HideSpecifiedAndInjected, HideInjected, None }

/** What "excluded" does in the Explorer: grey the entries out (default) or hide them entirely. */
enum class ExplorerExcludeEffect { GreyOut, Hide }

/**
 * Explorer "exclude files at the project root" preference, shared with the settings screen and the
 * Explorer (via [LocalExplorerHiddenSetting]) without threading params through JCodeShell (ART
 * register limit). [specifiedRaw] is the user's newline-separated pattern list; [hiddenPatternsFor]
 * resolves the effective exclude list for a project id per [mode], merging the by-line list with the
 * injected list the SCM extension pushes from each project's .gitignore. [effect] chooses whether a
 * matched entry is greyed out (default) or removed from the tree.
 */
class ExplorerHiddenSetting(
    val mode: ExplorerHiddenMode = ExplorerHiddenMode.HideSpecifiedAndInjected,
    val effect: ExplorerExcludeEffect = ExplorerExcludeEffect.GreyOut,
    val specifiedRaw: String = ".jcode",
    val onSetMode: (ExplorerHiddenMode) -> Unit = {},
    val onSetEffect: (ExplorerExcludeEffect) -> Unit = {},
    val onSetSpecifiedRaw: (String) -> Unit = {},
    val hiddenPatternsFor: (projectId: String?) -> List<String> = { emptyList() },
)

val LocalExplorerHiddenSetting = compositionLocalOf { ExplorerHiddenSetting() }
