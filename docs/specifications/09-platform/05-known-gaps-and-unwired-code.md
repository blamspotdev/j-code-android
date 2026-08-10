# Known gaps and unwired code

| | |
|---|---|
| **Status** | Reference |
| **Modules** | Repository-wide |
| **Primary sources** | See each entry |
| **Verified against** | commit `cea581c`, 2026-08-09 |

---

## 1. Purpose and scope

The honest inventory. Everything here is code that exists in the repository — often complete,
sometimes device-verified in isolation — that **nothing currently calls**, or that is a marker with
no behavior at all.

It exists so that reading a module name, a dependency, or a KDoc comment does not lead to a wrong
conclusion about what the app does. Each entry is also stated inline in the relevant subsystem
specification.

---

## 2. Built but unwired

Complete implementations with no live caller.

### 2.1 The tree-sitter stack

| Piece | State |
|---|---|
| `native/grammars` | Builds **14** grammar `.so` files at pinned upstream tags and ships them in the APK |
| `native/tree-sitter/src/jni_treesitter.c` | ~50 real JNI exports over upstream tree-sitter |
| `core/treesitter/src/main/java/dev/jcode/core/treesitter/TsHandles.kt` | A complete `Cleaner`-backed binding: parser, tree, node, cursor, query, language |
| `TsLanguage.load(libName, funcName, …)` | A working `dlopen`/`dlsym` grammar loader |
| `TsParseService` | Real incremental parsing: 30 ms debounce, cancel-and-replace, prefix/suffix diffing |

**Where the chain breaks:** `LanguageRegistry.registerDefaultLanguages()` calls
`TsLanguage.create(id, extensions)` — which yields `nativeHandle = 0` — and **never** calls
`TsLanguage.load(...)`. No grammar is ever `dlopen`'d. Separately,
`HighlightSpanProducer.produce()` is an explicit stub returning an empty list.

Syntax colouring actually comes from `core/buffer/src/main/java/dev/jcode/core/buffer/NativeHighlighter.kt` →
`native/buffer/src/highlight.cpp`, a hand-written tokenizer with a Kotlin twin. See
[Syntax highlighting and completion](../02-editor/05-syntax-highlighting-and-completion.md).

### 2.2 `:native:core`'s editor engine

`native/core/src/main/cpp/` contains a complete C++ `EditorState`, `UndoManager` and `ConfigService`
(with yaml-cpp), plus JNI bridges. `jni_editor_state.cpp` exports
`Java_dev_jcode_core_editor_EditorState_*` and `Java_dev_jcode_core_editor_UndoManager_*` —
targeting the **real** Kotlin classes — and `jni_config.cpp` backs
`dev.jcode.native.core.ConfigServiceNative`.

Nothing binds: `core/editor` and `core/config` declare **no `external fun` at all**. Only
`CoreNativeModule.nativeIsAvailable` and the library's `JNI_OnLoad` infrastructure are live.

### 2.3 `:native:libgit2`

Statically links real libgit2, libssh2 and mbedTLS (all `WHOLE_ARCHIVE`) into `libgit2_ffi.so`, but
exposes **no JNI functions** — the CMake target is `jcode_add_stub_library`. The crypto and Git code
is present in the shipped APK and unreachable.

---

## 3. Stub modules

Declared in `settings.gradle.kts`, depended on by `:app`, containing only a marker object.

| Module | Marker says | Reality |
|---|---|---|
| `:core:vcs` | "libgit2 JNI + porcelain API, Phase 9" | SCM is a WebView-hosted extension |
| `:core:ctags` | "universal-ctags driver + index DB (Room/SQLite), Phase 8" | No symbol index exists |
| `:core:state` | "Last-session restore, recents, breadcrumbs, Phase 17" | Session restore lives in `app/src/main/java/dev/jcode/SessionStore.kt` |
| `:core:ext` | "WASM host (wasmtime JNI), extension registry, contribution dispatcher, Phase 14" | Extensions run in a WebView or a Node process |
| `:native:editor-render` | — | No sources at all; only a stub `.so` |
| `:native:wasmtime-ffi` | — | Its Rust crate is **4 lines** returning a version number |

`:core:editor-decor` is a fourth kind: a module boundary whose only content is a documentation
marker. The decoration types physically live in `:core:editor`'s `decor` package, so anything
depending on `:core:editor-decor` is really depending on `:core:editor`.

### 3.1 Duplicate package leftovers

`:core:vcs`, `:core:ctags`, `:core:state` and `:core:ext` each ship the **same** marker under two
package names (`dev.jcode.<x>` and `dev.jcode.core.<x>`). Harmless, but the doubled file count is not
evidence of implementation.

---

## 4. Stub feature modules with the real UI in `:app`

Five `:feature:*` modules are marker objects; their working implementations are in `:app`:

| Stub module | Real implementation |
|---|---|
| `:feature:terminal-pane` | `app/src/main/java/dev/jcode/TerminalSessionHost.kt`, `app/src/main/java/dev/jcode/workbench/WorkbenchExtraKeys.kt` |
| `:feature:scm` | `app/src/main/java/dev/jcode/workbench/ScmExtensionHost.kt` |
| `:feature:problems` | `app/src/main/java/dev/jcode/IssuesPanel.kt` |
| `:feature:search` | `app/src/main/java/dev/jcode/workbench/SearchToolPanel.kt` |
| `:feature:debug` (UI half) | `app/src/main/java/dev/jcode/DebugSessionPanel.kt`, `app/src/main/java/dev/jcode/RunDebugPanel.kt` |

`:feature:marketplace` inverts this — it holds the logic and no UI; `:app` supplies the screens.

---

## 5. Partially wired features

### 5.1 Editor ↔ language server

Wired, via `dev.jcode.lsp.LspController` — diagnostics, completions, Go to Definition, Find
References, Rename Symbol and Format Document all reach a running server. See
[LSP client](../04-language-services/01-lsp-client.md). What remains:

- **Document sync is full-document, not incremental**: each debounced change re-sends the whole file.
- **Hover has no editor UI.** `LspSession.hover` and `LspController.hover` work; nothing calls them.
- No code actions, semantic tokens, signature help, document symbols, or workspace symbol search.

### 5.2 External formatters

A Dev Pack's `formatter.command` (`{{file}}` is the guest path) is parsed and **not executed**. Only
the built-in `CodeFormatter` runs.

### 5.3 Cross-architecture emulation

`Arch`, `needsQemu`, `qemuBinaryFor`, `extractQemu` and proot's `--qemu=` argument all exist, but no
QEMU user-mode binary is bundled, so the branch is inert. Two open items are recorded in the source:
the exact flag spelling must be confirmed on-device via `proot --help`, and — because proot
`execve`s the `--qemu` binary on the **host** — a shipped emulator must be a `jniLib` in
`nativeLibraryDir`, not an asset in `filesDir`.

### 5.4 Debug adapters

Device-verified findings:

| Adapter | State |
|---|---|
| `debugpy` (Python) | Fully working |
| `java-debug` | Works for Android attach through `tools/java-dap` |
| `js-debug` | A proot **loopback transport** problem, not a protocol defect — the child session cannot reach `__jsDebugChildServer` |
| `netcoredbg` | Attach stalls |

---

## 6. Latent defects

Real bugs found while writing these specifications. They are not hypothetical.

### 6.1 `MemoryPressure.LOW` is unreachable

`fromTrimLevel` tests `level < 40 → BACKGROUND`, then `level < 60 → MODERATE`, then
`level < 10 → LOW`. Any level below 10 already matched the first branch, so `LOW` is never returned,
and levels ≥ 60 fall through to `CRITICAL`. Android's real trim constants are not monotonic with
severity (`RUNNING_LOW = 10` is numerically below `BACKGROUND = 40`), which is what makes this easy
to get wrong.

### 6.2 Rootfs downloads are unverified on the fallback path

The built-in default manifest entries carry `sha256 = ""`, so integrity verification engages only
when a served manifest supplies a hash.

### 6.3 `TsParseService` document keying

Per-document state is keyed on `editorState.hashCode().toString()`, which is not a guaranteed-unique
identity. (Moot while tree-sitter is unwired.)

---

## 7. Declared but unused

| Declaration | Where | Reality |
|---|---|---|
| `kotlinx-serialization-json` | `core/debug/build.gradle.kts` | Never used; parsing is `org.json` |
| BouncyCastle | `core/vcs/build.gradle.kts` | The module is a stub |
| Room, Hilt, DataStore | `core/ctags`, `core/state`, `core/ext` | Those modules are stubs |
| `Layer.MINIMAP` | `core/editor/src/main/java/dev/jcode/core/editor/decor/Decoration.kt` | There is no minimap |
| `EffectiveEditorConfig.minimap = true` | `core/config` | Same |
| `supportedDistros` | `SdkCatalogEntry` | Supported by the model; no catalog entry uses it |
| `ThemeBundle.fontFamily` | `core/design` | No built-in bundle sets it |
| `Buffer.nativeOpenFromFd` | `core/buffer` | JNI export exists; no caller |
| `JCodePosture` | `core/adaptive` | Computed; no layout branches on `TableTop` or `Book` |
| `EditorGroupManager` splits | `:feature:editor-pane` | Model supports splits; no split-pane UI |
| Multi-caret | `EditorState.carets` is a list | The input layer creates one caret |

---

## 8. Documentation drift

| Artifact | Drift |
|---|---|
| `AGENTS.md` | Says the SDK catalog has **14** entries across 6 categories; it has **22** across 8. Points at `plans/JCode_Plan 2.md` and `plans/JCode_Verification 2.md`, which do not exist here (`plans/` is gitignored) |
| `VtParser.drainOsc` KDoc | Documents the OSC 7715 payload as `open;<token>;<b64label>;<b64cwd>;<b64user>`; the live format is `open;<token>;<label>` |
| `LspSession` KDoc | Says servers run "via `proot-distro login`"; JCode spawns proot directly and has no `proot-distro` dependency |
| `ColoredSpan` KDoc | Credits "tree-sitter's `HighlightSpanProducer`" as its producer; that producer is a stub |
| `EffectiveDistroConfig.id` | Defaults to `"ubuntu"`, which is not a real distro id (`ubuntu-24.04` / `ubuntu-26.04` are) |
| `minsdk-33-targetsdk-28` (memory note filename) | The targetSdk-28 pin is long gone; both are 33 |

---

## 9. Structural observations

- `:app` is very top-heavy: `JCodeShell.kt` (4,999 lines) and `MainViewModel.kt` (4,844) hold work
  the module layering would place in `:feature:*`.
- `DistroService.kt` (2,388 lines) mixes orchestration, catalog execution, apt self-heal and user
  management.
- Native wrappers (`Buffer`, `VtParser`, `TsParser`, `PtyProcess`) each roll their own `Cleaner`
  rather than extending `:core:resource`'s `NativeHandle`, and nothing registers with
  `ResourceManager` — so memory-pressure trimming has nothing to trim.
- Two distinct `DiagnosticSeverity` types (`core.lsp` and `core.editor.decor`) are converted at the
  boundary.
- The only CI workflow is the no-host-root guard; `detekt` is a placeholder task.
- Test coverage is narrow: the buffer and the highlighter have differential tests; the terminal, LSP,
  DAP, config merge and extension installer have none.
- A few source lines in `JCodeShell.kt`, `ExplorerView.kt` and `SettingsFeature.kt` contain literal
  control bytes where `//` was intended, which makes tooling treat those files as binary.

---

## 10. References

Every entry above is also stated in its subsystem specification:

- [Module map](../01-architecture/02-module-map.md)
- [Native layer and JNI](../01-architecture/04-native-layer-and-jni.md)
- [Concurrency and resource lifecycle](../01-architecture/03-concurrency-and-resource-lifecycle.md)
- [Syntax highlighting and completion](../02-editor/05-syntax-highlighting-and-completion.md)
- [LSP client](../04-language-services/01-lsp-client.md)
- [Debug Adapter Protocol](../04-language-services/02-debug-adapter-protocol.md)
- [Search and source control](../04-language-services/03-search-and-source-control.md)
- [Embedded Linux runtime](../03-runtime/03-embedded-linux-runtime.md)
- [CI, quality and invariants](03-ci-quality-and-invariants.md)
