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
| `debug` | `dev.jcode.debug` | JCode (debug) | `ic_launcher_debug` (red gradient) |
| `release` | `dev.jcode` | JCode | `ic_launcher` |
| `release -PjcodeIdSuffix=.beta` | `dev.jcode.beta` | JCode (beta) | `ic_launcher_beta` (purple gradient) |

All three install **side by side**. The `namespace` stays `dev.jcode` (the compile-time R and
BuildConfig package), so no source reference breaks.

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
val jcodeVersion = "1.4.5"
val jcodeVersionName = findProperty("jcodeVersionName") ?: jcodeVersion
val jcodeVersionCode = MAJOR * 10000 + MINOR * 100 + PATCH   // falls back to 10000
```

Properties of the scheme, as documented in the file: monotonic, deterministic, offline, and
independent of git history — **a squash-merge collapsed the old git-commit-count scheme and produced
downgrades**.

Pre-release suffixes (`1.4.5-beta`) are ignored by the code derivation, and are never stored in
`app/build.gradle.kts` — the release scripts apply them at build time via `-PjcodeVersionName`.
`scripts/bump-patch-version.sh` refuses to bump a version that has one, on the grounds that a
suffix in the file means something upstream is wrong.

The patch number is bumped for you: `.github/workflows/version-bump.yml` opens a standing bump
PR after each merge to `main`. See
[CI, quality and invariants](03-ci-quality-and-invariants.md).

> **The formula is duplicated in three places** and they must agree: `app/build.gradle.kts`
> (`jcodeVersionCode`), `scripts/build-release.ps1` (`$Code`), and
> `scripts/build-release-common.sh` (`CODE`). The shell scripts parse the version by
> `sed -n 's/^val jcodeVersion = "\([^"]*\)".*/\1/p'`, so **that line's shape is load-bearing**.

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

## 6. Release scripts

| Script | Platform |
|---|---|
| `scripts/build-release.ps1` | Windows (pwsh) |
| `scripts/build-release-linux.sh` | Linux |
| `scripts/build-release-macos.sh` | macOS |
| `scripts/build-release-common.sh` | Shared shell logic |

They run `:app:assembleRelease` with `-PjcodeVersionName=…` (plus `-PjcodeIdSuffix=.beta` for Beta),
resolve or install the SDK components (`platform-tools`, the platform package, build-tools,
`ndk;27.2.12479018`, a CMake package — pinned at `3.22.1` in the scripts), and sign.

**Every release script runs `scripts/check-no-host-root.sh` first** as a pre-flight — see
[CI, quality and invariants](03-ci-quality-and-invariants.md).

---

## 7. Native build

Covered fully in [Native layer and JNI](../01-architecture/04-native-layer-and-jni.md). Key points
for building:

- One `native/CMakeLists.txt` superbuild, selected per module by `-DJCODE_NATIVE_MODULE`.
- Rust FFI via `gradle/cargo.gradle.kts` (`cargo ndk`), falling back to a CMake stub when cargo is
  unavailable.
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

1. The version-code formula must match in all three places.
2. The `val jcodeVersion = "…"` line's shape must not change — the scripts parse it with `sed`.
3. `libproot*.so` must stay unstripped and legacy-packaged.
4. Release ABI is `arm64-v8a`; do not ship `x86_64` in release.
5. Keep `-Wl,-z,max-page-size=16384` and `-fvisibility=hidden` on every native target.
6. Do not change the release signing key.
7. No module may declare its own repository (`FAIL_ON_PROJECT_REPOS`).
8. `namespace` stays `dev.jcode` regardless of `applicationId`.

---

## 10. Failure modes

| Failure | Effect |
|---|---|
| Deep Windows checkout path | Native build fails on `MAX_PATH` |
| No network on a clean build | `FetchContent` cannot fetch pinned upstreams |
| `cargo` absent | Search falls back to the Kotlin walk; the app still links |
| `JCODE_KEYSTORE` set but missing | Build fails with a clear message |
| Version formula drifting between script and Gradle | The APK's `versionCode` disagrees with its filename |
| Wrong CMake version installed | Root script picks the newest available instead of 3.28.3 |

---

## 11. Known gaps

- `detekt` is a placeholder task with no configuration.
- R8 is disabled on release, so the APK ships unshrunk and unobfuscated.
- The only CI workflow is the no-host-root guard; there is no build or test workflow.

---

## 12. References

- [Native layer and JNI](../01-architecture/04-native-layer-and-jni.md)
- [CI, quality and invariants](03-ci-quality-and-invariants.md)
- [Module map](../01-architecture/02-module-map.md)
- [`README.md`](../../../README.md)
