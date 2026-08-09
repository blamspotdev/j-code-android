# Editor tabs and pages

| | |
|---|---|
| **Status** | Implemented |
| **Modules** | `:feature:editor-pane`, `:core:design`, `:app` |
| **Primary sources** | feature/editor-pane/src/main/java/dev/jcode/feature/editor/pane/EditorTab.kt, feature/editor-pane/src/main/java/dev/jcode/feature/editor/pane/EditorPane.kt, feature/editor-pane/src/main/java/dev/jcode/feature/editor/pane/EditorMenuExtras.kt, core/design/src/main/java/dev/jcode/design/TabColoring.kt, core/design/src/main/java/dev/jcode/design/TabMaxSize.kt, core/design/src/main/java/dev/jcode/design/TabColorDialog.kt, app/src/main/java/dev/jcode/workbench/MarkdownPreviewPage.kt, app/src/main/java/dev/jcode/workbench/ImageViewerPage.kt, app/src/main/java/dev/jcode/workbench/BrowserPage.kt |
| **Verified against** | commit `cea581c`, 2026-08-09 |

---

## 1. Purpose and scope

The tab model. JCode's editor area hosts more than files: settings, manager detail screens, an image
viewer, a browser, and a running Android app are all tabs. This document defines that model.

---

## 2. The tab

```kotlin
data class EditorTab(
    val id: String,
    val title: String,
    val filePath: File,
    val editorState: EditorState?,
    val isDirty: Boolean = false,
    val languageDescriptor: LanguageDescriptor? = null,
    val pageKind: EditorPageKind = EditorPageKind.None,
    val previewMode: Boolean = false,
    val pinned: Boolean = false,
) {
    val isPage: Boolean get() = pageKind != EditorPageKind.None
}
```

**The core distinction:** a *file* tab carries an `EditorState`; a *page* tab has
`editorState == null` and renders host-provided Compose content instead.

Factories:

| Factory | Produces |
|---|---|
| `EditorTab.create(file, id = file.path)` | A file tab; reads the file, builds a `Buffer`, guesses the language |
| `EditorTab.createFromText(text, title, id = title)` | A file tab backed by in-memory text (no path) |
| `EditorTab.page(id, title, kind)` | A page tab with `filePath = File("")` |

`guessLanguageDescriptor(fileName)` maps the extension to a `LanguageDescriptor`
(`json`/`jsonc`, `yaml`/`yml`, `kt`/`kts`, `java`, `ts`/`tsx`, and so on).

---

## 3. Page kinds

```kotlin
enum class EditorPageKind {
    None, Settings, Environment, SdkDetail, LspDetail, DebugEngineDetail,
    ExtensionDetail, ExtensionPermissions, ExtensionApp, VsixPanel,
    RunConfig, BuildConfig, Browser, ImageViewer, AndroidDevice, AppSandbox
}
```

| Kind | Content |
|---|---|
| `None` | An ordinary file tab |
| `Settings` | The settings screen (`:feature:settings`) |
| `Environment` | The environment/onboarding stepper |
| `SdkDetail` | One toolchain catalog entry |
| `LspDetail` | One language server |
| `DebugEngineDetail` | One debug adapter |
| `ExtensionDetail` | One installed or marketplace extension |
| `ExtensionPermissions` | An extension's capability grants |
| `ExtensionApp` | An extension's own full-page web UI |
| `VsixPanel` | An imported `.vsix` webview panel (`createWebviewPanel`) |
| `RunConfig` / `BuildConfig` | Run and build configuration editors |
| `Browser` | The in-app browser / web preview |
| `ImageViewer` | Image files |
| `AndroidDevice` | Connected-device tooling |
| `AppSandbox` | A real APK running inside JCode |

Opening a binary file routes to `ImageViewer` or is refused, decided by `MainViewModel`'s private
`OpenPrep` probe (`Missing` / `Image` / `Binary` / `Text`) so the check happens off the main thread.

---

## 4. Preview mode

`previewMode` flips a **file** tab between its source and a rendered view without opening a second
tab. Markdown is the shipped case (`MarkdownPreviewPage`, driven by `MarkdownHtml`), toggled from
the tab menu.

Related setting: `MARKDOWN_WRAP_PORTRAIT` (default `true`).

---

## 5. Groups and splits

```kotlin
data class EditorGroup(
    val id: String,
    val tabs: List<EditorTab> = emptyList(),
    val activeTabId: String? = null,
) {
    val activeTab: EditorTab? get() = tabs.find { it.id == activeTabId } ?: tabs.firstOrNull()
    fun withTabAdded(tab): EditorGroup
    fun withTabRemoved(tabId): EditorGroup
    fun withActiveTabChanged(tabId): EditorGroup
    fun withTabUpdated(updated): EditorGroup
}
```

All mutators are pure and return a new group.

`withTabRemoved` activates the **last remaining** tab when the closed tab was active, not the
neighbour.

`EditorGroupManager` holds `groups: List<EditorGroup>` and `activeGroupId`, with `splitGroup()`,
group removal (which **always keeps at least one group**), `setActiveGroup(id)` and
`openFile(file, groupId)`.

---

## 6. Pinning

> Pinned tabs sort to the front, hide their close `×` (close via the long-press menu instead), and
> are skipped by "Close others" and "Close to the right".

This makes pinning a genuine protection rather than only a reordering.

---

## 7. Tab appearance

### 7.1 Colouring

```kotlin
enum class TabColoring { RandomRemember, Random, DirectoryBased, Disabled }
```

| Mode | Behavior |
|---|---|
| `RandomRemember` | Random colour, remembered per file in `.jcode/<name>.yaml` `tabColors` |
| `Random` | Random colour each session (the default, `SettingsDefaults.TAB_COLORING`) |
| `DirectoryBased` | One colour per directory, remembered in `tabDirColors` |
| `Disabled` | No colours; the "Change Tab Color" menu item is hidden |

**A manually-set colour always wins in every mode except `Disabled`.**

The palette is a fixed 25-colour (5×5) grid of medium-saturation hues chosen to read clearly as a
thin accent bar in both light and dark themes. It is used by the picker (`TabColorDialog`) and drawn
from by the random modes.

`EditorTabColors(colorFor: (String) -> Color?, pickerEnabled: Boolean)` is exposed via
`LocalEditorTabColors` and backed by a plain map, so the per-frame tab-strip lookup is cheap. It
returns `null` for page tabs and in `Disabled` mode.

The persisted maps are project config, not app settings — see
[Configuration model](../05-workspace/02-configuration-model.md).

### 7.2 Width

```kotlin
enum class TabMaxSize(val titleMaxWidth: Dp) { Small(44.dp), Medium(64.dp), Large(104.dp) }
```

Default `Medium`. Titles longer than the cap are middle-ellipsized (`MiddleEllipsisText`), so the
distinguishing end of a filename stays visible.

### 7.3 Close button

`HIDE_TAB_CLOSE_BUTTON` (default `false`). When shown, the `×` appears only on the **active** tab,
which keeps a crowded strip tappable; other tabs close from the long-press menu.

---

## 8. Close guards

Closing is mediated by `EditorCloseChoice`:

```kotlin
enum class EditorCloseChoice { SAVE, DISCARD, CLOSE_SAVED, CANCEL }
```

Guarded situations: a tab with unsaved changes, switching workspace with dirty tabs, and closing
while a process is running (`CONFIRM_CLOSE_RUNNING`, default `true`). Dialogs live in
`workbench/dialog/WorkbenchDialogs.kt`; destructive options are not placed rightmost.

`EXIT_ON_SWIPE_AWAY` (default `true`) makes swiping the app away from Recents fully close sessions
rather than leaving orphaned proot trees.

---

## 9. External change reconciliation

When a file changes on disk while open, the tab is reloaded only if it is **clean** —
`EditorState.replaceAll(onlyIfClean = true)` returns `false` and skips the reload when there are
unsaved edits. See [Editor state and undo](../02-editor/02-editor-state-and-undo.md).

---

## 10. Tab menu extras

`EditorMenuExtras.kt` renders the long-press menu: pin/unpin, close variants, change tab colour,
toggle preview, plus extension-contributed `contributes.editorContextActions` entries — see
[Manifest reference](../07-extensions/03-manifest-reference.md).

---

## 11. Invariants and constraints

1. A page tab has `editorState == null`; never dereference it without checking `isPage`.
2. Tab ids default to the file path, so the same file cannot open twice in one group.
3. Pinned tabs are excluded from bulk-close operations.
4. Tab colours are project config, not app settings — they travel with the project.
5. Adding a page kind means extending `EditorPageKind` **and** the host's `when` that renders it.
6. Group removal must leave at least one group.

---

## 12. Failure modes

| Failure | Effect |
|---|---|
| File deleted while open | The tab stays with its in-memory buffer; saving recreates the file |
| Binary file opened | Routed to `ImageViewer` or refused by the `OpenPrep` probe |
| A page kind with no renderer | Blank tab body — the `when` must be exhaustive |
| Very long filename | Middle-ellipsized to `TabMaxSize.titleMaxWidth` |

---

## 13. Known gaps

- `EditorGroupManager` supports splits, but the shell currently drives a single group; there is no
  split-pane UI affordance.
- `previewMode` is implemented for Markdown only.

---

## 14. References

- [Shell layout and navigation](01-shell-layout-and-navigation.md)
- [Editor state and undo](../02-editor/02-editor-state-and-undo.md)
- [Configuration model](../05-workspace/02-configuration-model.md)
- [Settings reference](04-settings-reference.md)
