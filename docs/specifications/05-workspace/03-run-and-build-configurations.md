# Run and build configurations

| | |
|---|---|
| **Status** | Implemented |
| **Modules** | `:core:config`, `:app` |
| **Primary sources** | core/config/src/main/java/dev/jcode/core/config/RunConfig.kt, app/src/main/java/dev/jcode/run/ProjectRunner.kt (848 lines), app/src/main/java/dev/jcode/run/RunConfigPage.kt, app/src/main/java/dev/jcode/run/BuildConfigPage.kt, app/src/main/java/dev/jcode/RunDebugPanel.kt, app/src/main/java/dev/jcode/debug/DebugController.kt |
| **Verified against** | commit `cea581c`, 2026-08-09 |

---

## 1. Purpose and scope

How a project is built, run and debugged: the `.jcode/run.yaml` format, how configurations are
suggested when none exist, and how a run hands off to the debugger.

There is **one** "Run" tool. Debug is not a separate configuration type — it is a property of a run
configuration (`debugEntry`).

---

## 2. Data model

```kotlin
data class RunConfigTerminal(val label: String, val command: String)

data class RunConfig(
    val name: String,
    val readyPort: Int,
    val debugEntry: String = "",
    val terminals: List<RunConfigTerminal>,
)

data class BuildConfig(val name: String, val command: String)

data class ProjectConfigs(val runs: List<RunConfig>, val builds: List<BuildConfig>) {
    companion object { val EMPTY = ProjectConfigs(emptyList(), emptyList()) }
}
```

| Field | Meaning |
|---|---|
| `terminals` | Run **side by side**, each in its own terminal tab. Build-then-serve is expressed inside the bash command, not as separate phases |
| `readyPort` | When `> 0`, polled after launch and opened in the web preview |
| `debugEntry` | Guest path to the source file the Debug action launches under DAP. **Blank means this configuration is not debuggable** |

A multi-terminal configuration is the normal way to express a full-stack dev setup — for example an
ASP.NET Core API in one terminal and a Vite dev server in another.

---

## 3. `.jcode/run.yaml`

`RunConfigStore` (`REL_PATH = ".jcode/run.yaml"`) reads and writes it.

### 3.1 Current format (v2)

```yaml
version: 2
runs:
  - name: Dev server
    readyPort: 5173
    debugEntry: /workspace/myapp/src/main.ts
    terminals:
      - label: API
        command: dotnet run --project api
      - label: Web
        command: npm run dev
builds:
  - name: Release
    command: dotnet publish -c Release -o out
```

Written with SnakeYAML block flow style and key order `version`, `runs`, `builds`.

### 3.2 Legacy format (v1)

v1 had a **single configuration at the top level**:

```yaml
name: Dev server
readyPort: 5173
terminals:
  - label: Web
    command: npm run dev
```

`load()` detects the absence of a `runs:` key and parses the top-level map as a one-element `runs`
list, keeping it only if it has at least one terminal. v1 files are read but never written back —
the first save upgrades the file to v2.

### 3.3 Parsing tolerance

- Any parse failure returns `null` from `load()` (the whole read is wrapped in `runCatching`), so a
  broken `run.yaml` disables Run rather than crashing the app.
- A `name` that is missing or blank falls back to the **project directory name**.
- `readyPort` accepts a YAML number or a numeric string; anything else becomes `0`.
- A terminal entry without a non-blank `label` is dropped.
- A build entry without a non-blank `name` is dropped.
- `command` defaults to an empty string rather than failing.

> `version` is written but **not read**. Version detection is structural — the presence of `runs:`.

---

## 4. `ProjectRunner`

```kotlin
data class RunPlan(val kindLabel: String, val readyPort: Int, val terminals: List<RunTerminal>) {
    val url: String get() = "http://localhost:$readyPort"
}
```

| Member | Purpose |
|---|---|
| `loadProjectConfigs(project)` / `saveProjectConfigs(project, configs)` | Persist `run.yaml` |
| `effectiveRuns(project)` / `effectiveBuilds(project)` | Stored configurations, falling back to detection |
| `detectRunPlan(project)` | Probe a project opened without a JCode template |
| `runConfigToPlan(config)` | Adapt a stored configuration to a plan |
| `upsertRun` / `upsertRuns` / `upsertBuild` / `deleteRun` / `deleteBuild` | Editing |
| `suggestRunTriggers(project, extensionPresets)` | Suggestion list (§5) |
| `runInvocation(project, terminal)` | The exact bash line executed in a run terminal |

Detection is deliberately **not** persisted: recipes self-heal, so a probe result is recomputed
rather than frozen into `run.yaml` behind the user's back.

---

## 5. Run suggestions

`suggestRunTriggers` merges two sources into a list of `RunTrigger`s, each labelled with its origin —
`"Detected"` for a built-in probe, or the contributing extension's name.

| Source | Where |
|---|---|
| Built-in probes | `ProjectRunner` — file-glob matches such as `package.json` scripts, `*.csproj`, `*.sln` |
| Extension presets | `contributes.runConfigPresets` in an installed extension's manifest |

An extension preset declares `id`, `label`, `requires` (globs) or `match`, and either
`terminals: [{label, command}]` or a single `command` + `terminalLabel`, plus an optional
`readyPort`. See [Manifest reference](../07-extensions/03-manifest-reference.md).

Probe helpers substitute project-relative and guest paths (`rel(f)`, `guest(f)`,
`sanitizeStageName`), and a scan cap (`SCAN_TOTAL_CAP`) bounds work on a large monorepo. Extension
presets are evaluated **first** so a busy repository's generic probes cannot crowd them out of the
cap.

---

## 6. Running

```mermaid
sequenceDiagram
    participant U as Run action
    participant PR as ProjectRunner
    participant TSM as TerminalSessionManager
    participant OUT as Output panel

    U->>PR: effectiveRuns(project) → RunConfig
    loop each terminal
        PR->>TSM: createSession + write runInvocation + OSC 7713 marker
        TSM-->>OUT: onOutput (teed raw bytes)
        TSM-->>PR: onTaskComplete("run", exitCode)
    end
    Note over PR: run is "done" once every terminal reported
    PR->>PR: if readyPort > 0 → poll, then open web preview
```

Each terminal's command is written as:

```
<runInvocation>; printf '\033]7713;run;%s\007' "$?"
```

so completion and exit code are reported through
[OSC 7713](../03-runtime/02-shell-integration-protocol.md#34-osc-7713--task-completion). The run is
complete once every terminal has reported.

Run terminals also inherit the shell integration, so via `BASH_ENV` the tab is named after the
actual tool (`npm`, `vite`, `dotnet`) rather than the wrapper script.

The **Output** panel is a read-only log teed from the run terminals via
`TerminalSessionManager.onOutput`.

`readyPort > 0` is polled after launch, then opened in the web preview or the user's chosen browser
(Settings → Web preview). Guest programs can also request a URL themselves through OSC 7714, and
both paths honour the same browser choice.

### 6.1 The `noexec` constraint

Compiled output must be run from **ext4**, not from a FUSE-backed shared-storage path — the latter
is mounted `noexec`. Since projects live under `filesDir` this is satisfied by default; it matters
when a user points a run command at shared storage.

---

## 7. Debugging

`debugEntry` is a **guest** path. When non-blank, the Debug action on that configuration resolves an
engine from `DebugEngineCatalog` by project type and starts a `DebugSession` against it instead of a
plain run.

For an Android application module, `DebugController` detects the module
(`AndroidAppProject.appModuleFor`) and routes to `prepareAndroidAttach` instead — see
[Android app debugging](../08-virtual-device/03-android-app-debugging.md).

---

## 8. UI

| Screen | File | Tab kind |
|---|---|---|
| Run configuration editor | `app/src/main/java/dev/jcode/run/RunConfigPage.kt` | `EditorPageKind.RunConfig` |
| Build configuration editor | `app/src/main/java/dev/jcode/run/BuildConfigPage.kt` | `EditorPageKind.BuildConfig` |
| Run & Debug drawer tool | `app/src/main/java/dev/jcode/RunDebugPanel.kt` | `WorkbenchTool.RunDebug` |

---

## 9. Invariants and constraints

1. `debugEntry` is a guest path, not a host path.
2. Terminals within a configuration are concurrent, not sequential.
3. Detection results are never written to `run.yaml`.
4. Every run terminal must emit the OSC 7713 marker, or the run never completes.
5. A malformed `run.yaml` must degrade to "no configurations", never crash.
6. Saving always writes v2.
7. Executables must run from an exec-capable filesystem.

> **Naming collision:** `dev.jcode.core.config.BuildConfig` shares its simple name with the
> Gradle-generated `dev.jcode.BuildConfig`. In `:app` sources, importing the config type shadows the
> generated one. Qualify explicitly when both are needed.

---

## 10. Failure modes

| Failure | Effect |
|---|---|
| `run.yaml` unparseable | `load()` returns `null`; Run falls back to detection |
| Terminal exits without the marker | The run appears to hang as incomplete |
| `readyPort` never opens | Web preview never launches; the terminals keep running |
| Session cap reached mid-run | `createSession` returns `null`; that terminal does not start |
| Project on a `noexec` mount | Compiled output fails with permission denied |

---

## 11. Known gaps

- `version:` is written but ignored on read; a future v3 would need structural detection again or a
  read of that field.
- Detection is recomputed on every invocation, so a very large monorepo pays the probe cost each
  time (bounded by `SCAN_TOTAL_CAP`).
- There is no per-run environment-variable block in the schema; environment is set inside the bash
  command or through the global Env Var settings tab.

---

## 12. References

- [Configuration model](02-configuration-model.md)
- [Shell integration protocol](../03-runtime/02-shell-integration-protocol.md)
- [Debug Adapter Protocol](../04-language-services/02-debug-adapter-protocol.md)
- [Manifest reference](../07-extensions/03-manifest-reference.md)
- [File format index](../09-platform/01-file-format-index.md)
