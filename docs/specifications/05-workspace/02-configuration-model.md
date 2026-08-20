# Configuration model

| | |
|---|---|
| **Status** | Implemented |
| **Modules** | `:core:config`, `:feature:settings` |
| **Primary sources** | core/config/src/main/java/dev/blamspot/jcode/core/config/ConfigModels.kt (279 lines), core/config/src/main/java/dev/blamspot/jcode/core/config/ConfigService.kt (794 lines), core/config/src/main/java/dev/blamspot/jcode/core/config/schema/workspace.schema.json, core/config/src/main/java/dev/blamspot/jcode/core/config/schema/project.schema.json |
| **Verified against** | commit `cea581c`, 2026-08-09 |

---

## 1. Purpose and scope

Per-workspace and per-project configuration: the YAML files, how they merge, how live edits are
picked up, and the guarantees around malformed input.

**YAML is the user/project config format by project decision.** Do not migrate config work to JSON
or TOML.

---

## 2. The files

| File | Scope | Constant |
|---|---|---|
| `<workspaceRoot>/.jcode-workspace.yaml` | Workspace | `WORKSPACE_CONFIG_FILE_NAME` |
| `<folder>/.jcode/<folderName>.yaml` | Project | `projectConfigRelativePath(folderName)` |

The project file is named after its **folder**, not a fixed `project.yaml`. It doubles as the
folder-role marker read by `WorkspaceManager` — see
[Workspaces and projects](01-workspaces-and-projects.md#2-the-two-node-types).

`.jcode/run.yaml` is a separate file with its own schema — see
[Run and build configurations](03-run-and-build-configurations.md).

---

## 3. Nullability is the inheritance mechanism

```kotlin
@Serializable
data class WorkspaceConfig(
    val editor: EditorConfig = EditorConfig(),      // non-nullable, defaulted
    …
)

@Serializable
data class ProjectConfig(
    val editor: EditorConfig? = null,               // nullable = "inherit"
    …
)
```

`WorkspaceConfig` sub-blocks are **non-nullable with defaults**; `ProjectConfig` sub-blocks are
**nullable**, and `null` means "inherit from the workspace". Individual leaf fields inside each
block are nullable in both, so a project can override one field without restating the block.

---

## 4. Schema reference

### 4.1 `WorkspaceConfig` — `.jcode-workspace.yaml`

| Key | Type |
|---|---|
| `editor` | `EditorConfig` |
| `files` | `FilesConfig` |
| `explorer` | `ExplorerConfig` |
| `search` | `SearchConfig` |
| `git` | `GitConfig` |
| `terminal` | `TerminalConfig` |
| `languages` | `Map<String, LanguageConfig>` |
| `extensions` | `ExtensionsConfig` |
| `theme` | `ThemeConfig` |
| `distro` | `DistroConfig` |

### 4.2 `ProjectConfig` — `.jcode/<folderName>.yaml`

All of the above (nullable), plus:

| Key | Type | Meaning |
|---|---|---|
| `name` | `String?` | Display name |
| `type` | `String?` | `project` or `workspace` — the folder-role marker |
| `template` | `String?` | Template id this project was scaffolded from |
| `tabColors` | `Map<String, String>` | Project-relative **file** path → `#RRGGBB` |
| `tabDirColors` | `Map<String, String>` | Project-relative **directory** path → `#RRGGBB` |

### 4.3 Sub-blocks

| Block | Fields |
|---|---|
| `EditorConfig` | `fontSize: Float?`, `tabSize: Int?`, `insertSpaces: Boolean?`, `wordWrap: Boolean?`, `minimap: Boolean?`, `formatOnSave: Boolean?`, `ligatures: Boolean?`, `aggressiveAutocorrectKill: Boolean?`, `tabColoring: String?` |
| `FilesConfig` | `exclude: List<String>`, `watcherExclude: List<String>` |
| `ExplorerConfig` | `viewMode: String?` — `"Tree"` or `"List"` |
| `SearchConfig` | `exclude: List<String>` |
| `GitConfig` | `autoFetch: Boolean?` |
| `TerminalConfig` | `shell: ShellConfig?` → `ShellConfig(linux: String?)` |
| `LanguageConfig` | `formatter: String?`, `lspId: String?` |
| `ExtensionsConfig` | `allowed: List<String>?` |
| `ThemeConfig` | `id: String?` |
| `DistroConfig` | `id: String?`, `bind: List<BindMount>`, `user: String?` |
| `BindMount` | `host: String`, `target: String` |

### 4.4 Effective defaults

`EffectiveConfig` is the deep-merged, **always non-null** result the rest of the app consumes.

| Effective block | Defaults |
|---|---|
| `EffectiveEditorConfig` | `fontSize = 14f`, `tabSize = 4`, `insertSpaces = true`, `wordWrap = false`, `minimap = true`, `formatOnSave = false`, `ligatures = true`, `aggressiveAutocorrectKill = false`, `tabColoring = null` |
| `EffectiveFilesConfig` | `exclude = ["**/node_modules/**", "**/.git/**", "**/build/**"]`, `watcherExclude = ["**/.git/objects/**", "**/.git/subtree-cache/**"]` |
| `EffectiveExplorerConfig` | `viewMode = "Tree"` |
| `EffectiveSearchConfig` | `exclude = ["**/node_modules/**", "**/.git/**"]` |
| `EffectiveGitConfig` | `autoFetch = true` |
| `EffectiveTerminalConfig` | `shellLinux = null` |
| `EffectiveExtensionsConfig` | `allowed = null` |
| `EffectiveThemeConfig` | `id = "dark"` |
| `EffectiveDistroConfig` | `id = "ubuntu"`, `bind = []`, `user = "jcode"` |

```kotlin
enum class ConfigScope { Workspace, Project }
```

---

## 5. Merge order

```
built-in defaults  →  workspace config  →  project config   ⇒  EffectiveConfig
```

`computeEffective(workspace, project)` performs the merge field by field: a non-null project value
wins, else a non-null workspace value, else the default.

`editor.fontSize` has an extra layer: `setGlobalEditorFontSize(size)` folds the app-level Settings
default in **beneath** workspace and project, so the global preference acts as the base rather than
an override.

---

## 6. Live reload

`bindLocalConfigFiles(workspaceRoot, projectRoot)` resolves both files and installs `FileObserver`
watchers (`watchFile`) on each. An external edit — from the editor, a terminal, or another tool —
triggers `reloadWorkspaceConfig()` / `reloadProjectConfig()`.

### 6.1 The last-known-good invariant

> **Invalid YAML never replaces the last known-good config.**

A parse failure publishes to `workspaceError` / `projectError` (`StateFlow<String?>`, message
`"Workspace config is invalid: …"`) and leaves `workspaceConfig` / `projectConfig` untouched. The
editor keeps working with the previous configuration while the user fixes the file, and the error
surfaces in the Issues panel and Settings.

`setAllowDuplicateKeys(false)` on the SnakeYAML loader turns a duplicated key into a parse error
rather than a silent last-wins.

---

## 7. Writing config back

Programmatic updates — `updateEditorConfig`, `updateThemeConfig`, `updateProjectTabColorMaps`,
`updateExplorerConfig` — round-trip through `mergeWorkspaceDocument` / `mergeProjectDocument`.

**These merge into the raw `Map<String, Any?>` document, not just the typed model.** Any key the app
does not understand — a hand-edited field, a key from a newer version, an extension's own block —
survives the save. Without this, opening Settings once would silently strip everything JCode does
not model.

Serialization uses SnakeYAML-engine `Dump` with block flow style and 2-space indentation.

---

## 8. JSON Schemas

`core/config/src/main/java/dev/blamspot/jcode/core/config/schema/{workspace,project}.schema.json` are
draft-07 documents mirroring the data classes, with types and ranges (for example `fontSize` 8–72,
`tabSize` 1–16); the workspace schema also carries `"default"` values matching the effective
defaults.

> These are **documentation and tooling** artifacts (editor IntelliSense over `.jcode*.yaml`).
> `ConfigService` parses with SnakeYAML directly and performs **no** JSON-Schema validation at
> runtime.

---

## 9. Threading and lifecycle

`ConfigService` owns `CoroutineScope(SupervisorJob() + Dispatchers.IO)`. Watchers are `Job`s
cancelled and reinstalled on each `bindLocalConfigFiles`. All state is exposed as `StateFlow`.

---

## 10. Invariants and constraints

1. Invalid YAML never clobbers the last good config.
2. Unknown keys survive a programmatic save.
3. Duplicate keys are an error, not last-wins.
4. `null` in a project block means inherit — it is not the same as an explicit value.
5. The project config file is named after its folder.
6. The JSON schemas are documentation; changing them does not change runtime behavior. Keep them in
   step with `ConfigModels.kt` by hand.
7. YAML stays the config format.

---

## 11. Failure modes

| Failure | Effect |
|---|---|
| Malformed YAML | Error flow set; previous config retained |
| Duplicate key | Parse error (by design) |
| Config file deleted while open | Watcher fires; the config falls back to the next layer |
| Folder renamed | The project config path changes with it (`<folderName>.yaml`) — the old file is orphaned |
| Value out of the schema's documented range | Accepted at runtime; only the schema (used by tooling) constrains it |

---

## 12. Known gaps

- No runtime schema validation, so out-of-range values (a `fontSize` of 500) are accepted and only
  clamped later by the consumer.
- `EffectiveDistroConfig.id` defaults to the literal `"ubuntu"`, which is not a real distro id — the
  live ids are `ubuntu-24.04` and `ubuntu-26.04`. The bottom bar now reports actual environment
  state rather than this value.
- `EffectiveEditorConfig.minimap` defaults to `true` but there is no minimap.
- The two JSON schemas are maintained by hand alongside the Kotlin models with nothing enforcing
  agreement.

---

## 13. References

- [Workspaces and projects](01-workspaces-and-projects.md)
- [Run and build configurations](03-run-and-build-configurations.md)
- [Settings reference](../06-workbench/04-settings-reference.md)
- [File format index](../09-platform/01-file-format-index.md)
