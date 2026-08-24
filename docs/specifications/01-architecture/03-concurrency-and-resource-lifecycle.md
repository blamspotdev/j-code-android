# Concurrency and resource lifecycle

| | |
|---|---|
| **Status** | Implemented |
| **Modules** | `:core:editor`, `:core:buffer`, `:core:term`, `:core:lsp`, `:core:debug`, `:core:resource`, `:core:treesitter` |
| **Primary sources** | core/editor/src/main/java/dev/blamspot/jcode/core/editor/EditorState.kt, core/buffer/src/main/java/dev/blamspot/jcode/core/buffer/Buffer.kt, core/resource/src/main/java/dev/blamspot/jcode/core/resource/ResourceManager.kt, core/resource/src/main/java/dev/blamspot/jcode/core/resource/MemoryPressure.kt |
| **Verified against** | commit `cea581c`, 2026-08-09 |

---

## 1. Purpose and scope

Two cross-cutting concerns that every subsystem participates in:

1. **Which thread may touch what**, and how mutations are serialized.
2. **Who frees native memory**, and when.

Both have hard invariants that are easy to break silently — a wrong thread produces corruption
rather than an exception, and a wrong `Cleaner` capture produces a leak that never reports itself.

---

## 2. Threading model

There is no single application-wide dispatcher. Each subsystem owns a scope with a documented
policy:

| Subsystem | Scope | Policy |
|---|---|---|
| `EditorState` | `CoroutineScope(SupervisorJob() + dispatcher.limitedParallelism(1))`, dispatcher defaults to `Dispatchers.IO` (`EditorState.kt:127–131`) | **Single writer.** Every mutator runs `withContext(scope.coroutineContext)` |
| `TerminalSessionManager` | `CoroutineScope(Dispatchers.IO + SupervisorJob())`, process-lifetime | Reader loops drain every session continuously, attached to the UI or not |
| `LspSession` | `CoroutineScope(SupervisorJob() + Dispatchers.IO)` per session | Read loop + a `writeMutex` serializing writes |
| `DebugSession` | `CoroutineScope(SupervisorJob() + Dispatchers.IO)` per session | Same shape as `LspSession` |
| `TsParseService` | `CoroutineScope(SupervisorJob() + Dispatchers.Default)` | 30 ms debounce, cancel-and-replace keyed on a `generation` stamp |
| `ConfigService` | `CoroutineScope(SupervisorJob() + Dispatchers.IO)` | File watchers publish to `StateFlow`s |
| Views (`EditorView`, `TerminalView`) | `MainScope()` | Observe flows; never mutate directly |
| `MainViewModel` | `viewModelScope` with an **explicit `Dispatchers.IO`** on setup/environment work | `viewModelScope` defaults to `Dispatchers.Main`; long distro operations must not use it |

### 2.1 The single-writer editor invariant

**All buffer writes flow through `EditorState.applyEdit` on a single-threaded dispatcher.**

`limitedParallelism(1)` over `Dispatchers.IO` means writes are serialized onto one *logical* thread
even though the physical thread may vary between dispatches. That is sufficient: the underlying
`Buffer` and `PieceTable` are not thread-safe and rely on the caller for serialization.

Every mutator obeys it: `applyEdit`, `replaceAll`, `setSelection`, `scrollTo`, `addFold`,
`removeFold`, `toggleFold`.

`UndoManager.undo()` / `redo()` reach the same dispatcher through `runBlocking`, which means they
**block the calling thread** until the edit lands. Calling them from the main thread is a
deliberate, bounded stall, not an oversight.

### 2.2 State exposure

Subsystems publish state as `StateFlow` and events as `SharedFlow`. `EditorState` exposes
`snapshot`, `carets`, `viewport`, `folds`, `decorations`, `language`, `renderConfig`, `theme`,
`dirty`, an `events: SharedFlow<EditorEvent>`, and a one-shot `revealRequest` flow.

Publishing a value that a native parser is still mutating is the classic hazard here.
`TerminalSessionManager` avoids it explicitly by snapshotting `inputModes` after each `feed()` into
`inputModesSnapshot`, so the UI thread never calls into a live parser.

---

## 3. Native resource lifecycle

### 3.1 The rule

> Wrap JNI/native handles as `AutoCloseable` + `java.lang.ref.Cleaner`. Do not rely on finalizers.

`close()` is the deterministic path and calls `cleanable.clean()`, which is idempotent. The
`Cleaner` is only a safety net for objects that are dropped without `close()`.

### 3.2 The capture invariant

The cleanup lambda must capture **only the primitive handle**, never `this`:

```kotlin
// core/buffer/src/main/java/dev/blamspot/jcode/core/buffer/Buffer.kt
// The cleanup action must capture ONLY the primitive handle, never `this` — capturing the
// tracked object keeps it strongly reachable and permanently defeats the Cleaner.
val handle = nativeHandle
cleanable = cleaner.register(this) { if (handle != 0L) nativeCloseByHandle(handle) }
```

Capturing `this` makes the tracked object permanently reachable, so the `Cleaner` never fires and
the native allocation leaks for the process lifetime. This pattern is repeated in `Buffer`,
`Buffer.Snapshot`, `NativeHighlighter.Profile`, `PtyProcess`, `VtParser`, and every handle type in
`TsHandles.kt`.

### 3.3 The field-name invariant

`Buffer` and `Buffer.Snapshot` keep their handle in a field that **must be named `nativeHandle`** —
the C++ side looks it up by that literal name via `GetFieldID` (`Buffer.kt`, `getSnapshot`/
`setSnapshot`). Renaming the Kotlin field compiles fine and crashes at runtime.

### 3.4 Snapshot invalidation

`Buffer` caches its snapshot between edits. Invalidation **drops the reference** rather than closing
it, because a reader may already hold it and be mid-call; the `Cleaner` frees it after GC.

---

## 4. Memory pressure

`ResourceManager` (`@Singleton`, Hilt) implements `ComponentCallbacks2` and is the registry that
caches and pools attach to.

| Type | Role |
|---|---|
| `ResourceManager` | Registry + `pressure: StateFlow<MemoryPressure>`; `managedCache(...)` creates and self-registers. `onAppForegrounded()` clears the pressure reading |
| `ManagedCache` / `LruManagedCache` | `LinkedHashMap(accessOrder = true)` with a caller-supplied `sizeOf(K, V): Int` cost function, so eviction is by cost, not entry count. All operations `synchronized(map)` |
| `MemoryPressure` | Enum carrying a trim ratio |

### 4.1 What registers

Trimming only reclaims what has attached itself, so the list matters more than the mechanism:

| Registrant | Holds | Why it is worth trimming |
|---|---|---|
| `ExtensionIconCache` | Decoded icon `ImageBitmap`s, bounded at 64 | Replaced an unbounded map that pinned every icon ever decoded |
| `NativeExtensionLoader.LoadedPlugins` | One loaded extension APK per entry — a `DexClassLoader`, an `AssetManager` holding the archive open, and a `Context` | The heaviest thing the app caches. Eviction is safe rather than clever: a page already on screen holds its instance directly, so it costs a reload on the *next* open and nothing else |

Deliberately not registered: `WorkspaceManager.nodeMetaCache` is one small pair per project and
bounded in practice by how many projects exist, so attaching it would mean a new module dependency
for a few kilobytes.

### 4.2 Pressure levels

| Level | `trimRatio` |
|---|---|
| `NORMAL` | 0.0 |
| `BACKGROUND` | 0.3 |
| `MODERATE` | 0.5 |
| `LOW` | 0.7 |
| `CRITICAL` | 0.9 |

`onTrimMemory(level)` only ever **raises** pressure (`if (newPressure.ordinal > oldPressure.ordinal)`)
— it never lowers it automatically — but it calls `applyTrimming` unconditionally, so a repeated
callback at the same level keeps trimming.

---

## 5. Invariants and constraints

1. All buffer writes go through `EditorState.applyEdit` on the single-writer dispatcher.
2. `Cleaner` actions capture primitives only.
3. `Buffer`/`Snapshot` handle fields are named `nativeHandle`.
4. `viewModelScope` work that touches the distro, the filesystem, or a process must specify
   `Dispatchers.IO` explicitly.
5. Never publish a live native parser's state to the UI thread; publish a snapshot taken after the
   parser call returns.
6. `PtyProcess.awaitReadable(timeoutMs)` parks in `poll()` on the master fd. Linux `poll()` is
   **not** woken by another thread closing that fd, so callers must re-check session state on every
   wakeup; the timeout bounds the staleness.

---

## 6. Failure modes

| Failure | Symptom |
|---|---|
| Buffer mutated off the single-writer dispatcher | Piece-list corruption; wrong text, no exception |
| `Cleaner` lambda captures `this` | Silent native leak for the process lifetime |
| `nativeHandle` field renamed | `NoSuchFieldError` / crash on first snapshot |
| Blocking call on `viewModelScope`'s default `Dispatchers.Main` | UI freeze during rootfs download, extraction, or `waitFor()` |
| `close()` and `Cleaner` both racing a handle | Each wrapper registers a **capture-free** action over a copied handle and lets `Cleanable.clean()` be the single free path. `clean()` *runs* the action as well as deregistering it, so calling it alongside a manual free is a double free — which is exactly how `TsParser`/`TsTree` used to abort the process |

---

## 7. Known gaps

Four defects here were fixed at 1.6.2; what they were is kept because each is easy to reintroduce.

- **The trim mapping read ranges over constants that are not ordered by severity.** Android's levels
  are two families — `RUNNING_*` at 5/10/15 (foreground, system starving) and
  `UI_HIDDEN`/`BACKGROUND`/`MODERATE`/`COMPLETE` at 20/40/60/80 — so testing `level < 40` first
  swallowed all three `RUNNING_*` levels and `RUNNING_CRITICAL` trimmed 30% instead of 90%. `LOW`
  sat behind `level < 10` and could never be returned. Each constant is now matched exactly.
- **`pressure` only ever moved up.** One `CRITICAL` callback pinned the flow for the life of the
  process. It now reports the last level, and `onAppForegrounded()` (from `MainActivity.onResume`)
  clears it — `ComponentCallbacks2` has no "recovered" callback of its own.
- **`LruManagedCache` had two eviction paths and one of them was silent.** `removeEldestEntry`
  dropped entries without touching `currentSize` while `put` also called `trimToSize`, so the
  counter drifted above the real total and the cache over-evicted. `trimToSize` is now the only
  path.
- **`ResourcePool` and `NativeHandle` are gone.** Both were unused and both were broken —
  `acquire()` never decremented `currentSize`, so after `maxSize` releases the pool destroyed every
  object instead of pooling it; `NativeHandle` released twice and registered a `Cleaner` action
  capturing `this`, so its safety net could never fire.

Remaining, and honest:

- **The native wrappers still bypass `ResourceManager`.** `Buffer`, `VtParser` and `PtyProcess` each
  declare their own private `Cleaner`. That is correct as written — the capture-free form — just not
  shared.
- **WebViews are the largest thing still unmanaged.** `ScmWebViewHolder` and `VsixViewHolder` hold
  live `WebView`s per extension, which dwarf every registered cache. They are deliberately not
  trimmed: destroying one that is on screen blanks the user's panel, and this area has a history of
  blank-panel bugs. Wiring it needs a way to know which holders are currently composed.

---

## 8. References

- [Text buffer](../02-editor/01-text-buffer.md)
- [Editor state and undo](../02-editor/02-editor-state-and-undo.md)
- [Native layer and JNI](04-native-layer-and-jni.md)
- [Terminal, PTY and VT](../03-runtime/01-terminal-pty-and-vt.md)
