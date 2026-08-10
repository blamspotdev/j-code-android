# JCode — Engineering Specifications

**As-built** specifications for JCode, a native Android IDE with an embedded Linux runtime.

| | |
|---|---|
| **Product version** | 1.4.5 (`app/build.gradle.kts`, `val jcodeVersion`) |
| **Verified against** | commit `cea581c`, 2026-08-09 |
| **Scope** | The whole system: 43 Gradle modules across `:app`, `:core:*`, `:feature:*`, `:native:*` |

---

## What these documents are

These specs describe **what the system actually is today**, derived from the source tree — not
what it was planned to be. Where an intended design was never wired up, the spec says so in
place rather than describing the intent as if it shipped. The complete list of such cases lives
in [Known gaps and unwired code](09-platform/05-known-gaps-and-unwired-code.md).

They are *not* a requirements document. There are no `SHALL` statements and no requirement IDs;
nothing here is a promise about future behavior.

These documents supersede the system description embedded in
[`AGENTS.md`](../../AGENTS.md), which has drifted in places. `AGENTS.md` remains authoritative
for *how to work in this repo* (conventions, locked decisions, verification commands).

---

## Reading order

**New to the codebase** — read in this order:

1. [Product overview](00-overview/01-product-overview.md)
2. [Glossary and conventions](00-overview/02-glossary-and-conventions.md)
3. [System architecture](01-architecture/01-system-architecture.md)
4. [Module map](01-architecture/02-module-map.md)
5. [Storage and path model](01-architecture/05-storage-and-path-model.md)
6. …then whichever subsystem you are touching.

**Looking for a specific on-disk format** — go straight to the
[File format index](09-platform/01-file-format-index.md).

**Auditing what is real** — [Known gaps and unwired code](09-platform/05-known-gaps-and-unwired-code.md).

---

## Contents

### 00 — Overview

| Document | Covers |
|---|---|
| [01 Product overview](00-overview/01-product-overview.md) | What JCode is, capability surface, non-goals |
| [02 Glossary and conventions](00-overview/02-glossary-and-conventions.md) | Distro, rootfs, guest, workspace, project, `.jext`, Dev Pack, and this document set's conventions |

### 01 — Architecture

| Document | Covers |
|---|---|
| [01 System architecture](01-architecture/01-system-architecture.md) | Layers, the three processes, Android platform constraints |
| [02 Module map](01-architecture/02-module-map.md) | All 43 Gradle modules, the dependency rule, stub inventory |
| [03 Concurrency and resource lifecycle](01-architecture/03-concurrency-and-resource-lifecycle.md) | Dispatchers, single-writer editor, `Cleaner`, `ResourceManager` |
| [04 Native layer and JNI](01-architecture/04-native-layer-and-jni.md) | CMake superbuild, Cargo path, `.so` ↔ class ↔ export table |
| [05 Storage and path model](01-architecture/05-storage-and-path-model.md) | On-device layout, host↔guest path translation, SAF boundary |

### 02 — Editor

| Document | Covers |
|---|---|
| [01 Text buffer](02-editor/01-text-buffer.md) | `Buffer`, `Snapshot`, piece table / native piece tree, edit transactions |
| [02 Editor state and undo](02-editor/02-editor-state-and-undo.md) | `EditorState` flows, carets, folds, viewport, `UndoManager` |
| [03 Rendering and decorations](02-editor/03-rendering-and-decorations.md) | `Renderer`, `WrapMap`, decoration layers, dirty tracking |
| [04 Input, IME and gestures](02-editor/04-input-ime-and-gestures.md) | `EditorView`, `InputConnection`, selection handles, column encodings |
| [05 Syntax highlighting and completion](02-editor/05-syntax-highlighting-and-completion.md) | The real highlight path, the unwired tree-sitter stack, completions and snippets |

### 03 — Runtime

| Document | Covers |
|---|---|
| [01 Terminal, PTY and VT](03-runtime/01-terminal-pty-and-vt.md) | `PtyProcess`, `VtParser`, session manager, terminal view |
| [02 Shell integration protocol](03-runtime/02-shell-integration-protocol.md) | OSC 7711–7716 and OSC 52 — exact payloads |
| [03 Embedded Linux runtime](03-runtime/03-embedded-linux-runtime.md) | proot invocation, rootfs lifecycle, synthetic `/proc` |
| [04 Toolchain catalog and onboarding](03-runtime/04-toolchain-catalog-and-onboarding.md) | `catalog.yaml` schema and entries, setup state machine |
| [05 ADB bridge](03-runtime/05-adb-bridge.md) | ADB transport frames, auth, daemon/relay/discovery |

### 04 — Language services

| Document | Covers |
|---|---|
| [01 LSP client](04-language-services/01-lsp-client.md) | Framing, handshake, server catalog, diagnostics bus |
| [02 Debug Adapter Protocol](04-language-services/02-debug-adapter-protocol.md) | DAP framing, handshake order, reverse requests, engine catalog |
| [03 Search and source control](04-language-services/03-search-and-source-control.md) | ripgrep FFI and fallback, search scopes, the SCM panel |

### 05 — Workspace and configuration

| Document | Covers |
|---|---|
| [01 Workspaces and projects](05-workspace/01-workspaces-and-projects.md) | Room schema, workspace operations, breadcrumbs, SAF vs local |
| [02 Configuration model](05-workspace/02-configuration-model.md) | Workspace/project YAML, merge to effective config, live reload |
| [03 Run and build configurations](05-workspace/03-run-and-build-configurations.md) | `run.yaml` v1/v2, the runner, ready-port preview, debug handoff |

### 06 — Workbench

| Document | Covers |
|---|---|
| [01 Shell layout and navigation](06-workbench/01-shell-layout-and-navigation.md) | Modal vs docked drawers, sizing, orientation, session restore |
| [02 Editor tabs and pages](06-workbench/02-editor-tabs-and-pages.md) | Tab model, page kinds, groups, pinning, close guards |
| [03 Panels and tools](06-workbench/03-panels-and-tools.md) | Left/right drawer tools, command palette, key bindings |
| [04 Settings reference](06-workbench/04-settings-reference.md) | Scope tabs, groups, every key and default |
| [05 Design system](06-workbench/05-design-system.md) | Theme bundles, icon bundles, tokens, contrast rules |

### 07 — Extensions

| Document | Covers |
|---|---|
| [01 Extension model and lifecycle](07-extensions/01-extension-model-and-lifecycle.md) | Types, activation, capabilities, install/update, dependencies |
| [02 `.jext` package format](07-extensions/02-jext-package-format.md) | Byte layout, signing, encryption, manifest fingerprint |
| [03 Manifest reference](07-extensions/03-manifest-reference.md) | Every `extension.yaml` key and contribution point |
| [04 Extension API and hosts](07-extensions/04-extension-api-and-hosts.md) | The WebView bridge, and the `.vsix` Node host protocol |
| [05 Templates and scaffolding](07-extensions/05-templates-and-scaffolding.md) | Template model, recipe steps, inputs, token substitution |

### 08 — Virtual device

| Document | Covers |
|---|---|
| [01 App sandbox architecture](08-virtual-device/01-app-sandbox-architecture.md) | The `:guest` process, embedded vs full-screen, AIDL surface |
| [02 Guest runtime and hidden API](08-virtual-device/02-guest-runtime-and-hidden-api.md) | Class loading, identity, hooks, hidden-API table, input injection |
| [03 Android app debugging](08-virtual-device/03-android-app-debugging.md) | Module detection, JDWP forwarding, attach flow |

### 09 — Platform

| Document | Covers |
|---|---|
| [01 File format index](09-platform/01-file-format-index.md) | Every on-disk artifact JCode reads or writes |
| [02 Build variants and release](09-platform/02-build-variants-and-release.md) | Toolchain versions, variants, version codes, signing |
| [03 CI, quality and invariants](09-platform/03-ci-quality-and-invariants.md) | The no-host-root scanner, code-quality rules, verification commands |
| [04 Security and privacy](09-platform/04-security-and-privacy.md) | Trust model, sandbox boundaries, permissions, log hygiene |
| [05 Known gaps and unwired code](09-platform/05-known-gaps-and-unwired-code.md) | Stubs, orphaned code, declared-but-unused dependencies |

---

## Document conventions

Every specification uses the same shape:

```markdown
# <Title>

| | |
|---|---|
| **Status** | Implemented / Partially implemented / Built but unwired / Stub |
| **Modules** | `:core:x`, `:native:y` |
| **Primary sources** | path/one.kt, path/two.cpp |
| **Verified against** | commit <sha>, <date> |

## 1. Purpose and scope
## 2. Architecture
## 3. Public contract
## 4. Data model
## 5. Behavior
## 6. Protocol / format        (only where one exists)
## 7. Threading and lifecycle
## 8. Invariants and constraints
## 9. Failure modes
## 10. Known gaps
## 11. References
```

Sections that do not apply to a subsystem are omitted rather than left empty; the numbering
stays stable so `## 6` always means "protocol or format" where it appears.

**Status values**

| Status | Meaning |
|---|---|
| Implemented | Present and reachable from normal app use |
| Partially implemented | Reachable, with named behavior missing |
| Built but unwired | Code compiles and works in isolation; nothing calls it |
| Stub | A marker type or empty module with no behavior |

**Rules these documents follow**

- Every factual claim is traceable to a repo-relative source path. Non-obvious claims cite
  `path:line`.
- Enum members, defaults, ports, flags and magic numbers are **copied** from source, never
  paraphrased or recalled.
- Cross-references between specs are relative Markdown links.
- Mermaid diagrams appear only where a picture beats prose — process topology, protocol
  handshakes, binary layouts, launch flows.
- Line numbers drift. Symbol names and file paths are the durable anchors; treat a cited line
  as a hint, not a contract.

---

## Maintaining these documents

- A change that alters a **protocol, on-disk format, module boundary, or public contract**
  should update the corresponding spec in the same change.
- After a substantial edit, re-run the checks in
  [CI, quality and invariants](09-platform/03-ci-quality-and-invariants.md#4-specification-checks):
  every source path cited by a spec must still exist, and every relative spec link must resolve.
- Update the **Verified against** row of any document you re-check against source.
