# Native layer and JNI

| | |
|---|---|
| **Status** | Implemented |
| **Modules** | All 11 `:native:*`, plus the `:core:*` modules that load them |
| **Primary sources** | native/CMakeLists.txt, build.gradle.kts, gradle/cargo.gradle.kts, native/common/jcode_native_stub.c, native/core/build.gradle.kts, native/grammars/CMakeLists.txt, native/proot/build.gradle.kts |
| **Verified against** | commit `cea581c`, 2026-08-09 |

---

## 1. Purpose and scope

How native code is built, packaged and bound to Kotlin. The build wiring is unusual — one shared
CMake superbuild dispatched by a Gradle-injected variable, plus a parallel Cargo path for the Rust
modules — and is not discoverable from any single module's `build.gradle.kts`.

---

## 2. Architecture

### 2.1 One superbuild, many modules

There is a single `native/CMakeLists.txt`. It is a superbuild keyed on `-DJCODE_NATIVE_MODULE=<id>`
and it `FATAL_ERROR`s if that variable, `JCODE_JNI_OUTPUT_DIR`, or `JCODE_VARIANT_DIR` is missing.

The wiring is **injected centrally** by the root `build.gradle.kts` `subprojects { }` block, not by
each module. A `nativeModuleIds` map associates eight module paths with their superbuild id:

```kotlin
// build.gradle.kts
private val nativeModuleIds = mapOf(
    ":native:buffer" to "buffer",
    ":native:editor-render" to "editor-render",
    ":native:tree-sitter" to "tree-sitter",
    ":native:libgit2" to "libgit2",
    ":native:ripgrep-ffi" to "ripgrep-ffi",
    ":native:pty" to "pty",
    ":native:vt" to "vt",
    ":native:wasmtime-ffi" to "wasmtime-ffi"
)
```

For each match the root script sets `compileSdk = 36`, `minSdk = 33`, `ndkVersion = 27.2.12479018`,
ABI filters (`arm64-v8a` alone, on every variant), points
`externalNativeBuild.cmake.path` at `native/CMakeLists.txt`, and passes
`-DANDROID_STL=c++_static -DJCODE_NATIVE_MODULE=… -DJCODE_JNI_OUTPUT_DIR=… -DJCODE_VARIANT_DIR=…`.

**`:native:core` is the exception** — it is absent from that map and carries its own complete
`externalNativeBuild` block in `native/core/build.gradle.kts`. `:native:grammars` and
`:native:proot` are also outside the superbuild (see §2.4, §2.5).

### 2.2 Shared target configuration

```cmake
# native/CMakeLists.txt
function(jcode_configure_target target output_name)
    target_compile_options(${target} PRIVATE -fvisibility=hidden)
    target_link_options(${target} PRIVATE "-Wl,-z,max-page-size=16384")
    if(JCODE_VARIANT_DIR STREQUAL "release")
        target_link_options(${target} PRIVATE "-Wl,-s")
    endif()
    …
endfunction()
```

- `-fvisibility=hidden` — only `JNIEXPORT` symbols are exported.
- `-Wl,-z,max-page-size=16384` — required for 16 KB-page devices.
- `-Wl,-s` on release — strip.
- Language level: C11 and C++17, `CMAKE_POSITION_INDEPENDENT_CODE ON`, `BUILD_SHARED_LIBS OFF`
  (so fetched dependencies build static and get linked in).
- Output goes to `${JCODE_JNI_OUTPUT_DIR}/${JCODE_VARIANT_DIR}/${ANDROID_ABI}`.

### 2.3 Stub targets

`jcode_add_stub_library(target output_name)` builds a `.so` from
`native/common/jcode_native_stub.c` (13 lines) whose only export is
`jcode_native_module_name()` returning the `JCODE_STUB_MODULE` compile define.

Two different things use it, and conflating them is the main trap in this file:

- **Genuine stubs** — `editor-render`, `ripgrep-ffi`, `wasmtime-ffi`, `libgit2`. Nothing real is
  compiled in (though `libgit2` still *links* real static libraries).
- **A naming shim** — `tree-sitter` calls `jcode_add_stub_library`, then `target_sources` appends
  the real `jni_treesitter.c` and links upstream tree-sitter. Despite the function name, this
  target is real.

### 2.4 `-O2` in debug builds

Three targets override the AGP debug default of `-O0`:

| Target | Reason (verbatim from `native/CMakeLists.txt`) |
|---|---|
| `jcode_native_buffer` | "on the per-keystroke path; keep it optimized even in debug builds, where the AGP default -O0 makes it slower than the Kotlin fallback it replaces" |
| `jcode_native_pty` | "sits on the same per-chunk terminal drain path as the VT parser" |
| `jcode_native_vt` | "walks every PTY output byte" |

### 2.5 Fetched upstream dependencies

All via CMake `FetchContent` with `FETCHCONTENT_UPDATES_DISCONNECTED ON`:

| Dependency | Pin | Used by |
|---|---|---|
| tree-sitter | commit `8b9e570c5773b058f210834f86779dcbf1a1878b`, `SOURCE_SUBDIR lib`, WASM off | `tree-sitter` |
| yaml-cpp | tag `0.8.0` | `core` |
| mbedTLS | commit `34e66e1b…`, static only | `libgit2` |
| libssh2 | commit `3a735286…`, mbedTLS backend | `libgit2` |
| libgit2 | commit `0060d9cf…`, `USE_SSH=OFF`, `USE_HTTPS=OFF`, builtin SHA/HTTP parser | `libgit2` |

`:native:grammars` has its own `native/grammars/CMakeLists.txt` (outside the superbuild dispatch)
that fetches and builds 14 tree-sitter grammars at pinned tags: c, cpp, csharp, css, html, java,
javascript, json, kotlin, markdown, python, rust, typescript, tsx.

### 2.6 The Cargo path

`gradle/cargo.gradle.kts` registers `cargoBuildDebugJniLibs` / `cargoBuildReleaseJniLibs`, which
shell out to `cargo ndk` for the two Rust modules (`aarch64-linux-android`, on both variants).

The root `build.gradle.kts` then picks between the two outputs per variant:

```kotlin
val cargoModule = path == ":native:ripgrep-ffi" || path == ":native:wasmtime-ffi"
listOf("debug", "release").forEach { variant ->
    val cargoLibs = layout.buildDirectory.dir("generated/cargoJniLibs/$variant").get().asFile
    val hasCargoLibs = cargoModule && cargoLibs.walkTopDown().any { it.extension == "so" }
    if (!hasCargoLibs) {
        sourceSets.getByName(variant).jniLibs.srcDir(…"generated/jniLibs/$variant")
    }
}
```

Real Cargo output wins; the CMake stub directory is only registered when no Cargo `.so` exists for
that variant. Registering both would make the jniLibs merger see duplicates.

`:core:search` forces the ordering by making `preDebugBuild` / `preReleaseBuild` depend on the
corresponding `cargoBuild*JniLibs` task.

### 2.7 proot is prebuilt, not compiled

`native/proot/CMakeLists.txt` is a placeholder. proot ships as:

| Artifact | Location | Why |
|---|---|---|
| `libproot.so`, `libproot-loader.so`, `libproot-loader32.so` | `native/proot/src/main/jniLibs/arm64-v8a/` | Must be **exec'd**, and `nativeLibraryDir` is the only app-owned location W^X permits `execve` from at `targetSdk` ≥ 29 |
| `libtalloc-arm64-v8a.so`, `libandroid-shmem-arm64-v8a.so` | `native/proot/src/main/assets/bin/` | Only `mmap`'d, so they can be ordinary assets extracted at first run |

`app/build.gradle.kts` sets `jniLibs.useLegacyPackaging = true` so they are extracted to disk, and
`keepDebugSymbols += "**/libproot*.so"` so `llvm-strip` does not corrupt the hand-rolled minimal
ELF loader.

---

## 3. Library and binding table

| Kotlin class | Library | Loaded by | Native source |
|---|---|---|---|
| `dev.blamspot.jcode.core.buffer.Buffer`, `.Snapshot` | `jcodebuffer` | `System.loadLibrary("jcodebuffer")` in `Buffer` | `native/buffer/src/piece_tree.cpp`, `jni_buffer.cpp` |
| `dev.blamspot.jcode.core.buffer.NativeHighlighter` | `jcodebuffer` | `runCatching { System.loadLibrary("jcodebuffer") }` | `native/buffer/src/highlight.cpp`, `jni_highlight.cpp` |
| `dev.blamspot.jcode.core.term.PtyProcess` | `pty` | `System.loadLibrary("pty")` | `native/pty/src/pty.cpp`, `jni_pty.cpp` |
| `dev.blamspot.jcode.core.term.VtParser` | `jcode_vt` | `System.loadLibrary("jcode_vt")` | `native/vt/src/vt_parser.c`, `jni_vt.c` |
| `dev.blamspot.jcode.core.treesitter.Ts*` | `treesitter` | `runCatching { System.loadLibrary("treesitter") }` | `native/tree-sitter/src/jni_treesitter.c` |
| `dev.blamspot.jcode.core.search.NativeSearch` | `ripgrep_ffi` | `System.loadLibrary("ripgrep_ffi")` | `native/ripgrep-ffi/rust/src/lib.rs` (or CMake stub) |
| `dev.blamspot.jcode.native.core.*` | `jcode_core` | `CoreNativeModule` | `native/core/src/main/cpp/*.cpp` |

Each `:native:*` module also ships a `*NativeModule.kt` marker with a `loadLibrary()` helper and a
`nativeInit` export, used to force-load and probe a library independently of the consumer class.

### 3.1 Export surface by library

| Library | Exports | Notes |
|---|---|---|
| `jcodebuffer` | 16 `Buffer`/`Snapshot` + 3 `NativeHighlighter` | `Snapshot_nativeReadLines` batches N lines in one crossing |
| `pty` | 10 | Includes `nativeGetMasterFd` and `nativePoll` for the blocking-wait path |
| `jcode_vt` | 20 | Includes `nativeReadScreen` (whole screen in one crossing) and `nativeDrainOsc` |
| `treesitter` | ~50 across Parser/Tree/Node/Cursor/Query/Language | `TsLanguage_nativeLoad` is a `dlopen`/`dlsym` grammar loader |
| `ripgrep_ffi` | `nativeProbe`, `nativeSearch` | Probe is how the Kotlin side detects the stub |
| `jcode_core` | `CoreNativeModule_nativeIsAvailable`, 15 `ConfigServiceNative_*`, 11 `EditorState_*`/`UndoManager_*` | See §6 |

### 3.2 Crossing-reduction patterns

The JNI boundary is treated as expensive, and three subsystems batch explicitly:

- `Snapshot.nativeReadLines` returns a window of lines in one call (replacing a per-line,
  two-calls-each pattern).
- `VtParser.nativeReadScreen` returns the whole visible screen as one `int[]`, `CELL_STRIDE = 4`
  ints per cell.
- `TsQuery.nativeNextMatch` packs an entire match into one `IntArray`:
  `[patternIndex, captureCount, (captureIndex, startByte, endByte, startRow, startCol, endRow) × N]`.
- `NativeHighlighter.highlight` runs over the native `Snapshot`'s bytes and returns packed
  `[startByte, endByte, colorArgb, styleFlags]` quadruples — the document text never crosses the
  boundary at all.

---

## 4. Graceful degradation

Every optional native library has a Kotlin fallback and a documented detection method:

| Library | Detection | Fallback |
|---|---|---|
| `jcodebuffer` | `runCatching` around `loadLibrary` plus a `USE_NATIVE_BUFFER` flag | Kotlin `PieceTable` |
| `jcodebuffer` (highlighter) | `runCatching` | Kotlin `SyntaxHighlighter` in `:app` |
| `ripgrep_ffi` | `nativeProbe() == 1`, catching `Throwable` — the CMake stub has no `nativeProbe` symbol at all, so an `UnsatisfiedLinkError` is the signal | Kotlin directory walk with `Pattern` |
| `treesitter` | `runCatching` | No fallback needed; nothing depends on it |
| `jcode_vt` | **None** — plain `System.loadLibrary` | None. A terminal without a VT parser is not useful |

The Rust `nativeSearch` wraps its whole body in `catch_unwind` so a Rust panic cannot unwind into
the JVM.

---

## 5. Invariants and constraints

1. Native handle fields consumed by C++ `GetFieldID` must keep their exact names (`nativeHandle`
   for `Buffer`/`Snapshot`).
2. Bit-flag constants duplicated on both sides must move together — `NativeSearch.FLAG_*`
   (`REGEX=1`, `CASE_SENSITIVE=2`, `WHOLE_WORD=4`, `INCLUDE_HIDDEN=8`) mirror the Rust constants,
   and `VtParser.MODE_*` / `MOUSE_*` mirror the `VT_MODE_*` defines in `vt_parser.h`.
3. Never register both the Cargo output and the CMake stub directory for the same variant.
4. `libproot*.so` must not be stripped and must stay in `nativeLibraryDir`.
5. New superbuild modules must be added to `nativeModuleIds` in the root `build.gradle.kts`, or
   CMake aborts with `Unknown JCODE_NATIVE_MODULE`.

---

## 6. Known gaps

- **`:native:core`'s editor/undo/config engine is orphaned.** `jni_editor_state.cpp` exports
  `Java_dev_jcode_core_editor_EditorState_*` and `Java_dev_jcode_core_editor_UndoManager_*` —
  targeting the *real* Kotlin classes — but `core/editor` declares no `external fun` at all, so
  nothing binds. `jni_config.cpp` similarly backs `dev.blamspot.jcode.native.core.ConfigServiceNative`,
  which nothing references. Only `CoreNativeModule.nativeIsAvailable` and the library's
  `JNI_OnLoad` infrastructure are live.
- **`:native:grammars` output is never loaded.** `TsLanguage.nativeLoad` works, but no code path
  calls it. See [Syntax highlighting and completion](../02-editor/05-syntax-highlighting-and-completion.md).
- **`:native:libgit2` links real libgit2/libssh2/mbedTLS into `libgit2_ffi.so` but exposes no JNI
  functions**, so the crypto and git code is dead weight in the APK.
- **`:native:wasmtime-ffi`'s Rust crate is a 4-line stub** returning a version number; there is no
  WASM host.
- **`:native:editor-render` has no sources at all** — only the stub `.so`.
- `native/buffer`, `native/vt` and `native/pty` each repeat CMake arguments in their own
  `build.gradle.kts` that the root script already injects.

---

## 7. References

- [Module map](02-module-map.md)
- [Text buffer](../02-editor/01-text-buffer.md)
- [Terminal, PTY and VT](../03-runtime/01-terminal-pty-and-vt.md)
- [Build variants and release](../09-platform/02-build-variants-and-release.md)
- [`THIRD-PARTY-NOTICES.md`](../../../THIRD-PARTY-NOTICES.md) — licences for the fetched upstreams
