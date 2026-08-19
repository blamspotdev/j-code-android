package dev.jcode.fs

import android.content.Context
import java.io.File

internal data class StorageRoots(
    val workspaceRoot: File,
    val projectsRoot: File,
    val usingFallbackLocation: Boolean,
)

internal fun resolveStorageRoots(context: Context): StorageRoots {
    // Projects/workspaces live on app-private INTERNAL storage (context.filesDir), which is ext4:
    // it supports symlinks + hardlinks (needed by npm/node's node_modules/.bin) and is exec-capable,
    // unlike the shared /storage FUSE mount, and needs no runtime storage permission.
    // Trade-off: this location is wiped on app uninstall / "Clear data".
    //
    // Existing shared /storage/emulated/0/JCode projects are copied here once by
    // WorkspaceManager.migrateProjectsToExt4IfNeeded, which writes the ".migrated-ext4" marker on
    // success. Until that marker exists we KEEP resolving to the legacy shared root, so the app and
    // the (still shared-pointing) DB rows agree on where projects physically live — otherwise a not-
    // yet-migrated install would bind /workspace to an empty ext4 tree and orphan every project.
    // The "workspace/projects" segment MUST match core:distro WorkspaceHostPaths.
    val base = File(context.filesDir, "workspace")
    if (File(base, ".migrated-ext4").exists()) {
        val workspaceRoot = File(base, "workspaces/default")
        val projectsRoot = File(base, "projects")
        runCatching {
            workspaceRoot.mkdirs()
            projectsRoot.mkdirs()
        }
        return StorageRoots(workspaceRoot = workspaceRoot, projectsRoot = projectsRoot, usingFallbackLocation = false)
    }
    return StorageRoots(
        workspaceRoot = File(DEFAULT_SHARED_WORKSPACE_ROOT),
        projectsRoot = File(DEFAULT_SHARED_PROJECTS_ROOT),
        usingFallbackLocation = true,
    )
}
