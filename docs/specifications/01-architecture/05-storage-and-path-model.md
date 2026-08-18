# Storage and path model

| | |
|---|---|
| **Status** | Implemented |
| **Modules** | `:core:fs`, `:core:distro`, `:feature:marketplace`, `:app` |
| **Primary sources** | core/fs/src/main/java/dev/jcode/fs/StorageRoots.kt, core/distro/src/main/java/dev/jcode/core/distro/DistroModels.kt, core/distro/src/main/java/dev/jcode/core/distro/ProotManager.kt, core/distro/src/main/java/dev/jcode/core/distro/RootfsManager.kt, core/fs/src/main/java/dev/jcode/fs/FsContract.kt, app/src/main/java/dev/jcode/provider/ProjectsDocumentsProvider.kt |
| **Verified against** | commit `cea581c`, 2026-08-09 |

---

## 1. Purpose and scope

JCode moves files across three naming domains — Android app-private storage, the guest Linux
filesystem, and the Storage Access Framework. Getting a path into the wrong domain is the single
most common class of bug in this codebase, so the boundaries are specified here in one place.

> **Invariant.** Never pass a host `/storage/emulated/0/...` or `/data/data/...` path to a
> distro-side tool, and never pass a `/workspace/...` path to a host-side file API.

---

## 2. Why projects live on app-private internal storage

Projects are **not** on shared external storage. They live under `context.filesDir`, which is
ext4. The reasoning is recorded verbatim in `StorageRoots.kt`:

- ext4 supports **symlinks and hardlinks** — npm/node need them for `node_modules/.bin`.
- ext4 is **exec-capable**; the shared `/storage` FUSE mount is not.
- No runtime storage permission is needed.
- Trade-off, stated in the same comment: **this location is wiped on uninstall or "Clear data"**.

### 2.1 The ext4 migration latch

Legacy installs had projects at `/storage/emulated/0/JCode/projects`. `WorkspaceManager`
copies them once and writes a marker file. Two independent modules compute the root, and both gate
on that marker:

| Module | Function | Behavior |
|---|---|---|
| `:core:fs` | `resolveStorageRoots(context)` | Returns the ext4 roots if `filesDir/workspace/.migrated-ext4` exists, else the legacy shared roots with `usingFallbackLocation = true` |
| `:core:distro` | `WorkspaceHostPaths.projectsRoot` | Same check, latched into a `@Volatile var migrated` once true |

Until the marker exists both keep resolving to the legacy shared path, so the app and the (still
shared-pointing) database rows agree on where files physically are. Binding `/workspace` to an
empty ext4 tree mid-migration would orphan every project.

The two modules **do not depend on each other**; the literal segment `"workspace/projects"` is
duplicated in both and must stay in sync. Both files carry a comment saying so.

---

## 3. On-device layout

```
/data/data/dev.blamspot.jcode/files/                 (context.filesDir — ext4, app-private)
├─ bin/proot/
│   ├─ lib/
│   │   ├─ libtalloc.so.2                   extracted from assets (mmap-only)
│   │   └─ libandroid-shmem.so              extracted from assets (SysV-shm shim)
│   └─ qemu/                                reserved for cross-arch user-mode QEMU
├─ distros/
│   └─ <distroId>/                          e.g. ubuntu-24.04
│       ├─ rootfs/                          the extracted Linux root
│       └─ metadata.json
├─ workspace/
│   ├─ .migrated-ext4                       migration marker (see §2.1)
│   ├─ projects/                            → bound to guest /workspace
│   └─ workspaces/default/
├─ sources/                                 → bound to guest /sources (clone staging)
├─ jcode-transfer/                          → bound to guest /jcode-transfer (extension I/O)
├─ extensions/                              installed .jext contents
├─ session/                                 SessionStore manifest + dirty-buffer files
├─ vdevice/<packageName>/                   redirected dataDir for a guest APK
├─ tmp/                                     downloads; tmp/proot, tmp/proot-fakeproc
└─ logs/
```

The proot **binary** is not here — it is a `jniLib` in `nativeLibraryDir`. See
[Native layer and JNI](04-native-layer-and-jni.md#27-proot-is-prebuilt-not-compiled).

---

## 4. Host ↔ guest translation

`WorkspaceHostPaths` (`core/distro/src/main/java/dev/jcode/core/distro/DistroModels.kt`) is the single translator. It is
initialized once at startup with `filesDir` (the path cannot be a compile-time constant because
`filesDir` varies by package and Android user).

| Guest mount | Constant | Host directory |
|---|---|---|
| `/workspace` | `WORKSPACE_GUEST` (= `DEFAULT_DISTRO_WORKDIR`) | `projectsRoot` — `filesDir/workspace/projects`, or the legacy shared path pre-migration |
| `/sources` | `SOURCES_GUEST` | `sourcesRoot(filesDir)` = `filesDir/sources` |
| `/jcode-transfer` | `TRANSFER_GUEST` | `transferRoot(filesDir)` = `filesDir/jcode-transfer` |

```kotlin
fun hostToGuest(hostPath: String): String   // returns hostPath unchanged if not under projectsRoot
fun guestToHost(guestPath: String): String  // returns guestPath unchanged if not under /workspace
```

Both are deliberately **pass-through on no match**, so an already-translated path is safe to pass
twice, and an unrelated absolute path is left alone.

`/sources` is always app-private ext4 regardless of the migration marker — it holds remote-repo
clones that have not yet been classified as a Project or a Workspace, and never existed on shared
storage.

`/workspace` is also the `HOME` a runtime tool gets inside the guest.

---

## 5. The SAF boundary

`:core:fs` abstracts both worlds behind one contract:

```kotlin
sealed interface FsPath { val displayName: String; val stableId: String }
  data class Local(val file: File) : FsPath
  data class Saf(val uri: Uri) : FsPath

interface Fs { list(...); read(...); write(...); watch(...) }
```

| Implementation | Backing | Watch strategy |
|---|---|---|
| `PosixFs` | `java.io.File` | `android.os.FileObserver` (event-driven) |
| `SafFs` | `DocumentFile` / `ContentResolver` | **2-second poll** comparing a directory fingerprint — SAF has no change notification |

`SafFs.snapshotDirectory()` issues a single batched `ContentResolver.query` over
`buildChildDocumentsUriUsingTree` rather than N+1 per-child `DocumentFile` calls, falling back to
the slow path if a provider rejects the batched form.

`PosixFs.list()` retries `File.listFiles()` up to 4 times with a 50 ms delay: it returns `null` for
both "not a directory" and a transient I/O error under concurrent filesystem churn, and treating
the second case as the first blanked the Explorer.

### 5.1 SAF folders cannot be bound into the guest

proot binds host directory paths. A SAF tree URI has no stable filesystem path, so a SAF-backed
folder cannot be bind-mounted. `WorkspaceManager.resolveManageable` / `safTreeToLocal` remap a
SAF tree URI back to a local `File` when the provider is the local filesystem; when that fails, the
folder is editable but not buildable inside the distro.

### 5.2 Exposing projects back to the system

`ProjectsDocumentsProvider` publishes `filesDir/workspace` as a browsable SAF root ("JCode
Projects") so the system Files app can see projects that are otherwise app-private. Files never
leave ext4. The authority is `${applicationId}.documents` (per-variant), and it is guarded by
`android.permission.MANAGE_DOCUMENTS`, which only DocumentsUI holds.

---

## 6. Per-folder role marker

A folder's role as a **project** or a **workspace** is not stored in the database. It is derived at
runtime from a YAML file inside the folder:

```
<folder>/.jcode/<folderName>.yaml     →  type: project | workspace
```

`WorkspaceManager.folderNeedsType` / `addFolderWithType` / `upsertTypeLine` write it, and
`parseNodeMeta` reads it into `WorkspaceNodeType`. See
[Workspaces and projects](../05-workspace/01-workspaces-and-projects.md).

---

## 7. Invariants and constraints

1. `"workspace/projects"` is duplicated in `:core:fs` `StorageRoots` and `:core:distro`
   `WorkspaceHostPaths`. Change both or neither.
2. Every host↔guest crossing goes through `WorkspaceHostPaths`; no ad-hoc string surgery.
3. `WorkspaceHostPaths.init(filesDir)` must run before any translation. Reads before `init` fall
   back to `DEFAULT_PROJECTS_HOST_PATH` (`/storage/emulated/0/JCode/projects`) so the result is
   still sane rather than empty.
4. The migration marker is one-way. Nothing clears it.
5. Path-bearing log lines are redacted — see
   [Security and privacy](../09-platform/04-security-and-privacy.md).

---

## 8. Failure modes

| Failure | Effect |
|---|---|
| Marker written but the copy did not finish | Projects appear empty; the marker is the commit point, so it is written only on success |
| App uninstalled or data cleared | Everything under `filesDir` is gone: projects, rootfs, extensions, sessions |
| SAF permission revoked | The folder becomes unlistable; `SafPermissionStore` holds the remembered tree URIs and the grant must be re-taken |
| Guest path leaked to a host API | `FileNotFoundException` on `/workspace/...`, or a silently wrong file |
| Rootfs on a device with little free space | The `CheckStorage` wizard step gates on roughly 2 GB free |

---

## 9. References

- [Workspaces and projects](../05-workspace/01-workspaces-and-projects.md)
- [Embedded Linux runtime](../03-runtime/03-embedded-linux-runtime.md)
- [File format index](../09-platform/01-file-format-index.md)
