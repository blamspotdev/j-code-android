# Toolchain catalog and onboarding

| | |
|---|---|
| **Status** | Implemented |
| **Modules** | `:core:distro`, `:feature:onboarding`, `:feature:sdk-manager`, `:feature:lsp-manager`, `:feature:debug`, `:app` |
| **Primary sources** | core/distro/src/main/assets/distro/catalog.yaml (1,136 lines), core/distro/src/main/java/dev/jcode/core/distro/SdkCatalogModels.kt, core/distro/src/main/java/dev/jcode/core/distro/SdkCatalogLoader.kt, core/distro/src/main/java/dev/jcode/core/distro/LspCatalogModels.kt, core/distro/src/main/java/dev/jcode/core/distro/DebugEngineModels.kt, core/distro/src/main/java/dev/jcode/core/distro/DistroModels.kt, core/distro/src/main/java/dev/jcode/core/distro/DistroService.kt, feature/onboarding/src/main/java/dev/jcode/feature/onboarding/OnboardingFeature.kt, app/src/main/java/dev/jcode/ToolchainManagerPanel.kt |
| **Verified against** | commit `cea581c`, 2026-08-09 |

---

## 1. Purpose and scope

Three catalogs of installable guest software — SDKs, language servers, debug engines — and the
first-run wizard that brings an environment up to the point where they can be installed.

Only the SDK catalog is data-driven (`catalog.yaml`); the LSP and debug-engine catalogs are Kotlin
constants.

---

## 2. `catalog.yaml`

Location: `core/distro/src/main/assets/distro/catalog.yaml`. Loaded by `SdkCatalogLoader` at app
start into `SdkCatalogEntry` objects. Top-level shape:

```yaml
entries:
  - id: <string>
    category: <string>
    name: <string>
    description: <string>
    installScript: |
      …
    verifyScript: |
      …
    uninstallScript: |
      …
```

### 2.1 Field reference

| Key | Type | Default | Meaning |
|---|---|---|---|
| `id` | string | — | Stable identity. Keys the persisted installed-set, so **never rename an id** |
| `category` | string | — | Mapped to `SdkCatalogCategory` (§2.2) |
| `name` | string | — | Display name |
| `description` | string | — | Display description |
| `installScript` | shell | — | Runs in the guest with the catalog helpers prepended |
| `verifyScript` | shell | — | Prints `ready` on success; determines the Installed badge |
| `uninstallScript` | shell | — | |
| `updateCheckScript` | shell | `""` | Exits 0 when a newer version exists. Empty = update detection skipped |
| `multiVersion` | bool | `false` | When true, several versions coexist and the newest installed is the default on `PATH`; when false, installing replaces |
| `versionsScript` | shell | `""` | Prints installable versions, one per line, newest first (line 1 is "latest"). Empty = no version picker |
| `installedVersionsScript` | shell | `""` | For `multiVersion`: prints currently-installed versions, newest first. Empty = fall back to the binary `verifyScript` |
| `supportedDistros` | list | `[]` | Empty = every distro |
| `supportedArches` | list | `[]` | `Arch.rootfsKey` values (`arm64` / `amd64`). Empty = every arch |
| `requiredSdks` | list | `[]` | Catalog ids that must be installed first |
| `minInstallTimeoutMinutes` | int | `0` | Floor for this entry's install timeout. `0` = no floor |

Derived behavior:

```kotlin
fun installTimeoutMs(globalTimeoutMs: Long) = maxOf(globalTimeoutMs, minInstallTimeoutMinutes * 60_000L)
fun isSupportedOn(distroId: String, arch: Arch) =
    (supportedDistros.isEmpty() || distroId in supportedDistros) &&
    (supportedArches.isEmpty() || arch.rootfsKey in supportedArches)
```

The timeout floor exists so an entry that genuinely needs longer (hundreds of megabytes over a phone
connection) is not killed by a shorter global default; the user's setting still wins when larger.

### 2.2 Categories

`SdkCatalogCategory` — 8 members. `fromSerialized` normalizes case, hyphens, underscores and spaces,
and accepts aliases:

| Enum | Label | Accepted strings |
|---|---|---|
| `Languages` | Languages | `languages`, `language` |
| `BuildTools` | Build Tools | `buildtools`, `buildtool`, `tools` |
| `Android` | Android | `android` |
| `DotNet` | .NET | `dotnet`, `.net`, `net` |
| `Embedded` | Embedded | `embedded` |
| `Databases` | Databases | `databases`, `database`, `db` |
| `Virtualization` | Virtualization | `virtualization`, `virtual`, `vm`, `emulation` |
| `Ai` | AI | `ai`, `agents`, `agent` |

### 2.3 Entries — 22 across 8 categories

| Category | `id` | Name | Notes |
|---|---|---|---|
| Languages | `clangd` | Clang (C/C++ compiler) | Compiler only — the clangd **server** is an LSP-catalog entry and LLDB is a debug engine. The id is kept as `clangd` because it keys the persisted installed-set and the C/C++ Dev Pack names it |
| Languages | `python` | Python | python3 + venv + pip |
| Languages | `nodejs` | Node.js | `multiVersion` (nvm) |
| Languages | `jdk` | Java (JDK) | `multiVersion` |
| Languages | `go` | Go | `multiVersion` |
| Build Tools | `rust` | Rust toolchain | `multiVersion` |
| Build Tools | `cmake-ninja` | CMake and Ninja | |
| Build Tools | `git` | Git | |
| Build Tools | `docker-cli` | Docker CLI | |
| Android | `android-prereqs` | Android SDK prerequisites | `arm64` only |
| Android | `android-sdk` | Android SDK | `arm64` only; `requiredSdks: [android-prereqs]`; `multiVersion`; `minInstallTimeoutMinutes: 45` |
| Android | `adb` | ADB (Android Debug Bridge) | `arm64` only |
| .NET | `dotnet` | .NET SDK | `multiVersion` |
| Embedded | `arm-none-eabi` | ARM embedded GCC | |
| Databases | `postgresql` | PostgreSQL Client | Client only — see §2.5 |
| Databases | `mariadb` | MariaDB | |
| Databases | `redis` | Redis | |
| Databases | `sqlcmd` | SQL Server client (sqlcmd) | `arm64`, `amd64` |
| Virtualization | `qemu-system-x86` | QEMU (x86) | `arm64` only |
| AI | `opencode` | opencode | `arm64` only |
| AI | `claude` | Claude Code | `arm64` only |
| AI | `codex` | Codex | `arm64` only; `requiredSdks: [nodejs]` |

Entries with a `versionsScript` (version picker): `nodejs`, `jdk`, `go`, `rust`, `android-sdk`,
`dotnet`.

### 2.4 Script conventions

- `installScript` and `uninstallScript` run with `CATALOG_SHELL_HELPERS` prepended, so
  `jcode_progress`, `jcode_fetch` and `jcode_apt` are always available. See
  [Shell integration protocol §4](02-shell-integration-protocol.md#4-catalog-shell-helpers).
- Most entries are a single `jcode_apt <from> <to> "<label>" <packages…>` call, which yields a real
  progress bar from apt's own `APT::Status-Fd` percentages.
- `verifyScript` must print `ready` (typically
  `command -v <bin> >/dev/null 2>&1 && echo ready`).
- `updateCheckScript` is usually
  `apt list --upgradable 2>/dev/null | grep -qE '<pattern>'`. The patterns deliberately match
  **versioned** packages (`clang-18`, `python3.12`) because the unversioned metapackages almost
  never rev within a release.
- With a `versionsScript`, the chosen version replaces `{{version}}` in the install and uninstall
  scripts and is exported as `JCODE_VERSION`. The literal string `latest` is passed through so the
  script can resolve it itself.
- A `versionsScript` may emit a **tab-separated second column** as a presentational tag
  (`CatalogVersion(version, tag)`), which is how "LTS" badges appear in the picker.

### 2.5 Known execution constraints

Two entries are shaped by what does not work under proot:

- **PostgreSQL is client-only.** The server refuses to run under `proot -0`.
- **`yes | sdkmanager` deadlocks under proot.** Android SDK scripts use `< /dev/null` instead —
  the difference is a 2-second run versus an effectively infinite hang.

---

## 3. Language-server catalog

`LspCatalogModels.kt` → `LspServerCatalog.BUILT_IN`, 12 entries:

`clangd`, `typescript-language-server`, `csharp-ls`, `pyright`, `gopls`, `rust-analyzer`,
`kotlin-language-server`, `jdtls`, `vscode-html-language-server`, `vscode-css-language-server`,
`vscode-json-language-server`, `yaml-language-server`.

`:core:lsp`'s `LspServerDescriptor.BUILT_IN` is **derived from** this list rather than duplicating it,
"so the catalog never drifts". Descriptor fields: `id`, `languageIds`, `verifyCommand`,
`installCommand`, `runCommand`, `extensions`, `rootDetectors`.

---

## 4. Debug-engine catalog

`DebugEngineModels.kt` → `DebugEngineCatalog.BUILT_IN`, 5 entries:

| `id` | `debugType` | `transport` |
|---|---|---|
| `debugpy` | `python` | `stdio` |
| `lldb-dap` | `lldb` | `stdio` |
| `netcoredbg` | `coreclr` | `stdio` |
| `js-debug` | `pwa-node` | `tcp` |
| `java-debug` | `java` | `stdio` |

`debugType` is what a `.jcode/run.yaml` `debugEntry` resolves against. See
[Debug Adapter Protocol](../04-language-services/02-debug-adapter-protocol.md).

---

## 5. Catalog execution and state

`SdkCatalogState` is the UI-facing state:

| Field | Meaning |
|---|---|
| `entries` | Loaded catalog |
| `installedEntryIds`, `updatableEntryIds` | Badge state |
| `checking`, `runningEntryId`, `runningAction`, `executionLabel` | In-flight action |
| `logLines` | Live output |
| `selectedDistroId`, `errorMessage` | |
| `availableVersions: Map<String, List<CatalogVersion>>` | Populated lazily when a detail page opens for an entry with a `versionsScript` |
| `installedVersions: Map<String, List<String>>` | Newest first; index 0 is the default on `PATH` |
| `versionsLoadingEntryId` | Drives the picker spinner |

`SdkCatalogAction` is `Install` ("Install") / `Uninstall` ("Remove").

Installed state is persisted **per distro** in DataStore under
`sdk_catalog_installed.<distroId>`.

A **catalog lock serializes actions** — one install or uninstall at a time — which is why a single
`CatalogProgress(percent, label)` value suffices for the SDK, LSP and debug-engine managers
together.

Scripts run in the shared **Setup terminal** (`SetupTerminalRunner`) so the user can watch and scroll
real output, with completion detected via OSC 7713 and progress via OSC 7716. Without a PTY the
markers go nowhere and the UI stays indeterminate, but the script still runs.

`DistroService` also performs apt self-heal around installs (`--fix-broken`, `dpkg --configure -a`)
and re-runs `ensureSelectedDistroNetworking()` before apt operations.

---

## 6. First-run wizard

`WizardStepId` — the order of the enum **is** the execution order:

| Step | `key` | Does |
|---|---|---|
| `CheckStorage` | `check-storage` | Requires roughly 2 GB free |
| `ProotReady` | `proot-ready` | proot support files extracted and runnable |
| `DistroSelected` | `distro-selected` | User picked a distro |
| `DistroInstalled` | `distro-installed` | Rootfs downloaded and extracted |
| `WorkspaceReady` | `workspace-ready` | Workspace host directory created |
| `ToolchainBootstrapped` | `toolchain-bootstrapped` | `DEFAULT_BOOTSTRAP_PACKAGES` installed via apt |
| `JcodeUserCreated` | `jcode-user-created` | Non-root `jcode` user created in the distro |
| `AptUpdated` | `apt-updated` | `apt-get update` — **best-effort, never blocks setup** |
| `NodeInstalled` | `node-installed` | Node.js LTS from the catalog — **best-effort, never blocks setup** |
| `SmokeTest` | `smoke-test` | Final verification |

Driven by `DistroService.runWizardStep` / `runAllPendingSteps`. Progress is reported as
`DistroWizardProgress`:

```kotlin
sealed interface DistroWizardProgress {
    data object Idle
    data class Running(step, label, progressPercent: Int?, progressDetail: String?)
    data class Completed(step, detail)
    data class Failed(step, error)
    data class AllDone(totalSteps, completedSteps, summary)
}
```

`progressPercent` is `0..100` only while a step reports determinate progress (for example the rootfs
download); `progressDetail` is human text such as `"12.4 / 41.0 MB"`.

`DistroEnvironmentState` carries `completedSteps: Set<WizardStepId>`, `runningStep`,
`activityLog: List<String>` (streamed stdout/stderr, retained up to 240 entries) and
`errorMessage`.

### 6.1 UX rules baked into the flow

- Ask for the distro **first**, then run the rest as an automatic stepper — not a manual checklist.
- Steps are split fine-grained so Android's watchdog sees periodic activity; a single 15-minute
  chained apt command was previously killed.
- The `id` command does not exist in a minimal rootfs, so user detection uses
  `grep -q '^username:' /etc/passwd`, not `id -u`.
- All long-running work runs on `Dispatchers.IO`; `viewModelScope` defaults to `Dispatchers.Main`
  and previously froze the UI during download and extraction.

`OnboardingFeature.StepperScreen` (830 lines) is UI only — the state machine lives in
`DistroService`.

---

## 7. Invariants and constraints

1. Catalog ids are persistence keys. Renaming one orphans the installed-set.
2. `verifyScript` must print `ready`.
3. Every catalog script may assume the helpers exist and must tolerate them being no-ops without a
   PTY.
4. Adding a category requires extending both `SdkCatalogCategory` and `fromSerialized`.
5. One catalog action at a time; the lock is what makes single-valued progress correct.
6. `AptUpdated` and `NodeInstalled` must never block setup completion.
7. Never bundle a toolchain into the APK.

---

## 8. Failure modes

| Failure | Effect |
|---|---|
| Install exceeds the timeout | Killed; `minInstallTimeoutMinutes` is the per-entry floor |
| `requiredSdks` not installed | Resolved and installed first |
| Entry unsupported on the current arch/distro | Hidden by `isSupportedOn` |
| `updateCheckScript` empty | Entry never shows an update badge |
| Catalog YAML malformed | Loader fails at app start — the catalog is an app asset, not user data |
| Script runs without a PTY | Progress indeterminate; the install still proceeds |

---

## 9. Known gaps

- Only the SDK catalog is data-driven; the LSP and debug-engine catalogs are Kotlin constants and
  need a rebuild to change.
- `supportedDistros` is supported by the model but unused by every current entry.
- The `qemu-system-x86` entry installs a **system** emulator for VM work; it is unrelated to the
  unshipped proot `--qemu` user-mode path described in
  [Embedded Linux runtime §9](03-embedded-linux-runtime.md#9-known-gaps).

---

## 10. References

- [Embedded Linux runtime](03-embedded-linux-runtime.md)
- [Shell integration protocol](02-shell-integration-protocol.md)
- [LSP client](../04-language-services/01-lsp-client.md)
- [Debug Adapter Protocol](../04-language-services/02-debug-adapter-protocol.md)
- [File format index](../09-platform/01-file-format-index.md)
