<p align="center">
  <img src="media/jcode-app-icon.svg" width="96" alt="J Code icon" />
</p>

<h1 align="center">J Code</h1>

<p align="center">
  A native Android IDE with an embedded Linux runtime — build, run, and edit real
  projects entirely on-device.
</p>

---

J Code is an Android IDE written in **Kotlin + Jetpack Compose**, with **C++/NDK**
for the performance-critical subsystems (editor buffer, VT terminal, parsers). It
embeds a self-contained **Linux environment** via bundled `proot` — runtimes,
SDKs, compilers, and language servers live in an app-managed Ubuntu 24.04 / Debian
12 rootfs in app-private storage. No Termux dependency, no root.

## Features

- **Editor** — an in-house `Canvas` + IME code editor: language-pack-driven syntax
  highlighting, as-you-type completions + snippet helpers, a built-in **Format
  Document**, multi-tab editing, Save (`Ctrl+S` / button / dirty indicator), and
  auto-reload of unmodified files changed on disk.
- **Terminals** — real PTY sessions through `proot` that survive app backgrounding,
  with in-place progress redraw and a `code`/`jcode` command to open files in the
  editor.
- **Build & Run** — per-project `.jcode/run.yaml`; multi-terminal dev setups (e.g.
  an ASP.NET Core API + a Vite dev server side by side); a read-only **Output** log
  teed from the run terminals.
- **Extensions** — a marketplace of cryptographically-verified `.jext` packages
  (language packs, project templates), plus an **SDK Manager** and **LSP Manager**
  for installing toolchains and language servers per distro.
- **Embedded Linux** — bundled `proot` (arm64-v8a / x86_64) + a downloaded minimal
  rootfs; `apt`-managed toolchains; project dirs bind-mounted into the distro.

## Status

Active development. The app builds clean and the major features are device-verified
on arm64 (AYN Odin2). Honest gaps, not yet wired:

- editor ↔ language-server integration (Go to Definition / Find References / Rename
  surface a "coming soon" notice; the LSP client exists in `core/lsp`),
- the external `formatter.command` (the built-in formatter works; the external one
  is parsed but not yet executed),
- the **Problems** and **Debug Console** panels (placeholders).

## Build

Requirements: **JDK 21**, the Android SDK (`compileSdk 36`), **NDK r27c**
(`27.2.12479018`), and CMake 3.28+. `minSdk 28`. AGP 8.7.x / Gradle 8.11.x.

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
:core:*         editor, buffer, term, distro, lsp, design, config, fs, resource, …
:feature:*      editor-pane, marketplace, sdk-manager, lsp-manager, settings, …
:native:*       proot, vt (terminal), tree-sitter, buffer (piece tree), …
```

Module rule: **`:core:*` never depends on `:feature:*`; `:feature:*` depends only on
`:core:*`; `:app` depends on everything.** All buffer writes flow through
`EditorState.applyEdit` on a single-threaded dispatcher; JNI/native handles are
wrapped as `AutoCloseable` + `Cleaner`.

## Extensions

Language packs, templates, and (later) theme / icon sets install from the
[**JCode marketplace**](https://github.com/blamspotdev/j-code-marketplace) as
verified `.jext` packages. To build your own, see the marketplace's
[`CREATING-EXTENSIONS.md`](https://github.com/blamspotdev/j-code-marketplace/blob/main/CREATING-EXTENSIONS.md)
and the [`j-code-make-tools`](https://github.com/blamspotdev/j-code-make-tools) CLI.

## License

Licensed under the [MIT License](LICENSE). © 2026 blamspotdev (Janrick Samorin).
You're free to use, modify, and redistribute it under the MIT terms. Contributions
are covered by [`CONTRIBUTING.md`](CONTRIBUTING.md); third-party components and their
licenses are listed in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).
