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

- **Editor** — an in-house `Canvas` + IME code editor with a native piece-tree
  buffer, syntax colouring, LSP + Dev Pack completions, snippets, multi-tab
  editing, selection handles, word wrap, Save (`Ctrl+S` / button / dirty
  indicator), and auto-reload of unmodified files changed on disk.
- **Language intelligence** — language servers run inside the selected distro and
  provide diagnostics, completions, Go to Definition, Find References, Rename
  Symbol, and document formatting. Missing servers can be installed from the
  editor; their lifecycle follows the open project and documents.
- **Terminals** — real PTY sessions through `proot` that survive app backgrounding,
  with full xterm/VT support (mouse reporting, SGR, alt-screen), in-place progress
  redraw, and a `code`/`jcode` command to open files in the editor.
- **Build & Run** — per-project `.jcode/run.yaml`; multi-terminal dev setups (e.g.
  an ASP.NET Core API + a Vite dev server side by side); a read-only **Output** log
  teed from the run terminals; and ready-port polling into a web preview.
- **Debugging** — a Debug Adapter Protocol (DAP) client with gutter breakpoints,
  stepping, call stack, variables, and a debug console. Python (debugpy) and Java
  are device-verified under `proot`.
- **Android app sandbox** — build an APK and run it inside an editor tab, with a
  device-side ADB bridge and JDWP debugging against the same device.
- **Source Control** — an extension-hosted Git panel (status, stage, commit,
  branch, diffs) in the left drawer, plus live VCS status decorations in the
  Explorer. Git itself runs inside the distro.
- **Search** — project-wide find (ripgrep-backed) with Content / File-name /
  Current-document scopes and a Kotlin fallback when the native search library is
  unavailable.
- **Problems** — an Issues panel and status-bar count fed by a shared diagnostics
  bus (language servers, config-file errors, and syntax checks), with in-gutter
  squiggles.
- **Extensions** — a marketplace of Ed25519-verified `.jext` packages (Dev Packs,
  project templates, and manager UIs), plus `.vsix` import for the supported
  WebView slice of the VS Code API.
- **Toolchains** — a unified manager for installing and updating SDKs, language
  servers, debug engines, database clients, and developer tools per distro.
- **Embedded Linux** — bundled arm64-v8a `proot` binaries with downloaded ARM64
  Ubuntu 24.04 / 26.04 LTS profiles; `apt`-managed toolchains; project
  directories bind-mounted into the distro.

## Build

Requirements: **JDK 21**, the Android SDK (`compileSdk 36`), **NDK r27c**
(`27.2.12479018`), and **CMake 3.28.3**. `minSdk`/`targetSdk` 33
(Android 13+). The repository currently uses AGP 8.13.0, Kotlin 2.2.20, and the
Gradle 8.14.3 wrapper.

```bash
git clone https://github.com/blamspotdev/j-code-android.git
cd j-code-android
./gradlew :app:assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk
```

> **Windows note:** build from a short path (e.g. `X:\jc`). Deep checkout paths can
> exceed the Win32 `MAX_PATH` limit during the native (tree-sitter) build.

The first native build needs network access because CMake fetches pinned upstream
dependencies. Release builds and signing are documented in the
[build and release specification](docs/specifications/09-platform/02-build-variants-and-release.md).

## Architecture

A multi-module Gradle project:

```
:app            integration layer + the JCode shell
:core:*         editor, buffer, term, distro, lsp, debug, search, config, diagnostics,
                design, fs, treesitter, resource, and compatibility stubs
:feature:*      explorer, editor-pane, terminal-pane, scm, debug, problems, search,
                settings, sdk-manager, lsp-manager, marketplace, onboarding
:native:*       proot, vt (terminal), pty, tree-sitter, buffer (piece tree),
                libgit2, ripgrep-ffi, wasmtime-ffi, editor-render, …
```

Module rule: **`:core:*` never depends on `:feature:*`; `:feature:*` depends only on
`:core:*`; `:app` depends on everything.** All buffer writes flow through
`EditorState.applyEdit` on a single-threaded dispatcher; JNI/native handles are
wrapped as `AutoCloseable` + `Cleaner`.

## Documentation

Full as-built engineering specifications live in
[`docs/specifications/`](docs/specifications/README.md) — architecture, module
contracts, wire protocols, on-disk formats, and build/release details. Each document
closes with a "Known gaps" section recording what is stubbed, unwired or limited in
that subsystem, so the caveat sits beside the thing it qualifies.

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
