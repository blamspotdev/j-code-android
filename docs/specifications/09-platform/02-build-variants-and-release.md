# Build variants and release

| | |
|---|---|
| **Status** | Implemented |
| **Modules** | Root build, `:app`, all `:native:*` |
| **Primary sources** | build.gradle.kts, app/build.gradle.kts, settings.gradle.kts, gradle/libs.versions.toml, gradle/cargo.gradle.kts, gradle/wrapper/gradle-wrapper.properties, native/CMakeLists.txt, scripts/build-release.ps1, scripts/build-release-common.sh, scripts/build-release-linux.sh, scripts/build-release-macos.sh |
| **Verified against** | commit `cea581c`, 2026-08-09 |

---

## 1. Purpose and scope

How JCode is built and shipped: the toolchain matrix, the three app identities, the version scheme,
and the post-build signing flow.

---

## 2. Toolchain matrix

| Component | Version | Source |
|---|---|---|
| AGP | 8.13.0 | `gradle/libs.versions.toml` |
| Gradle | 8.14.3 | `gradle/wrapper/gradle-wrapper.properties` |
| Kotlin | 2.2.20 | `gradle/libs.versions.toml` |
| KSP | 2.2.20-2.0.2 | `gradle/libs.versions.toml` |
| Hilt | 2.57.1 | `gradle/libs.versions.toml` |
| Room | 2.8.4 | `gradle/libs.versions.toml` |
| Compose BOM | 2025.01.00 | `gradle/libs.versions.toml` |
| Material3 | 1.3.1 (adaptive 1.2.0) | `gradle/libs.versions.toml` |
| Coroutines | 1.10.1 | `gradle/libs.versions.toml` |
| JDK / JVM toolchain | 21 (Hilt's javac forced to 17) | root `build.gradle.kts` |
| `compileSdk` | 36 | `app/build.gradle.kts` |
| `minSdk` / `targetSdk` | 33 / 33 | `app/build.gradle.kts` |
| NDK | 27.2.12479018 | `app/build.gradle.kts`, root `build.gradle.kts`, `scripts/build-release-common.sh` |
| CMake | 3.28.3 desired; auto-detected from `$ANDROID_HOME/cmake`, newest installed as fallback | root `build.gradle.kts` |
| C / C++ | C11 / C++17 | `native/CMakeLists.txt` |

`settings.gradle.kts` sets `RepositoriesMode.FAIL_ON_PROJECT_REPOS`, so a module cannot declare its
own repository. Repositories are `google()`, `mavenCentral()` (plus `gradlePluginPortal()` for
plugins), with the foojay resolver for JVM toolchain provisioning.

### 2.1 Why `targetSdk` is 33

Deliberate. Lint's `ExpiredTargetSdkVersion` (a Play Store rule wanting 34+) is disabled with the
reason in `build.gradle.kts`:

> we distribute outside Play and hold targetSdk at 33 until the 34+ gates (FGS types, receiver
> export flags) are handled.

`NullSafeMutableLiveData` is also disabled — it crashes `lintVitalRelease` (androidx.lifecycle
detector versus the Kotlin 2.x analysis API).

---

## 3. App identities

There are **no product flavors**. Three identities come from build types plus a property:

| Build | `applicationId` | Label | Launcher icon |
|---|---|---|---|
| `debug` | `dev.blamspot.jcode.debug` | JCode (debug) | `ic_launcher_debug` (red gradient) |
| `release` | `dev.blamspot.jcode` | JCode | `ic_launcher` |
| `release -PjcodeIdSuffix=.beta` | `dev.blamspot.jcode.beta` | JCode (beta) | `ic_launcher_beta` (purple gradient) |

> **Temporary — remove at 1.7.0.** The package-migration bridge (Settings > Environment > "Export
> for migration", the onboarding "Import from previous install", and the cleanup prompt that follows
> an import) exists only to carry installs across the `dev.jcode` -> `dev.blamspot.jcode` rename
> shipped in 1.6.1. It has no purpose once users have moved. When `jcodeVersion` reads `1.7.0`,
> delete `MigrationBundle.kt`; the `exportMigrationBundle` / `importMigrationBundle` /
> `cleanUpAfterMigration` / `requestUninstall` / `refreshMigrationBundle` members and the
> `migrationBundle` / `migrationCleanup` state in `MainViewModel`; the cleanup dialog in
> `JCodeShell`; `onExportMigration` / `onImportMigration` / `migrationSummary` from
> `EnvironmentBackupActions`, `SettingsFeature` and `OnboardingFeature`; the
> `REQUEST_DELETE_PACKAGES` permission and the `<queries>` block in the manifest; and the
> `-PjcodeApplicationId` override below. Keep `DistroService.extractArchive` and the `isRootfs`
> parameter on `RootfsManager.extractRootfs` — environment backup/restore uses both.

All three install **side by side**. `namespace` (the compile-time R and BuildConfig package) is
also `dev.blamspot.jcode`; the suffixes apply to `applicationId` only, so the three variants share one
set of generated classes and differ only in identity on the device.

Each identity gets its own private data — Linux rootfs, settings, sessions — because the package
differs. Only the legacy shared `/storage/emulated/0/JCode` projects folder was common; post-migration
projects live under each package's own `filesDir`.

Manifest placeholders `appLabel`, `appIcon`, `appIconRound` carry the differences.

`release` sets `isMinifyEnabled = false` — R8 shrinking and obfuscation are **off**, though
`proguard-rules.pro` exists.

### 3.1 ABI filters

| Variant | ABIs |
|---|---|
| debug | `arm64-v8a`, `x86_64` |
| release | `arm64-v8a` |

### 3.2 Packaging

```kotlin
packaging {
    jniLibs {
        useLegacyPackaging = true                     // extract to disk: proot must be exec'd
        keepDebugSymbols += "**/libproot*.so"         // llvm-strip would corrupt the ELF loader
    }
}
```

`buildFeatures`: `compose`, `buildConfig`, and **`aidl`** (for `IGuestSession`).

---

## 4. Version scheme

Single source of truth in `app/build.gradle.kts`:

```kotlin
val jcodeVersion = "1.6.1"                                   // the train being prepared
val jcodeVersionName = findProperty("jcodeVersionName") ?: jcodeVersion
val jcodeVersionCode = (MAJOR * 10000 + MINOR * 100 + PATCH) * 100 + tier
```

Properties of the scheme, as documented in the file: monotonic, deterministic, offline, and
independent of git history — **a squash-merge collapsed the old git-commit-count scheme and produced
downgrades**.

### 4.1 Release trains

`jcodeVersion` is the version being **prepared**, not the last one shipped. `main` carries an open
train; merges do not move it. Previews of that train are built and published as `1.6.1-beta.N`, and
`1.6.1` is published from the same line of commits when it is ready — publishing it is what opens
the next train.

```
main = 1.6.1        merge, merge, merge          (jcodeVersion unchanged)
                    publish v1.6.1-beta.1        pre-release, dev.blamspot.jcode.beta
                    merge
                    publish v1.6.1-beta.2        pre-release
                    publish v1.6.1               release, dev.blamspot.jcode
main = next patch   opened automatically
```

Bumping per merge — which is what `version-bump.yml` used to do — would move the target every time
somebody landed a PR, and no version would stand still long enough for a preview to be a preview
*of* anything.

### 4.2 Pre-release labels and the tier

Pre-release labels are never stored in `app/build.gradle.kts`; they are applied at build time via
`-PjcodeVersionName`, and `scripts/bump-version.sh` refuses to bump a version that carries one.

A label must resolve to **`alpha.N`, `beta.N` or `rc.N`** — numbered, because a bare `beta` cannot be
iterated. Anything that does not is rejected rather than accepted, for the reason the `tier` exists
at all:

| versionName | versionCode | tier |
|---|---|---|
| `1.6.1-alpha.1` | 1060101 | `N` |
| `1.6.1-beta.1` | 1060131 | `30 + N` |
| `1.6.1-beta.2` | 1060132 | |
| `1.6.1-rc.1` | 1060161 | `60 + N` |
| `1.6.1` | 1060199 | `99` |

The old derivation ignored the suffix entirely, so `1.6.1-beta.1`, `1.6.1-beta.2` and `1.6.1` all
produced **the same code** — successive previews never climbed, which is the one thing a version
code has to do. An unrecognised label falls to the release tier, which is the safe end of the range
and the reason the labels are validated before a build starts.

The **number is derived, not typed.** `release.yml` reads the tags this train has already published
and takes one past the highest in the line asked for, so the label input accepts three shapes:

| Input | Resolves to |
|---|---|
| *(blank)* | the next beta — `beta.1`, then `beta.2`, … |
| `alpha` / `beta` / `rc` | the next in that line |
| `beta.7` | exactly that |

A new train starts back at `beta.1` on its own, because `v1.6.1-beta.*` is a different prefix.

Whatever the label resolves to, **nothing already published for the train may be at or above the
build's version code**, and that is checked in seconds rather than after an hour of building. It
covers republishing a label, which `gh release create` would otherwise only reject at the very last
step — and, for a label given by hand, going *backwards*, which no tag check can see and which would
put a lower version code on devices already carrying a higher one. It also means a train that has
shipped cannot be previewed again: open the next one instead.

### 4.3 Opening the next train

`.github/workflows/version-bump.yml` is **dispatched, never triggered by an event**: `release.yml`
asks for it at the end of a stable publish, and asks for a **patch** bump — a train that ships opens
the next one along the same minor line, and a train that is to carry features is raised by hand.
Running it by hand for any level is the other way in, and that is **admin-only** — the same check as
`release.yml`, skipped only for the dispatch from `release.yml`, which arrives as
`github-actions[bot]`.

> **It used to also listen for `release: published`, and that trigger is gone.** Three reasons at
> once. A release created with `GITHUB_TOKEN` raises no events that start other workflows — the loop
> guard — so it would never have heard this project's own releases. A `release` run's ref is the
> *tag*, so the `main`-only environment below would refuse it. And **anyone with push access can
> publish a release**, which made it the one way into this workflow that was not admin-gated.

It pushes to `main` directly. `protect-main` requires a pull request and binds every actor not on
its bypass list; **"Repository admin" is on that list**, so the job checks out with a PAT.

That PAT is an **environment secret on `version-bump`**, not a repository secret, for the reason
given in §6.1: a repository secret is readable by a workflow on any branch, so anyone with push
access could take it and push to `main` themselves — the exact rule it exists to bypass. The
environment's deployment branch policy is `main` alone and it carries **no reviewers**, because this
is the tail of a release that has already been approved and a second gate would only leave `main`
carrying a version that has shipped.

Without the PAT the push is refused and the run offers the bump as a PR on `chore/bump-version`.
Nothing is lost; it just needs merging by hand, and the run summary says so.

> **The formula is duplicated in four places** and they must agree: `app/build.gradle.kts`
> (`jcodeVersionCode`), `scripts/build-release.ps1` (`$Code`),
> `scripts/build-release-common.sh` (`version_code`), and `.github/workflows/release.yml`. The shell
> scripts and both workflows parse the version by
> `sed -n 's/^val jcodeVersion = "\([^"]*\)".*/\1/p'`, so **that line's shape is load-bearing**.

### 4.4 Update channels

The two identities are two applications, so "is there an update?" is a different question for each,
and `dev.blamspot.jcode.beta` can never be updated *into* `dev.blamspot.jcode`. `app/build.gradle.kts` derives
`BuildConfig.UPDATE_CHANNEL` from the id suffix, and `dev.blamspot.jcode.UpdateChecker` follows it:

| Channel | Asks GitHub for | Offers |
|---|---|---|
| `stable` | `releases/latest` — never a draft or pre-release | the `-release` APK asset |
| `beta` | the release *list*, highest version including pre-releases | the `-beta` APK asset |

A Beta build is still told when the train it was previewing ships: the final release is the highest
version on the list, and it is reported with **no APK URL**, so the app offers the release page
rather than an install it cannot perform. Comparison is Semantic Versioning 2.0.0 precedence —
`1.6.1-beta.2 < 1.6.1-rc.1 < 1.6.1` — covered by `app/src/test/java/dev/jcode/UpdateCheckerTest.kt`.

---

## 5. Signing

There is **no `signingConfigs {}` block in Gradle**. Release APKs are signed post-build by
`apksigner` from the newest installed build-tools.

Keystore resolution order:

1. `-KeystorePath` argument
2. `$env:JCODE_KEYSTORE`
3. The default `~/.jcode/jcode-release.jks`
4. An interactive file picker
5. An offer to create one: `keytool -genkeypair -keystore <path> -alias jcode -keyalg RSA -keysize 4096 -validity 10000 -dname 'CN=JCode, O=JCode, C=US'`

Password from `$env:JCODE_KEYSTORE_PASS` or a password file. If `JCODE_KEYSTORE` is set but is not a
file, or no password is available, the build **fails** rather than silently producing an unsigned
APK.

Fallbacks when no release key is chosen:

| Outcome | Output name |
|---|---|
| Release-signed | `builds/jcode-v<versionName>-<code>-<variant>.apk` |
| Debug-keystore signed | `…-debugsigned.apk` |
| Unsigned | `…-unsigned.apk` (with a printed `apksigner sign …` hint) |

The script prints the output size and SHA-256.

> **Changing the keystore breaks Play Protect's recognition of the app** and blocks a
> same-signature silent self-update. Keep the release key stable.

---

## 6. Release scripts and the release workflow

Publishing is `.github/workflows/release.yml`, run by hand from the Actions tab. The version is
**not** an input — it is read from `jcodeVersion`, so a release can only publish the train the
repository says it is preparing. What you choose is the channel:

| Input | versionName | Tag | App id | GitHub |
|---|---|---|---|---|
| `beta`, label blank | `1.6.1-beta.N` (next) | `v1.6.1-beta.N` | `dev.blamspot.jcode.beta` | pre-release |
| `stable` | `1.6.1` | `v1.6.1` | `dev.blamspot.jcode` | release |

It builds the Rust JNI libraries, assembles, signs with `apksigner`, verifies the signature, then
creates the tag and the release with the APK attached.

> **The workflow installs no CMake.** `native/CMakeLists.txt` uses
> `$<LINK_LIBRARY:WHOLE_ARCHIVE,…>`, so the resolver in the root `build.gradle.kts` discards every
> installed version below 3.24 — `cmake;3.22.1` included, which is what the SDK installs by default
> and what the *release scripts* still pin. Asking for it adds a download the build then refuses to
> use. The runner image ships a usable one; the workflow asserts that rather than installing, because
> the resolver's fallback names `3.28.3`, a version the SDK does not publish as a package at all. The tag is created *by* the publish step
(`--target`), so a failed build never leaves a tag pointing at a release that does not exist, and an
already-published tag is refused before the build rather than after it.

### 6.1 Who may publish

Signing needs four secrets: `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`,
`RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`. They are **environment** secrets on a `release`
environment, deliberately not repository secrets: a repository secret is readable by a workflow
running on *any* branch, so anyone with push access could take the keystore by dispatching a branch
of their own. §5's rule — that the key cannot be rotated without every user uninstalling first —
is what makes that worth guarding.

| Control | Setting | What it stops |
|---|---|---|
| Deployment branches | `main` only | A pushed branch reaching the secrets at all |
| Required reviewers | the repo admin | A non-admin's run starting |
| Allow administrators to bypass | on | An admin's own release waiting on a second approver |

The `publish` job declares `environment: release`; the `guard` job ahead of it refuses a
non-admin actor outright. **The guard is courtesy, not control** — it lives in a file anyone with
push access can edit on their own branch. The environment is the control, and it is the reason
editing the guard away gains nothing.

The local scripts remain, and are the same build without the publishing:

| Script | Platform |
|---|---|
| `scripts/build-release.ps1` | Windows (pwsh) |
| `scripts/build-release-linux.sh` | Linux |
| `scripts/build-release-macos.sh` | macOS |
| `scripts/build-release-common.sh` | Shared shell logic |

They run `:app:assembleRelease` with `-PjcodeVersionName=…` (plus `-PjcodeIdSuffix=.beta` for Beta),
resolve or install the SDK components (`platform-tools`, the platform package, build-tools,
`ndk;27.2.12479018`, a CMake package — pinned at `3.22.1` in the scripts), and sign. `--label=` takes
the same `alpha.N|beta.N|rc.N` shape and is validated the same way, but is **always explicit**
(default `beta.1`): deriving the number means reading the published tags, and these scripts build
without a network or a GitHub token by design. They produce a local APK and never publish, so
nothing they name can collide with a release.

**Every release script runs `scripts/check-no-host-root.sh` first** as a pre-flight — see
[CI, quality and invariants](03-ci-quality-and-invariants.md).

---

## 7. Native build

Covered fully in [Native layer and JNI](../01-architecture/04-native-layer-and-jni.md). Key points
for building:

- One `native/CMakeLists.txt` superbuild, selected per module by `-DJCODE_NATIVE_MODULE`.
- Rust FFI via `gradle/cargo.gradle.kts` (`cargo ndk`), falling back to a CMake stub when cargo is
  unavailable. Once cargo has produced the real library for a variant, the root build passes
  `-DJCODE_REAL_LIB_PRESENT=ON` and the stub is built **STATIC** instead of shared — a shared one
  lands in the CMake object directory under the same name AGP takes the cargo library from, and
  `mergeReleaseNativeLibs` cannot choose between them. Dropping the target altogether would leave
  the module's CMake project with nothing to build; a `.a` is never packaged. The same flag decides
  whether `generated/jniLibs/<variant>` is registered as a `jniLibs` srcDir.
- CMake `FetchContent` pulls tree-sitter, yaml-cpp, libgit2, libssh2 and mbedTLS at pinned revisions,
  so the **first** build needs network access.

---

## 8. Building locally

```bash
./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

> **Windows:** build from a short path (for example `X:\jc`). A deep checkout path can exceed the
> Win32 `MAX_PATH` limit during the native (tree-sitter) build.

Planned CI command set: `./gradlew assembleDebug lintDebug testDebugUnitTest detekt connectedDebugAndroidTest`.
The root `detekt` task is currently a **bootstrap placeholder** registered in `build.gradle.kts`.

---

## 9. Invariants and constraints

1. The version-code formula must match in all four places (§4.2).
2. The `val jcodeVersion = "…"` line's shape must not change — the scripts and both workflows parse
   it with `sed`.
3. `libproot*.so` must stay unstripped and legacy-packaged.
4. Release ABI is `arm64-v8a`; do not ship `x86_64` in release.
5. Keep `-Wl,-z,max-page-size=16384` and `-fvisibility=hidden` on every native target.
6. Do not change the release signing key.
7. No module may declare its own repository (`FAIL_ON_PROJECT_REPOS`).
8. `namespace` is `dev.blamspot.jcode` and does not take the variant suffixes `applicationId` does.

---

## 10. Failure modes

| Failure | Effect |
|---|---|
| Deep Windows checkout path | Native build fails on `MAX_PATH` |
| No network on a clean build | `FetchContent` cannot fetch pinned upstreams |
| `cargo` absent | Search falls back to the Kotlin walk; the app still links |
| `JCODE_KEYSTORE` set but missing | Build fails with a clear message |
| Version formula drifting between script and Gradle | The APK's `versionCode` disagrees with its filename |
| A pre-release label that resolves to none of `alpha.N`/`beta.N`/`rc.N` | Refused before the build — it would derive the release tier, giving a preview the same `versionCode` as the release |
| A hand-written label at or below one already published for the train | Refused before the build; a tag check alone cannot see the "below" case |
| Release keystore secrets missing in Actions | The workflow fails at the signing step; nothing is published |
| Wrong CMake version installed | Root script picks the newest available instead of 3.28.3 |

---

## 11. Known gaps

- `detekt` is a placeholder task with no configuration.
- R8 is disabled on release, so the APK ships unshrunk and unobfuscated.
- There is still no CI workflow that builds or tests a pull request; `release.yml` is the first
  workflow that compiles the app, and it only runs when a release is published by hand.
- `UPDATE_CHANNEL` has exactly two values. A second side-by-side preview slot (an `alpha` app id
  beside `beta`) would need a third, and `jcodeIdSuffix` is a free-form string that nothing
  validates against the channel derivation.

---

## 12. References

- [Native layer and JNI](../01-architecture/04-native-layer-and-jni.md)
- [CI, quality and invariants](03-ci-quality-and-invariants.md)
- [Module map](../01-architecture/02-module-map.md)
- [`README.md`](../../../README.md)
