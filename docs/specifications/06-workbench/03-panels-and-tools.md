# Panels and tools

| | |
|---|---|
| **Status** | Implemented |
| **Modules** | `:app`, `:core:design` |
| **Primary sources** | app/src/main/java/dev/jcode/workbench/WorkbenchModel.kt, app/src/main/java/dev/jcode/workbench/WorkbenchPanels.kt, app/src/main/java/dev/jcode/CommandPalette.kt, app/src/main/java/dev/jcode/PaletteCommandsUi.kt, core/design/src/main/java/dev/jcode/design/CommandPaletteSettings.kt, core/design/src/main/java/dev/jcode/design/DesignSystem.kt, core/design/src/main/java/dev/jcode/design/ExtraKeys.kt, core/design/src/main/java/dev/jcode/design/VolumeKeysSetting.kt, app/src/main/java/dev/jcode/IssuesPanel.kt, app/src/main/java/dev/jcode/OutputLog.kt, app/src/main/java/dev/jcode/TaskManagerPanel.kt, app/src/main/java/dev/jcode/ToolchainManagerPanel.kt, app/src/main/java/dev/jcode/workbench/DevToolsPanel.kt |
| **Verified against** | commit `cea581c`, 2026-08-09 |

---

## 1. Purpose and scope

What lives in the two drawers, the command palette, and the input affordances (keyboard shortcuts,
the extra-keys row, volume-key bindings).

---

## 2. Left drawer — `WorkbenchTool`

```kotlin
internal enum class WorkbenchTool(
    val label: String, val icon: JCodeIcon,
    val compactLabel: String = label,
    val available: Boolean = true,     // hidden from the activity bar until it has a working UI
)
```

| Member | Label | Compact | Content |
|---|---|---|---|
| `Explorer` | Explorer | Files | File tree, VCS decorations, context actions |
| `Search` | Search | Find | Content / Names / Current-document search |
| `Scm` | SCM | SCM | Extension-hosted Git WebView |
| `RunDebug` | Run | Run | Run and build configurations, debug session |
| `Extensions` | Extensions | Ext | Marketplace and installed extensions |
| `ToolchainManager` | Toolchains | Tools | SDKs, language servers and debug engines in one searchable catalog |
| `DbManager` | DB Managers | DB | Hidden until a DB-manager client extension is installed |
| `VmManager` | VM Manager | VM | Virtual machine manager |
| `Settings` | Settings | Settings | Opens the settings page tab |

`available` keeps a not-yet-shipped tool in the enum for `when` exhaustiveness while hiding it from
the activity bar. A persisted selection that is no longer available falls back to `Explorer`.

---

## 3. Right drawer — `RightPanelTab`

```kotlin
internal enum class RightPanelTab(val label: String, val icon: JCodeIcon, val enabled: Boolean = true)
```

| Member | Label | Content | Visibility |
|---|---|---|---|
| `Terminal` | Terminal | PTY session tabs | Always |
| `Output` | Output | Read-only log teed from run terminals | Always |
| `Problems` | Issues | Diagnostics from `DiagnosticsBus` | Always |
| `DebugConsole` | Debug | DAP console, stack, variables | Always |
| `Tasks` | Tasks | Sessions plus a `/proc` process list | Always |
| `Devtools` | DevTools | Built-in browser console / network / elements | Only once the in-app browser has been opened this session |
| `ExtensionDev` | Ext Dev | Extension inspector, manifest validator, live log | Only when Developer options is on |

### 3.1 Why the selection is not a plain enum

```kotlin
internal sealed interface RightPanelSelection {
    data class Builtin(val tab: RightPanelTab) : RightPanelSelection
    data class Extension(val extensionId: String) : RightPanelSelection

    fun asKey(): String                                        // stable for rememberSaveable
    fun clampedTo(allowed: Set<RightPanelTab>): RightPanelSelection
}
```

> The built-ins are a fixed set and stay an enum; extensions are not, so they cannot be. An imported
> `.vsix` gets a tab of its own, which is why the selection has to *name* one rather than being a
> single hard-coded slot.

`asKey()` produces `"builtin:<NAME>"` or `"ext:<id>"` for saveable persistence; `clampedTo(allowed)`
falls back to the default when the named built-in is not currently offered.

---

## 4. Panels in detail

| Panel | File | Notes |
|---|---|---|
| Issues | `app/src/main/java/dev/jcode/IssuesPanel.kt` | Fed by `DiagnosticsBus`; drives the status-bar count and in-gutter squiggles |
| Output | `app/src/main/java/dev/jcode/OutputLog.kt` | Fed by `TerminalSessionManager.onOutput`, a raw byte tee from run terminals |
| Tasks | `app/src/main/java/dev/jcode/TaskManagerPanel.kt` | Backend sessions plus a live `/proc` listing |
| Toolchains | `app/src/main/java/dev/jcode/ToolchainManagerPanel.kt` | SDK, LSP and debug-engine catalogs merged |
| DevTools | `app/src/main/java/dev/jcode/workbench/DevToolsPanel.kt` | For `BuiltinBrowser` |
| Ext Dev | `app/src/main/java/dev/jcode/workbench/ExtensionDevPanel.kt`, `ExtensionDevLog.kt` | Unsigned sideloads only; the extension's `console` output lands here, **not** in logcat |
| Terminal host | `app/src/main/java/dev/jcode/TerminalSessionHost.kt` | Session tabs, nested `↳` sub-shell tabs |

**Source Control refreshes only while it is on screen.** The SCM extension re-reads the working tree
when the app raises its `filesChanged` hint, but the app only ever raised it for changes *it* made —
an editor save, an explorer file operation. Anything done in the terminal (`git add`, a build, a
coding agent) therefore left the staged/changed lists stale. `JCodeShell` now also ticks that hint
every `SCM_VISIBLE_REFRESH_MS` (3 s) while the Source Control tool is selected **and** the left
drawer is genuinely visible — `compactDrawerState.isOpen` in the modal layout, `leftSidebarExpanded`
in the docked one — with the loop parked by `repeatOnLifecycle(STARTED)` while the app is
backgrounded. The first tick fires immediately, so opening the panel re-reads the tree. It is gated
on visibility because each tick costs a `git status` inside the distro, which is not worth spending
on a panel nobody is looking at.

Terminal tabs can be **pinned** (long-press → Pin): a pinned tab sorts to the front, shows a pin
instead of its `×`, and is skipped by *Close others* / *Close all*. The pin set lives in `JCodeApp`
alongside `terminalSessionIds`, **not** in the terminal panel — the right drawer's content is
disposed whenever the drawer is collapsed or closed (and again when it swaps between modal and
docked), which takes a panel-local `rememberSaveable` registry with it. This is the same hazard as
the collapsible settings groups; see
[Settings reference](04-settings-reference.md).

---

## 5. Command palette

```kotlin
data class CommandSpec(
    val id: String, val title: String, val group: String,
    val action: () -> Unit, val isEnabled: () -> Boolean, val icon: JCodeIcon,
)
object CommandRegistry   // linkedMapOf + a Compose-state `version` counter
```

Opened with **Ctrl+Shift+P** or a volume-key binding. In modal layouts it renders as a bottom sheet;
otherwise as a centred dialog (`CommandPalette(compact = usesModalWorkspace, …)`).

### 5.1 User-configurable commands

`PaletteCommandCatalog` — each entry can be switched off in Settings → Input:

| `id` | Label | Description |
|---|---|---|
| `view.orientationLock` | Orientation Lock/Unlock | Pin the screen to its current orientation |
| `view.hideChrome` | Hide Header and Tabs | Distraction-free editing; a floating pill restores the chrome |
| `view.fullscreen` | Fullscreen | Hide the system bars; swipe from an edge to peek |
| `view.keepAwake` | Keep Awake | Prevent the screen sleeping while the app is open |
| `editor.goToLine` | Go to Line | Jump to a line (or `line:column`) |
| `tools.colorSearch` | Color Search | Tap anywhere to sample a pixel as copyable HEX/RGB(A) |
| `editor.formatDocument` | Format Document | Format the active file when its language is identified |
| `editor.fontSizeIncrease` | Increase Editor Font Size | One point, clamped 8–72 |
| `editor.fontSizeDecrease` | Decrease Editor Font Size | One point, clamped 8–72 |

Registration goes through a local `registerConfigurable(...)` helper that consults
`LocalCommandPaletteSetting.disabledIds` **and** a per-command visibility predicate, so a command
can also be hidden by context (no active editor, no project).

### 5.2 Always-on commands

`workspace.newFolder`, `workspace.openFolder`, `workspace.autoSetupEnvironment`,
`workbench.focusExplorer`, `workbench.showSearch`, `settings.openPage`,
`settings.openWorkspaceYaml`, `settings.openProjectYaml`.

The last two are predicated on a local project being open.

---

## 6. Keyboard shortcuts

Handled at the shell root by `onPreviewKeyEvent`, which runs **before** the focused editor or
terminal `AndroidView`, so it wins in every focus state:

| Shortcut | Action |
|---|---|
| Ctrl/Cmd+Shift+P | Open the command palette |
| Ctrl/Cmd+Shift+S | Save all open editor tabs |
| Escape | Close the command palette when open |

Handled inside `EditorView`:

| Shortcut | Action |
|---|---|
| Ctrl+S | Save the active buffer |
| Ctrl+Shift+S | Save all (also handled at the root; the per-view handler is a fallback) |
| Ctrl+Z / Ctrl+Shift+Z | Undo / redo |
| Ctrl+G | Go to line |
| Ctrl+W | Close the active editor tab |
| Shift+Tab | Dedent |

Handled inside `TerminalView`:

| Shortcut | Action |
|---|---|
| Ctrl+Shift+W | Close this terminal tab |
| Ctrl+Shift+S | Save all editor tabs |

> **Ctrl+W is deliberately not intercepted in the terminal** — it stays the shell's own
> delete-previous-word.

---

## 7. Extra-keys row

A chip row above the soft keyboard supplying keys a phone IME lacks.

```kotlin
enum class ExtraKey(val label: String) {
    Esc("ESC"), Slash("/"), Dash("-"), Tab("TAB"), Ctrl("CTRL"), Alt("ALT"), Shift("SHIFT"),
    Left("←"), Up("↑"), Down("↓"), Right("→"),
    Home("HOME"), End("END"), PageUp("PGUP"), PageDown("PGDN"),
    F1("F1") … F12("F12"),
}
```

`Ctrl`, `Alt` and `Shift` are **one-shot sticky modifiers**, not sent keys: tapping one arms it for
the next keystroke, which is then translated to the corresponding control byte or xterm modified
sequence for the terminal. `Shift` also latches from a soft keyboard's own Shift key (see
`TerminalView.pendingShift`), because on-screen keyboards send Shift as a key of its own and leave
`META_SHIFT_ON` off the key that follows.

```kotlin
enum class ExtraKeysVisibility { Hidden, WithKeyboard, Always }
```

Chosen independently per orientation (`EXTRA_KEYS_PORTRAIT` default `WithKeyboard`,
`EXTRA_KEYS_LANDSCAPE` default `Hidden`), with `EXTRA_KEYS_FUNCTION_KEYS` (default `false`)
appending the F1–F12 chips.

The row is hosted in the `Scaffold`'s `bottomBar`, **above** the status bar, so `innerPadding`
reserves space for it and content shrinks instead of being covered while typing.

Glyph note: arrows render from JetBrains Mono; `⏵` and similar symbols need Noto Sans Symbols 2,
which is bundled for exactly that reason.

---

## 8. Volume-key bindings

```kotlin
enum class VolumeKeyAction(val repeatable: Boolean) {
    SystemDefault(false), Undo(false), Redo(false),
    KeyLeft(true), KeyRight(true), KeyUp(true), KeyDown(true),
    ScrollUp(true), ScrollDown(true), CommandPalette(false),
}
```

Each hardware volume button is bound independently (`VOLUME_UP_ACTION`, `VOLUME_DOWN_ACTION`, both
`SystemDefault`). `repeatable` marks actions that auto-repeat on key hold.

> Persisted **by `.name`, never ordinal** — reordering the enum must not silently rebind a user's
> keys.

---

## 9. Status bar

`WorkbenchStatusBar` shows caret position, language, the diagnostics count, and `distro:` state.

The distro label is `remember`ed on only the fields it actually reads
(`runningStep`, `errorMessage`, `prootInstalled`, `distroInstalled`, `jcodeUserReady`,
`runtime.selectedDistro.id`) because `environmentState` also carries `activityLog`, which churns on
every line of setup output and would otherwise recompose the row continuously during an install.

`BOTTOM_STATUS_BAR` (default `HideOnKeyboard`) controls visibility.

---

## 10. Invariants and constraints

1. Persisted tool and panel selections are clamped against the currently available set.
2. Volume-key and other enum settings persist by `.name`.
3. Ctrl+W belongs to the shell in the terminal, not to the workbench.
4. Root `onPreviewKeyEvent` runs before focused interop views — keep the per-view fallbacks.
5. Extension `console` output goes to the Ext Dev log, not logcat.
6. Status-bar `remember` keys must exclude churning fields.
7. State that must outlive the right drawer (terminal pins) is held in `JCodeApp`, never in a panel.
8. Polling that costs guest work (the SCM refresh tick) is gated on the panel being visible AND the
   app being started — never on composition alone, which outlives visibility in a modal drawer.

---

## 11. Known gaps

- `DbManager` and `VmManager` depend on extensions being installed; with none present they are
  hidden.
- `workbench.showSearch` is registered as "Show Search Placeholder".
- Several right-panel implementations live in `:app` rather than their nominal `:feature:*` module —
  see [Module map](../01-architecture/02-module-map.md).

---

## 12. References

- [Shell layout and navigation](01-shell-layout-and-navigation.md)
- [Settings reference](04-settings-reference.md)
- [Terminal, PTY and VT](../03-runtime/01-terminal-pty-and-vt.md)
- [Extension model and lifecycle](../07-extensions/01-extension-model-and-lifecycle.md)
