# Settings reference

| | |
|---|---|
| **Status** | Implemented |
| **Modules** | `:feature:settings`, `:core:design`, `:core:config` |
| **Primary sources** | feature/settings/src/main/java/dev/blamspot/jcode/feature/settings/SettingsFeature.kt (1,949 lines), core/design/src/main/java/dev/blamspot/jcode/design/SettingsDefaults.kt, core/design/src/main/java/dev/blamspot/jcode/design/SettingsDropdownRow.kt, core/design/src/main/java/dev/blamspot/jcode/design/SettingsResettableRow.kt, core/design/src/main/java/dev/blamspot/jcode/design/SettingsTextFieldRow.kt, core/design/src/main/java/dev/blamspot/jcode/design/DesignSystem.kt, app/src/main/java/dev/blamspot/jcode/SettingsBackup.kt |
| **Verified against** | commit `cea581c`, 2026-08-09 |

---

## 1. Purpose and scope

The settings surface: the scope model, the groups, and the complete table of app-level keys with
their factory defaults.

Settings open as an editor **page tab** (`EditorPageKind.Settings`), not a separate screen.

---

## 2. Scope model

Four tabs, three of which are configuration scopes:

| Tab | `ConfigScope` | Storage |
|---|---|---|
| **GLOBAL** | `null` | Jetpack DataStore (app preferences) |
| **WORKSPACE** | `ConfigScope.Workspace` | `.jcode-workspace.yaml` |
| **PROJECT** | `ConfigScope.Project` | `.jcode/<folderName>.yaml` |
| **ENV VAR** | — | Not a scope; an environment-variable editor for the guest |

> **Search is scoped to the selected tab**, mirroring VS Code's User/Workspace split. A search on the
> Project tab never surfaces global settings that are not project-overridable.

The tab strip is a `ScrollableTabRow` with its own divider suppressed and a full-width one drawn
behind, because the built-in divider spans only the tab content.

---

## 3. Groups

### 3.1 GLOBAL tab

| Group | Contains |
|---|---|
| **Appearance** | Theme bundle, UI icons, file icons (hidden until an icon pack provides one), fonts, terminal font size, immersive keyboard, display cutout, right-drawer split, bottom status bar, extra-keys row |
| **Input** | Volume-key bindings, command-palette command toggles |
| **Startup** | Restore last session |
| **Performance** | Hardware acceleration, auto-close idle terminals, idle timeout, max terminal sessions |
| **Web preview** | Which browser opens web previews (global, with a per-project override) |
| **Environment** | Distro selection, background process limit, Android device, virtual device |
| **About** | Version, update check, backup and restore |
| **Diagnostics** | Opt-in diagnostic logging: detail level, system-log and crash capture, view/export/clear |
| **Editor** | Editor defaults, gestures, tabs, formatter, Markdown preview |
| **Explorer** | Exclude files and folders, Trash |
| **Developer** | Developer options |

### 3.2 WORKSPACE / PROJECT tabs

| Group | Contains |
|---|---|
| **Editor** | Editor behavior (`stateKey = "scoped.Editor"`) |
| **Explorer** | View mode, excludes |
| **Files** | YAML file access and workspace/project YAML warnings |

Groups are **collapsible and collapsed by default**. Expansion state is held *outside* the
composition — `rememberSaveable` dies on rotation because the workbench swaps between the modal and
docked layouts, disposing the subtree and its saveable registry. Search bypasses the collapse
wrapper entirely rather than expanding it.

---

## 4. Global defaults

`SettingsDefaults` is the single source of truth, shared by the ViewModel's DataStore fallbacks and
by the Settings screen's modified/reset-to-default logic.

| Key | Default | Notes |
|---|---|---|
| `HARDWARE_ACCELERATION` | `true` | Manifest declares `hardwareAccelerated=false`; the window flag can only enable |
| `CONFIRM_CLOSE_RUNNING` | `true` | Warn before closing with a running process |
| `EXIT_ON_SWIPE_AWAY` | `true` | Swipe-away from Recents closes sessions |
| `AUTO_CLOSE_IDLE_TERMINALS` | `false` | |
| `IDLE_TIMEOUT_MINUTES` | `30` | |
| `MAX_TERMINAL_SESSIONS` | `12` | Hard cap 24 (`MAX_SESSIONS_CAP`) |
| `NESTED_SHELL_TABS` | `false` | OSC 7715 sub-shell relocation |
| `INSTALL_TIMEOUT_MINUTES` | `30` | Per-entry floors can raise it |
| `HIDE_STATUS_BAR_WITH_KEYBOARD` | `false` | |
| `HIDE_TAB_CLOSE_BUTTON` | `false` | |
| `EDITOR_DRAG_MOVES_CURSOR` | `false` | |
| `CURSOR_DRAG_LEVEL` | `2` | 1–5 |
| `RESTORE_LAST_SESSION` | `true` | |
| `EXTRA_KEYS_PORTRAIT` | `ExtraKeysVisibility.WithKeyboard` | |
| `EXTRA_KEYS_LANDSCAPE` | `ExtraKeysVisibility.Hidden` | |
| `EXTRA_KEYS_FUNCTION_KEYS` | `false` | Appends F1–F12 |
| `BOTTOM_STATUS_BAR` | `BottomBarVisibility.HideOnKeyboard` | |
| `HIDDEN_ROOT_MODE` | `ExplorerHiddenMode.HideSpecifiedAndInjected` | |
| `EXCLUDE_EFFECT` | `ExplorerExcludeEffect.GreyOut` | |
| `HIDDEN_ROOT_PATTERNS` | `".jcode"` | Newline-separated pattern list |
| `TRASH_ENABLED` | `true` | Off makes an Explorer delete and an SCM discard permanent |
| `TRASH_RETENTION_DAYS` | `30` | 0 keeps until the user empties it |
| `RESPECT_DEVICE_CUTOUT` | `false` | |
| `MARKDOWN_WRAP_PORTRAIT` | `true` | |
| `EDITOR_FONT_SIZE` | `14f` | Clamped 8–72 |
| `EDITOR_WORD_WRAP` | `false` | |
| `TERMINAL_FONT_SIZE` | `13f` | sp, clamped 6–40; scaled by display density before `TerminalView.setFontSize` |
| `DEVELOPER_OPTIONS` | `false` | Gates the Ext Dev panel |
| `DIAGNOSTIC_LOGGING` | `false` | Master opt-in; nothing is recorded until it is on |
| `DIAGNOSTIC_LEVEL` | `DiagLevel.Normal` | `Errors` / `Normal` / `Verbose` |
| `DIAGNOSTIC_SYSTEM_LOG` | `true` | Only takes effect while `DIAGNOSTIC_LOGGING` is on |
| `DIAGNOSTIC_CRASHES` | `true` | Only takes effect while `DIAGNOSTIC_LOGGING` is on |
| `RIGHT_DRAWER_PERSISTENT` | `false` | Landscape split |
| `RIGHT_DRAWER_PERSISTENT_FRACTION` | `0.5f` | |
| `RIGHT_DRAWER_MIN_FRACTION` | `0.3f` | Drag bound |
| `RIGHT_DRAWER_MAX_FRACTION` | `0.7f` | Drag bound |
| `RUN_IN_VIRTUAL_DEVICE` | `false` | |
| `VOLUME_UP_ACTION` | `VolumeKeyAction.SystemDefault` | |
| `VOLUME_DOWN_ACTION` | `VolumeKeyAction.SystemDefault` | |
| `TAB_COLORING` | `TabColoring.Random` | See below |
| `TAB_MAX_SIZE` | `TabMaxSize.Medium` | 64 dp title cap |

> `TAB_COLORING` defaults to the **ephemeral** `Random` deliberately, so tab colouring is visible out
> of the box **without silently writing a `.jcode` directory on first file open**. Persistence
> (`RandomRemember`, `DirectoryBased`) is opt-in.

---

## 5. Supporting enums

Each lives in its own small file under `core/design/`:

| Enum | Members | File |
|---|---|---|
| `ExtraKeysVisibility` | `Hidden`, `WithKeyboard`, `Always` | `ExtraKeys.kt` |
| `ExtraKey` | `Esc`, `Slash`, `Dash`, `Tab`, `Ctrl`, `Alt`, `Shift`, arrows, `Home`, `End`, `PageUp`, `PageDown`, `F1`–`F12` | `ExtraKeys.kt` |
| `BottomBarVisibility` | `Hidden`, `HideOnKeyboard`, `AlwaysShow` | `BottomBarSetting.kt` |
| `ExplorerHiddenMode` | `HideSpecifiedAndInjected`, `HideInjected`, `None` | `DesignSystem.kt` |
| `ExplorerExcludeEffect` | `GreyOut`, `Hide` | `DesignSystem.kt` |
| `VolumeKeyAction` | `SystemDefault`, `Undo`, `Redo`, `KeyLeft`, `KeyRight`, `KeyUp`, `KeyDown`, `ScrollUp`, `ScrollDown`, `CommandPalette` | `VolumeKeysSetting.kt` |
| `TabColoring` | `RandomRemember`, `Random`, `DirectoryBased`, `Disabled` | `TabColoring.kt` |
| `TabMaxSize` | `Small(44.dp)`, `Medium(64.dp)`, `Large(104.dp)` | `TabMaxSize.kt` |
| `ThemeMode` | `System`, `Light`, `Dark` | `DesignSystem.kt` |
| `DiagLevel` | `Errors`, `Normal`, `Verbose` | `:core:diag` `DiagnosticLog.kt` |

> **Enum settings persist by `.name`, never by ordinal.** Reordering an enum must not silently
> rebind a user's choice. Some enum member names are retained purely for persistence compatibility
> even where a clearer name exists.

### 5.1 Explorer exclusion

`hiddenPatternsFor(projectId)` resolves the effective exclude list per `ExplorerHiddenMode`, merging
the user's newline-separated `specifiedRaw` list with an **injected** list that the SCM extension
pushes from each project's `.gitignore`. `ExplorerExcludeEffect` then decides whether a matched
entry is greyed out (default) or removed from the tree.

### 5.2 Trash

`TRASH_ENABLED` decides whether an Explorer delete and an extension's `workbench.trash` call move to
the bin or destroy. The bin itself opens from the Explorer toolbar as an editor page
(`EditorPageKind.Trash`, `TrashPage`). The Explorer reads it through `LocalTrashSettings` and words its confirmation
accordingly; the workbench applies it on behalf of extensions, so whether a discard is kept stays a
decision about JCode rather than one each extension makes for itself.

`TRASH_RETENTION_DAYS` is swept at startup (`MainViewModel.init`), when the Trash page opens, and
immediately when the value is lowered. `TRASH_RETENTION_CHOICES` holds the offered periods; 0 is
"until I empty it" and never expires.

---

## 6. Row widgets

| Widget | Purpose |
|---|---|
| `SettingsDropdownRow` | The standardized dropdown used by every enum setting |
| `SettingsResettableRow` | Long-press to reset one setting to its default; shows a "modified" dot |
| `SettingsTextFieldRow` | Text input |

Per-setting reset compares the live value against `SettingsDefaults`, which is why that object must
stay the single source of truth.

---

## 7. Scoped settings and the effective value

WORKSPACE and PROJECT rows write through `ConfigService.update*` into the corresponding YAML,
preserving unknown keys. The value the app actually uses is the merged `EffectiveConfig` — see
[Configuration model](../05-workspace/02-configuration-model.md).

`editor.fontSize` is the one setting with a three-layer merge: the global preference is folded in as
the **base**, beneath workspace and project.

---

## 8. Backup and restore

`SettingsBackup` (Settings → About) exports and imports settings. Environment backup is separate and
produces a `tar.gz` of the rootfs via `RootfsArchiver`.

---

## 9. Invariants and constraints

1. `SettingsDefaults` is the only place a factory default is written.
2. Enum settings persist by `.name`.
3. Group expansion state must live outside the composition.
4. Search must not depend on a group being expanded.
5. Every enum setting uses `SettingsDropdownRow` for consistency.
6. Scoped writes go through `ConfigService`, never direct YAML writing.

---

## 10. Failure modes

| Failure | Effect |
|---|---|
| Enum reordered without care | Persisted `.name` still resolves; ordinal-based storage would not |
| Scoped setting written with no project open | The PROJECT tab is unavailable |
| Malformed workspace/project YAML | Config error surfaces; the last good values remain in effect |
| Rotation with a group expanded | Expansion survives because it is held outside the composition |

---

## 11. Known gaps

- Some enum member names are historical, kept for persistence compatibility rather than clarity.
- The ENV VAR tab sits in the scope strip but is not a scope, which can read as one.

### 11.1 Wiring audit, 1.6.2

Every control on this screen was traced from its row through to something that acts on the value —
55 persisted preference keys, 43 settings `CompositionLocal`s and the scoped `EditorConfig` block.
All but one are wired. Two things are worth recording because they made the audit harder than it
looks, and will do so again:

- **A setting is not dead just because its flow has no reader.** `restoreLastSession` looks unused
  until you notice `restoreSessionOnLaunch` reads `restoreLastSessionKey` — the raw preference key —
  rather than the `StateFlow`. Volume keys are the same: the Settings screen reads
  `LocalVolumeKeysSetting`, while the behaviour reads `viewModel.volumeUpAction` in `MainActivity`.
- **A `CompositionLocal` whose only reader is `SettingsFeature` is the shape to look for**, but nine
  of them match that and eight are legitimate — either the row *is* the feature (backup and update
  actions), or the value reaches behaviour by a second path (word wrap through `applyConfigToTab`,
  fonts through `MonoFontCatalog.resolve`, tab colouring through `effectiveTabColoring`,
  diagnostics through `DiagnosticLog.configure`).

The one that was genuinely dead was the minimap toggle, removed along with `Layer.MINIMAP`. In the
scoped config, `formatOnSave` was wired up and `aggressiveAutocorrectKill` and `watcherExclude` were
removed — see [Configuration model](../05-workspace/02-configuration-model.md).

---

## 12. References

- [Configuration model](../05-workspace/02-configuration-model.md)
- [Shell layout and navigation](01-shell-layout-and-navigation.md)
- [Panels and tools](03-panels-and-tools.md)
- [Design system](05-design-system.md)
