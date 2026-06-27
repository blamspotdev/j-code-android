# J Code — Implementation Plan (v2)

> **Status:** Planning document only. No code is to be written from this file alone — each step contains an *Internal Prompt* meant to be handed to an implementing agent in its own task.
> **Companion file:** [`JCode_Verification.md`](JCode_Verification.md) covers acceptance & behavioral verification per phase.
>
> **v2 changes (this revision):**
> - **No third-party editor.** The code editor is built from scratch (piece tree in C++ NDK, custom Android `View` + Canvas renderer, custom `InputConnection`). Phase 4 is rewritten and considerably larger.
> - **Toolchain lives in a proot-distro chroot.** First-run wizard asks the user to pick **Ubuntu 24.04 LTS** or **Debian 12 (Bookworm)**. All compilers, LSPs, debuggers, build tools (clangd, kotlin-lsp, tsserver, gdb, lldb, SDKs, NDKs, Gradle, Cargo, npm, .NET) live inside that distro, configurable by the user. J Code orchestrates via `proot-distro login`. No toolchains are bundled in the APK.

---

## 0. Product summary

**J Code** is a native Android IDE targeting Android 13+ (API 33+), built primarily in **Kotlin** with a **C++ NDK core** for performance-sensitive subsystems (text buffer, parsing, rendering hot paths, search, VCS, debugger transport). It uses **Termux + proot-distro** to host the user's Linux environment (Ubuntu LTS or Debian) where all toolchains and language services run. It embeds a custom-built VS Code-class editor across phone / tablet / foldable / ChromeOS / Android desktop windowing.

### Locked technology decisions

| Concern | Decision | Rationale (see research) |
|---|---|---|
| UI framework | Jetpack Compose + Material 3 (`material3:1.4.x`, `material3-adaptive:1.2.0`) | Adaptive layouts; smaller code; AndroidView interop where needed |
| **Code editor** | **From scratch.** Custom `View` + Canvas renderer; **piece tree** text buffer in C++ NDK; **custom `InputConnection`**; layered decoration renderer | Full control over IME, multi-cursor, perf, no LGPL dep |
| Syntax/struct | **tree-sitter** via vendored `android-tree-sitter` JNI (v4.3.1 source, rebuilt locally) — runs in our process, drives editor decorations | Incremental parsing, broad grammar support |
| **Toolchain backend** | **Termux + proot-distro 5.1+**, user picks Ubuntu 24.04 LTS or Debian 12 at first run | User-configurable SDKs/NDKs/LSPs; no bake-in; real apt; long-term maintainable |
| Interactive shells | J Code-owned PTY (forkpty JNI) executing `proot-distro login <distro>` via the linker64 trick into Termux's bash | Single in-process PTY; full distro toolchain accessible from one shell |
| One-shot tasks | Termux **RUN_COMMAND** intent dispatched into `proot-distro login <distro> -- bash -lc "…"` | Background tasks with PendingIntent result |
| Tier-1 nav | universal-ctags arm64 binary (in app `nativeLibraryDir`, no distro dependency) | Cross-language goto-def floor available before distro install |
| Tier-2 LSP | LSPs **installed via apt inside the distro** (clangd, kotlin-lsp, typescript-language-server, pyright, gopls, rust-analyzer, omnisharp). Transport: stdio over PTY pair through `proot-distro login` | Real toolchains, user-managed |
| Terminal view | Custom Android `View` (VT100 parser in C++ NDK) inside J Code | No third-party terminal; consistent UI with editor; same renderer family |
| VCS | **libgit2 via custom JNI** with mbedTLS + libssh2 (in-process, fast); CLI `git` inside the distro available for power users via terminal | 2-3× clone perf on mobile |
| Search | ripgrep compiled as FFI lib (in-process) | Industry standard speed |
| Config | YAML (snakeyaml-engine 2.x) | User requirement |
| Storage | App-specific external storage (`getExternalFilesDir`) and `/sdcard/JCode/projects/` as workspace roots, bind-mounted into the distro at `/workspace` | Play-policy safe; no `MANAGE_EXTERNAL_STORAGE`; distro sees projects without copies |
| Extensions | **WASM components via wasmtime-android** + data-only contribs (themes, grammars, snippets) | Sandbox-safe, Play-safe |
| DI | Hilt | Standard |
| Persistence | DataStore (settings/state) + Room (workspaces, extension registry, recents, ctags index) | Standard |
| Min SDK | 33 | User requirement; enables `Cleaner`, modern FGS, 16KB page-size alignment |
| Target SDK | 36 (Android 16, 2026) | Latest |
| ABI | arm64-v8a (release); +x86_64 (debug) | >99.5% Android-13+ coverage |

### Module layout (top-level Gradle)

```
:app                       # Compose UI, Hilt graph, navigation
:core:design               # M3 theme, density, hotkey infra, icons
:core:adaptive             # Window-size + posture state, layout scaffolds
:core:fs                   # Workspace abstraction, SAF bridge, file watcher (inotify via JNI)

# === Editor stack (was sora) ===
:core:buffer               # JNI to libjcodebuffer.so (piece tree); Kotlin API: Buffer, Edit, Snapshot, LineRef
:core:editor               # Custom EditorView (View), renderer, InputConnection, gesture/scroll, selection
:core:editor-decor         # Decoration framework (layers, dirty-region tracking, inlays)
:core:editor-completion    # Completion UI window, providers, ghost-text inlay, snippet engine
:core:treesitter           # JNI bindings + grammar registry + query runner + producer driving editor decorations

# === Backend / toolchain ===
:core:term                 # Custom terminal View + VT100 parser JNI + PTY JNI + Termux IPC
:core:distro               # proot-distro orchestration: install, login, exec, bind-mounts, env, SDK manager
:core:lsp                  # LSP4J client over proot PTY transport, diagnostics → editor decorations bridge
:core:ctags                # universal-ctags driver + index DB (Room/SQLite)
:core:debug                # DAP client (JSON-RPC over proot PTY)

# === Cross-cutting ===
:core:vcs                  # libgit2 JNI + porcelain API
:core:search               # ripgrep FFI + result streaming
:core:config               # YAML parse/serialize + schema validation
:core:ext                  # WASM host (wasmtime JNI), extension registry, contribution dispatcher
:core:state                # Last-session restore, recents, breadcrumbs

# === Features ===
:feature:explorer
:feature:editor-pane
:feature:terminal-pane
:feature:problems
:feature:scm
:feature:search
:feature:debug
:feature:settings
:feature:sdk-manager        # UI over :core:distro for apt / rustup / sdkmanager / nvm-style install actions
:feature:onboarding         # First-run wizard: Termux → permissions → proot-distro → distro pick → toolchain bootstrap

# === Native ===
:native:buffer              # libjcodebuffer.so — piece tree, UTF-8, mmap original buffer
:native:editor-render       # libjcodernd.so — shaping cache, glyph atlas helpers (where Canvas APIs are insufficient)
:native:tree-sitter         # libtreesitter.so (core) + libtree-sitter-<lang>.so per grammar
:native:libgit2             # libgit2_ffi.so + libssh2 + libmbedtls
:native:ripgrep-ffi         # libripgrep_ffi.so (Rust + cargo-ndk)
:native:pty                 # libpty.so (forkpty, openpty, TIOCSWINSZ)
:native:vt                  # libvtparser.so — VT100/ANSI parser for the terminal View
:native:wasmtime-ffi        # libjcode_wasm.so (Rust + cargo-ndk, wasmtime + WIT bindings)
```

### Folder layout on device

```
# J Code's app-private files
/data/data/dev.jcode/files/
  ├─ wasm-cache/                    # compiled extension modules
  ├─ index/<workspace-hash>.db      # ctags + search index
  ├─ bin/ctags                      # arm64 universal-ctags
  └─ logs/

# Workspaces (default; user may relocate)
/storage/emulated/0/JCode/projects/<project>/
    ├─ .jcode/
    │   ├─ project.yaml             # project-level config
    │   ├─ launch.yaml              # run/debug configs
    │   ├─ breakpoints.yaml
    │   ├─ state.yaml               # last open files, cursor positions
    │   ├─ extensions.lock
    │   └─ trash/<ts>/              # soft-delete
    └─ <source files>

# Termux (different UID; J Code talks to it via intents + PTY bridge)
/data/data/com.termux/files/
  ├─ usr/                           # Termux $PREFIX (bash, proot, proot-distro …)
  ├─ home/                          # Termux $HOME (jcode boot scripts)
  └─ usr/var/lib/proot-distro/
       └─ containers/<distro>/      # the user's Linux rootfs (Ubuntu/Debian)
                ├─ rootfs/          # actual filesystem
                └─ env              # canonical login env (built by J Code's distro module)

# Inside the distro (visible after `proot-distro login`)
/workspace/                          # bind-mounted /storage/emulated/0/JCode/projects/
/home/jcode/                         # non-root user created by J Code's bootstrap
   ├─ .jcode/                        # cache of per-distro toolchain settings
   ├─ .npm /.cargo /.gradle /.dotnet # toolchain user dirs
   └─ ...
```

---

## Phase 0 — Repository bootstrap, tooling, CI

**Goal:** Multi-module Gradle project compiling an empty Compose app + NDK toolchain wired.

### 0.1 Initialize repo + version catalog

- Configure: AGP 8.7+, Gradle 8.11+, Kotlin 2.1, JDK 21, NDK r27c (`27.2.12479018`), CMake 3.28.3.
- `gradle/libs.versions.toml` declares: Compose BOM (2026.x stable), Hilt, Room, DataStore, Coroutines, snakeyaml-engine, LSP4J (debug + lsp), Bouncy Castle (ed25519), JetBrains kotlinx.serialization.

**Internal prompt:**
> Create a multi-module Gradle project at the repo root with the layout in `JCode_Plan.md §0` "Module layout". Use AGP 8.7, Kotlin 2.1, JDK 21, NDK r27c. Set up `gradle/libs.versions.toml` per the same section. Each module starts as an empty stub. `:app`'s `MainActivity` displays "J Code" centered using Compose Material 3. Min SDK 33, target SDK 36. Verify with `./gradlew :app:assembleDebug`.

### 0.2 NDK + CMake superbuild

- `:native:*` modules build into per-ABI staging then publish as `jniLibs`.
- Enforce `-Wl,-z,max-page-size=16384`, `-fvisibility=hidden`, strip release.
- ABI: arm64-v8a (release), +x86_64 (debug).
- Superbuild `native/CMakeLists.txt` fetches: tree-sitter core, libgit2, libssh2, mbedtls — pinned commits.

**Internal prompt:**
> Add CMake-based external native build to each `:native:*` module. The top-level `native/CMakeLists.txt` superbuild pins via `FetchContent`: tree-sitter (vendored from android-tree-sitter 4.3.1 source), libgit2 1.9.x, libssh2, mbedtls — all built statically and linked into the per-module `.so` artifacts. Output libs per §"Module layout" `:native:*`. Wire into Gradle as `jniLibs.srcDirs`. Set 16KB page alignment flag and `-fvisibility=hidden`. Verify each native lib loads via `System.loadLibrary` in a smoke instrumented test.

### 0.3 Rust toolchain for native crates

- `cargo-ndk` integration for `:native:ripgrep-ffi` and `:native:wasmtime-ffi`.
- A shared `gradle/cargo.gradle.kts` runs cargo + copies artifacts into module's `jniLibs`.

**Internal prompt:**
> Configure `cargo-ndk` 3.x for `:native:ripgrep-ffi` (uses `grep`/`ignore`/`regex` crates) and `:native:wasmtime-ffi` (uses `wasmtime` 27+ with component-model feature). Share `gradle/cargo.gradle.kts`. Run before `:core:search:preBuild` and `:core:ext:preBuild`. Output `libripgrep_ffi.so`, `libjcode_wasm.so` for arm64-v8a (release) and +x86_64 (debug).

### 0.4 CI

- GitHub Actions: assemble, lint, unit, detekt, ktlint, spotless, instrumented smoke on `pixel_6_api_34` emulator.

**Internal prompt:**
> Add `.github/workflows/ci.yml` running on push and PR: JDK 21, NDK r27c, Rust stable, `./gradlew assembleDebug lintDebug testDebugUnitTest detekt connectedDebugAndroidTest`. Cache Gradle + Cargo + NDK + Konan. Fail on any new warning above baseline.

---

## Phase 1 — Adaptive shell, design system, navigation

**Goal:** Window adapts across Compact / Medium / Expanded; activity bar + drawer + editor area + status bar render with placeholders.

(Unchanged in v2 — full content below for completeness.)

### 1.1 Theme & density

- `:core:design`: typography (JetBrains Mono for code; system sans for UI; dense overrides), color schemes (dark default, light secondary), `LocalDensityMode` enum from physical-keyboard presence.
- Custom dense rows; sub-48dp targets gated on `LocalDensityMode.current == Compact`.

**Internal prompt:**
> In `:core:design`, define `M3Theme` wrapping `MaterialTheme` with dark (default, seed `#1E1E2E`) and light (`#FAFAFA`) schemes. Override typography: `bodyMedium` 13sp/1.30, `labelMedium` 12sp/1.25, monospace via JetBrains Mono `FontFamily`. Provide `LocalDensityMode = compositionLocalOf { DensityMode.Comfortable }`, `LocalIconSize = 18.dp`. Add a `DenseRow` composable parameterized over leading/content/trailing/height; defaults to 28.dp in Compact, 40.dp in Comfortable.

### 1.2 Window adaptive state

**Internal prompt:**
> In `:core:adaptive`, expose `@Composable fun rememberJCodeWindowInfo(): State<JCodeWindowInfo>` with `widthClass`, `heightClass`, `posture (Flat | TableTop | Book)`, `hasPhysicalKeyboard`. Drive widthClass/heightClass from `currentWindowAdaptiveInfo(supportLargeAndXLargeWidth = true)`; posture from `WindowInfoTracker.windowLayoutInfo(activity)` inside `repeatOnLifecycle(STARTED)`; keyboard from `LocalConfiguration.keyboard != KEYBOARD_NOKEYS` plus `InputManager.InputDeviceListener` hot-plug.

### 1.3 Shell scaffold

- Compact: `NavigationBar` bottom + `ModalNavigationDrawer`.
- Medium: `NavigationRail` + `DismissibleNavigationDrawer`.
- Expanded+: `NavigationRail` + `PermanentNavigationDrawer` + `SupportingPaneScaffold`.
- `PaneExpansionState` drag handle for splits.

**Internal prompt:**
> In `:app`, build `JCodeShell()` selecting drawer/rail variant per `rememberJCodeWindowInfo()`. Activity bar destinations: Explorer, Search, SCM, Run/Debug, Extensions, SDK Manager, Settings. Drawer body + main pane are stubs. Use `SupportingPaneScaffold` with the supporting pane = bottom panel (Terminal / Problems / Output / Debug Console). Add a `TopAppBar` with workspace/project name, breadcrumb, command palette button. Add a bottom status bar (16 dp tall) with cells: branch, errors/warnings, cursor pos, language, encoding, **active distro**.

### 1.4 Command palette skeleton

**Internal prompt:**
> In `:core:design`, add `CommandRegistry` singleton with `register(id, title, group, action, when)`. In `:app`, add `CommandPalette()` triggered by `Ctrl/Meta+Shift+P` via `Modifier.onPreviewKeyEvent` at activity root. Show `ModalBottomSheet` (Compact) or centered `Dialog` (Medium+). Fuzzy match; register three placeholder commands.

---

## Phase 2 — Workspace & project model, filesystem

**Goal:** Open/create a workspace, add projects (folders), persist across launches, abstract SAF vs POSIX, and **predeclare the bind-mount path** that the distro will mount.

### 2.1 Storage model

- **Workspace** = a `.jcode-workspace.yaml` file referencing one or more project roots and global settings.
- Default workspace root: `/storage/emulated/0/JCode/workspaces/default/`.
- Default project root: `/storage/emulated/0/JCode/projects/<name>/`. This path will be bind-mounted at `/workspace/<name>/` inside the distro (Phase 7).
- Internal `WorkspaceRoot` sealed class: `Local(File)`, `Saf(Uri)`.

**Internal prompt:**
> In `:core:fs`, define `FsPath { Local(File), Saf(Uri) }`, `FsNode`, `Fs { list, read, write, watch }`. Provide `PosixFs` (uses `java.io.File`) and `SafFs` (uses `DocumentFile` + `ContentResolver`). The `SafFs.watch` polls every 2 s; `PosixFs.watch` uses inotify via JNI in `:native:pty`'s lib (add `fs_watch` syscalls there to avoid an extra lib). Persist SAF tree URIs in DataStore.

### 2.2 Workspace manager

- Hilt singleton; Room schema: `workspaces`, `projects`, `recents`.
- API: `openWorkspace`, `closeWorkspace`, `addProject(FsPath)`, `removeProject(id)`, `currentWorkspace: StateFlow<Workspace?>`.

**Internal prompt:**
> In `:core:fs`, implement `WorkspaceManager`. Room schema: `workspaces(id, name, rootPath, lastOpened)`, `projects(id, workspaceId, kind, location, name, distroBindTarget, order)`, `recents(uri, kind, lastOpened, pinned)`. `addProject` accepts an `FsPath`, validates writable, creates `.jcode/project.yaml`, and computes a canonical distro-side bind target (`/workspace/<sanitized-name>`). On first launch with no workspaces, create `/storage/emulated/0/JCode/workspaces/default/`.

### 2.3 SAF picker

**Internal prompt:**
> In `:core:fs`, add `rememberOpenFolderLauncher()` wrapping `ActivityResultContracts.OpenDocumentTree`. On success, `takePersistableUriPermission(READ|WRITE)`, route to `WorkspaceManager.addProject(FsPath.Saf(uri))`. Warn the user that SAF projects can't be bind-mounted into the distro and will only be usable via in-process editor + manual file sync.

---

## Phase 3 — Explorer feature

**Goal:** Virtualized, drag-droppable explorer with Tree and List view modes.

### 3.1 Tree model
**Internal prompt:**
> In `:feature:explorer`, build `TreeViewModel(workspace, fs, scmStatus, problems)`. State: `rows: List<TreeRow>` with `id, node, depth, expanded, selected, badge`. `LazyColumn` with stable keys. Lazy expand. Status badges from VCS (Phase 9), error counts (Phase 11), unsaved.

### 3.2 List view mode
**Internal prompt:**
> Add `ExplorerView(mode: TreeOrList)` with a `SegmentedButton` header toggle. List mode: current-folder contents, name/size/mtime columns, breadcrumb. Selection state shared with Tree via `ExplorerSelectionState`.

### 3.3 File operations
**Internal prompt:**
> Context menu + toolbar for create/rename/copy/cut/paste/delete. Delete soft-moves to `<workspace>/.jcode/trash/<ts>/`. Drag-and-drop via `Modifier.dragAndDropSource`/`dragAndDropTarget`; outside drops accept `MIMETYPE_TEXT_URILIST` and copy in via SAF. Errors → `Snackbar` with undo.

---

## Phase 4 — Editor (built from scratch)

**Goal:** A custom code editor: piece tree text buffer in C++ NDK, custom Android `View` + Canvas renderer, custom `InputConnection` (correct CJK + autocorrect + undo), multi-cursor, soft + physical keyboard, smooth scroll/fling/zoom, gutter, folds, decorations, AI ghost text, accessibility. **No sora-editor, no third-party editor framework.**

This phase is large and is sub-phased to deliver visible progress. Each sub-phase ends with verifiable behavior in `JCode_Verification.md §Phase 4`.

### 4.0 Architecture (read first)

```
                   ┌────────────────────────────────────────────┐
                   │   :app + :feature:editor-pane (Compose)    │
                   │  hosts EditorView via AndroidView interop  │
                   └────────────────────────────────────────────┘
                              ▲              │
                              │ events       │ controls
                              │              ▼
┌──────────────────────────────────────────────────────────────────┐
│  :core:editor — EditorView (custom Android View)                 │
│  ┌──────────────┐  ┌────────────────┐  ┌────────────────────┐    │
│  │ Renderer     │  │ GestureRouter  │  │ EditorInputConn    │    │
│  │ (layered,    │  │ (touch + mouse │  │ (custom IC, not    │    │
│  │  dirty-rect) │  │  + stylus +    │  │  BaseInputConn)    │    │
│  │              │  │  keyboard)     │  │                    │    │
│  └──────┬───────┘  └───────┬────────┘  └─────────┬──────────┘    │
│         │                  │                     │               │
│         ▼                  ▼                     ▼               │
│  ┌──────────────────────────────────────────────────────────┐    │
│  │ EditorState (Kotlin) — caret set, selection, viewport,   │    │
│  │ folds, decorations, language descriptor, theme           │    │
│  └────────────────────────────┬─────────────────────────────┘    │
│                               │                                  │
└───────────────────────────────┼──────────────────────────────────┘
                                ▼
              ┌─────────────────────────────────────┐
              │ :core:buffer — JNI Buffer façade    │
              │ Snapshot, EditTx, LineRef, Iter     │
              └────────────────┬────────────────────┘
                               ▼
              ┌─────────────────────────────────────┐
              │ :native:buffer — libjcodebuffer.so  │
              │ Piece tree, UTF-8, mmap original,   │
              │ red-black, per-node line counts,    │
              │ RCU snapshots (lock-free reads)     │
              └─────────────────────────────────────┘

Parallel:
  :core:treesitter  — listens to buffer edits, parses on bg thread,
                      emits ColoredSpan + ErrorRange → :core:editor-decor
  :core:lsp         — listens, emits Diagnostic → :core:editor-decor
  :core:editor-completion — completion windows + ghost text inlay
```

### 4.1 Piece-tree text buffer (C++ NDK)

- Implement VS Code-style piece tree in C++17 under `:native:buffer`.
- UTF-8 internal storage; conversion to UTF-16 only at JNI boundary.
- Original buffer mmap-ed (`MAP_PRIVATE`) when reading from disk; added buffer is `std::vector<uint8_t>` append-only.
- Red-black tree of pieces; each node carries `length_utf8`, `line_count`, `first_line_start_offset` for prefix-sum line lookup.
- SIMD line-break scanning (`memchr` fallback, NEON `vceqq_u8` fast path).
- Snapshots via RCU-style ref-counted root pointer → lock-free reads while a single writer thread mutates.
- Edits batched as `EditTx { ops: [Insert(offset, bytes), Delete(start, end)] }`; commit returns a `Snapshot`.

**Internal prompt:**
> In `:native:buffer`, implement `libjcodebuffer.so`:
> - `Buffer` class: `open(fd or bytes) -> Buffer*`, `snapshot() -> Snapshot*`, `apply(EditTx) -> Snapshot*`, `close()`.
> - `Piece` red-black tree node `{ source: Original|Added, start: u32, length: u32, lineCount: u32, lineStarts: vector<u32>, treeMeta… }`.
> - `Snapshot` is a ref-counted root pointer. Readers acquire by `incRef`, release by `decRef`. Writers build a new root, swap atomically, `decRef` the old.
> - APIs (JNI): `byteLength()`, `lineCount()`, `lineAt(idx) -> (offsetStart, offsetEnd)`, `offsetToLineColumn`, `lineColumnToOffset`, `readRange(start, end, out: ByteBuffer)`, `findChar(byte, from, to)`.
> - Edits in UTF-8; expose `readRangeAsUtf16(start, end, out: CharBuffer)` for IME convenience.
> - On `open(fd)`, `fstat` then `mmap(MAP_PRIVATE)` the file. Track original buffer per file.
> - Line-break scanner: detect `\n`, `\r`, `\r\n`; record EOL per piece-node region.
> - Provide microbenchmark in `:native:buffer` to insert 1M chars at random offsets in a 100 MB file: target < 2 s on Pixel 6.
> Expose Kotlin façade in `:core:buffer`: `class Buffer : AutoCloseable`, `class Snapshot : AutoCloseable`, `EditTx.builder()`, `LineRef`. Use `Cleaner` for lifecycle.

### 4.2 EditorState model (Kotlin)

- Holds: current `Snapshot`, `carets: List<Caret>` (sorted), `viewport: Viewport`, `folds: List<FoldRange>`, `decorations: DecorationSet`, `language: LanguageDescriptor?`, `theme: EditorTheme`.
- Mutations posted to a dedicated `EditorDispatcher` (single thread) so the buffer has a single writer.
- Emits `editorEvents: SharedFlow<EditorEvent>` (TextChanged, SelectionChanged, ViewportChanged, FoldsChanged, DecorationsChanged).

**Internal prompt:**
> In `:core:editor`, implement `class EditorState(buffer: Buffer)`. Internal coroutine context: `newSingleThreadContext("Editor-<id>")`. Public APIs (suspending where appropriate): `applyEdit(EditTx)`, `setSelection(carets)`, `scrollTo(line, col)`, `addFold(range)`, `removeFold(range)`. Observers: `snapshot: StateFlow<Snapshot>`, `selection: StateFlow<List<Caret>>`, `viewport: StateFlow<Viewport>`, `events: SharedFlow<EditorEvent>`. All buffer mutations go through `applyEdit`, which also pushes an undo entry.

### 4.3 Undo / redo

- Linear-history undo (no branching).
- Group edits within a single IME composing session, or within a 500 ms typing burst, or until selection moves.

**Internal prompt:**
> Add `class UndoManager(state: EditorState)`. Records `UndoEntry { invertedEditTx, selectionBefore, selectionAfter, timestamp }`. Group adjacent entries unless: IME composing transaction boundary, selection cursor moved >0 chars, 500 ms idle, or explicit `flushGroup()`. `undo()` applies inverted tx and restores selection; `redo()` reverses. Cap history at 500 groups or 50 MB total inverted-tx bytes, evict oldest. Bind to Ctrl/Cmd+Z and Ctrl/Cmd+Shift+Z.

### 4.4 Renderer — base View, line layout, glyph rendering

- `EditorView` extends `View`. `onMeasure`, `onLayout`, `onDraw(Canvas)`, `onSizeChanged`.
- Compute visible lines from `viewport.scrollY / lineHeight`.
- For each visible line: walk piece tree pieces intersecting the line, build a `LineRun` (array of `{ pieceBytes, color, decorations }`).
- Shaping cache `LruCache<LineShapeKey, ShapedLine>` keyed by `(lineHash, fontKey, sizePx)`.
- ASCII fast path: `Canvas.drawText(char[], offset, count, x, y, paint)`. Complex-script path: `Canvas.drawTextRun(...)` with context bounds.
- Ligatures: `paint.fontFeatureSettings = "'liga' on, 'calt' on"`; disabled when caret is inside a selection covering the run.
- Font: JetBrains Mono via app font asset; user override via config.

**Internal prompt:**
> Implement `class EditorView : View` in `:core:editor`. Override `onMeasure` (match parent), `onSizeChanged` (recompute viewport), `onDraw` (delegate to `Renderer.draw(canvas, viewport)`).
> `Renderer.draw`:
> 1. Compute `visibleLines = (top..bottom)` from viewport scroll.
> 2. For each line, get pieces from `snapshot` intersecting `[lineStart, lineEnd)`.
> 3. Look up `ShapedLine` in `LineShapeCache`; on miss, shape and insert (cache LRU size 5000).
> 4. Draw selection rects, then squigglies, then glyphs, then composing-underline, then ghost text, then inlays, then carets — per §4.7 layered renderer.
> 5. Draw gutter in a separate `canvas.clipRect` on the left.
> Use `Paint { isAntiAlias = true; isSubpixelText = true; typeface = jetbrainsMono; textSize = ... }`. Provide a `RenderConfig` (font, sizePx, lineHeightMul, tabWidth, showWhitespace). ASCII fast path via `Canvas.drawText(char[], …)`; complex script (per-line BiDi/script detect) via `Canvas.drawTextRun(...)`.

### 4.5 InputConnection (IME) — the hard part

- `EditorView.onCreateInputConnection(outAttrs)`:
  - `outAttrs.inputType = TYPE_CLASS_TEXT or TYPE_TEXT_FLAG_NO_SUGGESTIONS or TYPE_TEXT_FLAG_MULTI_LINE`.
  - `outAttrs.imeOptions = IME_FLAG_NO_EXTRACT_UI or IME_FLAG_NO_FULLSCREEN or IME_FLAG_NO_PERSONALIZED_LEARNING or IME_ACTION_NONE`.
  - Optional `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` toggle in settings ("aggressive disable autocorrect"; breaks CJK so off by default).
- Implement `EditorInputConnection : InputConnection` (not `BaseInputConnection`).
  - Composing region kept *outside* the piece tree until `commitText` / `finishComposingText`.
  - Override every method per research §3: composing, commit, delete (codepoint variant), selection clamp, sendKeyEvent, getTextBeforeCursor/AfterCursor/Selected, requestCursorUpdates, performEditorAction, commitContent.
  - On every caret move + scroll: `imm.updateCursorAnchorInfo(view, info)` with `setMatrix`, `setInsertionMarkerLocation`, and `addCharacterBounds` for each composing-region character.
- Per-IME quirk shims (in code, not config): Hangul jamo replace-composing pattern; Vietnamese telex; Pinyin/Japanese multi-key compose; Samsung Keyboard's per-jamo behavior.

**Internal prompt:**
> Implement `class EditorInputConnection(view: EditorView, state: EditorState) : InputConnection` in `:core:editor`. Maintain `composingRegion: Range?` outside the buffer.
> - `setComposingText(text, newCursorPosition)`: replace previous composing region (if any) and the buffer-projected region with `text`; do not commit to undo; record region.
> - `commitText`: write to buffer (one undo group with all composing edits), clear composingRegion.
> - `setComposingRegion(start, end)`: mark existing buffer range as composing without modification.
> - `finishComposingText`: clear composingRegion without modification.
> - `deleteSurroundingTextInCodePoints(before, after)`: required for emoji/surrogate correctness; convert codepoints → UTF-16 offsets → buffer UTF-8 byte offsets.
> - `getTextBeforeCursor(n, flags)` / `getTextAfterCursor(n, flags)` / `getSelectedText(flags)`: serve from snapshot via `readRangeAsUtf16` to a thread-local `CharBuffer` (no per-call allocation).
> - `requestCursorUpdates(mode)`: store mode; on caret/viewport changes call `view.imm().updateCursorAnchorInfo(...)`.
> - `sendKeyEvent(KeyEvent)`: forward to `EditorView.dispatchKeyEvent`.
> - `commitContent(InputContentInfo, …)`: reject or insert as path string per setting.
> - Override `beginBatchEdit` / `endBatchEdit` to defer view invalidation until the batch closes.
> Wire `view.onCreateInputConnection(outAttrs)` to populate `outAttrs` per `JCode_Plan §4.5` and return a new `EditorInputConnection` instance per attach. Ensure undo grouping: composing transactions are one group; flush group on `finishComposingText`, selection move, or 500 ms idle.

### 4.6 Gesture, scroll, fling, zoom, keyboard

- Touch routing: `GestureDetector` (tap, double-tap, long-press) + `ScaleGestureDetector` (pinch zoom) + manual fling via `OverScroller` + `VelocityTracker`.
- Mouse: `MotionEvent` with `TOOL_TYPE_MOUSE` → cursor changes, hover, right-click context menus.
- Stylus: `TOOL_TYPE_STYLUS` → `InputMethodManager.startStylusHandwriting(view)` when over text area; ignore over gutter.
- Physical keyboard: `dispatchKeyEvent` handles motion, edits, hotkeys; passes unhandled keys back via `super`.
- Soft keyboard inset: `WindowInsetsAnimationCallback` (API 30+) drives smooth scroll-to-caret.

**Internal prompt:**
> In `:core:editor`, implement `GestureRouter`. Handle: single tap → place caret; double tap → select word (`BreakIterator.getWordInstance`); triple tap → select line; long-press → show selection handles + magnifier (`android.widget.Magnifier`); two-finger pinch → `ScaleGestureDetector` font scale (clamp 8–32 sp; intermediate scales via `Canvas.scale`, settle by re-shape); single-finger drag with selection → extend; ACTION_DOWN starts `VelocityTracker`, ACTION_UP fling via `OverScroller`. Drive scroll from `Choreographer.postFrameCallback` until fling settles. Mouse `TOOL_TYPE_MOUSE`: `pointerHoverIcon = Text`, `Hand` over links, secondary-button → `DropdownMenu` context menu. Stylus: enter handwriting via `InputMethodManager.startStylusHandwriting(view)` when over text area. Physical keyboard: `dispatchKeyEvent` routes through `HotkeyRegistry` first, then editor commands. Soft keyboard: register a `WindowInsetsAnimationCallback` to animate viewport padding in step with IME show/hide; on settle, smooth-scroll caret into view (250 ms ease-out when delta > one viewport).

### 4.7 Layered decoration renderer

- Layers (bottom → top): background, selection rects, squigglies, glyphs, composing-underline, ghost text, inlays, carets, gutter, minimap, popups.
- `Decoration` interface: `bounds()`, `dirtyRectFor(edit)`, `zIndex`, `draw(Canvas, viewport, glyphPositions)`.
- Dirty-rect tracker unions invalidations from edits; `view.invalidate(rect)`.
- Inlay model: `InlineDecoration(offset, widthPx, draw(canvas, x, y, lineHeight))` — line layout walks pieces interleaved with inlays.

**Internal prompt:**
> In `:core:editor-decor`, define `interface Decoration { fun bounds(viewport): Rect; fun zIndex(): Int; fun draw(canvas, viewport, glyphLayout) }` and `DecorationSet` with subscribe/notify. Z-order per `JCode_Plan §4.7`. Add `DirtyTracker` that consumes `EditorEvent.TextChanged(range)` and produces invalidation rects per layer (background[L], glyphs[L..viewport-end], squigglies[L..diagnostic-range], gutter[L]). The renderer composites by computing the union of layer dirty rects and clipping per-layer. Add `InlineDecoration` API: when shaping a line, walk pieces interleaved with inlays whose offsets fall in `[lineStart, lineEnd)`; advance x by the inlay's `widthPx`; selection/caret hit-tests skip inlay extents.

### 4.8 Tabs, splits, and the editor pane feature

- `:feature:editor-pane`: tab strip, `Resizable2Pane` for split editors, breadcrumb header.
- Per-tab `EditorState` cached by URI; LRU 24.

**Internal prompt:**
> In `:feature:editor-pane`, build `EditorPane(group: EditorGroup)`. Horizontal scrollable tab strip (`LazyRow` of `Tab` with close + dirty-dot). The active tab hosts `EditorView` via `AndroidView`. `EditorGroupManager` allows N groups arranged in `Resizable2Pane` (drag handle, min 200 dp). Persist layout in `WorkspaceState` (Phase 17).

### 4.9 Open/save pipeline

- `openFile(FsPath)`: open `fd` via `ContentResolver.openFileDescriptor` (SAF) or `FileInputStream` (POSIX) → pass `fd` to native `Buffer.open(fd)` (mmap original).
- Encoding detection (BOM + heuristic UTF-8); EOL detection by sampling first 64 KB.
- Save: write buffer's current full content atomically (`<file>.tmp` then rename). Optional debounced auto-save.
- Large file (>5 MB) opens in read-only mode by default with a banner.

**Internal prompt:**
> In `:core:editor`, implement `EditorOpener.open(FsPath): EditorState`. Open the file descriptor via `Fs`; pass `fd` (POSIX) or copy to a temp cache file then pass that `fd` (SAF) to native `Buffer.open(fd)` — this enables mmap. Detect encoding (BOM > UTF-8 confidence > Latin-1 fallback); attach EOL info; resolve language via `LanguageRegistry` (Phase 5). On >5 MB, set `EditorState.readOnly = true` and surface a banner with "Edit anyway" option. `save(state)` writes the full buffer content through `Fs.write` to `<file>.tmp` then renames. Auto-save: 1 s debounce, off by default.

### 4.10 Completion UI window + snippet engine + ghost text

- `:core:editor-completion` provides a `CompletionWindow` (anchored to caret via `CursorAnchorInfo` coords), a `SnippetEngine` (LSP-snippet syntax), and an `InlayProvider` for AI ghost text.
- Providers (Phase 8 wires them in): `TreeSitterCompletionProvider`, `LspCompletionProvider`, `SnippetCompletionProvider`, `LlmInlineProvider`.

**Internal prompt:**
> In `:core:editor-completion`, build `CompletionWindow` as a `PopupWindow` anchored at the caret screen position (read from `CursorAnchorInfo.insertionMarkerLocation`). Renders a virtualized `LazyColumn` of `CompletionItem(label, kind, detail, snippet)`. Selection with arrow keys, accept with Tab/Enter, dismiss with Esc. Add `SnippetEngine` parsing LSP snippet syntax (`$0`, `${1:name}`, transforms), inserting via `EditTx` and driving tab-stop navigation. Add `InlayProvider` for ghost text — registered into the decoration system as a transient `InlineDecoration` at the caret with dim alpha; Tab accepts, Esc rejects.

### 4.11 Accessibility

**Internal prompt:**
> Implement `AccessibilityNodeProvider` on `EditorView` exposing one virtual node per visible line plus an editor root node. Per line: `setText(lineContent)`, `setBoundsInParent`, `setMovementGranularities(CHARACTER|WORD|LINE|PARAGRAPH)`, actions `NEXT/PREVIOUS_AT_MOVEMENT_GRANULARITY`, `SET_SELECTION`. Diagnostics summary status-bar cell uses `accessibilityLiveRegion = polite`. Fire `TYPE_VIEW_TEXT_SELECTION_CHANGED` on selection change. Use AndroidX `ExploreByTouchHelper` as scaffold.

### 4.12 Selection, multi-cursor, magnifier

**Internal prompt:**
> Add `Caret(anchor, head, preferredColumn)` sorted set in `EditorState`. Edit operations iterate carets in reverse offset order. `BreakIterator` for word boundaries; double-tap → word, triple-tap → line; long-press → handles + `Magnifier`. Column/box selection: Alt+drag (physical kb) and three-finger drag (touch); materialize one caret per intersected line at the column range. Ctrl+D adds next occurrence; Ctrl+Alt+Up/Down adds caret above/below. Primary caret blinks at 530 ms; secondaries solid. Selection handles in `PopupWindow`s (overflow keyboard).

### 4.13 Folding

**Internal prompt:**
> Add `FoldRange(start, end, summaryText?)` to `EditorState`. Renderer skips folded ranges in line iteration and substitutes a single placeholder glyph row showing `…` + summary. Gutter shows chevrons on lines that begin a foldable region (data comes from Phase 5 `folds.scm`). Click toggles. Fold persistence in `WorkspaceState`.

### 4.14 Minimap

**Internal prompt:**
> Add `MinimapRenderer` drawing the document at 1/8 scale to an off-screen `Picture` cached per snapshot. Re-render only on `TextChanged` (incremental: re-render affected line range). The minimap renders in a 60 dp wide rail on the right of the editor when `editor.minimap = true`. Click teleports viewport; drag scrubs.

---

## Phase 5 — Tree-sitter parsing & language detection

**Goal:** Syntax highlighting, outline, folding, identifier completion for: C, C++, C#, TypeScript, JavaScript, JSON, YAML, Kotlin, Java, Python, Rust, Markdown, HTML, CSS.

### 5.1 JNI runtime

**Internal prompt:**
> Vendor android-tree-sitter v4.3.1 JNI source into `:native:tree-sitter`. Build `libtreesitter.so`. In `:core:treesitter`, expose Kotlin classes `TsParser`, `TsTree (AutoCloseable)`, `TsNode`, `TsCursor`, `TsQuery`, `TsLanguage` as thin handle wrappers; lifecycle via `Cleaner`. Smoke test parsing `"int x = 1;"` with `tree-sitter-c`.

### 5.2 Grammar build

**Internal prompt:**
> Under `native/grammars/`, add CMake subprojects building tree-sitter grammars for: c, cpp, c-sharp, typescript (typescript + tsx), javascript, json, yaml, kotlin, java, python, rust, markdown, html, css. Each wraps upstream `parser.c` (+ `scanner.c` when present) into `libtree-sitter-<lang>.so`. Pin upstream commits in `grammars/versions.txt`. Copy authoritative `queries/{highlights,locals,folds,tags}.scm` from upstream into `native/grammars/<lang>/queries/`. Strip release.

### 5.3 Language registry

**Internal prompt:**
> In `:core:treesitter`, add `LanguageRegistry` (Hilt singleton). For each grammar: register extensions, MIMEs, query resources, optional LSP descriptor id, optional formatter command id (which Phase 12 will resolve inside the distro). Provide `findByExtension(ext): LanguageDescriptor?`. `EditorOpener` (Phase 4.9) consults this to attach a `TsLanguage` to the `EditorState`.

### 5.4 Highlight + outline + folding contributors

- Background `TsParseService` re-parses on `EditorEvent.TextChanged` with `TSInputEdit`-based incremental edits, debounced 30 ms.
- `HighlightSpanProducer` walks the tree via `TsQueryCursor` and emits a `List<ColoredSpan>` per viewport; pushed to `:core:editor-decor` as the bottom-most glyph-color decoration.
- `OutlineProvider.compute(tree, tags.scm)`, `FoldRangesProvider.compute(tree, folds.scm)` exposed as `Flow<>`s on `EditorState`.

**Internal prompt:**
> In `:core:treesitter`, implement `TsParseService` running parses on `Dispatchers.Default`. Listen to `EditorState.events.filterIsInstance<TextChanged>()`. Apply `TSInputEdit { start_byte, old_end_byte, new_end_byte, start_point, old_end_point, new_end_point }` to the previous tree and re-parse. Emit `TreeState(tree, ranges)` over a `StateFlow`. Implement `HighlightSpanProducer` consuming the tree state + `highlights.scm` + theme map → `List<ColoredSpan>` clipped to the viewport. Push into `EditorState.decorations` as the lowest-z glyph-color layer. Implement `OutlineProvider` and `FoldRangesProvider` analogously. Add `TreeSitterCompletionProvider` for Tier-0 identifier completion using `locals.scm`.

---

## Phase 6 — Configuration system (YAML)

(Unchanged from v1 logic; updated to include distro config keys.)

### 6.1 Schema
**Internal prompt:**
> In `:core:config`, define `WorkspaceConfig` and `ProjectConfig` data classes (kotlinx.serialization). Hand-write `schema/{workspace,project}.schema.json`. Include keys:
> - `editor.{fontSize,tabSize,insertSpaces,wordWrap,minimap,formatOnSave,ligatures,aggressiveAutocorrectKill}`
> - `files.{exclude,watcherExclude}`
> - `search.exclude`
> - `git.autoFetch`
> - `terminal.shell.linux` (override of default `proot-distro login`)
> - `languages.<id>.{formatter, lspId}`
> - `extensions.allowed`
> - `theme.id`
> - `distro.id` (e.g. `ubuntu` or `debian`)
> - `distro.bind` (list of `{ host, target }` overrides)
> - `distro.user` (username inside the distro, default `jcode`)
> Provide `ConfigService` with `workspace`, `project`, `effective` StateFlows (defaults ← workspace ← project deep merge).

### 6.2 Live reload + 6.3 Settings UI
(Same as v1.)

---

## Phase 7 — Termux + proot-distro environment setup

**Goal:** Onboard a user from zero to a working Ubuntu/Debian environment driven from J Code, with the chosen distro installed in Termux's proot-distro, a non-root `jcode` user, bind-mounted projects at `/workspace`, and a baseline toolchain ready. Provide a long-lived interactive PTY into the distro and a one-shot RUN_COMMAND adapter that routes through `proot-distro login`.

### 7.1 First-run wizard

Multi-step, recoverable, re-runnable from Settings. Steps:

1. **Termux installed?** Check `PackageManager.getPackageInfo("com.termux", 0).versionName >= 0.118`. If missing or older, deep-link to F-Droid + GitHub Releases.
2. **Termux:API installed?** (`com.termux.api`) — for clipboard/notification helpers from inside the distro. Optional but recommended.
3. **Grant RUN_COMMAND.** Show user how to enable in `Settings → Apps → J Code → Additional Permissions → RUN_COMMAND`. Open the intent for them.
4. **Enable `allow-external-apps`.** Send a RUN_COMMAND (after step 3 grants it) writing to `~/.termux/termux.properties` and reloading. Validate via a no-op `echo OK` round-trip.
5. **Install `proot-distro`.** Run `pkg install -y proot-distro` via RUN_COMMAND; stream stderr to a progress UI.
6. **Pick distro.** Radio: **Ubuntu 24.04 LTS** (recommended; ~2.5 GB after toolchain) vs **Debian 12 Bookworm** (~2.0 GB after toolchain). Default Ubuntu.
7. **Install distro.** `proot-distro install <id>` (`ubuntu:24.04` or `debian:bookworm`) via a foreground-service-protected RUN_COMMAND so Android doesn't kill it. Progress UI streams from a side-channel log file.
8. **Workspace dir.** Default `/storage/emulated/0/JCode/projects`. Created via `mkdir -p` + bind-mount target set in `:core:distro` config.
9. **Bootstrap toolchain.** Default install set (apt-get -y): `build-essential clang clangd lldb gdb cmake ninja-build git python3 python3-pip nodejs npm openjdk-21-jdk-headless`. Distro-specific deltas listed. Show a per-line progress log.
10. **Create non-root user `jcode`.** `useradd -m -u 1000 -s /bin/bash jcode && passwd -d jcode`, give it sudo (`apt install -y sudo && echo "jcode ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers.d/jcode`).
11. **Smoke test.** Run `proot-distro login <id> --user jcode -w /workspace -b /storage/emulated/0/JCode/projects:/workspace -- bash -lc "clangd --version && node --version && echo ok"` and verify "ok" returned.

Wizard persists each step's completion in DataStore; failure on any step shows actionable error + retry.

**Internal prompt:**
> Build `:feature:onboarding/TermuxDistroWizard`. Implement the 11 steps in `JCode_Plan §7.1` as separate `StepDescriptor` objects with `id`, `title`, `check()`, `run()`, `progressFlow`. Use `BackendService` (FGS) for steps 7 and 9 so they survive backgrounding. Validate step 4 via a no-op RUN_COMMAND `echo OK`. Validate step 11 via the smoke-test command's stdout. Persist completion in DataStore. Re-runnable entry under Settings → Environment.

### 7.2 `:core:distro` orchestration module

- `DistroService` (Hilt singleton) holds: `installed: List<Distro>`, `active: StateFlow<Distro?>`, configuration of binds, user, env, working dir.
- API:
  - `install(id, progress): Flow<DistroEvent>` — runs `proot-distro install`.
  - `bootstrap(id, packages, user): Flow<DistroEvent>` — apt + user setup.
  - `login(id, opts): PtyProcess` — spawn an interactive PTY into the distro (see 7.3).
  - `exec(id, cmd, env, cwd): TermuxRunResult` — one-shot via RUN_COMMAND wrapper.
  - `backup(id, outPath)` / `restore(id, inPath)` via `proot-distro backup`/`restore`.
  - `seccompFallback`: detect proot failures and retry with `PROOT_NO_SECCOMP=1`.

**Internal prompt:**
> Implement `:core:distro` with `DistroService` per `JCode_Plan §7.2`. Construct canonical login args:
> ```
> proot-distro login <id>
>   --user jcode
>   -w /workspace
>   -b /storage/emulated/0/JCode/projects:/workspace
>   -e JCODE=1
>   [-e PROOT_NO_SECCOMP=1 when fallback enabled]
>   -- bash -lc "$CMD"
> ```
> Allow project-config overrides (`distro.user`, `distro.bind` lists, extra `-e` env). `exec()` uses the RUN_COMMAND intent's background mode with a PendingIntent result and the 100 KB output cap; document the cap. `install`/`bootstrap` stream progress by tailing a log file written by the spawned command. Detect proot seccomp failure (exit code 1 + stderr mentions "seccomp") and auto-retry once with `PROOT_NO_SECCOMP=1`.

### 7.3 Interactive PTY into the distro

- `:native:pty` exposes `Pty.create(exe, argv, env, cwd, cols, rows)`.
- Default for J Code's terminal/LSP/debugger sessions: `exe = /system/bin/linker64`, `argv = ["linker64", "/data/data/com.termux/files/usr/bin/bash", "-lc", "exec proot-distro login <id> --user jcode -w /workspace -b ... -- bash"]`, env including `LD_LIBRARY_PATH=/data/data/com.termux/files/usr/lib`, `PREFIX=/data/data/com.termux/files/usr`, `HOME=/data/data/com.termux/files/home`.
- All PTY-based services (Phase 8 LSP, Phase 13 DAP) get an `OpenPty(opts) -> PtyProcess` factory pre-configured for the active distro.

**Internal prompt:**
> Implement `libpty.so` in `:native:pty` with `forkpty` per research; expose JNI for create/read/write/resize/waitFor/close. In `:core:distro`, add `DistroPty.openInteractive(id, cmd): PtyProcess` that constructs the linker64 → bash → `proot-distro login` chain. Auto-detect Termux paths via `PackageManager` (Termux's data dir is well-known at `/data/data/com.termux/files`). Set TERM=`xterm-256color`, COLORTERM=`truecolor`. PTY `cols/rows` driven by the consuming View's measured size (Phase 7.5).

### 7.4 RUN_COMMAND one-shots

- `TermuxOneShot` wraps Intent construction + PendingIntent result reception; routes through `proot-distro login` when an `inDistro = true` flag is set.

**Internal prompt:**
> In `:core:distro`, implement `TermuxOneShot.runInDistro(distroId, cmd, args, workdir, env): TermuxRunResult` building an Intent to `com.termux/com.termux.app.RunCommandService` with `RUN_COMMAND_PATH = /data/data/com.termux/files/usr/bin/proot-distro`, `RUN_COMMAND_ARGUMENTS = ["login", distroId, "--user", "jcode", "-w", workdir, "-b", "...:...", "--", "bash", "-lc", "$JOINED_CMD"]`, `RUN_COMMAND_BACKGROUND = true`, with a `PendingIntent` result receiver. Apply 30 s timeout default; surface `truncated=true` when stdout+stderr > 100 KB.

### 7.5 Custom terminal `View`

- `:native:vt` implements a VT100/xterm-256 parser as a single `Parser` struct consuming bytes and emitting `Cell[][]` + cursor + scroll deltas.
- `:core:term` exposes `TerminalView : View` rendering the cell grid via the same Canvas pipeline used by the editor (shared paint/font setup, glyph cache).
- Selection, copy/paste, virtual key bar (Esc/Tab/Ctrl/Arrows) on Compact, soft keyboard via custom `InputConnection` similar to Phase 4.5 (but simpler — no compose, just key forwarding).

**Internal prompt:**
> Implement `libvtparser.so` in `:native:vt`: streaming VT100/xterm parser with state machine (CSI, OSC, sixel-NO, mouse-events SGR), 256-color + truecolor, alternate screen buffer. Output: a `Screen { rows, cols, cells[rows][cols], cursor, dirtyRanges }` snapshot ABI. In `:core:term`, build `TerminalView : View` rendering the screen via Canvas; reuse JetBrains Mono. Selection (drag + double-click word + triple-click line) with `Magnifier`. Custom `InputConnection` forwards keystrokes to the PTY (no composing/undo logic). Above the soft keyboard render a virtual key bar (Esc, Tab, Ctrl, Shift, arrows, |, /, $, ~) when no physical keyboard is attached. Drive PTY resize on view size change via `Pty.resize(cols, rows)`.

### 7.6 BackendService

- Foreground service `specialUse` subtype `interactive_terminal_and_build_runner` hosts: terminal sessions, LSP processes, debug adapters.
- Started when first session begins, stopped when all sessions close.

**Internal prompt:**
> Add `BackendService` (FGS) in `:app` declared with `android:foregroundServiceType="specialUse"` and `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="interactive_terminal_and_build_runner"/>`. Track active sessions via a `SessionRegistry`; start on first registration, stop on zero. Notification: persistent low-priority showing active session count. WorkManager fallback for batch non-interactive jobs (e.g., scheduled lint).

### 7.7 SDK Manager UI

- `:feature:sdk-manager`: shows a curated catalog of common SDKs/toolchains (Android SDK + NDK + tools, Kotlin via SDKMAN!, Rust via rustup, Go, .NET, Node via nvm, JDK versions, Python venv presets).
- Each catalog entry executes a prepared `proot-distro login ... -- bash -lc "<install script>"` and streams progress.

**Internal prompt:**
> Build `:feature:sdk-manager`. UI: list of catalog entries with category (Languages, Build Tools, Android, .NET, Embedded). Each entry has `name`, `description`, `installScript` (multi-line bash to run inside the distro as user `jcode`), `verifyScript`, `uninstallScript`. Catalog defined in `:core:distro/catalog.yaml`. Execution streams stdout to a Terminal-like log pane. Persist installed-state per entry per distro in DataStore.

---

## Phase 8 — Language services & code intelligence

**Goal:** Three-tier intelligence — tree-sitter (always), ctags (always, in-app), optional LSPs (per language, **running inside the distro**).

### 8.1 Tier 0 (tree-sitter completion + outline)

(Wired from Phase 5.4.)

**Internal prompt:**
> Confirm `TreeSitterCompletionProvider` is registered as the default `CompletionProvider` for every language in `LanguageRegistry`. Wire its output into `:core:editor-completion`'s `CompletionWindow`. Add a "TS" suffix in the completion item kind so users can tell tier 0 hits from LSP hits.

### 8.2 Tier 1 (ctags, in-app)

**Internal prompt:**
> Ship arm64 universal-ctags binary in `:app/src/main/assets/bin/ctags`. On first launch copy to `applicationContext.applicationInfo.nativeLibraryDir`-adjacent dir (`filesDir/bin/ctags`), `chmod 700`. In `:core:ctags`, implement `CtagsIndexer` walking the active project (respecting `files.exclude`), running `ctags --output-format=json --fields=+nKsSafet`, bulk-inserting into Room table `symbols(file, line, name, kind, scope, signature, language)`. Re-index on `FsChange` debounced 2 s. Expose `goToDefinition(symbol, currentFile)` and `findWorkspaceSymbols(query)`.

### 8.3 Tier 2 (LSPs inside the distro)

- LSPs install via `apt`/npm/cargo inside the chosen distro. **No in-app downloads of LSP binaries.**
- Transport: spawn LSP through `DistroPty.openInteractive(id, "<lsp-cmd>")` (interactive PTY into distro running the LSP). Read/write LSP JSON-RPC over the PTY's stdin/stdout (set the distro shell to print no banners; `bash --noprofile --norc -c 'exec <lsp-cmd>'`).
- LSP4J `Launcher.createIoLauncher(server, LanguageServer::class, in, out)` wraps the PTY streams.

**Internal prompt:**
> In `:core:lsp`, define `LspDescriptor { id, languageIds, distroInstallCmd, distroVerifyCmd, distroRunCmd, rootDetector }`. Built-in descriptors:
> - clangd: install `apt-get install -y clangd`; run `/usr/bin/clangd --background-index`.
> - kotlin-language-server (fwcd): install via SDKMAN! kotlin-lsp; run `kotlin-language-server`.
> - typescript-language-server: install `npm i -g typescript typescript-language-server`; run `typescript-language-server --stdio`.
> - pyright: `npm i -g pyright`; run `pyright-langserver --stdio`.
> - gopls: install via `apt` or `go install`; run `gopls`.
> - rust-analyzer: `rustup component add rust-analyzer`; run `rust-analyzer`.
> Implement `LspSession(descriptor, projectRoot)`:
> 1. Resolve distro path mapping: host `/storage/emulated/0/JCode/projects/<p>/` ↔ distro `/workspace/<p>/`. All LSP `Uri`s translated via this mapping (encode/decode at the LSP boundary).
> 2. Spawn `DistroPty.openInteractive(activeDistroId, "exec bash --noprofile --norc -c '${descriptor.distroRunCmd}'")`.
> 3. Wire LSP4J `Launcher.createIoLauncher(LanguageServer::class, pty.inputStream, pty.outputStream)`.
> 4. Forward `textDocument/publishDiagnostics` into `DiagnosticsBus` (Phase 11). Forward completion into `EditorState.completionProviders`. Forward hover/codeAction/definition/references through editor commands.
> 5. Provide an `installIfMissing()` step that runs `descriptor.distroVerifyCmd` and, if it fails, runs `descriptor.distroInstallCmd` via SDK Manager flow.
> Surface LSP status (starting/healthy/crashed) in the status bar.

### 8.4 Tier 3 LLM completion (optional)

**Internal prompt:**
> Add `:core:editor-completion/LlmInlineProvider`. Config: `{ baseUrl, apiKey, model, maxTokens, temperature }` stored in EncryptedSharedPreferences (Jetpack Security with Keystore-backed master key). Trigger on idle (300 ms after keystroke), 200-char prefix + 100-char suffix as context. Render via `:core:editor-decor` `InlineDecoration` (ghost text). Tab accepts; Esc rejects. Off by default.

---

## Phase 9 — Source control / Git

**Goal:** Native libgit2-backed VCS (in-process, fast). The CLI `git` inside the distro remains available for power users via the terminal.

### 9.1 libgit2 JNI
**Internal prompt:**
> Build libgit2 1.9.x in `:native:libgit2` with mbedTLS + libssh2 static, output `libgit2_ffi.so`. Hand-roll JNI for: open/init/clone(creds, progress), status, index_add/remove, commit, branch_create/checkout/delete, remote_fetch/push, merge, diff, blame, log. Kotlin façade in `:core:vcs`: each call on `newSingleThreadContext("git")` (libgit2 not thread-safe per repo). For projects under SAF roots: warn and fall back to CLI `git` inside the distro via PTY.

### 9.2 Credentials
**Internal prompt:**
> `CredentialStore` (EncryptedSharedPreferences). SSH ed25519 generated in-app (BouncyCastle). UI in Settings → Source Control. The same store can export keys to the distro at `/home/jcode/.ssh/` (file copy) so the user's CLI git works identically.

### 9.3 SCM view + 9.4 Conflicts
(Same as v1; diff view uses `EditorView` in read-only mode with side-by-side `EditorState`s sharing horizontal scroll.)

---

## Phase 10 — Search & replace
(Unchanged; ripgrep runs in-process for performance.)

**Internal prompt:**
> Same as v1 Phase 10.

---

## Phase 11 — Real-time errors / Problems pane

### 11.1 Diagnostics bus

**Internal prompt:**
> Add `:core:editor/DiagnosticsBus` (Hilt singleton). `data class Diagnostic(file, range, severity, code, source, message, related)`. Producers: `TreeSitterErrorProducer` (ERROR nodes), `LspDiagnosticsProducer`, `YamlSchemaProducer`, `JsonSchemaProducer`. Consumers: the editor decoration system renders squigglies (a `Decoration` layer in `:core:editor-decor`); Problems pane lists; status-bar shows totals.

### 11.2 Problems pane
(Same as v1.)

---

## Phase 12 — Refactoring

### 12.1 Rename
**Internal prompt:**
> Same as v1 — LSP `textDocument/rename` if available, tree-sitter scope-aware fallback. LSP-resulting `WorkspaceEdit` translated through distro↔host path mapping (Phase 8.3) before applying via `EditTx` to each open `EditorState` or `Fs.write` for unopened files.

### 12.2 Code actions
(Same as v1.)

### 12.3 Format

- Formatters live in the distro (clang-format, prettier, ktfmt, black, rustfmt).
- Resolve via `LanguageDescriptor.formatter` (a command name); invoke via `TermuxOneShot.runInDistro` piping content through stdin, capturing stdout.

**Internal prompt:**
> Add `Formatter.format(file, content): String`. Resolve formatter command from `LanguageDescriptor.formatter` + project config override (e.g., `formatter: clang-format`). Build `proot-distro login <id> --user jcode -w /workspace -- bash -lc "$FORMATTER --assume-filename=$BASENAME"`. Pipe `content` via stdin, capture stdout (handle the 100 KB RUN_COMMAND cap — for large files, fall back to running through the long-lived `DistroPty` so there's no cap). Replace editor content via one `EditTx`. Trigger on save when `editor.formatOnSave = true`.

---

## Phase 13 — Integrated debugging (DAP)

**Goal:** Debug adapters (lldb, debugpy, vscode-js-debug, vscode-go, codelldb-rust) running **inside the distro**; J Code drives them via DAP over the distro PTY.

### 13.1 DAP client

**Internal prompt:**
> In `:core:debug`, use `org.eclipse.lsp4j:debug:0.24+`. Spawn adapter via `DistroPty.openInteractive(activeDistroId, "<adapter-cmd>")`; wrap with `DebugLauncher`. Expose `DebugSession`: `launch(config)`, `setBreakpoints(file, lines)`, `continue`, `step*`, `pause`, `stackTrace`, `scopes`, `variables`, `evaluate`. Path translation host↔distro applied to every path in launch config and DAP frames.

### 13.2 Run/debug configs

**Internal prompt:**
> `.jcode/launch.yaml` schema: `configurations: [{ name, type, request: launch|attach, program, args, cwd, env, preLaunchTask, debuggerPath, distro }]`. `type` resolves to a debug adapter descriptor (lldb, debugpy, etc.) similar to `LspDescriptor` — including `distroInstallCmd` for SDK Manager. UI in `:feature:debug` with config dropdown, Run/Debug buttons, callstack tree, variables tree, watch, console, evaluate field.

### 13.3 Breakpoints

**Internal prompt:**
> Add gutter region in `EditorView` (`:core:editor`) where clicks toggle breakpoints. Persist per project in `.jcode/breakpoints.yaml`. On debug start, send `setBreakpoints` per file. Conditional breakpoint dialog on right-click. Breakpoints render via the gutter `Decoration` layer in `:core:editor-decor`.

---

## Phase 14 — Extensions (WASM)

(Unchanged manifest + host design. Distro is a separate path.)

### 14.1 Manifest

**Internal prompt:**
> Define `ExtensionManifest` per v1 §14. Add an optional `distro` contribution allowing an extension to declare apt/npm/cargo install commands it wants to run in the user's distro (with permission prompt). Validate against `extension.schema.json`.

### 14.2 WASM host

**Internal prompt:**
> Build `:native:wasmtime-ffi` exposing `libjcode_wasm.so`. WIT in `wit/jcode.wit` defining worlds for commands/languages/views/formatters/debuggers. Implement `ExtensionHost` in `:core:ext` instantiating engine + per-extension store. Capabilities gated per manifest's `permissions`.

### 14.3 Data-only contribs
(Same as v1.)

### 14.4 Marketplace + sideload
(Same as v1.)

---

## Phase 15 — Themes

**Internal prompt:**
> In `:core:design`, `Theme = { ui: ColorScheme, editor: EditorPalette, syntax: Map<TmScope, Style> }`. Bundle 4 themes (JSON in `assets/themes/`). `ThemeRegistry` Hilt singleton with `current: StateFlow<Theme>`. Applied to Compose `MaterialTheme` and to `EditorView.themePalette`. Picker in Settings → Appearance shows live preview rendered with our own `EditorView` showing a code snippet per theme.

---

## Phase 16 — Physical keyboard, mouse, and hotkeys

(Same as v1, but `EditorView.dispatchKeyEvent` participates in hotkey routing first.)

**Internal prompt:**
> Add `HotkeyRegistry` per v1 §16.1. At activity root install `Modifier.onPreviewKeyEvent` mapping `KeyEvent → KeySequence` → command lookup. `EditorView.dispatchKeyEvent` consults the registry before applying as text input. Override `Activity.onProvideKeyboardShortcuts` to surface in system Meta+/ helper. Mouse hover icons + right-click menus via `Modifier.pointerHoverIcon` and `pointerInput`. Trackpad pinch zooms editor font (mouse/touchpad only).

---

## Phase 17 — State persistence

**Internal prompt:**
> Same as v1 — `.jcode/state.yaml` debounced 5 s, on `STOPPED`, on configuration change. Persist editor groups, tabs (path, viewport, selection, dirtyContentHash, draftPath), layout, panels, terminal sessions (`{name, cwd}` inside distro), **active distro id**, recently used distros. On startup: restore tabs/layout; prompt to "Restore N terminals?" — when accepted, re-spawn `DistroPty` sessions with original cwd.

---

## Phase 18 — Polish, packaging, release
(Same as v1; macrobenchmarks now include editor-specific budgets from `JCode_Verification.md §Phase 4`.)

---

## Cross-cutting standing rules

1. **No bundled compilers / LSPs / debuggers in the APK.** Everything beyond tree-sitter, ctags, libgit2, ripgrep lives in the distro.
2. **Path translation discipline.** Every host↔distro boundary normalizes paths via `DistroPathMap`. Never pass `/storage/emulated/0/...` to an LSP/debugger; never pass `/workspace/...` to a host-side `Fs`.
3. **Single writer per buffer.** All buffer mutations go through `EditorState.applyEdit` on a single-threaded dispatcher.
4. **JNI handles wrapped `AutoCloseable` + `Cleaner`.** No finalizers.
5. **Permissions on demand.** RUN_COMMAND requested only when first needed. `POST_NOTIFICATIONS` when first FGS starts. SAF when adding external project.
6. **Failure surfaces.** Long ops post to `OperationsBus` (status bar + notifications). Distro failures with seccomp auto-retry once with `PROOT_NO_SECCOMP=1` then surface.
7. **Module isolation.** `:core:*` never depends on `:feature:*`; `:feature:*` depends only on `:core:*`; `:app` depends on everything.
8. **No PII in logs or telemetry.** Path-redact crash traces.

---

## Order of execution (suggested critical path)

1. **Phase 0** → repo scaffolding
2. **Phase 1** → adaptive shell
3. **Phase 2 → 3** → workspace + explorer
4. **Phase 4.1 → 4.4** → buffer + minimal renderer + open-a-file-show-text *(MVP editor visible)*
5. **Phase 4.5 → 4.6** → IME + gestures *(typing works)*
6. **Phase 5** → tree-sitter highlighting *(it looks like an editor)*
7. **Phase 6** → config
8. **Phase 7** → Termux + distro setup + terminal view *(toolchain available)*
9. **Phase 4.7 → 4.10 → 4.13** → decorations + completion + folding *(productive editing)*
10. **Phase 8** → LSPs in distro *(real intelligence)*
11. **Phase 9** → SCM
12. **Phase 10 → 11** → search + problems
13. **Phase 12 → 13** → refactor + debug
14. **Phase 4.11 → 4.12 → 4.14** → a11y + multi-cursor + minimap
15. **Phase 14 → 15** → extensions + themes
16. **Phase 16 → 17** → keyboard + state
17. **Phase 18** → ship

Each phase ends with the corresponding section in [`JCode_Verification.md`](JCode_Verification.md) being green before moving on.
