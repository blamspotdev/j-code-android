# Shell layout and navigation

| | |
|---|---|
| **Status** | Implemented |
| **Modules** | `:app`, `:core:adaptive`, `:core:design` |
| **Primary sources** | app/src/main/java/dev/blamspot/jcode/JCodeShell.kt (4,999 lines), app/src/main/java/dev/blamspot/jcode/workbench/WorkbenchChrome.kt, app/src/main/java/dev/blamspot/jcode/workbench/WorkbenchChromeBars.kt, app/src/main/java/dev/blamspot/jcode/workbench/WorkbenchModel.kt, app/src/main/java/dev/blamspot/jcode/WorkbenchStatusBar.kt, app/src/main/java/dev/blamspot/jcode/SessionStore.kt, core/adaptive/src/main/java/dev/blamspot/jcode/adaptive/WindowInfo.kt, app/src/main/java/dev/blamspot/jcode/MainActivity.kt |
| **Verified against** | commit `cea581c`, 2026-08-09 |

---

## 1. Purpose and scope

The workbench frame: how the left and right drawers behave across form factors and orientations, how
the layout responds to the keyboard, and how a session is restored.

---

## 2. Window classification

`:core:adaptive` classifies the window:

```kotlin
enum class JCodeWindowWidthClass  { Compact, Medium, Expanded }
enum class JCodeWindowHeightClass { Compact, Medium, Expanded }
enum class JCodePosture           { Flat, TableTop, Book }

data class JCodeWindowInfo(
    val widthClass: JCodeWindowWidthClass,
    val heightClass: JCodeWindowHeightClass,
    val posture: JCodePosture,
    val hasPhysicalKeyboard: Boolean,
)
```

`rememberJCodeWindowInfo()` combines Material 3 adaptive size classes, `WindowInfoTracker`'s
`FoldingFeature` (for posture), and a live `InputManager.InputDeviceListener` so an attached or
detached physical keyboard is reflected immediately rather than only on configuration change.

---

## 3. Modal versus docked — keyed on **height**

```kotlin
val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
val isMobileLandscapeMode = isLandscape && windowInfo.heightClass == JCodeWindowHeightClass.Compact
val usesModalWorkspace = !isLandscape || isMobileLandscapeMode
```

> A phone in landscape is **wide** (Expanded width) but **short** (Compact height); a tablet in
> landscape is tall too. Keying off height means phones keep the modal drawers they use in portrait
> instead of switching to the cramped persistent side panels a tablet wants.

This is the single most consequential layout decision in the shell, and getting it backwards (keying
on width) produces an unusable phone-landscape layout.

| Mode | Left drawer | Right drawer |
|---|---|---|
| `usesModalWorkspace` | `ModalNavigationDrawer` with a scrim | `AnimatedVisibility` slide-in overlay, own scrim at 36% black |
| Otherwise | Persistent sidebar | Optional docked pane |

### 3.1 Right-drawer docking

```kotlin
val persistentRightDrawer = rightDrawerSplit.enabled && isLandscape
val rightSidebarDocked   = isLandscape && (!usesModalWorkspace || persistentRightDrawer)
```

The opt-in landscape split (Settings → Appearance) deliberately **does not** go through
`usesModalWorkspace`, because that flag also drives the left `ModalNavigationDrawer` — docking the
left drawer on a phone would leave no editor at all. Only the right side changes.

### 3.2 Sizing

| Value | Rule |
|---|---|
| `leftSidebarWidth` | 284 dp when `widthClass == Expanded`, else 236 dp |
| `rightSidebarWidth` (modal overlay) | `screenWidthDp × 0.75` |
| Docked right pane | Draggable between `RIGHT_DRAWER_MIN_FRACTION` 0.3 and `RIGHT_DRAWER_MAX_FRACTION` 0.7; default `RIGHT_DRAWER_PERSISTENT_FRACTION` 0.5 |

Only the opt-in split is draggable; the large-screen sidebar keeps its fixed width, so enabling the
setting is the only thing that changes how the workspace is divided.

---

## 4. Keyboard handling

```kotlin
Box(modifier = modifier.fillMaxSize()
    .windowInsetsPadding(WindowInsets.imeAnimationTarget))
```

**`imeAnimationTarget`, not `imePadding`.** Following the IME animation re-lays-out the entire shell
on every frame of the keyboard slide — measured at roughly 27 ms of measure/layout per frame on
low-end hardware, and the dominant source of keyboard-toggle frame drops. Snapping the padding to
the animation's *target* does **one** relayout per toggle; the keyboard then slides over an
already-settled layout.

The editor's `RenderNode` cache is the other half of this optimization — see
[Input, IME and gestures §7](../02-editor/04-input-ime-and-gestures.md#7-the-rendernode-content-cache).

Related settings: `HIDE_STATUS_BAR_WITH_KEYBOARD` (default `false`), `BOTTOM_STATUS_BAR`
(`HideOnKeyboard`), `EXTRA_KEYS_PORTRAIT` (`WithKeyboard`), `EXTRA_KEYS_LANDSCAPE` (`Hidden`).

---

## 5. Panel availability

The right drawer's tab set is computed, not fixed:

```kotlin
val portraitRightSidebarTabs = remember(developerModeEnabled) {
    RightPanelTab.entries
        .filter { it.enabled && (it != RightPanelTab.ExtensionDev || developerModeEnabled) }
        .toSet()
}
```

`RightPanelSelection.clampedTo(allowed)` falls back to the default when a persisted selection names
a tab the caller is not offering — which is what stops a saved `ExtensionDev` selection from
surviving Developer options being switched off.

The same pattern guards the left drawer: `WorkbenchTool` carries an `available` flag, and a
`LaunchedEffect` resets `selectedTool` to `Explorer` when a persisted tool is no longer available.

---

## 6. Rotation and state survival

Two rotation hazards are handled explicitly.

**`rememberSaveable` does not survive.** Rotation swaps the workbench between the modal and docked
layouts, disposing the subtree **and its saveable registry**. State that must persist (for example
settings-group expansion) is held *outside* that composition rather than in `rememberSaveable`.

**WebViews must be re-parented, not disposed.** `AndroidView`'s `onDispose` would `removeView` a
WebView out of the mount that had just adopted it, blanking extension and SCM panels.
`PersistentWebViewHost` keeps its own `FrameLayout` and checks `parent !== host` before re-parenting.
See [Extension API and hosts](../07-extensions/04-extension-api-and-hosts.md).

`MainActivity` declares a broad `android:configChanges` set (orientation, screenSize, screenLayout,
smallestScreenSize, keyboard, keyboardHidden, navigation, uiMode, density, fontScale, locale,
layoutDirection) so the activity is not recreated for these changes.

**Mouse right-click opens a context menu, not Back.** Android synthesizes a Back key press when
nothing consumes the secondary mouse button, so with a mouse attached every right-click navigated
backwards. `MouseContextClick` (owned by `MainActivity`, the only place that sees pointer events
whichever pane has focus) swallows the whole secondary-button gesture — `ACTION_BUTTON_PRESS` /
`ACTION_BUTTON_RELEASE` on the generic-motion path, `ACTION_DOWN`…`ACTION_UP` on the touch path —
and replays it as a **touch long-press** at the same point. Every surface with a long-press menu
therefore gets the desktop behaviour without opting in, and one with no menu simply does nothing.
A Back key arriving within 400 ms of a right-click is dropped as a backstop; the real Back button
and the system back gesture are untouched.

---

## 7. Session restore

`SessionStore` (`filesDir/session/`) persists:

- A JSON manifest: `workspaceId`, `projectId`, `activeTabId`, and the tab list.
- One buffer file per **dirty** tab under `session/buffers`.

Writes use **write-then-rename** so a crash mid-write cannot corrupt the manifest.

Controlled by `RESTORE_LAST_SESSION` (default `true`).

On restore, `MainViewModel` re-opens the workspace, re-creates the tabs, and restores unsaved
buffers. Tabs whose files no longer exist are dropped rather than resurrected as empty documents.

---

## 8. Status bar and chrome

`WorkbenchStatusBar` shows the caret position, language, encoding, the diagnostics count from
`DiagnosticsBus.totalCount`, and the live `distro:` environment state. The `branch:` cell reads
`.git/HEAD` directly and is **omitted** when the project is not a git repository, rather than shown
holding a placeholder.

`WorkbenchChrome` / `WorkbenchChromeBars` own the top bar and activity bar;
`WorkbenchSnackbar` owns transient messages and the post-update prompts ("Reload extension",
"Restart app"); `dialog/WorkbenchDialogs.kt` owns the confirm dialogs, including the close guards
(unsaved changes, switching workspace, a running process). Destructive actions are deliberately
**not** the rightmost button.

Fullscreen, orientation lock, keep-awake and chrome-hiding are command-palette commands — see
[Panels and tools](03-panels-and-tools.md).

---

## 9. State plumbing

The shell passes state through `CompositionLocal` bundles (`WorkbenchRunActions`,
`WorkbenchManagerActions`, `DebugSessionUi`, `IssueActions`, `LocalExplorerScmUi`, …) rather than
composable parameters. This is not stylistic: `JCodeShell`'s root composable is near the **ART
verifier's per-method register limit**, and adding parameters risks a verification failure at
runtime.

The same constraint explains why font typefaces are hoisted into the outer `JCodeApp` composable
rather than being created inside the shell.

---

## 10. Invariants and constraints

1. Modal-versus-docked keys off **height class**, never width.
2. Use `imeAnimationTarget`, not `imePadding`, for the shell's IME inset.
3. Persisted panel and tool selections must be clamped against the currently available set.
4. Do not rely on `rememberSaveable` across rotation inside the workbench subtree.
5. WebViews are re-parented, never disposed, on layout swaps.
6. Prefer a `CompositionLocal` bundle over another parameter on the shell composable.
7. Destructive buttons are not placed rightmost in dialogs.

---

## 11. Failure modes

| Failure | Effect | Handling |
|---|---|---|
| Persisted tool no longer available | Blank drawer | `LaunchedEffect` resets to `Explorer` |
| Persisted right tab hidden | Blank panel | `clampedTo(allowed)` |
| Rotation during an extension panel session | Blank WebView | `PersistentWebViewHost` |
| Crash mid session-save | Corrupt manifest | Write-then-rename |
| Adding shell composable parameters | Possible ART verification failure | Use `CompositionLocal` |

---

## 12. Known gaps

- `JCodeShell.kt` (4,999 lines) and `MainViewModel.kt` (4,844 lines) hold work the module layering
  would place in `:feature:*`.
- `JCodePosture` is computed but no layout currently branches on `TableTop` or `Book`.
- A few source lines in `JCodeShell.kt`, `ExplorerView.kt` and `SettingsFeature.kt` contain literal
  control bytes where `//` was intended, which makes tooling treat those files as binary. Use
  `grep -a` or the Read tool.

---

## 13. References

- [Editor tabs and pages](02-editor-tabs-and-pages.md)
- [Panels and tools](03-panels-and-tools.md)
- [Settings reference](04-settings-reference.md)
- [Input, IME and gestures](../02-editor/04-input-ime-and-gestures.md)
