# Workspaces and projects

| | |
|---|---|
| **Status** | Implemented |
| **Modules** | `:core:fs`, `:feature:explorer`, `:app` |
| **Primary sources** | core/fs/src/main/java/dev/blamspot/jcode/fs/WorkspaceManager.kt (755 lines), core/fs/src/main/java/dev/blamspot/jcode/fs/WorkspaceModel.kt, core/fs/src/main/java/dev/blamspot/jcode/fs/WorkspacePersistence.kt, core/fs/src/main/java/dev/blamspot/jcode/fs/StorageRoots.kt, core/fs/src/main/java/dev/blamspot/jcode/fs/FsContract.kt, core/fs/src/main/java/dev/blamspot/jcode/fs/FsOperations.kt (494 lines), core/fs/src/main/java/dev/blamspot/jcode/fs/FsImplementations.kt |
| **Verified against** | commit `cea581c`, 2026-08-09 |

---

## 1. Purpose and scope

The unit-of-work model: what a workspace is, what a project is, how they are persisted, and how the
Explorer's file operations work across both local and SAF storage.

---

## 2. The two node types

```kotlin
enum class WorkspaceNodeType { Project, Workspace }
```

| Type | Meaning |
|---|---|
| `Project` | A **buildable** folder, optionally scaffolded from a template |
| `Workspace` | A plain **container** folder that holds projects |

> **The role is not stored in the database.** It is derived at load time from the folder's own
> `.jcode/<folderName>.yaml` `type:` field. That makes a folder self-describing: moving it, copying
> it, or opening it on another install preserves its role.

`folderNeedsType(path)` asks whether the marker is missing; `addFolderWithType(path, nodeType)`
writes it via `upsertTypeLine`; `parseNodeMeta(content)` reads it back (along with the optional
`templateId`).

Because a workspace can contain workspaces, navigation is a tree: `enterWorkspaceFolder(project)`
drills in, `WorkspaceCrumb` records the trail, `navigateToWorkspace(id)` and `navigateBack()` move
along it.

---

## 3. Persistence — Room

```kotlin
@Entity("workspaces")
data class WorkspaceEntity(id, name, rootPath, lastOpened)

@Entity("projects", foreignKeys = [ForeignKey(WorkspaceEntity, "id" → "workspaceId", CASCADE)],
        indices = [Index("workspaceId")])
data class ProjectEntity(id, workspaceId, kind, location, name, distroBindTarget, order)

@Entity("recents")
data class RecentEntity(uri /* PK */, kind, lastOpened, pinned)
```

`WorkspaceDatabase` is **version 1** with `fallbackToDestructiveMigration`.

Domain models are separate and `@Immutable` for Compose: `Workspace`, `Project`, `WorkspaceCrumb`,
plus `WorkspaceWithProjects` (`@Embedded` + `@Relation`) and `toDomain()`, which sorts projects by
`order`.

```kotlin
enum class ProjectKind { Local, Saf }
```

`Project.fsPath` maps `Local` → `FsPath.Local(File(location))` and `Saf` → `FsPath.Saf(Uri.parse(location))`.
`Project.nodeType` and `Project.templateId` are derived, not persisted.

### 3.1 The raw-UPDATE invariant

`updateWorkspaceRootPath(id, rootPath)` is a raw `UPDATE` statement and is deliberately **not** an
`@Insert(onConflict = REPLACE)`. `REPLACE` on the parent row deletes and reinserts it, and the
`CASCADE` foreign key would take every project with it — which is exactly what happens during the
ext4 migration, when the root path changes.

### 3.2 SAF permissions

`SafPermissionStore` (DataStore-backed) remembers granted tree URIs so a SAF folder stays usable
across restarts. Hilt module `FsModule` provides the database, the DAO, and the preferences
DataStore.

---

## 4. Storage roots and migration

Covered in detail in
[Storage and path model](../01-architecture/05-storage-and-path-model.md). Summary:

- Roots resolve to app-private ext4 (`filesDir/workspace/{projects,workspaces/default}`) **once**
  `filesDir/workspace/.migrated-ext4` exists.
- Until then they resolve to the legacy shared paths
  (`DEFAULT_SHARED_PROJECTS_ROOT = /storage/emulated/0/JCode/projects`,
  `DEFAULT_SHARED_WORKSPACE_ROOT = /storage/emulated/0/JCode/workspaces/default`).
- `migrateProjectsToExt4IfNeeded()` performs the one-time copy, rewrites project `location` rows
  through a `remap` function, and writes the marker last, so an interrupted migration is retried
  rather than half-applied.

---

## 5. `WorkspaceManager` operations

| Group | Members |
|---|---|
| Lifecycle | `openWorkspace(id)`, `restoreWorkspace(id)`, `closeWorkspace()`, `workspaceExists(id)`, `ensureDefaultWorkspace()`, `ensureDefaultWorkspaceId()` |
| Adding | `addFolder(path)`, `addFolderWithType(path, nodeType)`, `createNode(...)`, `createNodeIn(...)`, `adoptFolderIn(workspaceId, staged, nodeType)` |
| Navigation | `enterWorkspaceFolder(project)`, `enterWorkspaceFolder(folder, displayName)`, `enterFolderAsWorkspace(path)`, `navigateToWorkspace(id)`, `navigateBack()`, `pushCrumb(id, name)` |
| Editing | `renameProject(projectId, newName)`, `removeProject(projectId)` |
| Storage | `refreshStorageRoots()`, `isOnManagedStorage(path)`, `isManagedRoot(path)`, `resolveManageable(path)`, `safTreeToLocal(uri)` |
| Queries | `isWorkspaceFolder(path)`, `folderNeedsType(path)`, `fsFor(path)`, `sanitizedFolderName(name)` |

`adoptFolderIn` is the clone-staging hand-off: a repository cloned into `/sources` is classified by
the user and moved under the projects root as a Project or a Workspace.

`resolveManageable` remaps a SAF tree URI back to a local `File` when the provider is the local
filesystem, which is what lets an "Open folder" via SAF still be bind-mounted into the distro. When
the remap fails the folder remains editable but not buildable.

All disk work runs on `Dispatchers.IO`.

---

## 6. The `Fs` abstraction

```kotlin
sealed interface FsPath { val displayName: String; val stableId: String }
  data class Local(val file: File) : FsPath
  data class Saf(val uri: Uri) : FsPath

enum class FsKind { File, Directory }
data class FsNode(val path: FsPath, val name: String, val kind: FsKind,
                  val sizeBytes: Long, val modifiedAtMillis: Long)

enum class FsWatchEventType { Created, Modified, Deleted, FullRescan }
data class FsWatchEvent(val root: FsPath, val affectedPath: FsPath?, val type: FsWatchEventType)

interface Fs { list(...); read(...); write(...); watch(...) }
```

`FsPath.stableId` is the absolute host path (or the URI string) and is the key used by Explorer
decorations and tab identity.

| Implementation | Watch | Notes |
|---|---|---|
| `PosixFs` | `android.os.FileObserver` — event-driven | `list()` retries `listFiles()` up to 4× with 50 ms delay |
| `SafFs` | **2-second poll** of a directory fingerprint | SAF has no change notification |

### 6.1 The `listFiles()` retry

`File.listFiles()` returns `null` for **both** "not a directory" and a transient I/O error under
concurrent filesystem churn. Treating the second case as the first blanked the Explorer, so the
POSIX implementation retries before concluding the path is not a directory.

### 6.2 The SAF batched query

`SafFs.snapshotDirectory()` issues a **single** `ContentResolver.query` over
`buildChildDocumentsUriUsingTree` rather than N+1 per-child `DocumentFile` calls, falling back to
the slow path if a provider rejects the batched form.

---

## 7. File operations

`FsOperations.kt` is a set of free functions, each with a POSIX and a SAF branch
(`*Posix` / `*Saf` private variants):

`createFile`, `createDirectory`, `renameFile`, `deletePermanently`, `copyFileOrDir`,
`importContentUris`, `exportFileToUri`, `copyLocalTreeToDocumentTree`, `scanFolderForImport`,
`copyFolderToLocal`, `folderDisplayName`.

`FolderScan(fileCount, totalBytes)` is returned by `scanFolderForImport` so the UI can show a
determinate import progress bar (`ImportPhase.Scanning` then `ImportPhase.Copying`).

Deletes route through the **Trash** (`Trash.kt`) when `TRASH_ENABLED` is on, and through
`deletePermanently` when it is not. The bin is app-private — `<filesDir>/trash/<id>/` holding an
`entry.json` and a `data/<name>` payload — rather than a folder inside the project, so `git clean`
cannot destroy it and a trashed build directory never appears in `git status`. A local project and
the bin share a volume, so taking something in is a rename; SAF entries are copied. Restoring lands
beside an occupant under a numbered name rather than overwriting it.

The bin is read from `TrashPage` (`EditorPageKind.Trash`), an editor page rather than a dialog: what
decides whether an entry is the one to restore is its contents, so the list picks and the pane beside
it previews — text, images, and the files inside a trashed folder, which can themselves be opened.
`Trash.listInside` caps its listing and `Trash.read` caps its bytes; both exist to show someone what
they are about to restore, not to reproduce it.

---

## 8. Explorer

`ExplorerView` (1,044 lines) plus `TreeViewModel` (435). Notable behavior:

- Live updates driven by `Fs.watch` on **visible directories only**, debounced.
- Extension-contributed VCS badges and context actions through `LocalExplorerScmUi` — see
  [Search and source control](../04-language-services/03-search-and-source-control.md).
- Cut and Delete are suppressed on the project root row (`LocalProjectRootId`).
- Exclude rules with a grey-out or hide effect (`EXCLUDE_EFFECT`, `HIDDEN_ROOT_MODE`,
  `HIDDEN_ROOT_PATTERNS`, default `".jcode"`) — see
  [Settings reference](../06-workbench/04-settings-reference.md).

---

## 9. Invariants and constraints

1. Node type comes from the folder's YAML, never from the database.
2. `updateWorkspaceRootPath` stays a raw `UPDATE`; a `REPLACE` cascades away every project.
3. The ext4 migration marker is written **last**, after a successful copy.
4. `FsPath.stableId` is the identity used across Explorer, tabs and decorations.
5. `listFiles() == null` is not proof that a path is not a directory.
6. SAF folders cannot be bind-mounted unless `safTreeToLocal` resolves them.
7. The project root row cannot be moved or deleted from the tree.

---

## 10. Failure modes

| Failure | Effect |
|---|---|
| Migration interrupted | Marker absent, so the legacy roots stay authoritative and the copy is retried |
| SAF permission revoked | Folder unlistable; the grant must be re-taken. `SafPermissionStore` holds the URI |
| Folder opened with no `.jcode/<name>.yaml` | `folderNeedsType` is true; the UI asks Project or Workspace |
| Room schema change without a migration | `fallbackToDestructiveMigration` **drops the database** — workspaces and projects must be re-added |
| Rapid filesystem churn during a listing | Retried up to 4× before treating the path as unreadable |

---

## 11. Known gaps

- Room is at version 1 with destructive fallback: any schema change loses the user's workspace list.
- `SafFs` polls every 2 seconds; a large SAF tree is correspondingly costly to watch.
- `RecentEntity.pinned` is modelled but the recents UI surface is minimal.

---

## 12. References

- [Storage and path model](../01-architecture/05-storage-and-path-model.md)
- [Configuration model](02-configuration-model.md)
- [Search and source control](../04-language-services/03-search-and-source-control.md)
- [File format index](../09-platform/01-file-format-index.md)
