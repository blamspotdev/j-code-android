# Glossary and conventions

| | |
|---|---|
| **Status** | Reference |
| **Modules** | All |
| **Verified against** | commit `cea581c`, 2026-08-09 |

---

## 1. Purpose and scope

The vocabulary these specifications use, and the conventions they follow. Several terms are
overloaded across the industry and mean something specific here — `workspace`, `guest` and `sandbox`
in particular.

---

## 2. Glossary

### 2.1 Runtime and environment

| Term | Meaning |
|---|---|
| **proot** | A ptrace-based userspace sandbox, bundled as a prebuilt binary. JCode's mechanism for running a Linux userland inside the app's own uid. Not a security boundary |
| **rootfs** | The extracted Linux root filesystem under `filesDir/distros/<id>/rootfs/` |
| **distro** | An installed rootfs plus its metadata, identified by a `DistroProfile.id` such as `ubuntu-24.04` |
| **environment** | The user-facing name for a distro plus its installed toolchains. One is active at a time |
| **guest** | Ambiguous by inheritance — **always qualified**: *guest Linux* means the proot environment; *guest APK* / *`:guest` process* means the app sandbox (§2.4) |
| **host** | The Android/JVM side, as opposed to the guest Linux side |
| **bind mount** | A host directory exposed inside the guest at a different path, via proot's `-b` |
| **catalog** | `catalog.yaml`, the YAML-defined list of installable toolchains |
| **wizard step** | One entry of `WizardStepId`, the first-run setup state machine |

### 2.2 Workspace model

| Term | Meaning |
|---|---|
| **workspace** | A **container folder** that holds projects. Marked `type: workspace` in its `.jcode/<name>.yaml`. Workspaces nest |
| **project** | A **buildable folder**, optionally scaffolded from a template. Marked `type: project` |
| **node** | Either of the above; `WorkspaceNodeType` is `Project` or `Workspace` |
| **breadcrumb** | One level of the workspace navigation trail (`WorkspaceCrumb`) |
| **staging** (`/sources`) | Where a cloned repository lands before the user classifies it as a Project or a Workspace |
| **transfer** (`/jcode-transfer`) | The bind-mounted directory extensions use to move files between SAF and the guest |

> Note the inversion from some other IDEs: here a **workspace contains projects**, and both are
> ordinary folders that describe their own role in a YAML file.

### 2.3 Extensions

| Term | Meaning |
|---|---|
| **`.jext`** | A JCode extension package. Signed format-2 packages are Ed25519-signed and AES-GCM-wrapped around a plain ZIP |
| **Dev Pack** | The user-facing name for a `type: language` extension: syntax rules, completions, snippets, formatter configuration. The manifest key is still `language` |
| **contribution point** | A place an extension can add to the host — one of the six keys under `contributes:` |
| **capability** | A declared API family (`api`, `exec`, `fs`, `config`, `workbench`, `service`) that the user can revoke per extension |
| **activation** | When an extension's contributions turn on: `AutoStart`, `OnDemand` (default) or `Manual` |
| **sideload** | Installing an **unsigned** `.jext` through Developer options; marks the extension `dev = true` |
| **`.vsix`** | A VS Code extension package. JCode implements the webview slice of the VS Code API |
| **template** | A project scaffold: inputs plus a shell recipe, in `templates/<id>/template.yaml` |

### 2.4 Virtual device

| Term | Meaning |
|---|---|
| **app sandbox** | Running a built APK inside JCode. A **preview, not a security boundary** |
| **`:guest` process** | The second process of the JCode app that hosts a guest APK. Same uid, same permissions |
| **guest APK** | The application being previewed |
| **embedded mode** | The guest composited into an editor tab via `SurfaceControlViewHost` |
| **full-screen mode** | The guest as a real activity in its own task, backed by a stub activity |
| **virtual device** | The app sandbox seen as an ADB target |

### 2.5 Editor

| Term | Meaning |
|---|---|
| **buffer** | The document store: a piece table (Kotlin) or piece tree (C++), addressing **UTF-8 bytes** |
| **snapshot** | An immutable read view of a buffer at a point in time |
| **decoration** | Anything drawn over or around text, at a `Layer` z-index |
| **span** | A `ColoredSpan` — a byte range with a colour and style flags |
| **visual row** | One rendered line under soft wrap; a logical line may span several |
| **page tab** | An editor tab with no document (`EditorPageKind != None`), rendering host content |
| **single writer** | The invariant that all buffer mutations run on one logical thread |

### 2.6 Protocol

| Term | Meaning |
|---|---|
| **OSC** | Operating System Command — the escape sequences carrying JCode's shell-integration protocol (codes 7711–7716, plus standard 52) |
| **shell integration** | The scripts JCode installs in the guest that emit those sequences |
| **LSP** | Language Server Protocol — JSON-RPC 2.0 with `Content-Length` framing |
| **DAP** | Debug Adapter Protocol — a different envelope over the same framing |
| **relay** | The fixed-port TCP pump giving the guest one stable ADB endpoint |
| **reverse request** | A DAP request from the adapter to the client (`startDebugging`) |

---

## 3. Naming conventions in the codebase

| Convention | Example |
|---|---|
| Gradle module paths use colons | `:core:editor`, `:native:vt` |
| Native libraries are named for their subsystem | `libjcodebuffer.so`, `libjcode_vt.so`, `libpty.so` |
| Extension ids are reverse-DNS | `dev.example.webpack` |
| Catalog ids are lowercase-hyphenated | `android-sdk`, `cmake-ninja` |
| Enum settings persist by `.name`, never ordinal | `TabColoring.RandomRemember` |
| Composition locals are `Local*` | `LocalIconBundle`, `LocalExplorerScmUi` |
| Host↔guest path translation always goes through `WorkspaceHostPaths` | — |

---

## 4. Document conventions

Every specification in this set follows the shape described in
[the index](../README.md#document-conventions). In short:

- **Status** values: Implemented / Partially implemented / Built but unwired / Stub.
- Every claim is traceable to a repo-relative source path; non-obvious claims cite `path:line`.
- Enum members, defaults, ports and magic numbers are **copied** from source, never paraphrased.
- Section numbering is stable: where a `## 6. Protocol / format` section appears, it means exactly
  that.
- Mermaid diagrams appear only where a picture beats prose.
- Line numbers drift; symbol names and file paths are the durable anchors.

### 4.1 Reading the tables

Where a table lists a default, that default is the **factory** value from source, not what a
particular device has. Where a table lists a range or a cap, it is enforced in code unless the row
says otherwise.

### 4.2 Where a claim is "as designed" rather than "as built"

It says so, in place, and the subsystem's own closing "Known gaps" section repeats it. If a
document describes something without such a note, it is describing running code.

---

## 5. Units and encodings

| Quantity | Unit |
|---|---|
| Buffer offsets, spans, decorations | **UTF-8 bytes** |
| Text measurement, `InputConnection`, `WrapMap` columns | **UTF-16 code units** |
| Search match columns | UTF-16 code units (both native and Kotlin paths) |
| Font sizes | `sp` (multiply by display density for `Paint.textSize`) |
| Spacing and layout | `dp` |
| Colours | ARGB — `Int` in decorations, `Long` in `EditorTheme` |
| Timestamps | Epoch milliseconds |
| Timeouts | Milliseconds, unless a name says otherwise (`IDLE_TIMEOUT_MINUTES`) |

The byte/UTF-16 split is the single most common source of off-by-N bugs in this codebase. Convert
only through `WrapMap.byteColToCharIndex` and `WrapMap.charIndexToByteCol`.

---

## 6. References

- [Product overview](01-product-overview.md)
- [System architecture](../01-architecture/01-system-architecture.md)
- [File format index](../09-platform/01-file-format-index.md)
- [Specification index](../README.md)
