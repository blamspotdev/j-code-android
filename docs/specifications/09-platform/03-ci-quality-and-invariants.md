# CI, quality and invariants

| | |
|---|---|
| **Status** | Implemented — one enforced guard plus version-bump automation; broader CI is planned |
| **Modules** | Repository-wide |
| **Primary sources** | scripts/check-no-host-root.sh, scripts/bump-version.sh, .github/workflows/version-bump.yml, .githooks/pre-commit, scripts/install-git-hooks.sh, scripts/build-release.ps1, CONTRIBUTING.md, AGENTS.md, app/src/androidTest/, core/buffer/src/test/, core/buffer/src/androidTest/ |
| **Verified against** | commit `cea581c`, 2026-08-09 |

---

## 1. Purpose and scope

What is mechanically enforced, what is enforced by convention, and how to verify a change —
including these specifications.

---

## 2. The no-host-root guard

The one invariant with real teeth. It runs in **two** places from a single script:

| Location | Trigger |
|---|---|
| `.githooks/pre-commit` | Every commit, once hooks are enabled |
| `scripts/build-release.ps1` and the shell release scripts | Pre-flight before every release build |

> There was a third: a `no-host-root.yml` workflow that ran the same script on every push and
> pull request. It was removed as duplicate of the commit hook. Note what that costs — the hook
> only runs for people who have enabled it (`scripts/install-git-hooks.sh`), so a contributor
> without hooks, or a pull request from a fork, is no longer checked before merge.

### 2.1 What it scans

Pathspec: `*.kt *.java *.gradle *.gradle.kts *.toml *AndroidManifest.xml`.

Documentation (`*.md`) and proot catalog scripts (`*.yaml`, `*.sh`) are **intentionally excluded** —
catalog scripts legitimately use the guest `sudo` shim, and the scanner itself is `*.sh`, so it never
scans itself.

Three `git grep -nIE` patterns:

| # | Label | Pattern (abridged) |
|---|---|---|
| 1 | host `su` execution | `exec…("su"` / `"su", "-c"` / `/system/x?bin/su` |
| 2 | root helper library | `com.topjohnwu.superuser`, `topjohnwu`, `libsuperuser`, `eu.chainfire`, `rikka.shizuku`, `(dev\|moe).shizuku`, `com.stericson.(RootTools\|RootShell)` |
| 3 | privileged manifest | `sharedUserId = "android.uid.system"`, `android:sharedUserId`, `WRITE_SECURE_SETTINGS`, `MOUNT_UNMOUNT_FILESYSTEMS` |

Any hit prints the offending lines and exits 1.

### 2.2 Explicitly not flagged

Legitimate, all in-sandbox userspace:

- The proot guest `sudo` shim used by catalog install scripts.
- proot's `-0` flag (fake uid 0 **inside** the guest only).
- `rootfs` / `distroRoot` naming.

### 2.3 Enabling the hook

```bash
git config core.hooksPath .githooks
```

or

```bash
sh scripts/install-git-hooks.sh
```

Run it manually at any time:

```bash
sh scripts/check-no-host-root.sh
```

---

## 2a. Automatic patch bumping

`main` has merged a feature batch without a version bump more than once (the #28 and #34 bump PRs
exist only to correct that), so `.github/workflows/version-bump.yml` now does it: every merged PR
bumps the patch on `main`.

**This requires a bypass actor on the `protect-main` ruleset.** That ruleset requires a pull request
and declares no bypass actors, and rulesets bind every actor — admins and `GITHUB_TOKEN` included —
so a direct push to `main` is refused. Add one under *Settings → Rules → protect-main → Bypass list*:
the **GitHub Actions** app (for the built-in token), or **Repository admin** paired with a PAT in
`secrets.VERSION_BUMP_TOKEN`.

Without it the run does not fail: it falls back to pushing the bump to `chore/bump-version` and
offering it as a PR, and the run summary says which path it took and what to configure.

| Aspect | Behaviour |
|---|---|
| Trigger | A pull request **merged** into `main`, plus `workflow_dispatch` |
| Level | `patch`, unless the PR carries `bump-minor` / `bump-major`, or a manual run picks one |
| Normal path | Commit straight onto `main` |
| Fallback | `chore/bump-version` + a PR, when the ruleset refuses the push |
| Skipped when | The merged PR changed `jcodeVersion` itself, or carries the `skip-bump` label |

Each push attempt re-reads `main` and recomputes the bump rather than replaying a stale one, so two
merges landing together cannot reuse a version number; only a ruleset refusal breaks the retry loop
early, because retrying that cannot help.

The bump itself is `scripts/bump-version.sh` — a script, not YAML, so it can be run and tested
locally. It refuses anything that is not a plain `MAJOR.MINOR.PATCH` (a pre-release label belongs on
`-PjcodeVersionName` at build time, never in the file), and rewrites both `app/build.gradle.kts` and
the specifications that state the product version.

> Pushes made with `GITHUB_TOKEN` do not trigger other workflows, so a direct bump neither re-runs
> this workflow nor the no-host-root guard. A PAT does trigger them, which is still not a loop — a
> push cannot fire `pull_request`.

---

## 3. Code-quality standards

Enforced by review and by `AGENTS.md`, not by tooling:

- **Warning-free Kotlin builds.** Verify with
  `./gradlew :app:assembleDebug --warning-mode all`.
- **Deprecation handling.** When overriding a deprecated method, add `@Deprecated` with an
  explanation. When using a deprecated API, prefer the modern alternative (for example
  `FileObserver(File, Int)` over `FileObserver(String, Int)`).
- **Type safety.** Avoid nullable mismatches; prefer explicit checks (`if (obj.has("key"))`) over
  passing `null` to a non-nullable parameter.
- **C/C++.** Warnings from third-party natives (libgit2's llhttp, for example) are acceptable;
  warnings from JCode's own code are not.
- **Module rule.** `:core:*` never depends on `:feature:*` — restated in `CONTRIBUTING.md`.
- **No dead scaffolding.** Remove placeholder code, unused dependencies and unreachable imports as
  you go.
- **UI contrast.** WCAG AA, tested in both light and dark — see
  [Design system §6](../06-workbench/05-design-system.md#6-accessibility-and-contrast-rules).

---

## 4. Specification checks

These documents are checkable. Run from the repository root.

### 4.1 Every cited source path exists

```bash
grep -rhoE '\b(app|core|feature|native|tools|scripts|gradle|docs)/[A-Za-z0-9._/-]+\.(kt|java|cpp|c|h|rs|kts|yaml|yml|json|js|aidl|toml|md|sh|ps1)' docs/specifications | sort -u | while read -r p; do [ -e "$p" ] || echo "MISSING: $p"; done
```

Expect no output.

### 4.2 Every relative link resolves

```bash
find docs/specifications -name '*.md' -print0 | while IFS= read -r -d '' f; do d=$(dirname "$f"); grep -ohE '\]\(\.\.?/[^)#]+\.md' "$f" | sed 's/^](//' | while read -r l; do [ -e "$d/$l" ] || echo "BROKEN: $f -> $l"; done; done
```

Expect no output.

### 4.3 Spot-check transcribed values

Enum members, defaults, ports and magic numbers in these documents are copied from source. When a
spec is re-verified, re-check at least:

| Value | Source |
|---|---|
| Settings keys and defaults | `core/design/src/main/java/dev/blamspot/jcode/design/SettingsDefaults.kt` |
| `EditorPageKind` | `feature/editor-pane/src/main/java/dev/blamspot/jcode/feature/editor/pane/EditorTab.kt` |
| `WorkbenchTool`, `RightPanelTab` | `app/src/main/java/dev/blamspot/jcode/workbench/WorkbenchModel.kt` |
| `WizardStepId` order | `core/distro/src/main/java/dev/blamspot/jcode/core/distro/DistroModels.kt` |
| Catalog entry ids and categories | `core/distro/src/main/assets/distro/catalog.yaml` |
| OSC 7711–7716 | `core/term/src/main/java/dev/blamspot/jcode/core/term/VtParser.kt`, `TerminalSessionManager.kt` |
| Version matrix | `gradle/libs.versions.toml`, `app/build.gradle.kts` |

Then update the document's **Verified against** row.

---

## 5. Tests

| Test | Kind | Covers |
|---|---|---|
| `core/buffer/src/test/java/dev/blamspot/jcode/core/buffer/PieceTableTest.kt` | JVM unit | The Kotlin piece table |
| `core/buffer/src/androidTest/java/dev/blamspot/jcode/core/buffer/NativeBufferDifferentialTest.kt` | Instrumented | Fuzzes the C++ piece tree against the Kotlin table **and** a naive reference, through the real JNI, on device |
| `app/src/androidTest/java/dev/blamspot/jcode/NativeLibrariesSmokeTest.kt` | Instrumented | Every native library loads |
| `app/src/androidTest/java/dev/blamspot/jcode/SyntaxHighlighterDifferentialTest.kt` | Instrumented | The native tokenizer against the Kotlin one |

> `NativeBufferDifferentialTest` **must stay green before any change to the buffer ships** — it is
> the correctness gate for the dual-implementation design. See
> [Text buffer](../02-editor/01-text-buffer.md).

The differential-test pattern is the house style for anything with two implementations.

---

## 6. Verification practice

From `AGENTS.md`:

- Prefer focused verification after each change over one giant pass at the end.
- UX-sensitive work is checked on **both a phone and a tablet**; keyboard-heavy work also needs a
  physical keyboard.
- When an ADB device is connected, install the latest debug build after implementation work so the
  change can be tried immediately.

Device-verification recipes recorded across this repository:

| Technique | Use |
|---|---|
| `uiautomator` | Scripted UI checks |
| `adb shell dumpsys gfxinfo <pkg> framestats` | Frame-timing regressions (the IME jank work) |
| `adb shell setprop log.tag.EditorRecord DEBUG` | Editor re-record diagnosis |
| `adb shell wm size 1920x1080` | Landscape testing on a device that cannot rotate |
| Ext Dev log | Extension `console` output — **not** logcat |

> A release build and a `.debug` build can both be installed. When verifying, check the `package=`
> in the install output — it is easy to test the wrong one.

---

## 7. Contribution rules

From `CONTRIBUTING.md`:

1. Branch off `main`.
2. Build and verify locally (`./gradlew :app:assembleDebug`).
3. Keep changes focused; match the surrounding style; the module rule is strict.
4. Open the PR against `main` with a description of the change and how it was verified.

Contributions are under the **MIT License**. Suspected security vulnerabilities go to the Licensor
privately, not to a public issue.

Repository conventions worth knowing:

- Never push directly to `main`; branch, then open a PR.
- Squash-merge divergence is repaired with `git merge -s ours origin/main` (note: `-s ours`, not
  `-X ours`).
- A new extension's source repository is added as a submodule to the marketplace repo at
  `extensions/<short-name>` in the publishing PR.

---

## 8. Known gaps

- **No CI workflow gates anything.** The two that exist — `release.yml` and `version-bump.yml` —
  publish and bump; neither checks. The no-host-root scanner runs only from the commit hook and the
  release scripts, so a contributor without hooks enabled, or a pull request from a fork, reaches
  `main` unscanned. There is no build, lint, unit-test or instrumented-test workflow.
- `detekt` is a placeholder task with no configuration.
- Warning-free builds and the contrast standards are conventions, not gates.
- Test coverage is narrow: the buffer and the highlighter have differential tests; the terminal, LSP,
  DAP, config merge and extension installer have none.

---

## 9. References

- [Build variants and release](02-build-variants-and-release.md)
- [Security and privacy](04-security-and-privacy.md)
- [Text buffer](../02-editor/01-text-buffer.md)
- [`CONTRIBUTING.md`](../../../CONTRIBUTING.md)
- [`AGENTS.md`](../../../AGENTS.md)
