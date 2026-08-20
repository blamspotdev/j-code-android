# Module map

| | |
|---|---|
| **Status** | Implemented |
| **Modules** | All 43 |
| **Primary sources** | settings.gradle.kts, app/build.gradle.kts, `*/*/build.gradle.kts` |
| **Verified against** | commit `cea581c`, 2026-08-09 |

---

## 1. Purpose and scope

An index of every Gradle module: what it is for, what it depends on, and whether it actually
contains an implementation. Several modules are declared and wired but hold only a marker object;
those are called out rather than described as if they worked.

---

## 2. The dependency rule

```
:core:*     never depends on :feature:*
:feature:*  depends only on :core:*
:app        depends on everything
:native:*   depends on nothing (leaf modules producing .so files)
```

Verified by the extracted dependency table below: no `:core:*` module lists a `:feature:*`
project dependency, and no `:feature:*` module lists another `:feature:*`.

The one structural exception is `:core:distro → :core:config`, which is legal under the rule but
worth knowing: the distro layer reads project configuration to resolve bind mounts.

---

## 3. `:app`

| | |
|---|---|
| Path | `app/` |
| Namespace | `dev.blamspot.jcode` |
| Depends on | All 19 `:core:*`, all 12 `:feature:*`, all 11 `:native:*` |

The integration layer. It also *contains* a large amount of feature implementation that the module
layering would place elsewhere — see §6.

Largest files: `JCodeShell.kt` (4,999 lines), `MainViewModel.kt` (4,844).

---

## 4. `:core:*` — 14 modules

| Module | Depends on | Status | Responsibility |
|---|---|---|---|
| `:core:adaptive` | — | Implemented | `WindowInfo` window-size/orientation classes |
| `:core:buffer` | `:native:buffer` | Implemented | Text buffer, piece table, snapshots, native tokenizer facade |
| `:core:config` | — | Implemented | Workspace/project YAML, merge to effective config, `run.yaml` |
| `:core:diag` | — | Implemented | Opt-in `DiagnosticLog`: redacted rotating file, own-process logcat tee, crash capture |
| `:core:debug` | `:core:term`, `:core:distro` | Implemented | DAP client |
| `:core:design` | `:core:diag` | Implemented | Design system, theme/icon bundles, settings widgets, command registry |
| `:core:distro` | `:core:config` | Implemented | proot, rootfs, toolchain catalog, ADB stack. Largest core module |
| `:core:editor` | `:core:buffer`, `:core:resource` | Implemented | `EditorState`, `EditorView`, `Renderer`, `UndoManager`, `WrapMap`, decorations |
| `:core:editor-completion` | `:core:editor` | Implemented | Completion model, snippet engine, completion popup |
| `:core:fs` | `:core:design` | Implemented | `Fs` abstraction (POSIX + SAF), workspace/project Room DB, file operations |
| `:core:lsp` | `:core:term`, `:core:distro` | Implemented | LSP client, diagnostics bus |
| `:core:resource` | — | Implemented | Memory-pressure registry, managed caches, pools, `NativeHandle` base |
| `:core:search` | — | Implemented | Search engine over ripgrep FFI with a Kotlin fallback |
| `:core:term` | `:core:distro`, `:core:diag` | Implemented | PTY, VT parser, session manager, terminal view |
| `:core:treesitter` | `:core:editor`, `:core:buffer` | **Built but unwired** | Complete tree-sitter binding; no grammar is ever loaded |

`:core:search` declares Gradle task dependencies on `:native:ripgrep-ffi:cargoBuild*JniLibs` so the
Rust library is built before it compiles.

---

## 5. `:feature:*` — 8 modules

| Module | Depends on | Status | Entry point |
|---|---|---|---|
| `:feature:debug` | `:core:debug`, `:core:design`, `:core:distro` | **Partially stub** | `DebugFeature` is a marker; `DebugEngineManagerFeature.kt` is real |
| `:feature:editor-pane` | `:core:editor`, `:core:editor-completion`, `:core:buffer`, `:core:design`, `:core:config` | Implemented | `EditorPane`, `EditorTab`, `EditorGroup` |
| `:feature:explorer` | `:core:fs`, `:core:design` | Implemented | `ExplorerView`, `TreeViewModel`, `ExplorerScmUi` |
| `:feature:lsp-manager` | `:core:distro`, `:core:design` | Implemented | Per-language-server detail page |
| `:feature:marketplace` | `:core:distro` | Implemented (logic only) | `ExtensionInstaller`, `JextCrypto`, `VsixPackage`, `TemplateScaffolder`. No UI — `:app` renders it |
| `:feature:onboarding` | `:core:distro`, `:core:design` | Implemented | `OnboardingFeature.StepperScreen` |
| `:feature:sdk-manager` | `:core:distro`, `:core:design` | Implemented | Per-SDK detail page |
| `:feature:settings` | `:core:config`, `:core:design`, `:core:distro` | Implemented | `SettingsFeature.Content` (1,949 lines) |

---

## 6. Where the feature code actually lives

Four such `:feature:*` stubs were removed at 1.6.2 (`terminal-pane`, `scm`, `problems`, `search`) —
see [Known gaps](../09-platform/05-known-gaps-and-unwired-code.md) for where each one's work is done.
One split of this shape remains, because the module still holds real code alongside its UI half:

| Stub module | Real implementation |
|---|---|
| `:feature:debug` (UI half) | `app/src/main/java/dev/blamspot/jcode/DebugSessionPanel.kt`, `app/src/main/java/dev/blamspot/jcode/RunDebugPanel.kt` |

`:feature:marketplace` inverts this: it holds the logic and no UI, and `:app` supplies the
screens (`app/src/main/java/dev/blamspot/jcode/workbench/marketplace/ExtensionsPanel.kt`).

---

## 7. `:native:*` — 11 modules

All native modules are Android library modules whose only job is to produce a `.so`. Eight of them
share one CMake superbuild selected by `-DJCODE_NATIVE_MODULE`; the wiring is injected centrally by
the root `build.gradle.kts`. See [Native layer and JNI](04-native-layer-and-jni.md) for the full
mechanism.

| Module | Output `.so` | Build path | Status |
|---|---|---|---|
| `:native:buffer` | `libjcodebuffer.so` | Superbuild, real C++ | Implemented |
| `:native:core` | `libjcode_core.so` | Own `externalNativeBuild`, real C++ + yaml-cpp | **Built but unwired** (editor/undo/config halves) |
| `:native:editor-render` | `libjcodernd.so` | Superbuild stub | **Stub** — no sources exist |
| `:native:grammars` | 14 grammar `.so` files | Own CMakeLists, real | **Built but unwired** — never `dlopen`'d |
| `:native:libgit2` | `libgit2_ffi.so` | Superbuild stub linking real libgit2/libssh2/mbedTLS | **Built but unwired** — no JNI surface |
| `:native:proot` | prebuilt `libproot*.so` | None (prebuilt jniLibs + assets) | Implemented |
| `:native:pty` | `libpty.so` | Superbuild, real C++ | Implemented |
| `:native:ripgrep-ffi` | `libripgrep_ffi.so` | Cargo (real) or superbuild stub | Implemented |
| `:native:tree-sitter` | `libtreesitter.so` | Superbuild + upstream tree-sitter | Implemented (binding); unused |
| `:native:vt` | `libjcode_vt.so` | Superbuild, real C | Implemented |
| `:native:wasmtime-ffi` | `libjcode_wasm.so` | Cargo (4-line stub) or superbuild stub | **Stub** |

---

## 8. Invariants and constraints

- **Never grow a catch-all file.** New work belongs in the module that owns the responsibility
  (`AGENTS.md`, "Locked project decisions").
- **No third-party editor or terminal framework.** The editor and terminal are in-house by decision;
  do not add sora-editor or an off-the-shelf terminal widget.
- **Toolchains never ship in the APK.** Compilers, JDKs, LSPs and debug adapters install into the
  guest rootfs at runtime.
- **A module boundary is not evidence of implementation.** Nine marker-only modules were removed at
  1.6.2; before that, `:app` depended on several modules containing a single empty object. If a
  module has no sources, it does no work — check before assuming a boundary means a subsystem.

---

## 9. Known gaps

- Stub modules: `:feature:debug` (UI half), `:native:editor-render`, `:native:wasmtime-ffi`. The
  nine marker-only `:core:*`/`:feature:*` stubs were removed at 1.6.2 (45 modules down to 36).
- Built-but-unwired: `:core:treesitter`, `:native:grammars`, `:native:libgit2`, the editor/undo/config
  halves of `:native:core`.
- Full inventory with reasons: [Known gaps and unwired code](../09-platform/05-known-gaps-and-unwired-code.md).

---

## 10. References

- [System architecture](01-system-architecture.md)
- [Native layer and JNI](04-native-layer-and-jni.md)
- [Build variants and release](../09-platform/02-build-variants-and-release.md)
