<p align="center">
  <img src="media/jcode-app-icon.svg" width="96" alt="JCode icon" />
</p>

<h1 align="center">JCode</h1>

<p align="center">
  A native Android IDE with an embedded Linux runtime — build, run, and edit real
  projects entirely on-device.
</p>

---

JCode is an Android IDE written in **Kotlin + Jetpack Compose**, with **C++/NDK**
for the performance-critical subsystems (editor buffer, VT terminal, parsers). It
embeds a self-contained **Linux environment** via bundled `proot` — runtimes,
SDKs, compilers, and language servers live in an app-managed Ubuntu 24.04 / 26.04
LTS rootfs in app-private storage. No Termux dependency, no root.

## Features

- **Editor** — an in-house `Canvas` + IME code editor: tree-sitter + Dev-Pack
  syntax highlighting, as-you-type completions + snippet helpers, a built-in
  **Format Document**, multi-tab editing, text-selection handles, word wrap, Save
  (`Ctrl+S` / button / dirty indicator), and auto-reload of unmodified files
  changed on disk.
- **Terminals** — real PTY sessions through `proot` that survive app backgrounding,
  with full xterm/VT support (mouse reporting, SGR, alt-screen), in-place progress
  redraw, and a `code`/`jcode` command to open files in the editor.
- **Build & Run** — per-project `.jcode/run.yaml`; multi-terminal dev setups (e.g.
  an ASP.NET Core API + a Vite dev server side by side); a read-only **Output** log
  teed from the run terminals.
- **Debugging** — a Debug Adapter Protocol (DAP) client with gutter breakpoints,
  stepping, call stack, variables, and a debug console. Python (debugpy) and Java
  are device-verified under `proot`.
- **Source Control** — a Git panel (status, stage, commit, branch, diffs) in the
  left drawer, plus live VCS status decorations in the Explorer.
- **Search** — project-wide find (ripgrep-backed) with Content / File-name /
  current-document scopes.
- **Problems** — an Issues panel and status-bar count fed by a shared diagnostics
  bus (config-file errors, on-save syntax checks), with in-gutter squiggles.
- **Extensions & Toolchains** — a marketplace of cryptographically-verified `.jext`
  packages (Dev Packs, project templates), plus a unified **Toolchains** manager for
  installing SDKs, language servers, and debug engines per distro.
- **Embedded Linux** — bundled `proot` (arm64-v8a / x86_64) + a downloaded minimal
  rootfs (Ubuntu 24.04 / 26.04 LTS); `apt`-managed toolchains; project dirs
  bind-mounted into the distro.

## Status

Active development. The app builds clean and the major features are device-verified
on arm64 (AYN Odin2). Honest gaps, not yet wired:

- **Editor ↔ language-server integration.** The LSP client exists (`core/lsp`
  speaks hover / definition / references / rename), but the editor's semantic
  actions (Go to Definition / Find References / Rename Symbol) still surface a
  "needs a language server (coming soon)" notice, and LSP diagnostics aren't yet
  fed to the Issues panel.
- **External formatters.** The built-in **Format Document** works; a Dev Pack's
  external `formatter.command` is parsed but not yet executed.

## Build

Requirements: **JDK 21**, the Android SDK (`compileSdk 36`), **NDK r27c**
(`27.2.12479018`), and CMake 3.28+. `minSdk`/`targetSdk` 33 (Android 13+). AGP 8.13.x / Gradle 8.14.x.

```bash
git clone https://github.com/blamspotdev/j-code-android.git
cd j-code-android
./gradlew :app:assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk
```

> **Windows note:** build from a short path (e.g. `X:\jc`). Deep checkout paths can
> exceed the Win32 `MAX_PATH` limit during the native (tree-sitter) build.

## Architecture

A multi-module Gradle project:

```
:app            integration layer + the JCode shell
:core:*         editor, buffer, term, distro, lsp, vcs, debug, search, config,
                design, fs, treesitter, ctags, resource, state, …
:feature:*      explorer, editor-pane, terminal-pane, scm, debug, problems, search,
                settings, sdk-manager, lsp-manager, marketplace, onboarding
:native:*       proot, vt (terminal), pty, tree-sitter, buffer (piece tree),
                libgit2, ripgrep-ffi, wasmtime-ffi, editor-render, …
```

Module rule: **`:core:*` never depends on `:feature:*`; `:feature:*` depends only on
`:core:*`; `:app` depends on everything.** All buffer writes flow through
`EditorState.applyEdit` on a single-threaded dispatcher; JNI/native handles are
wrapped as `AutoCloseable` + `Cleaner`.

## Extensions

Dev Packs, templates, and (later) theme / icon sets install from the
[**JCode marketplace**](https://github.com/blamspotdev/j-code-marketplace) as
verified `.jext` packages. To build your own, see the marketplace's
[`CREATING-EXTENSIONS.md`](https://github.com/blamspotdev/j-code-marketplace/blob/main/CREATING-EXTENSIONS.md)
and the [`j-code-make-tools`](https://github.com/blamspotdev/j-code-make-tools) CLI.

## License

Licensed under the [MIT License](LICENSE). © 2026 blamspotdev (Janrick Samorin).
You're free to use, modify, and redistribute it under the MIT terms. Contributions
are covered by [`CONTRIBUTING.md`](CONTRIBUTING.md); third-party components and their
licenses are listed in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).
