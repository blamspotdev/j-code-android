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

## 2. Built but unwired — removed at 1.6.2

Three complete-looking native stacks shipped in every APK with no reachable caller. All are gone;
git history holds them if any is ever wanted back.

| Removed | Was shipping | Why it could not run |
|---|---|---|
| `:native:libgit2` | **1.3 MB/ABI** — libgit2 + libssh2 + mbedTLS, statically linked | The CMake target was `jcode_add_stub_library`: **zero JNI functions**. Using it meant writing the entire JNI surface first |
| `:native:core` | **629 KB/ABI** — a C++ `EditorState`, `UndoManager` and `ConfigService` with yaml-cpp | `core/editor` and `core/config` declare **no `external fun` at all**, and `CoreNativeModule` itself had no callers. All 35 symbols unreachable |
| `:core:treesitter` + `:native:tree-sitter` + `:native:grammars` | 111 KB/ABI | Nothing referenced the binding, `HighlightSpanProducer.produce()` was a stub, and — the detail that settles it — `native/grammars` had **no `externalNativeBuild` block**, so its CMakeLists never ran and the 14 grammars were never built or shipped. The binding had nothing to bind to |

That last row corrects a claim this document used to make: the grammars did **not** ship in the APK.

Syntax colouring always came from `core/buffer/.../NativeHighlighter.kt` → `native/buffer/src/highlight.cpp`,
a hand-written tokenizer with a Kotlin twin, and still does. See
[Syntax highlighting and completion](../02-editor/05-syntax-highlighting-and-completion.md).

Removing these took the debug APK from 90.2 MB to 86.1 MB and the module count from 36 to 31.

---

## 3. Stub modules — removed at 1.6.2

Nine modules were declared in `settings.gradle.kts` and depended on by `:app` while containing
nothing but a marker object. They are gone; `:app` no longer depends on anything empty, and the
module count went from 45 to 36. What each one *claimed* to be, and where that job is really done:

| Removed module | Marker claimed | Where the work actually happens |
|---|---|---|
| `:core:vcs` | libgit2 JNI + porcelain API | SCM is a WebView-hosted extension |
| `:core:ctags` | universal-ctags driver + index DB | No symbol index exists |
| `:core:state` | Last-session restore, recents | `app/src/main/java/dev/blamspot/jcode/SessionStore.kt` |
| `:core:ext` | WASM host, extension registry | Extensions run in a WebView or a Node process |
| `:core:editor-decor` | The decoration framework | The types live in `:core:editor`'s `decor` package |
| `:feature:terminal-pane` | The terminal pane | `TerminalSessionHost.kt`, `workbench/WorkbenchExtraKeys.kt` |
| `:feature:scm` | Source control UI | `workbench/ScmExtensionHost.kt` |
| `:feature:problems` | The problems pane | `IssuesPanel.kt` |
| `:feature:search` | Search UI | `workbench/SearchToolPanel.kt` |

Two module boundaries that look similar are **not** stubs and stay: `:native:proot` carries the
proot binary, its ELF loaders and the mmap-only support libraries, and `:native:grammars` is a
CMake target. Neither has Kotlin sources and neither needs any.

`:feature:debug` keeps `DebugEngineManagerFeature`; only its two marker objects went. `:feature:marketplace`
inverts the usual split — it holds logic and no UI, and `:app` supplies the screens.

Still stubs, deliberately, because each ships an `.so` the smoke test loads: `:native:editor-render`
(a stub `.so`, no sources) and `:native:wasmtime-ffi` (a four-line Rust crate returning a version).

---

## 5. Partially wired features

### 5.1 Editor ↔ language server

Wired, via `dev.blamspot.jcode.lsp.LspController` — diagnostics, completions, Go to Definition, Find
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

### 6.1 `MemoryPressure.LOW` was unreachable — **fixed at 1.6.2**

`fromTrimLevel` tested `level < 40 -> BACKGROUND` before `level < 10 -> LOW`, so `LOW` could never
be returned. Worse than the dead branch: all three `RUNNING_*` levels fall under 40, so
`TRIM_MEMORY_RUNNING_CRITICAL` — the most urgent signal Android sends, when the app is closest to
being killed — trimmed 30% instead of 90%.

The trap is that the constants are **two families and are not ordered by severity**: `RUNNING_*` is
5/10/15 (app foreground, system starving) and `UI_HIDDEN`/`BACKGROUND`/`MODERATE`/`COMPLETE` is
20/40/60/80 (app backgrounded, moving down the LRU). The mapping now matches each constant exactly
and never by range, and `MemoryPressureTest` pins every level.

This was live, not theoretical: `ExtensionIcon` registers its icon cache with `ResourceManager`, so
the wrong ratio really was applied. (An earlier revision of §9 claimed nothing registers — that was
already stale.)

### 6.2 Rootfs downloads are unverified on the fallback path — **surfaced at 1.6.2**

The served manifest supplies checksums; the built-in fallback entries carry `sha256 = ""`, and
verification simply did not engage. A rootfs is extracted and its binaries executed under proot, and
one of the two fallback URLs is a **third-party repository's `master` branch**, so this mattered.

Pinning hashes here was considered and rejected: both fallback URLs are moving targets somebody else
controls, so a pinned hash would go stale on their schedule and break first-run setup for everyone
with a mismatch. Instead the outcome now travels with the result — `DownloadProgress.Completed`
carries `verified` and the hash actually computed, and `RootfsManager` writes either
"checksum verified" or "downloaded WITHOUT a checksum" into the setup log the user is already
watching. A mismatch against a checksum that *is* present still deletes the file and fails.

**The real fix remains to keep the served manifest reachable and populated**, which is the only
place a checksum can be updated in step with what it points at.

---

## 7. Declared but unused

Cleared at 1.6.2: the unused `kotlinx-serialization-json` dependency, `ThemeBundle.fontFamily`, and
`Layer.MINIMAP` together with the whole minimap setting — a Settings toggle described as "useful on
tablets and desktop windows" that was wired through config to nothing, because no minimap renderer
has ever existed. `Buffer.nativeOpenFromFd` was already gone. What is left:

| Declaration | Where | Reality |
|---|---|---|
| `supportedDistros` | `SdkCatalogEntry` | Supported by the model; no catalog entry uses it |
| `JCodePosture` | `core/adaptive` | Computed; no layout branches on `TableTop` or `Book` |
| Multi-caret | `EditorState.carets` is a list | The input layer creates one caret |

---

## 8. Documentation drift

| Artifact | Drift |
|---|---|
| `AGENTS.md` | Says the SDK catalog has **14** entries across 6 categories; it has **22** across 8. Points at `plans/JCode_Plan 2.md` and `plans/JCode_Verification 2.md`, which do not exist here (`plans/` is gitignored) |
| `VtParser.drainOsc` KDoc | Documents the OSC 7715 payload as `open;<token>;<b64label>;<b64cwd>;<b64user>`; the live format is `open;<token>;<label>` |
| `LspSession` KDoc | Says servers run "via `proot-distro login`"; JCode spawns proot directly and has no `proot-distro` dependency |
| `EffectiveDistroConfig.id` | Defaults to `"ubuntu"`, which is not a real distro id (`ubuntu-24.04` / `ubuntu-26.04` are) |

---

## 9. Structural observations

- `:app` is very top-heavy: `JCodeShell.kt` (4,999 lines) and `MainViewModel.kt` (4,844) hold work
  the module layering would place in `:feature:*`.
- `DistroService.kt` (2,388 lines) mixes orchestration, catalog execution, apt self-heal and user
  management.
- Native wrappers (`Buffer`, `VtParser`, `PtyProcess`) each roll their own `Cleaner`. `NativeHandle`,
  the base class they might have shared, was removed at 1.6.2 — it had no users and released twice.
  `ExtensionIcon` does register its cache with `ResourceManager`, so trimming is live (see §6.1).
- Two distinct `DiagnosticSeverity` types (`core.lsp` and `core.editor.decor`) are converted at the
  boundary.
- The only CI workflow is the no-host-root guard; `detekt` is a placeholder task.
- Test coverage is narrow: the buffer and the highlighter have differential tests; the terminal, LSP,
  DAP, config merge and extension installer have none.
- Literal control bytes in source are gone as of 1.6.2. The last three were real VT sequences in
  `TerminalView.kt`, now written `[…` — they worked either way, but a raw ESC is invisible in
  every editor and diff that will ever show the line.

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
