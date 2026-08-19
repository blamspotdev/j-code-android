package dev.jcode.core.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Workspace-level configuration.
 * Stored as YAML at the workspace root.
 */
@Serializable
data class WorkspaceConfig(
    @SerialName("editor")
    val editor: EditorConfig = EditorConfig(),

    @SerialName("files")
    val files: FilesConfig = FilesConfig(),

    @SerialName("explorer")
    val explorer: ExplorerConfig = ExplorerConfig(),

    @SerialName("search")
    val search: SearchConfig = SearchConfig(),

    @SerialName("git")
    val git: GitConfig = GitConfig(),

    @SerialName("terminal")
    val terminal: TerminalConfig = TerminalConfig(),

    @SerialName("languages")
    val languages: Map<String, LanguageConfig> = emptyMap(),

    @SerialName("extensions")
    val extensions: ExtensionsConfig = ExtensionsConfig(),

    @SerialName("theme")
    val theme: ThemeConfig = ThemeConfig(),

    @SerialName("distro")
    val distro: DistroConfig = DistroConfig(),
)

/**
 * Project-level configuration.
 * Stored as YAML at `<project>/.jcode/project.yaml`.
 */
@Serializable
data class ProjectConfig(
    @SerialName("name")
    val name: String? = null,

    /** Folder role: "project" (a buildable project) or "workspace" (a container of projects). */
    @SerialName("type")
    val type: String? = null,

    /** Template id this project was scaffolded from (project folders only). */
    @SerialName("template")
    val template: String? = null,

    @SerialName("editor")
    val editor: EditorConfig? = null,

    @SerialName("files")
    val files: FilesConfig? = null,

    @SerialName("explorer")
    val explorer: ExplorerConfig? = null,

    @SerialName("search")
    val search: SearchConfig? = null,

    @SerialName("git")
    val git: GitConfig? = null,

    @SerialName("terminal")
    val terminal: TerminalConfig? = null,

    @SerialName("languages")
    val languages: Map<String, LanguageConfig> = emptyMap(),

    @SerialName("extensions")
    val extensions: ExtensionsConfig? = null,

    @SerialName("theme")
    val theme: ThemeConfig? = null,

    @SerialName("distro")
    val distro: DistroConfig? = null,

    /** Manually-set / remembered editor tab colors, keyed by project-relative file path -> #RRGGBB. */
    @SerialName("tabColors")
    val tabColors: Map<String, String> = emptyMap(),

    /** Remembered per-directory tab colors (Directory-based mode), keyed by project-relative dir -> #RRGGBB. */
    @SerialName("tabDirColors")
    val tabDirColors: Map<String, String> = emptyMap(),
)

@Serializable
data class EditorConfig(
    @SerialName("fontSize")
    val fontSize: Float? = null,

    @SerialName("tabSize")
    val tabSize: Int? = null,

    @SerialName("insertSpaces")
    val insertSpaces: Boolean? = null,

    @SerialName("wordWrap")
    val wordWrap: Boolean? = null,

    @SerialName("minimap")
    val minimap: Boolean? = null,

    @SerialName("formatOnSave")
    val formatOnSave: Boolean? = null,

    @SerialName("ligatures")
    val ligatures: Boolean? = null,

    @SerialName("aggressiveAutocorrectKill")
    val aggressiveAutocorrectKill: Boolean? = null,

    /** Editor tab coloring mode ([TabColoring] name); null inherits the app-level default. */
    @SerialName("tabColoring")
    val tabColoring: String? = null,
)

@Serializable
data class FilesConfig(
    @SerialName("exclude")
    val exclude: List<String> = emptyList(),

    @SerialName("watcherExclude")
    val watcherExclude: List<String> = emptyList(),
)

@Serializable
data class ExplorerConfig(
    /** Explorer file-browser layout: "Tree" or "List". */
    @SerialName("viewMode")
    val viewMode: String? = null,
)

@Serializable
data class SearchConfig(
    @SerialName("exclude")
    val exclude: List<String> = emptyList(),
)

@Serializable
data class GitConfig(
    @SerialName("autoFetch")
    val autoFetch: Boolean? = null,
)

@Serializable
data class TerminalConfig(
    @SerialName("shell")
    val shell: ShellConfig? = null,
)

@Serializable
data class ShellConfig(
    @SerialName("linux")
    val linux: String? = null,
)

@Serializable
data class LanguageConfig(
    @SerialName("formatter")
    val formatter: String? = null,

    @SerialName("lspId")
    val lspId: String? = null,
)

@Serializable
data class ExtensionsConfig(
    @SerialName("allowed")
    val allowed: List<String>? = null,
)

@Serializable
data class ThemeConfig(
    @SerialName("id")
    val id: String? = null,
)

@Serializable
data class DistroConfig(
    @SerialName("id")
    val id: String? = null,

    @SerialName("bind")
    val bind: List<BindMount> = emptyList(),

    @SerialName("user")
    val user: String? = null,
)

@Serializable
data class BindMount(
    @SerialName("host")
    val host: String,

    @SerialName("target")
    val target: String,
)

/**
 * Effective configuration after deep-merging defaults, workspace, and project configs.
 */
data class EffectiveConfig(
    val editor: EffectiveEditorConfig,
    val files: EffectiveFilesConfig,
    val explorer: EffectiveExplorerConfig,
    val search: EffectiveSearchConfig,
    val git: EffectiveGitConfig,
    val terminal: EffectiveTerminalConfig,
    val languages: Map<String, LanguageConfig>,
    val extensions: EffectiveExtensionsConfig,
    val theme: EffectiveThemeConfig,
    val distro: EffectiveDistroConfig,
)

data class EffectiveEditorConfig(
    val fontSize: Float = 14f,
    val tabSize: Int = 4,
    val insertSpaces: Boolean = true,
    val wordWrap: Boolean = false,
    val minimap: Boolean = true,
    val formatOnSave: Boolean = false,
    val ligatures: Boolean = true,
    val aggressiveAutocorrectKill: Boolean = false,
    /** [TabColoring] name from workspace/project .jcode; null means "inherit the app-level default". */
    val tabColoring: String? = null,
)

data class EffectiveFilesConfig(
    val exclude: List<String> = listOf("**/node_modules/**", "**/.git/**", "**/build/**"),
    val watcherExclude: List<String> = listOf("**/.git/objects/**", "**/.git/subtree-cache/**"),
)

data class EffectiveExplorerConfig(
    val viewMode: String = "Tree",
)

data class EffectiveSearchConfig(
    val exclude: List<String> = listOf("**/node_modules/**", "**/.git/**"),
)

data class EffectiveGitConfig(
    val autoFetch: Boolean = true,
)

data class EffectiveTerminalConfig(
    val shellLinux: String? = null,
)

data class EffectiveExtensionsConfig(
    val allowed: List<String>? = null,
)

data class EffectiveThemeConfig(
    val id: String = "dark",
)

data class EffectiveDistroConfig(
    val id: String = "ubuntu",
    val bind: List<BindMount> = emptyList(),
    val user: String = "jcode",
)

enum class ConfigScope {
    Workspace,
    Project,
}
