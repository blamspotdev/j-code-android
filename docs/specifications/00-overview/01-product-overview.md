# Product overview

| | |
|---|---|
| **Status** | Reference |
| **Modules** | All |
| **Primary sources** | README.md, AGENTS.md, app/build.gradle.kts, settings.gradle.kts |
| **Verified against** | commit `cea581c`, 2026-08-09 |

---

## 1. What JCode is

JCode is a **native Android IDE with an embedded Linux runtime**. It builds, runs, edits and debugs
real projects entirely on-device, with no companion app and no root.

| | |
|---|---|
| Version | 1.4.5 |
| Package | `dev.jcode` (`.debug` / `.beta` variants install side by side) |
| Platform | Android 13+ (`minSdk` / `targetSdk` 33, `compileSdk` 36) |
| Release ABI | `arm64-v8a` |
| Language | Kotlin + Jetpack Compose, with C, C++ and Rust for performance-critical subsystems |
| Licence | MIT |
| Distribution | Outside the Play Store |

The distinguishing claim is the second half of the sentence: the IDE is a real Android app, and the
toolchains it drives are a real Ubuntu userland running inside the same app's sandbox through a
bundled `proot`. Compilers, JDKs, language servers, debug adapters, `git` and `node` are installed
by `apt` into an app-managed rootfs — not bundled into the APK, and not borrowed from Termux.

---

## 2. Capability surface

| Area | What exists | Specification |
|---|---|---|
| **Editor** | In-house `Canvas` + IME code editor: piece-tree buffer, syntax colouring, as-you-type completions and snippets, built-in Format Document, multi-tab editing, selection handles, word wrap, save with dirty indicator, auto-reload of unmodified files changed on disk | [02-editor](../02-editor/01-text-buffer.md) |
| **Terminals** | Real PTY sessions through proot that survive backgrounding; full xterm/VT support including mouse reporting, SGR, truecolor and alt-screen; a `code`/`jcode` command that opens files in the editor; optional relocation of a nested sub-shell into its own tab | [03-runtime](../03-runtime/01-terminal-pty-and-vt.md) |
| **Build and run** | Per-project `.jcode/run.yaml`; multi-terminal dev setups running side by side; a read-only Output log teed from the run terminals; ready-port polling into a web preview | [05-workspace](../05-workspace/03-run-and-build-configurations.md) |
| **Debugging** | A DAP client with gutter breakpoints, stepping, call stack, variables and a debug console. Python (debugpy) and Java are device-verified under proot | [04-language-services](../04-language-services/02-debug-adapter-protocol.md) |
| **Source control** | A Git panel (status, stage, commit, branch, diffs) in the left drawer, plus live VCS decorations in the Explorer | [04-language-services](../04-language-services/03-search-and-source-control.md) |
| **Search** | Project-wide find (ripgrep-backed, gitignore-aware) with Content / File-name / Current-document scopes | [04-language-services](../04-language-services/03-search-and-source-control.md) |
| **Problems** | An Issues panel and status-bar count fed by a shared diagnostics bus, with in-gutter squiggles | [04-language-services](../04-language-services/01-lsp-client.md) |
| **Extensions** | A marketplace of Ed25519-verified `.jext` packages — Dev Packs, project templates, manager UIs — plus `.vsix` import for the webview slice of the VS Code API | [07-extensions](../07-extensions/01-extension-model-and-lifecycle.md) |
| **Toolchains** | A unified manager for SDKs, language servers and debug engines, installed per distro from a YAML catalog with real progress reporting | [03-runtime](../03-runtime/04-toolchain-catalog-and-onboarding.md) |
| **Embedded Linux** | Bundled proot, a downloaded minimal Ubuntu rootfs, `apt`-managed toolchains, project directories bind-mounted into the guest | [03-runtime](../03-runtime/03-embedded-linux-runtime.md) |
| **Android app sandbox** | Build an APK and run it **inside** JCode in an editor tab, with a device-side ADB daemon and JDWP debugging against the same device | [08-virtual-device](../08-virtual-device/01-app-sandbox-architecture.md) |

---

## 3. Design decisions that are locked

These are settled and should not be revisited casually:

| Decision | Rationale |
|---|---|
| **Android app only**; Kotlin + Compose + Material 3, `AndroidView` only where a custom `View` is required | Native feel and performance on mobile |
| **The editor is in-house** — custom `View` + `Canvas` renderer + custom `InputConnection` + C++ piece tree. **No sora-editor or any third-party editor framework** | Control over the input and rendering paths, which is where mobile editing goes wrong |
| **The terminal is in-house** — custom `View` + VT parser + PTY JNI. **No third-party terminal widget** | Same reason, plus the private shell-integration protocol |
| **No host root, ever** | User data safety; mechanically enforced in CI |
| **Toolchains live in the guest rootfs**, downloaded on first run | A 150 MB APK of compilers is not shippable, and `apt` already solves the problem |
| **YAML is the config format** | Human-editable on a phone |
| **JetBrains Mono for code**, system sans for UI | Legibility at small sizes with ligature support |
| **Module boundaries are strict**: `:core:*` never depends on `:feature:*` | Keeps subsystems reusable and testable |

---

## 4. Non-goals

- **Not a security sandbox for untrusted code.** proot is a convenience sandbox and the app-sandbox
  `:guest` process shares JCode's uid. See [Security and privacy](../09-platform/04-security-and-privacy.md).
- **Not a Termux replacement or client.** JCode has no Termux dependency and does not aim to be a
  general terminal environment.
- **Not a VS Code clone.** `.vsix` support covers the webview slice of the API; `languages`, `debug`,
  `tasks` and `scm` throw by name.
- **Not multi-architecture yet.** Release ships `arm64-v8a`; cross-architecture emulation exists in
  outline only.
- **Not a Play Store app.** Distribution is outside Play, which is why `targetSdk` can sit at 33.

---

## 5. Honest status

The app builds clean and the major features are device-verified on arm64 (AYN Odin2). The
significant gaps, stated up front:

- **Editor ↔ language-server integration is incomplete.** The LSP client works, but Go to Definition,
  Find References and Rename Symbol still show a "coming soon" notice, and LSP diagnostics do not
  reach the Issues panel.
- **External formatters are not executed.** The built-in Format Document works; a Dev Pack's
  `formatter.command` is parsed and ignored.
- **Tree-sitter is built but never loaded.** Colouring comes from a hand-written tokenizer.
- Several `:feature:*` modules are empty markers whose real UI lives in `:app`.

The complete list is [Known gaps and unwired code](../09-platform/05-known-gaps-and-unwired-code.md).

---

## 6. Shape of the codebase

```
:app            integration layer + the JCode shell
:core:*         19 modules — editor, buffer, term, distro, lsp, debug, vcs, search,
                config, design, fs, treesitter, ctags, resource, state, …
:feature:*      12 modules — explorer, editor-pane, terminal-pane, scm, debug, problems,
                search, settings, sdk-manager, lsp-manager, marketplace, onboarding
:native:*       11 modules — proot, vt, pty, tree-sitter, buffer, libgit2, ripgrep-ffi,
                wasmtime-ffi, editor-render, grammars, core
```

43 Gradle modules, roughly 485 tracked files. See [Module map](../01-architecture/02-module-map.md).

---

## 7. Related repositories

| Repository | Contents |
|---|---|
| [`j-code-android`](https://github.com/blamspotdev/j-code-android) | This repository — the app |
| [`j-code-marketplace`](https://github.com/blamspotdev/j-code-marketplace) | Extension index, published `.jext` packages, `CREATING-EXTENSIONS.md` |
| [`j-code-make-tools`](https://github.com/blamspotdev/j-code-make-tools) | The `jext` / `jsign` packaging CLI |

---

## 8. References

- [Glossary and conventions](02-glossary-and-conventions.md)
- [System architecture](../01-architecture/01-system-architecture.md)
- [Known gaps and unwired code](../09-platform/05-known-gaps-and-unwired-code.md)
- [`README.md`](../../../README.md)
- [`THIRD-PARTY-NOTICES.md`](../../../THIRD-PARTY-NOTICES.md)
