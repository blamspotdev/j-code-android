# Design system

| | |
|---|---|
| **Status** | Implemented |
| **Modules** | `:core:design`, `:core:adaptive` |
| **Primary sources** | core/design/src/main/java/dev/jcode/design/DesignSystem.kt (532 lines), core/design/src/main/java/dev/jcode/design/ThemeBundle.kt (227), core/design/src/main/java/dev/jcode/design/IconBundle.kt (206), core/design/src/main/java/dev/jcode/design/CustomIconBundle.kt (184), core/design/src/main/java/dev/jcode/design/ManagerUi.kt (713), core/design/src/main/java/dev/jcode/design/ContextMenu.kt, core/design/src/main/java/dev/jcode/design/Tooltip.kt, app/src/main/java/dev/jcode/MonoFontCatalog.kt |
| **Verified against** | commit `cea581c`, 2026-08-09 |

---

## 1. Purpose and scope

The shared visual language: theme bundles, icon bundles, spacing and density tokens, the reusable
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

## 3. Icon bundles

```kotlin
enum class JCodeIcon { Run, Stop, Terminal, Files, Folder, …, Help }   // ~55 members

class IconBundle(
    val id: String, val name: String, val description: String,
    val author: String = "JCode",
    private val overrides: Map<JCodeIcon, ImageVector>,
    private val fallback: IconBundle? = null,
) {
    operator fun get(icon: JCodeIcon): ImageVector
}
```

Every icon in the app is referenced through the `JCodeIcon` enum, never as a direct
`Icons.Rounded.*`, so a bundle can replace the whole set.

| Bundle | `id` | Description |
|---|---|---|
| Material Rounded | `material` | The built-in Material Rounded set; the complete map |
| JCode Line | `jcode-line` | Hand-drawn vector line pack, with `fallback = defaultIconBundle` for unfilled slots |

`IconBundleRegistry.builtIns = listOf(defaultIconBundle) + customIconBundles`;
`byId(id)` falls back to the default. Access is via `LocalIconBundle`, a
`staticCompositionLocalOf` — bundles change rarely, so a static local avoids invalidating every
reader on unrelated recomposition.

The fallback chain means a partial bundle is legitimate: fill in the icons you want and inherit the
rest.

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
| Unknown theme or icon bundle id | Falls back to the default |
| Partial icon bundle without a fallback | Missing icons throw on `get` |
| Tint alpha below the documented minimum | Fails WCAG AA in one of the two themes |
| Theme changed but not pushed to `EditorState` | The editor keeps the old colours |

---

## 9. Known gaps

- Theme and icon bundles are compiled in; there is no extension contribution point for them yet,
  though the data model would support one.
- `ThemeBundle.fontFamily` exists but no built-in bundle sets it.

---

## 10. References

- [Settings reference](04-settings-reference.md)
- [Editor state and undo](../02-editor/02-editor-state-and-undo.md) — `EditorTheme`
- [Rendering and decorations](../02-editor/03-rendering-and-decorations.md)
- [Panels and tools](03-panels-and-tools.md)
