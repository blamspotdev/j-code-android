# Design system

| | |
|---|---|
| **Status** | Implemented |
| **Modules** | `:core:design`, `:core:adaptive` |
| **Primary sources** | core/design/src/main/java/dev/blamspot/jcode/design/DesignSystem.kt (532 lines), core/design/src/main/java/dev/blamspot/jcode/design/ThemeBundle.kt (227), core/design/src/main/java/dev/blamspot/jcode/design/UiIconSet.kt, core/design/src/main/java/dev/blamspot/jcode/design/JCodeLineIconSet.kt, core/design/src/main/java/dev/blamspot/jcode/design/FileIconSet.kt, core/design/src/main/java/dev/blamspot/jcode/design/IconPackLoader.kt, core/design/src/main/java/dev/blamspot/jcode/design/SvgImageVector.kt, core/design/src/main/java/dev/blamspot/jcode/design/IconArt.kt, core/design/src/main/java/dev/blamspot/jcode/design/ManagerUi.kt (713), core/design/src/main/java/dev/blamspot/jcode/design/ContextMenu.kt, core/design/src/main/java/dev/blamspot/jcode/design/Tooltip.kt, app/src/main/java/dev/blamspot/jcode/MonoFontCatalog.kt |
| **Verified against** | commit `cea581c`, 2026-08-09 |

---

## 1. Purpose and scope

The shared visual language: theme bundles, the two icon sets, spacing and density tokens, the reusable
manager chrome, and the accessibility rules every screen must meet.

---

## 2. Theme bundles

```kotlin
data class ThemeBundle(
    val id: String, val name: String, val description: String,
    val author: String = "JCode",
    val dark: ColorScheme, val light: ColorScheme,
    val darkSemantic: JCodeSemanticColors, val lightSemantic: JCodeSemanticColors,
    val fontFamily: FontFamily? = null,
) {
    fun colorScheme(darkTheme: Boolean): ColorScheme
    fun semanticColors(darkTheme: Boolean): JCodeSemanticColors
}
```

A bundle carries **both** a dark and a light Material 3 `ColorScheme`, so switching light/dark never
falls back to a generated scheme.

```kotlin
data class JCodeSemanticColors(
    val success: Color, val onSuccess: Color,
    val warning: Color, val onWarning: Color,
    val info: Color, val onInfo: Color,
)
```

These fill the gap Material 3 leaves: `colorScheme` has `error` but no success/warning/info pair.

### 2.1 Built-ins

`ThemeBundleRegistry.builtIns`:

| `id` | Name | Dark | Light |
|---|---|---|---|
| `catppuccin` | Catppuccin | Mocha, primary `#89B4FA` | Latte, primary `#1E66F5` |
| `dracula` | Dracula | Classic, primary `#BD93F9` | Soft variant, primary `#7C4DD6` |
| `midnight` | Midnight OLED | True black `#000000`, primary `#82AAFF` | Primary `#2E7DE9` |

`ThemeBundleRegistry.default` is `catppuccin`; `byId(id)` falls back to the default for an unknown
id.

### 2.2 Theme mode

```kotlin
enum class ThemeMode(val configId: String) { System("system"), Light("light"), Dark("dark") }
```

`fromConfigId(id)` maps `"light"` → `Light`, `"system"` / `"auto"` → `System`, and **anything else
— including `null` — to `Dark`**. That is why `EffectiveThemeConfig.id` defaults to `"dark"`.

### 2.3 The editor does not inherit `MaterialTheme`

`EditorView` is a custom `View` with its own `EditorTheme` (eight ARGB longs). Changing the app theme
has no effect on the editor unless the host explicitly pushes the new theme into
`EditorState.theme`. The same applies to `TerminalView`'s colour resolution.

---

## 3. Icon sets

JCode draws its icons from **two independent sets**, chosen separately in Settings.

| Set | Covers | Built-ins |
|---|---|---|
| **UI icon set** | The app's own chrome: toolbars, tabs, menus, panel headers, the activity rail | `material`, `jcode-line` |
| **File icon set** | The badge on every file and folder the app lists | **none** — extensions only |

```kotlin
enum class JCodeIcon { Run, Stop, Terminal, Files, Folder, …, Restore }   // ~90 members

sealed interface IconArt {
    data class Vector(val image: ImageVector) : IconArt
    data class Raster(val bitmap: ImageBitmap) : IconArt
}

class UiIconSet(
    val id: String, val name: String, val description: String,
    val author: String = "JCode",
    val providerId: String? = null,            // null for a built-in
    private val overrides: Map<JCodeIcon, IconArt>,
    private val fallback: UiIconSet? = null,
) {
    fun art(icon: JCodeIcon): IconArt
    val filledSlots: Int
}
```

Every icon in the app is referenced through the `JCodeIcon` enum, never as a direct
`Icons.Rounded.*`, so a set can replace the whole chrome.

Art is an `IconArt`, not an `ImageVector`, because an installed pack may ship SVG **or** PNG — so
`jcIcon(slot)` returns a **`Painter`**, and call sites read `Icon(painter = jcIcon(…), …)`. The few
host composables that take an icon parameter (`WorkbenchIconActionButton`, `StatusCell`,
`FloatingRestorePill`, …) take a `Painter` for the same reason.

| UI set | `id` | Description |
|---|---|---|
| Material Rounded | `material` | The built-in Material Rounded set; the complete map |
| JCode Line | `jcode-line` | Hand-drawn vector line pack, with `fallback = defaultUiIconSet` for unfilled slots |

`UiIconSetRegistry.builtIns = listOf(defaultUiIconSet) + customUiIconSets`; `byId(id, installed)`
resolves against the built-ins plus whatever extensions provide, falling back to the default. Access
is via `LocalUiIconSet`, a `staticCompositionLocalOf` — sets change rarely, so a static local avoids
invalidating every reader on unrelated recomposition.

The fallback chain means a partial set is legitimate: fill in the icons you want and inherit the
rest.

### 3.1 File icon sets

```kotlin
data class FileIconDef(
    val id: String, val file: File,
    val designSize: Dp = 16.dp,    // the grid the art was drawn on, NOT the rendered size
    val scale: Float = 1f,         // multiplier on the host's size
    val tinted: Boolean = false,   // false = draw as authored (multi-colour badges)
)

class FileIconSet(…) {
    fun resolve(name: String, isDirectory: Boolean, isExpanded: Boolean = false): FileIconDef?
}
```

There are no built-in file icon sets: with none selected, files and folders use two `JCodeIcon`
slots from the active UI set. `LocalFileIconSet` is therefore **nullable**, and the Settings card is
hidden entirely while no installed extension provides one.

Name matching goes through `IconRuleTable`, which buckets a pack's rules by **specificity** rather
than declaration order:

```
names:       exact file name (case-insensitive)   ← wins first
globs:       * and ? against the whole name
patterns:    a regular expression
extensions:  the file's extension                 ← checked last
```

Extensions are tried longest-compound-first (`index.d.ts` reaches a `d.ts` rule before `ts`), and a
leading dot is part of the name rather than the start of an extension. Resolution is memoized per
set (bounded LRU, 512 entries) because the Explorer asks the same question for the same row on every
frame of a scroll.

One composable, `FileTypeIcon(name, isDirectory, size, …)`, is the single call site for all of it —
Explorer tree and list, editor tabs, search results, the trash page and the project list all go
through it, so turning a pack on changes every one of them and turning it off restores every one.

### 3.2 Where sets come from

| | |
|---|---|
| **Package layout** | `:feature:marketplace` — `IconPackLayout` (the one place the on-disk conventions are defined) |
| **Reading art** | `:core:design` — `IconPackLoader` (YAML index → set), `SvgImageVector` (SVG → `ImageVector`), `IconArtLoader` (decode + cache) |
| **Selection** | `MainViewModel.uiIconSetId` / `fileIconSetId`, surfaced to Settings through `LocalIconSetSettings` |

An extension may provide **any number of sets of each kind** — outlined/filled chrome, colour/mono
file badges — one directory per variant. Each is registered under `"<extensionId>/<setId>"` so two
packs shipping an `outlined` do not collide; a pack with a single set of a kind keeps the bare
extension id.

Caching runs at three levels, because all three are re-derived far more often than they change:

| Level | Keyed by | Invalidated by |
|---|---|---|
| Parsed set (`IconPackLoader`) | provider + index path + index & directory mtime | a reinstall, or `evict()` on uninstall |
| Decoded art (`IconArtLoader`) | art path + mtime, bounded LRU (256) | the same |
| Resolved name → icon (`FileIconSet`) | file name + kind + expanded, bounded LRU (512) | the set being replaced |

A UI set is decoded **eagerly** when loaded — it fills at most one slot per `JCodeIcon` and all of
them are on screen at once. A file set stays **lazy**: a pack may define hundreds of icons of which a
project shows a dozen, so art is decoded off the main thread on first use, with the host's own glyph
drawn until it arrives.

`SvgImageVector` parses a documented subset (paths, primitive shapes, groups with
translate/scale/rotate/axis-aligned matrix, presentation attributes and inline `style`). Gradients,
`<use>`, `<text>`, filters and masks are skipped rather than approximated, and the XML reader is
configured with DTDs and external entities disabled — icon art is third-party content.

The on-disk index format is specified in
[File format index](../09-platform/01-file-format-index.md); the authoring guide lives in
`j-code-make-tools/docs/ICON-PACKS.md`.

---

## 4. Tokens

```kotlin
data class JCodeSpacing(val xs: Dp = 4.dp, val sm: Dp = 8.dp, val md: Dp = 12.dp,
                        val lg: Dp = 16.dp, val xl: Dp = 24.dp)

enum class DensityMode { Compact, Comfortable }

val LocalDensityMode = compositionLocalOf { DensityMode.Comfortable }
val LocalIconSize    = compositionLocalOf { 18.dp }
val LocalSpacing     = /* JCodeSpacing() */
```

Accessed as `JCodeTheme.spacing`. `DensityMode` drives control heights (28 dp compact,
40 dp comfortable) so a compact-density screen does not need per-widget overrides.

### 4.1 Typography

Code font is **JetBrains Mono** (bundled, `core/editor/src/main/res/font/jetbrains_mono_regular.ttf`).
UI text uses the system sans.

**Noto Sans Symbols 2** (`app/src/main/res/font/noto_sans_symbols2.ttf`) is bundled specifically so
glyphs like `⏵` render in the terminal; the default system font lacks them.

`MonoFontCatalog` backs the font-family picker. `Local*Typeface` values are hoisted into the outer
`JCodeApp` composable rather than the shell — the shell sits at the ART verifier's per-method
register limit.

---

## 5. Shared chrome

### 5.1 `ManagerUi`

One set of components reused by every install/manage surface — Toolchains, SDK detail, LSP detail,
debug-engine detail, extension detail:

| Component | Role |
|---|---|
| `ManagerItemStatus` | `NotInstalled`, `Installed`, `UpdateAvailable` |
| `ManagerStatusChip` | Status badge |
| `ManagerPanelHeader` | Panel title and actions |
| `ManagerFilterChip` | Category filter |
| `ManagerListRow` | One catalog entry |
| `ManagerSectionCard` | Grouped section |
| `ManagerDetailScreen` | The full detail page shell |

Layout note: the gear and Hide actions are pinned **outside** the `horizontalScroll` that holds the
filter chips, so they stay reachable however many categories exist.

### 5.2 `CompactContextMenu`

**One** context menu implementation for every long-press menu in the app:

```kotlin
data class ContextAction(
    val icon: JCodeIcon, val label: String,
    val destructive: Boolean = false, val enabled: Boolean = true,
    val onClick: () -> Unit,
)
```

### 5.3 `Tooltip`

A small custom focusable tooltip (36 lines) — the Material tooltip was not focusable, which broke
keyboard and D-pad traversal.

### 5.4 Other primitives

`CompactSearchField`, `FloatingRestorePill` (restores hidden chrome), `SettingsDropdownRow`,
`SettingsResettableRow`, `SettingsTextFieldRow`, `TabColorDialog`.

---

## 6. Accessibility and contrast rules

These are project requirements, not suggestions:

- All text and UI must meet **WCAG AA**: 4.5:1 for normal text, 3:1 for large text.
- Minimum alpha values when tinting a `Surface` or `Card`:

  | Overlay | Minimum alpha |
  |---|---|
  | Primary colour (interactive states) | `0.22f` |
  | Surface variant (containers) | `0.28f` |
  | Surface (chips, pills) | `0.75f` |

- Use `MaterialTheme.colorScheme` semantic roles (`onSurfaceVariant` and friends) rather than
  hard-coded colours.
- **Test in both light and dark before considering a feature complete.**

---

## 7. Invariants and constraints

1. Icons are referenced through `JCodeIcon`, never `Icons.*` directly.
2. A theme bundle must supply both a dark and a light `ColorScheme`.
3. The editor and terminal do not inherit `MaterialTheme` — push the theme explicitly.
4. Use `JCodeTheme.spacing` rather than literal `dp` padding.
5. Every long-press menu uses `CompactContextMenu`.
6. Typefaces are created in the outer `JCodeApp`, not inside the shell.
7. Unknown bundle ids fall back to the default rather than failing.

---

## 8. Failure modes

| Failure | Effect |
|---|---|
| Unknown theme or UI icon set id | Falls back to the default |
| Unknown file icon set id, or its pack uninstalled | `LocalFileIconSet` is null; files use the UI set's own glyphs |
| Partial UI icon set without a fallback | Unfilled slots resolve to a plain circle |
| Malformed icon-pack index or unparseable art | The set (or that one glyph) is dropped; nothing throws |
| Tint alpha below the documented minimum | Fails WCAG AA in one of the two themes |
| Theme changed but not pushed to `EditorState` | The editor keeps the old colours |

---

## 9. Known gaps

- Theme bundles are compiled in; there is no extension contribution point for them yet, though the
  data model would support one. Icon sets **do** have one — see §3.2.
- There are no built-in file icon sets, so that Settings card is hidden until a pack provides one.
- `ThemeBundle.fontFamily` exists but no built-in bundle sets it.
- A raster (PNG) UI icon ignores `autoMirror`; only vector art is mirrored in a right-to-left locale.

---

## 10. References

- [Settings reference](04-settings-reference.md)
- [Editor state and undo](../02-editor/02-editor-state-and-undo.md) — `EditorTheme`
- [Rendering and decorations](../02-editor/03-rendering-and-decorations.md)
- [Panels and tools](03-panels-and-tools.md)
