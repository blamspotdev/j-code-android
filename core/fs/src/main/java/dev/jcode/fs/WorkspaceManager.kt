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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val currentWorkspaceId = MutableStateFlow<Long?>(null)

    @Volatile
    private var storageRootsCache: StorageRoots? = null
    private val storageRoots: StorageRoots
        get() = storageRootsCache ?: resolveStorageRoots(context).also { storageRootsCache = it }

    /** Id of the root "Default Workspace" (whose projects live in [StorageRoots.projectsRoot]). */
    @Volatile
    private var defaultWorkspaceId: Long? = null

    /** Navigation trail from the Default Workspace down to the currently-open container. */
    private val _breadcrumb = MutableStateFlow<List<WorkspaceCrumb>>(emptyList())
    val breadcrumb: StateFlow<List<WorkspaceCrumb>> = _breadcrumb.asStateFlow()

    val currentWorkspace: StateFlow<Workspace?> = currentWorkspaceId
        .flatMapLatest { workspaceId ->
            if (workspaceId == null) {
                flowOf(null)
            } else {
                workspaceDao.observeWorkspace(workspaceId).map { relation -> relation?.toDomain()?.withNodeTypes() }
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val rememberedSafTreeUris: StateFlow<Set<Uri>> = safPermissionStore.rememberedTrees
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** Recently opened folders (projects/workspaces), most-recent first, pinned on top. */
    val recents: StateFlow<List<RecentEntity>> = workspaceDao.observeRecents(12)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch {
            val workspace = ensureDefaultWorkspace()
            _breadcrumb.value = listOf(WorkspaceCrumb(workspace.id, workspace.name))
            currentWorkspaceId.value = workspace.id
        }
    }

    /**
     * Re-resolve the storage roots after a runtime storage-permission grant. The roots are cached
     * from before the grant (the shared /JCode location was unwritable then, so the app-private
     * fallback won); this re-anchors the Default Workspace on the shared location.
     */
    suspend fun refreshStorageRoots() {
        val old = storageRootsCache
        val fresh = resolveStorageRoots(context)
        storageRootsCache = fresh
        if (old != null && old.workspaceRoot == fresh.workspaceRoot) return
        val previousDefaultId = defaultWorkspaceId
        val workspace = ensureDefaultWorkspace()
        if (currentWorkspaceId.value == null || currentWorkspaceId.value == previousDefaultId) {
            _breadcrumb.value = listOf(WorkspaceCrumb(workspace.id, workspace.name))
            currentWorkspaceId.value = workspace.id
        }
    }

    suspend fun openWorkspace(workspaceId: Long) {
        workspaceDao.updateWorkspaceLastOpened(workspaceId, System.currentTimeMillis())
        currentWorkspaceId.value = workspaceId
    }

    /** True if a workspace row with this id still exists (it may have been deleted since last session). */
    suspend fun workspaceExists(workspaceId: Long): Boolean = withContext(Dispatchers.IO) {
        workspaceDao.observeWorkspace(workspaceId).first() != null
    }

    suspend fun closeWorkspace() {
        currentWorkspaceId.value = null
    }

    suspend fun addFolder(path: FsPath): Project {
        validateWritable(path)
        createProjectScaffold(path)
        return registerProject(path)
    }

    /**
     * Create a typed top-level folder under the local projects root and register it. A
     * [WorkspaceNodeType.Workspace] is a plain container; a [WorkspaceNodeType.Project] records its
     * [templateId] in `.jcode/<name>.yaml`. The returned project carries the derived type/template
     * and its `distroBindTarget` (the runtime path templates scaffold into).
     */
    suspend fun createNode(
        name: String,
        nodeType: WorkspaceNodeType,
        templateId: String?,
    ): Project {
        // Nest inside the open workspace's folder; the Default Workspace keeps using the flat
        // projects root (its rootPath is workspaces/default, which must not hold projects).
        val active = currentWorkspace.value
        val base = if (active != null && active.id != defaultWorkspaceId) {
            File(active.rootPath)
        } else {
            storageRoots.projectsRoot
        }
        val directory = File(base, sanitizedFolderName(name))
        val path = FsPath.Local(directory)
        validateWritable(path)
        writeNodeConfig(directory, nodeType, templateId)
        return registerProject(path).copy(nodeType = nodeType, templateId = templateId)
    }

    /**
     * Open a Workspace folder as the current container: find/create its [WorkspaceEntity], register
     * any child folders it already holds, switch the current workspace, and push a breadcrumb.
     * Returns the workspace id. SAF containers open but are not scanned (best-effort).
     */
    suspend fun enterWorkspaceFolder(project: Project): Long {
        val folder = (project.fsPath as? FsPath.Local)?.file
            ?: return (currentWorkspaceId.value ?: ensureDefaultWorkspace().id)
        return enterWorkspaceFolder(folder, project.name)
    }

    suspend fun enterWorkspaceFolder(folder: File, displayName: String): Long = withContext(Dispatchers.IO) {
        val rootPath = folder.absolutePath
        val workspaceId = workspaceDao.getWorkspaceByRootPath(rootPath)?.id
            ?: workspaceDao.upsertWorkspace(
                WorkspaceEntity(name = displayName, rootPath = rootPath, lastOpened = System.currentTimeMillis()),
            )

        // Add-only reconcile: register child folders not already tracked under this workspace.
        val existing = workspaceDao.getProjectLocations(workspaceId).toSet()
        folder.listFiles { f -> f.isDirectory && f.name != ".jcode" && f.name != ".trash" }
            ?.sortedBy { it.name.lowercase() }
            ?.forEach { childDir ->
                val location = childDir.absolutePath
                if (location !in existing) {
                    workspaceDao.upsertProject(
                        ProjectEntity(
                            workspaceId = workspaceId,
                            kind = ProjectKind.Local,
                            location = location,
                            name = sanitizedFolderName(childDir.name),
                            distroBindTarget = uniqueBindTarget(workspaceId, childDir.name),
                            order = workspaceDao.projectCount(workspaceId),
                        ),
                    )
                }
            }

        openWorkspace(workspaceId)
        pushCrumb(workspaceId, displayName)
        workspaceId
    }

    /**
     * Resolve a SAF tree under primary external storage to a Local path so picked folders that live
     * inside our managed storage can drive the runtime; non-primary SAF trees pass through unchanged.
     */
    fun resolveManageable(path: FsPath): FsPath = when (path) {
        is FsPath.Local -> path
        is FsPath.Saf -> safTreeToLocal(path.uri)?.let { FsPath.Local(it) } ?: path
    }

    private fun safTreeToLocal(uri: Uri): File? = runCatching {
        val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
        val parts = docId.split(":", limit = 2)
        if (parts.getOrNull(0).equals("primary", ignoreCase = true)) {
            File(android.os.Environment.getExternalStorageDirectory(), parts.getOrNull(1).orEmpty())
        } else {
            null
        }
    }.getOrNull()?.takeIf { it.isDirectory }

    /** True if the folder is tagged as a Workspace container in its `.jcode` config. */
    suspend fun isWorkspaceFolder(path: FsPath): Boolean = withContext(Dispatchers.IO) {
        parseNodeMeta(readFolderConfig(path)).first == WorkspaceNodeType.Workspace
    }

    /**
     * Stamp [path] as a Workspace and enter it as the current container (re-opening an existing
     * workspace or declaring a fresh one). Returns the workspace id, or null if the folder isn't a
     * Local path we can manage as a container (e.g. a non-primary SAF tree).
     */
    suspend fun enterFolderAsWorkspace(path: FsPath): Long? {
        setFolderType(path, WorkspaceNodeType.Workspace)
        val folder = (resolveManageable(path) as? FsPath.Local)?.file ?: return null
        return enterWorkspaceFolder(folder, folder.name)
    }

    /** Rename a Local project folder (and its `.jcode/<name>.yaml`) and update the DB row. */
    suspend fun renameProject(projectId: Long, newName: String): Boolean = withContext(Dispatchers.IO) {
        val entity = workspaceDao.getProject(projectId) ?: return@withContext false
        if (entity.kind != ProjectKind.Local) return@withContext false
        val sanitized = sanitizedFolderName(newName)
        if (sanitized == entity.name) return@withContext false
        val oldDir = File(entity.location)
        val newDir = File(oldDir.parentFile, sanitized)
        if (newDir.exists()) return@withContext false
        if (!oldDir.renameTo(newDir)) return@withContext false

        val jcodeDir = File(newDir, ".jcode")
        File(jcodeDir, "${oldDir.name}.yaml").takeIf { it.isFile }?.renameTo(File(jcodeDir, "$sanitized.yaml"))

        workspaceDao.updateProject(projectId, sanitized, newDir.absolutePath)
        nodeMetaCache.remove(oldDir.absolutePath)
        nodeMetaCache.remove(newDir.absolutePath)
        true
    }

    // --- Workspace navigation (breadcrumb) ---

    private fun pushCrumb(id: Long, name: String) {
        val trail = _breadcrumb.value
        if (trail.lastOrNull()?.id == id) return
        // If we're stepping back into an ancestor, trim to it instead of duplicating.
        val existingIdx = trail.indexOfFirst { it.id == id }
        _breadcrumb.value = if (existingIdx >= 0) trail.subList(0, existingIdx + 1) else trail + WorkspaceCrumb(id, name)
    }

    suspend fun navigateToWorkspace(id: Long) {
        val idx = _breadcrumb.value.indexOfFirst { it.id == id }
        if (idx >= 0) _breadcrumb.value = _breadcrumb.value.subList(0, idx + 1)
        openWorkspace(id)
    }

    suspend fun navigateBack() {
        val trail = _breadcrumb.value
        if (trail.size > 1) navigateToWorkspace(trail[trail.size - 2].id)
    }

    private suspend fun registerProject(path: FsPath): Project {
        val workspaceId = currentWorkspaceId.value ?: ensureDefaultWorkspace().id
        val now = System.currentTimeMillis()
        val name = sanitizedFolderName(path.displayName.ifBlank { "project-$now" })

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
            val rootPath = storageRoots.workspaceRoot.absolutePath
            val existing = workspaceDao.getWorkspaceByRootPath(rootPath)
            val workspaceId = existing?.id ?: workspaceDao.upsertWorkspace(
                WorkspaceEntity(
                    name = "Default Workspace",
                    rootPath = rootPath,
                    lastOpened = now,
                )
            )
            defaultWorkspaceId = workspaceId

            workspaceDao.observeWorkspace(workspaceId).first()?.toDomain()
                ?: Workspace(
                    id = workspaceId,
                    name = existing?.name ?: "Default Workspace",
                    rootPath = rootPath,
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
                val configFileName = "${path.file.name}.yaml"
                File(jcodeDir, configFileName).takeIf { !it.exists() }?.writeText("name: ${path.file.name}\n")
            }

            is FsPath.Saf -> withContext(Dispatchers.IO) {
                val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, path.uri)
                    ?: error("Unable to open SAF tree: ${path.uri}")
                val jcodeDir = tree.findFile(".jcode") ?: tree.createDirectory(".jcode")
                val folderName = tree.name ?: "External Folder"
                val configFileName = "$folderName.yaml"
                val projectFile = jcodeDir?.findFile(configFileName) ?: jcodeDir?.createFile("application/x-yaml", configFileName)
                context.contentResolver.openOutputStream(projectFile?.uri ?: error("Unable to create $configFileName"), "wt")?.use { stream ->
                    stream.write("name: $folderName\n".toByteArray())
                }
            }
        }
    }

    private suspend fun writeNodeConfig(
        dir: File,
        nodeType: WorkspaceNodeType,
        templateId: String?,
    ) = withContext(Dispatchers.IO) {
        val jcodeDir = File(dir, ".jcode").apply { mkdirs() }
        val typeValue = if (nodeType == WorkspaceNodeType.Workspace) "workspace" else "project"
        val content = buildString {
            append("name: ${dir.name}\n")
            append("type: $typeValue\n")
            if (templateId != null) append("template: $templateId\n")
        }
        File(jcodeDir, "${dir.name}.yaml").writeText(content)
        nodeMetaCache.remove(dir.absolutePath)
    }

    private suspend fun uniqueBindTarget(workspaceId: Long, name: String): String {
        val existing = workspaceDao.observeWorkspace(workspaceId).first()?.projects.orEmpty().map { it.distroBindTarget }.toSet()
        val base = "/workspace/${sanitizedFolderName(name)}"
        var candidate = base
        var suffix = 1
        while (candidate in existing) {
            candidate = "$base-$suffix"
            suffix += 1
        }
        return candidate
    }

    private fun sanitizedFolderName(name: String): String {
        return name.lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')
            .ifBlank { "project" }
    }

    // --- Folder type detection + assignment (.jcode is the source of truth; not stored in the DB) ---

    /** Derived (nodeType, templateId) per project location. The workspace flow re-derives node types
     *  on every emission; caching keeps the (flaky, IPC-bound) SAF `.jcode` read from running each
     *  time, so a folder's type stays stable instead of flickering. Invalidated whenever we write it. */
    private val nodeMetaCache = java.util.concurrent.ConcurrentHashMap<String, Pair<WorkspaceNodeType, String?>>()

    private fun Workspace.withNodeTypes(): Workspace =
        copy(projects = projects.map { it.withNodeType() })

    private fun Project.withNodeType(): Project {
        val (type, template) = nodeMetaCache.getOrPut(location) {
            parseNodeMeta(readFolderConfig(fsPath))
        }
        return copy(nodeType = type, templateId = template)
    }

    private fun FsPath.locationKey(): String = when (this) {
        is FsPath.Local -> file.absolutePath
        is FsPath.Saf -> uri.toString()
    }

    /** Whether an opened folder has no `type:` recorded yet, so the caller must ask Project vs Workspace. */
    suspend fun folderNeedsType(path: FsPath): Boolean = withContext(Dispatchers.IO) {
        val content = readFolderConfig(path) ?: return@withContext true
        content.lineSequence().none { it.trimStart().startsWith("type:") }
    }

    /** Stamp `type:` into `.jcode/<name>.yaml` (creating it if needed) then register the opened folder. */
    suspend fun addFolderWithType(path: FsPath, nodeType: WorkspaceNodeType): Project {
        validateWritable(path)
        setFolderType(path, nodeType)
        return registerProject(path).copy(nodeType = nodeType)
    }

    private suspend fun setFolderType(path: FsPath, nodeType: WorkspaceNodeType) = withContext(Dispatchers.IO) {
        val typeValue = if (nodeType == WorkspaceNodeType.Workspace) "workspace" else "project"
        when (path) {
            is FsPath.Local -> {
                val dir = path.file
                val configFile = File(dir, ".jcode/${dir.name}.yaml")
                configFile.parentFile?.mkdirs()
                val existing = if (configFile.isFile) configFile.readText() else "name: ${dir.name}\n"
                configFile.writeText(upsertTypeLine(existing, typeValue))
            }

            is FsPath.Saf -> {
                val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, path.uri)
                    ?: return@withContext
                val folderName = tree.name ?: "External Folder"
                val jcodeDir = tree.findFile(".jcode") ?: tree.createDirectory(".jcode") ?: return@withContext
                val configFile = jcodeDir.findFile("$folderName.yaml")
                    ?: jcodeDir.createFile("application/x-yaml", "$folderName.yaml")
                    ?: return@withContext
                val existing = runCatching {
                    context.contentResolver.openInputStream(configFile.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: "name: $folderName\n"
                context.contentResolver.openOutputStream(configFile.uri, "wt")?.use { stream ->
                    stream.write(upsertTypeLine(existing, typeValue).toByteArray())
                }
            }
        }
        nodeMetaCache.remove(path.locationKey())
    }

    /** Replace an existing `type:` line, or append one, preserving the rest of the `.jcode` config. */
    private fun upsertTypeLine(content: String, typeValue: String): String {
        if (content.lineSequence().any { it.trimStart().startsWith("type:") }) {
            return content.lineSequence().joinToString("\n") { line ->
                if (line.trimStart().startsWith("type:")) "type: $typeValue" else line
            }
        }
        val base = content.trimEnd('\n', ' ')
        return (if (base.isEmpty()) "" else "$base\n") + "type: $typeValue\n"
    }

    /** Read the raw `.jcode/<name>.yaml` for a folder (Local file or SAF tree), or null if absent. */
    private fun readFolderConfig(path: FsPath): String? = when (path) {
        is FsPath.Local -> File(path.file, ".jcode/${path.file.name}.yaml")
            .takeIf { it.isFile }
            ?.let { runCatching { it.readText() }.getOrNull() }

        is FsPath.Saf -> {
            val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, path.uri)
            val folderName = tree?.name ?: "External Folder"
            tree?.findFile(".jcode")?.findFile("$folderName.yaml")?.let { file ->
                runCatching {
                    context.contentResolver.openInputStream(file.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
            }
        }
    }

    /** Tolerant line scan over the simple YAML we author; defaults to a plain Project. */
    private fun parseNodeMeta(content: String?): Pair<WorkspaceNodeType, String?> {
        if (content == null) return WorkspaceNodeType.Project to null
        var type = WorkspaceNodeType.Project
        var template: String? = null
        content.lineSequence().forEach { raw ->
            val line = raw.trimStart()
            when {
                line.startsWith("type:") -> {
                    val v = line.removePrefix("type:").trim().trim('"', '\'').lowercase()
                    if (v == "workspace") type = WorkspaceNodeType.Workspace
                }
                line.startsWith("template:") -> {
                    template = line.removePrefix("template:").trim().trim('"', '\'').ifBlank { null }
                }
            }
        }
        return type to template
    }
}
