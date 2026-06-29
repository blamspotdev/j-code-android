# Third-Party Notices

J Code is licensed under the Apache License 2.0 (see [`LICENSE`](LICENSE)). J Code bundles,
links, or otherwise distributes the third-party components listed below. Each
component remains under its own license; the notices below are provided to comply
with those licenses and are for informational purposes only.

This file covers components shipped in or with the application. Build-time-only
tools (Gradle, the Android Gradle Plugin, Kotlin/KSP compiler plugins, test
frameworks) are not redistributed and are omitted.

---

## 1. Bundled executables and shared libraries (assets)

These are pre-compiled binaries shipped under `native/proot/src/main/assets/bin/`
and extracted to app-private storage at runtime. PRoot and its loaders run as
**separate processes** (spawned via a PTY); they are not linked into the
application. libtalloc and libandroid-shmem are loaded by the PRoot process via
`LD_LIBRARY_PATH`, not by J Code's own code.

| Component | Version | License | Source |
|---|---|---|---|
| PRoot (incl. `loader` / `loader32`) | 5.1.107.76 (Termux build) | **GPL-2.0** | https://github.com/termux/proot |
| libtalloc | 2.4.3 | **LGPL-3.0** | https://talloc.samba.org/ |
| libandroid-shmem | (Termux build) | MIT | https://github.com/termux/libandroid-shmem |

### Written offer for corresponding source (GPL-2.0 / LGPL-3.0)

In accordance with the GNU GPL v2 and LGPL v3, the complete corresponding source
code for the **PRoot** binaries and the **libtalloc** shared library distributed
with this application is available from the upstream projects linked above, at the
stated versions. The Licensor will, on request and for no more than the cost of
physically performing distribution, provide a copy of the corresponding source for
the exact versions bundled in a given release. Contact the Licensor (see
[`README.md`](README.md)) to make such a request.

These binaries are unmodified upstream builds. Their licenses (GPL-2.0 / LGPL-3.0)
apply only to the binaries themselves and, because they execute as separate
processes / dynamically loaded libraries of a separate process, do not extend to
J Code's own code.

---

## 2. Native libraries compiled from source

These are fetched and compiled by the NDK/CMake build (see `native/CMakeLists.txt`)
and linked into J Code's `.so` libraries.

| Component | Version | License | Linkage | Source |
|---|---|---|---|---|
| yaml-cpp | 0.8.0 | MIT | static (into `jcode_core.so`) | https://github.com/jbeder/yaml-cpp |
| tree-sitter | 0.23.0 | MIT | static | https://github.com/tree-sitter/tree-sitter |
| tree-sitter grammars (c, javascript, python, kotlin, json, yaml, rust, cpp, java, typescript, tsx, html, css, markdown, csharp) | various | MIT | shared `.so` | https://github.com/tree-sitter |
| libgit2 | 0.28.0 | **GPL-2.0 WITH the libgit2 linking exception** | static | https://github.com/libgit2/libgit2 |
| libssh2 | 1.11.1 | BSD-3-Clause | static | https://github.com/libssh2/libssh2 |
| Mbed TLS | 3.6.2 | Apache-2.0 (selected; the project is Apache-2.0 OR GPL-2.0) | static | https://github.com/Mbed-TLS/mbedtls |

**libgit2 linking exception.** libgit2 is GPL-2.0 *with* an explicit exception
granting "unlimited permission to link the compiled version of this library into
combinations with other programs, and to distribute those combinations without any
restriction." J Code relies on this exception to statically link libgit2 without
the GPL extending to J Code's own code. A copy of libgit2's `COPYING` (which
contains the exception) is distributed with the corresponding source on request.

> Some of the modules above (libgit2/libssh2/Mbed TLS, tree-sitter) back features
> that are still being wired up; they are listed here for completeness so the
> attribution travels with every build that contains them.

---

## 3. JVM / Android dependencies

Bundled into the APK (`classes.dex` / merged resources). Unless noted, each is
**Apache License 2.0**.

### Apache-2.0

- AndroidX Jetpack — Core-KTX, AppCompat, Activity-Compose, Lifecycle
  (runtime-ktx / runtime-compose), DataStore (preferences), Room
  (runtime / ktx), DocumentFile, Window — https://developer.android.com/jetpack
- Jetpack Compose — UI, UI-Graphics, UI-Tooling-Preview, Foundation,
  Material 3, Material 3 Adaptive (+ adaptive-layout), Material Icons Extended —
  https://developer.android.com/jetpack/compose
- Dagger / Hilt 2.55 — https://github.com/google/dagger
- Kotlin standard library 2.1.0, kotlinx.coroutines 1.10.1,
  kotlinx.serialization 1.8.0 — https://github.com/JetBrains/kotlin,
  https://github.com/Kotlin/kotlinx.coroutines,
  https://github.com/Kotlin/kotlinx.serialization
- SnakeYAML Engine 2.9 — https://bitbucket.org/snakeyaml/snakeyaml-engine

### Other permissive / weak-copyleft

| Component | Version | License | Notes |
|---|---|---|---|
| Eclipse LSP4J | 0.23.1 | **EPL-2.0 / EDL** | Java library; dynamically linked. EPL is weak copyleft and applies only to modifications of LSP4J itself. https://github.com/eclipse-lsp4j/lsp4j |
| Bouncy Castle (`bcprov-jdk18on`, `bcpkix-jdk18on`) | 1.80 | Bouncy Castle (MIT-style) | https://www.bouncycastle.org/ |
| XZ for Java (`org.tukaani:xz`) | 1.10 | 0BSD / public domain | https://tukaani.org/xz/java.html |

---

## 4. J Code's own native and JVM code

All first-party modules — `:app`, `:core:*`, `:feature:*`, and the native
libraries `jcodebuffer`, `jcode_core`, `pty`, `jcode_vt`, and the
editor-render / ripgrep-ffi / wasmtime-ffi / tree-sitter / libgit2 FFI wrappers —
are © 2026 blamspotdev (Janrick Samorin) and licensed under the Apache License 2.0 (see
[`LICENSE`](LICENSE)).
