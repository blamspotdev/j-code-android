package dev.jcode.fs

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workspaceDao: WorkspaceDao,
    private val safPermissionStore: SafPermissionStore,
    private val posixFs: PosixFs,
    private val safFs: SafFs,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val currentWorkspaceId = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
    private val storageRoots by lazy { resolveStorageRoots(context) }

    val currentWorkspace: StateFlow<Workspace?> = currentWorkspaceId
        .flatMapLatest { workspaceId ->
            if (workspaceId == null) {
                flowOf(null)
            } else {
                workspaceDao.observeWorkspace(workspaceId).map { relation -> relation?.toDomain() }
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val rememberedSafTreeUris: StateFlow<Set<Uri>> = safPermissionStore.rememberedTrees
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    init {
        scope.launch {
            val workspace = ensureDefaultWorkspace()
            currentWorkspaceId.value = workspace.id
        }
    }

    suspend fun openWorkspace(workspaceId: Long) {
        workspaceDao.updateWorkspaceLastOpened(workspaceId, System.currentTimeMillis())
        currentWorkspaceId.value = workspaceId
    }

    suspend fun closeWorkspace() {
        currentWorkspaceId.value = null
    }

    suspend fun createProjectInDefaultLocation(name: String): Project {
        val directory = File(storageRoots.projectsRoot, name)
        return addProject(FsPath.Local(directory))
    }

    suspend fun addProject(path: FsPath): Project {
        val workspaceId = currentWorkspaceId.value ?: ensureDefaultWorkspace().id
        val now = System.currentTimeMillis()
        val name = sanitizedProjectName(path.displayName.ifBlank { "project-$now" })

        validateWritable(path)
        createProjectScaffold(path)

        val project = ProjectEntity(
            workspaceId = workspaceId,
            kind = when (path) {
                is FsPath.Local -> ProjectKind.Local
                is FsPath.Saf -> ProjectKind.Saf
            },
            location = when (path) {
                is FsPath.Local -> path.file.absolutePath
                is FsPath.Saf -> path.uri.toString()
            },
            name = name,
            distroBindTarget = uniqueBindTarget(workspaceId, name),
            order = workspaceDao.projectCount(workspaceId),
        )

        val projectId = workspaceDao.upsertProject(project)
        workspaceDao.upsertRecent(
            RecentEntity(
                uri = project.location,
                kind = project.kind,
                lastOpened = now,
                pinned = false,
            )
        )

        if (path is FsPath.Saf) {
            safPermissionStore.remember(path.uri)
        }

        workspaceDao.updateWorkspaceLastOpened(workspaceId, now)
        currentWorkspaceId.value = workspaceId

        return Project(
            id = projectId,
            kind = project.kind,
            location = project.location,
            name = project.name,
            distroBindTarget = project.distroBindTarget,
            order = project.order,
        )
    }

    suspend fun removeProject(projectId: Long) {
        workspaceDao.deleteProject(projectId)
    }

    fun fsFor(path: FsPath): Fs = when (path) {
        is FsPath.Local -> posixFs
        is FsPath.Saf -> safFs
    }

    suspend fun ensureDefaultWorkspace(): Workspace {
        return withContext(Dispatchers.IO) {
            storageRoots.workspaceRoot.mkdirs()
            storageRoots.projectsRoot.mkdirs()

            val now = System.currentTimeMillis()
            val existing = workspaceDao.getMostRecentWorkspace()
            val workspaceId = if (existing == null) {
                workspaceDao.upsertWorkspace(
                    WorkspaceEntity(
                        name = "Default Workspace",
                        rootPath = storageRoots.workspaceRoot.absolutePath,
                        lastOpened = now,
                    )
                )
            } else {
                existing.id
            }

            workspaceDao.observeWorkspace(workspaceId).first()?.toDomain()
                ?: Workspace(
                    id = workspaceId,
                    name = existing?.name ?: "Default Workspace",
                    rootPath = existing?.rootPath ?: storageRoots.workspaceRoot.absolutePath,
                    lastOpened = existing?.lastOpened ?: now,
                    projects = emptyList(),
                )
        }
    }

    private suspend fun validateWritable(path: FsPath) {
        when (path) {
            is FsPath.Local -> {
                val file = path.file
                if (!file.exists()) {
                    file.mkdirs()
                }
                check(file.exists() && file.isDirectory) { "Project path must be a directory: ${file.path}" }
                check(file.canWrite() || file.parentFile?.canWrite() == true) { "Project path is not writable: ${file.path}" }
            }

            is FsPath.Saf -> {
                val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, path.uri)
                    ?: error("Unable to open SAF tree: ${path.uri}")
                check(root.canWrite()) { "SAF tree is not writable: ${path.uri}" }
            }
        }
    }

    private suspend fun createProjectScaffold(path: FsPath) {
        when (path) {
            is FsPath.Local -> withContext(Dispatchers.IO) {
                val jcodeDir = File(path.file, ".jcode").apply { mkdirs() }
                File(jcodeDir, "project.yaml").takeIf { !it.exists() }?.writeText("name: ${path.file.name}\n")
            }

            is FsPath.Saf -> withContext(Dispatchers.IO) {
                val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, path.uri)
                    ?: error("Unable to open SAF tree: ${path.uri}")
                val jcodeDir = tree.findFile(".jcode") ?: tree.createDirectory(".jcode")
                val projectFile = jcodeDir?.findFile("project.yaml") ?: jcodeDir?.createFile("application/x-yaml", "project.yaml")
                context.contentResolver.openOutputStream(projectFile?.uri ?: error("Unable to create project.yaml"), "wt")?.use { stream ->
                    stream.write("name: ${tree.name ?: "External Project"}\n".toByteArray())
                }
            }
        }
    }

    private suspend fun uniqueBindTarget(workspaceId: Long, name: String): String {
        val existing = workspaceDao.observeWorkspace(workspaceId).first()?.projects.orEmpty().map { it.distroBindTarget }.toSet()
        val base = "/workspace/${sanitizedProjectName(name)}"
        var candidate = base
        var suffix = 1
        while (candidate in existing) {
            candidate = "$base-$suffix"
            suffix += 1
        }
        return candidate
    }

    private fun sanitizedProjectName(name: String): String {
        return name.lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')
            .ifBlank { "project" }
    }
}
