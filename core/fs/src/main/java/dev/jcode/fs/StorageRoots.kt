package dev.jcode.fs

import android.content.Context
import java.io.File

internal data class StorageRoots(
    val workspaceRoot: File,
    val projectsRoot: File,
    val usingFallbackLocation: Boolean,
)

internal fun resolveStorageRoots(context: Context): StorageRoots {
    val sharedWorkspace = File(DEFAULT_SHARED_WORKSPACE_ROOT)
    val sharedProjects = File(DEFAULT_SHARED_PROJECTS_ROOT)

    val sharedUsable = runCatching {
        sharedWorkspace.mkdirs()
        sharedProjects.mkdirs()
        sharedWorkspace.exists() && sharedProjects.exists() &&
            (sharedWorkspace.canWrite() || sharedWorkspace.parentFile?.canWrite() == true) &&
            (sharedProjects.canWrite() || sharedProjects.parentFile?.canWrite() == true)
    }.getOrDefault(false)

    if (sharedUsable) {
        return StorageRoots(
            workspaceRoot = sharedWorkspace,
            projectsRoot = sharedProjects,
            usingFallbackLocation = false,
        )
    }

    val appExternal = checkNotNull(context.getExternalFilesDir(null)) {
        "App-specific external storage is unavailable"
    }
    val fallbackWorkspace = File(appExternal, "JCode/workspaces/default")
    val fallbackProjects = File(appExternal, "JCode/projects")
    fallbackWorkspace.mkdirs()
    fallbackProjects.mkdirs()

    return StorageRoots(
        workspaceRoot = fallbackWorkspace,
        projectsRoot = fallbackProjects,
        usingFallbackLocation = true,
    )
}
