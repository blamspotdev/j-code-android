package dev.jcode

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.jcode.backend.BackendSessionKind
import dev.jcode.backend.SessionRegistry
import dev.jcode.core.config.ConfigScope
import dev.jcode.core.config.ConfigService
import dev.jcode.core.config.ConfigServiceLocator
import dev.jcode.core.config.EffectiveConfig
import dev.jcode.core.distro.DistroProfile
import dev.jcode.core.distro.DistroWizardProgress
import dev.jcode.core.distro.SdkCatalogAction
import dev.jcode.core.distro.DistroService
import dev.jcode.core.distro.DistroServiceLocator
import dev.jcode.core.distro.WizardStepId
import dev.jcode.core.resource.ResourceManager
import dev.jcode.core.resource.ResourceManagerLocator
import dev.jcode.design.ThemeMode
import dev.jcode.feature.editor.pane.EditorGroup
import dev.jcode.feature.editor.pane.EditorPageKind
import dev.jcode.feature.editor.pane.EditorTab
import dev.jcode.feature.marketplace.MarketplaceServiceLocator
import dev.jcode.feature.marketplace.ProjectTemplate
import dev.jcode.feature.marketplace.TemplateCatalog
import dev.jcode.feature.marketplace.TemplateScaffolder
import dev.jcode.fs.DEFAULT_SHARED_PROJECTS_ROOT
import dev.jcode.fs.FsKind
import dev.jcode.fs.FsNode
import dev.jcode.fs.FsPath
import dev.jcode.fs.Project
import dev.jcode.fs.Workspace
import dev.jcode.fs.WorkspaceCrumb
import dev.jcode.fs.WorkspaceManager
import dev.jcode.fs.WorkspaceNodeType
import dev.jcode.fs.WorkspaceServiceLocator
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Process-singleton holder for the app-level UI-preferences DataStore. A DataStore must be created
 * exactly once per file per process; constructing a second one (e.g. when MainViewModel is rebuilt
 * after the Activity is recreated) throws "multiple DataStores active for the same file".
 */
private object UiPreferencesStore {
    @Volatile
    private var instance: DataStore<Preferences>? = null

    fun get(context: Context): DataStore<Preferences> =
        instance ?: synchronized(this) {
            instance ?: PreferenceDataStoreFactory.create {
                context.applicationContext.preferencesDataStoreFile("ui-preferences.preferences_pb")
            }.also { instance = it }
        }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val workspaceManager: WorkspaceManager = WorkspaceServiceLocator.workspaceManager(application)
    val configService: ConfigService = ConfigServiceLocator.configService()
    val distroService: DistroService = DistroServiceLocator.distroService(application)
    val resourceManager: ResourceManager = ResourceManagerLocator.resourceManager(application)
    val currentWorkspace: StateFlow<Workspace?> = workspaceManager.currentWorkspace
    val breadcrumb: StateFlow<List<WorkspaceCrumb>> = workspaceManager.breadcrumb

    private val appContext: Context = application.applicationContext
    val templateCatalog: TemplateCatalog = MarketplaceServiceLocator.templateCatalog(application)
    private val templateScaffolder: TemplateScaffolder = MarketplaceServiceLocator.templateScaffolder(application)
    val scaffoldState = templateScaffolder.state

    private val _templates = MutableStateFlow<List<ProjectTemplate>>(emptyList())
    val templates: StateFlow<List<ProjectTemplate>> = _templates.asStateFlow()

    private val _showNewItemDialog = MutableStateFlow(false)
    val showNewItemDialog: StateFlow<Boolean> = _showNewItemDialog.asStateFlow()

    /** An opened folder awaiting a Project/Workspace choice (it has no `.jcode` type yet). */
    private val _openFolderTypePrompt = MutableStateFlow<FsPath?>(null)
    val openFolderTypePrompt: StateFlow<FsPath?> = _openFolderTypePrompt.asStateFlow()

    private val _selectedProjectId = MutableStateFlow<Long?>(null)
    val selectedProject: StateFlow<Project?> = currentWorkspace
        .combine(_selectedProjectId) { workspace, selectedProjectId ->
            val projects = workspace?.projects.orEmpty()
            projects.firstOrNull { it.id == selectedProjectId } ?: projects.firstOrNull()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _editorGroup = MutableStateFlow(EditorGroup.create())
    val editorGroup: StateFlow<EditorGroup> = _editorGroup.asStateFlow()

    // App-level (non-workspace) UI preferences. Durable across restarts, mirroring how the fs and
    // distro modules persist their own preference stores. Uses a process singleton: creating a second
    // DataStore for the same file (e.g. when a new MainViewModel is built after Activity recreation)
    // crashes with "multiple DataStores active for the same file".
    private val uiPreferences = UiPreferencesStore.get(appContext)
    private val railToolOrderKey = stringPreferencesKey("rail_tool_order")

    /** Persisted left-rail icon order as tool names; empty until the user reorders. */
    val railToolOrder: StateFlow<List<String>> = uiPreferences.data
        .map { prefs -> prefs[railToolOrderKey]?.split(',')?.filter { it.isNotBlank() }.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setRailToolOrder(order: List<String>) {
        viewModelScope.launch {
            uiPreferences.edit { prefs -> prefs[railToolOrderKey] = order.joinToString(",") }
        }
    }

    private val terminalDoubleTapKey = booleanPreferencesKey("terminal_double_tap_focus")

    /** When true (default), a single terminal tap opens links/paths and a double tap shows the keyboard. */
    val terminalDoubleTapToFocus: StateFlow<Boolean> = uiPreferences.data
        .map { prefs -> prefs[terminalDoubleTapKey] ?: true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setTerminalDoubleTapToFocus(enabled: Boolean) {
        viewModelScope.launch {
            uiPreferences.edit { prefs -> prefs[terminalDoubleTapKey] = enabled }
        }
    }

    val workspaceConfig = configService.workspaceConfig
    val projectConfig = configService.projectConfig
    val workspaceConfigError = configService.workspaceError
    val projectConfigError = configService.projectError
    val effectiveConfig: StateFlow<EffectiveConfig> = configService.effectiveConfig
    val themeMode: StateFlow<ThemeMode> = effectiveConfig
        .map { ThemeMode.fromConfigId(it.theme.id) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ThemeMode.fromConfigId(effectiveConfig.value.theme.id),
        )
    val environmentState = distroService.environmentState
    val environments = distroService.environments
    val sdkCatalogState = distroService.sdkCatalogState
    val autoSetupProgress = distroService.autoSetupProgress

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _templates.value = runCatching { templateCatalog.templates() }.getOrDefault(emptyList())
        }

        viewModelScope.launch {
            currentWorkspace.collectLatest { workspace ->
                val currentSelection = _selectedProjectId.value
                if (workspace?.projects?.none { it.id == currentSelection } != false) {
                    _selectedProjectId.value = workspace?.projects?.firstOrNull()?.id
                }
            }
        }

        viewModelScope.launch {
            combine(currentWorkspace, selectedProject) { workspace, project ->
                workspace to project
            }.collectLatest { (workspace, project) ->
                val workspaceRoot = workspace?.rootPath?.let(::File)
                val projectRoot = (project?.fsPath as? FsPath.Local)?.file
                configService.bindLocalConfigFiles(workspaceRoot, projectRoot)
            }
        }

        viewModelScope.launch {
            effectiveConfig.collectLatest { config ->
                applyEffectiveConfigToOpenTabs(config)
            }
        }

        viewModelScope.launch {
            combine(
                selectedProject,
                effectiveConfig.map { it.distro }.distinctUntilChanged(),
            ) { project, distro ->
                project to distro
            }.collectLatest { (project, distro) ->
                withContext(Dispatchers.IO) {
                    val projectHostPath = (project?.fsPath as? FsPath.Local)?.file?.absolutePath
                    distroService.updateRuntimeConfig(
                        distroConfig = distro,
                        projectHostPath = projectHostPath,
                        projectTargetPath = project?.distroBindTarget,
                    )
                    distroService.refreshEnvironment()
                }
            }
        }

    }

    data class NewItemRequest(
        val name: String,
        val isWorkspace: Boolean,
        val templateId: String?,
    )

    fun requestNew() {
        templateScaffolder.reset()
        _showNewItemDialog.value = true
    }

    fun dismissNewDialog() {
        _showNewItemDialog.value = false
        templateScaffolder.reset()
    }

    fun createNewItem(request: NewItemRequest) {
        viewModelScope.launch {
            val fallback = if (request.isWorkspace) "untitled-workspace" else "untitled-project"
            val name = request.name.trim().ifBlank { fallback }
            val nodeType = if (request.isWorkspace) WorkspaceNodeType.Workspace else WorkspaceNodeType.Project
            val template = if (request.isWorkspace) null else request.templateId?.let(templateCatalog::template)

            if (request.isWorkspace) {
                // Open the new workspace (enter it) so its (empty) project list shows.
                val workspaceNode = workspaceManager.createNode(name, nodeType, null)
                workspaceManager.enterWorkspaceFolder(workspaceNode)
                _showNewItemDialog.value = false
                emitMessage("Workspace '${workspaceNode.name}' created.")
                return@launch
            }

            // Single-slot Default: clear the currently open project before creating the new one.
            resetDefaultWorkspaceProject()
            val project = workspaceManager.createNode(name, nodeType, template?.id)
            _selectedProjectId.value = project.id

            if (template == null || template.isEmpty) {
                _showNewItemDialog.value = false
                emitMessage("Project '${project.name}' created.")
                return@launch
            }

            // Keep the dialog open to stream scaffold progress. Hold a JOB session so Android
            // does not kill the long-running npm/dotnet steps while the panel is backgrounded.
            SessionRegistry.registerSession(
                appContext,
                BackendSessionKind.JOB,
                "scaffold:${template.id}:${project.name}",
            ).use {
                val ok = templateScaffolder.scaffold(
                    TemplateScaffolder.Request(
                        template = template,
                        projectName = project.name,
                        projectDir = project.distroBindTarget,
                    ),
                )
                emitMessage(
                    if (ok) "Project '${project.name}' ready."
                    else "Project '${project.name}' created with errors; see the scaffold log.",
                )
            }
        }
    }

    /**
     * Open a folder the user picked. A tagged Workspace is entered as a container; an untyped folder
     * prompts Project vs Workspace first; a plain folder opens as the single project.
     */
    fun openExternalFolder(path: FsPath) {
        viewModelScope.launch {
            val resolved = workspaceManager.resolveManageable(path)
            when {
                workspaceManager.isWorkspaceFolder(resolved) &&
                    workspaceManager.enterFolderAsWorkspace(resolved) != null -> Unit

                workspaceManager.folderNeedsType(resolved) -> _openFolderTypePrompt.value = resolved

                else -> {
                    resetDefaultWorkspaceProject()
                    val project = workspaceManager.addFolder(resolved)
                    _selectedProjectId.value = project.id
                    emitMessage("Opened '${project.name}'.")
                }
            }
        }
    }

    fun resolveOpenFolderType(isWorkspace: Boolean) {
        val path = _openFolderTypePrompt.value ?: return
        _openFolderTypePrompt.value = null
        viewModelScope.launch {
            if (isWorkspace && workspaceManager.enterFolderAsWorkspace(path) != null) {
                return@launch
            }
            resetDefaultWorkspaceProject()
            val nodeType = if (isWorkspace) WorkspaceNodeType.Workspace else WorkspaceNodeType.Project
            val project = workspaceManager.addFolderWithType(path, nodeType)
            _selectedProjectId.value = project.id
            emitMessage("Opened ${if (isWorkspace) "Workspace" else "Project"} '${project.name}'.")
        }
    }

    fun dismissOpenFolderPrompt() {
        _openFolderTypePrompt.value = null
    }

    fun removeProject(projectId: Long) {
        viewModelScope.launch {
            workspaceManager.removeProject(projectId)
            emitMessage("Removed from workspace.")
        }
    }

    fun selectProject(projectId: Long) {
        _selectedProjectId.value = projectId
    }

    /** Roster tap / "Open": a Workspace is entered (its projects show); a Project is selected. */
    fun openProject(project: Project) {
        if (project.nodeType == WorkspaceNodeType.Workspace) {
            viewModelScope.launch { workspaceManager.enterWorkspaceFolder(project) }
        } else {
            _selectedProjectId.value = project.id
        }
    }

    /** Leave the current User Workspace and return to the Default Workspace (the first crumb). */
    fun closeWorkspace() {
        val defaultId = breadcrumb.value.firstOrNull()?.id ?: return
        viewModelScope.launch { workspaceManager.navigateToWorkspace(defaultId) }
    }

    /**
     * Close the project open in the Default Workspace: clear the editor and unregister the Default
     * Workspace's project(s) (folders kept) → clean welcome screen. Terminal sessions are torn down
     * by the UI layer (it owns their lifecycle).
     */
    fun closeProject() {
        viewModelScope.launch {
            clearEditorTabs()
            clearDefaultWorkspaceProjects()
            emitMessage("Project closed.")
        }
    }

    private fun clearEditorTabs() {
        val tabs = _editorGroup.value.tabs
        tabs.filterNot { it.isPage }.forEach { it.editorState?.close() }
        // Page tabs (e.g. Settings) are app-level, not project content: keep them across project switches.
        _editorGroup.value = tabs.filter { it.isPage }
            .fold(EditorGroup.create()) { group, tab -> group.withTabAdded(tab) }
    }

    /** The Default Workspace holds a single open project; unregister whatever it currently has. */
    private suspend fun clearDefaultWorkspaceProjects() {
        if (breadcrumb.value.size > 1) return
        currentWorkspace.value?.projects.orEmpty().forEach { workspaceManager.removeProject(it.id) }
    }

    /** Single-slot Default: before opening/creating a project there, clear the current one. */
    private suspend fun resetDefaultWorkspaceProject() {
        if (breadcrumb.value.size > 1) return
        clearEditorTabs()
        clearDefaultWorkspaceProjects()
    }

    fun renameProject(projectId: Long, newName: String) {
        viewModelScope.launch {
            val ok = workspaceManager.renameProject(projectId, newName)
            emitMessage(if (ok) "Renamed to '${newName.trim()}'." else "Rename failed.")
        }
    }

    fun updateEditorFontSize(scope: ConfigScope, fontSize: Float) {
        viewModelScope.launch {
            if (!ensureScopeAvailable(scope)) return@launch
            configService.updateEditorConfig(scope) { it.copy(fontSize = fontSize.coerceIn(8f, 72f)) }
        }
    }

    fun updateEditorTabSize(scope: ConfigScope, tabSize: Int) {
        viewModelScope.launch {
            if (!ensureScopeAvailable(scope)) return@launch
            configService.updateEditorConfig(scope) { it.copy(tabSize = tabSize.coerceIn(1, 16)) }
        }
    }

    fun updateEditorWordWrap(scope: ConfigScope, enabled: Boolean) {
        viewModelScope.launch {
            if (!ensureScopeAvailable(scope)) return@launch
            configService.updateEditorConfig(scope) { it.copy(wordWrap = enabled) }
        }
    }

    fun updateEditorMinimap(scope: ConfigScope, enabled: Boolean) {
        viewModelScope.launch {
            if (!ensureScopeAvailable(scope)) return@launch
            configService.updateEditorConfig(scope) { it.copy(minimap = enabled) }
        }
    }

    fun updateEditorLigatures(scope: ConfigScope, enabled: Boolean) {
        viewModelScope.launch {
            if (!ensureScopeAvailable(scope)) return@launch
            configService.updateEditorConfig(scope) { it.copy(ligatures = enabled) }
        }
    }

    fun setThemeMode(mode: ThemeMode, scope: ConfigScope = ConfigScope.Workspace) {
        viewModelScope.launch {
            if (!ensureScopeAvailable(scope)) return@launch
            configService.updateThemeConfig(scope) { it.copy(id = mode.configId) }
        }
    }

    fun updateExplorerViewMode(scope: ConfigScope, viewMode: String) {
        viewModelScope.launch {
            if (!ensureScopeAvailable(scope)) return@launch
            configService.updateExplorerConfig(scope) { it.copy(viewMode = viewMode) }
        }
    }

    // --- Multi-environment ("docker-style") management ---

    fun setActiveEnvironment(environmentId: String) {
        distroService.setActiveEnvironment(environmentId)
    }

    fun deleteEnvironment(environmentId: String) {
        viewModelScope.launch {
            val label = environmentId
            val removed = distroService.deleteEnvironment(environmentId)
            emitMessage(if (removed) "Removed environment '$label'." else "Could not remove '$label'.")
        }
    }

    fun createEnvironment(profile: DistroProfile) {
        viewModelScope.launch(Dispatchers.IO) {
            // Hold a foreground session so Android won't kill the app during the long download/extract.
            val session = SessionRegistry.registerSession(
                context = getApplication(),
                kind = BackendSessionKind.JOB,
                name = "environment:create:${profile.id}",
            )
            try {
                distroService.createEnvironment(profile).collect { /* progress surfaced via environmentState */ }
            } finally {
                session.close()
            }
        }
    }

    fun openWorkspaceConfigFile() {
        viewModelScope.launch {
            val file = configService.ensureConfigFile(ConfigScope.Workspace)
            if (file == null) {
                emitMessage("Workspace config is unavailable.")
                return@launch
            }
            openLocalFile(file)
        }
    }

    fun openProjectConfigFile() {
        viewModelScope.launch {
            val file = configService.ensureConfigFile(ConfigScope.Project)
            if (file == null) {
                emitMessage("Project overrides require a local project.")
                return@launch
            }
            openLocalFile(file)
        }
    }

    fun refreshEnvironment() {
        viewModelScope.launch(Dispatchers.IO) {
            distroService.refreshEnvironment()
        }
    }

    fun runEnvironmentStep(
        @Suppress("UNUSED_PARAMETER")
        stepId: WizardStepId,
        @Suppress("UNUSED_PARAMETER")
        callerContext: Context? = null,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            if (stepNeedsForegroundService(stepId)) {
                val session = SessionRegistry.registerSession(
                    context = getApplication(),
                    kind = BackendSessionKind.JOB,
                    name = "environment:${stepId.key}",
                )
                try {
                    distroService.runWizardStep(stepId)
                } finally {
                    session.close()
                }
            } else {
                distroService.runWizardStep(stepId)
            }
        }
    }

    fun selectWizardDistro(profile: DistroProfile) {
        distroService.setSelectedDistro(profile)
    }

    fun deferFirstRunEnvironmentSetup() {
        viewModelScope.launch {
            distroService.setFirstRunSetupDeferred(true)
        }
    }

    fun runAutoSetup() {
        viewModelScope.launch(Dispatchers.IO) {
            // Register a foreground session so Android won't kill the app during long bootstrap
            val session = SessionRegistry.registerSession(
                context = getApplication(),
                kind = BackendSessionKind.JOB,
                name = "environment:auto-setup",
            )
            try {
                distroService.runAllPendingSteps()
            } finally {
                session.close()
            }
        }
    }

    fun refreshSdkCatalog() {
        viewModelScope.launch {
            distroService.refreshSdkCatalog()
        }
    }

    fun installSdkCatalogEntry(entryId: String) {
        runSdkCatalogAction(entryId, SdkCatalogAction.Install)
    }

    fun verifySdkCatalogEntry(entryId: String) {
        runSdkCatalogAction(entryId, SdkCatalogAction.Verify)
    }

    fun uninstallSdkCatalogEntry(entryId: String) {
        runSdkCatalogAction(entryId, SdkCatalogAction.Uninstall)
    }

    private var lastBootstrappedProjectId: Long? = null

    fun ensureProjectBootstrapTab() {
        // Ignore page tabs (e.g. Settings): only an open file tab should suppress the bootstrap file.
        if (_editorGroup.value.tabs.any { !it.isPage }) return
        val project = selectedProject.value ?: return
        // Bootstrap a project's file at most once: after the tabs are cleared (e.g. Close Project),
        // the selection may briefly still point at it — don't re-open what the user just closed.
        if (project.id == lastBootstrappedProjectId) return
        lastBootstrappedProjectId = project.id
        viewModelScope.launch {
            openBootstrapFile(project)
        }
    }

    fun openFile(node: FsNode) {
        if (node.kind != FsKind.File) return
        viewModelScope.launch {
            when (val path = node.path) {
                is FsPath.Local -> openLocalFile(path.file)
                is FsPath.Saf -> openSafFile(node)
            }
        }
    }

    /**
     * Open a file referenced by a single tap in a terminal: a guest `/workspace/...` path, an absolute
     * host path, or a path relative to the open project. A trailing `:line`/`:line:col` is stripped.
     * No-op if the token doesn't resolve to an existing file.
     */
    fun openFileByGuestPath(token: String) {
        val raw = token.trim().trimEnd('.', ',', ')', ']', '}', ';', '"', '\'')
        if (raw.isEmpty()) return
        val pathPart = raw.replace(Regex(":\\d+(:\\d+)?$"), "")
        val file = resolveHostFile(pathPart) ?: return
        if (!file.isFile) return
        viewModelScope.launch { openLocalFile(file) }
    }

    private fun resolveHostFile(path: String): File? {
        val projectsRoot = DEFAULT_SHARED_PROJECTS_ROOT.trimEnd('/')
        return when {
            path == "/workspace" -> null
            path.startsWith("/workspace/") -> File(projectsRoot + path.removePrefix("/workspace"))
            path.startsWith("/") -> File(path) // absolute host path
            else -> {
                val base = (selectedProject.value?.fsPath as? FsPath.Local)?.file ?: return null
                File(base, path) // relative to the open project
            }
        }
    }

    /** Open (or focus) the in-editor Settings page as an editor tab. */
    fun openSettingsPage() {
        val existing = _editorGroup.value.tabs.firstOrNull { it.pageKind == EditorPageKind.Settings }
        if (existing != null) {
            _editorGroup.value = _editorGroup.value.withActiveTabChanged(existing.id)
            return
        }
        val tab = EditorTab.page(SETTINGS_TAB_ID, "Settings", EditorPageKind.Settings)
        _editorGroup.value = _editorGroup.value.withTabAdded(tab)
    }

    fun selectEditorTab(tabId: String) {
        _editorGroup.value = _editorGroup.value.withActiveTabChanged(tabId)
    }

    fun closeEditorTab(tabId: String) {
        val existing = _editorGroup.value.tabs.firstOrNull { it.id == tabId }
        existing?.editorState?.close()
        _editorGroup.value = _editorGroup.value.withTabRemoved(tabId)
    }

    private suspend fun openBootstrapFile(project: Project) {
        when (val path = project.fsPath) {
            is FsPath.Local -> {
                val root = path.file
                val bootstrapFile = findBootstrapFile(root)
                if (bootstrapFile != null) {
                    openLocalFile(bootstrapFile)
                }
            }

            is FsPath.Saf -> emitMessage("Open a file from the explorer to start editing SAF projects.")
        }
    }

    private suspend fun openLocalFile(file: File) {
        val stableId = file.absolutePath
        val existing = _editorGroup.value.tabs.firstOrNull { it.id == stableId }
        if (existing != null) {
            _editorGroup.value = _editorGroup.value.withActiveTabChanged(existing.id)
            return
        }

        if (!file.exists() || !file.isFile) {
            emitMessage("File no longer exists: ${file.name}")
            return
        }

        if (!file.isLikelyTextFile()) {
            emitMessage("Binary preview is not implemented yet for ${file.name}.")
            return
        }

        val tab = EditorTab.create(file, stableId)
        applyConfigToTab(tab, effectiveConfig.value)
        _editorGroup.value = _editorGroup.value.withTabAdded(tab)
    }

    private suspend fun openSafFile(node: FsNode) {
        val stableId = when (val path = node.path) {
            is FsPath.Local -> path.file.absolutePath
            is FsPath.Saf -> path.uri.toString()
        }
        val existing = _editorGroup.value.tabs.firstOrNull { it.id == stableId }
        if (existing != null) {
            _editorGroup.value = _editorGroup.value.withActiveTabChanged(existing.id)
            return
        }

        val bytes = workspaceManager.fsFor(node.path).read(node.path)
        if (!bytes.isLikelyText()) {
            emitMessage("Binary preview is not implemented yet for ${node.name}.")
            return
        }

        val tab = EditorTab.createFromText(
            text = bytes.toString(Charsets.UTF_8),
            title = node.name,
            id = stableId,
        )
        applyConfigToTab(tab, effectiveConfig.value)
        _editorGroup.value = _editorGroup.value.withTabAdded(tab)
    }

    private suspend fun emitMessage(message: String) {
        _messages.emit(message)
    }

    private fun findBootstrapFile(root: File): File? {
        val preferred = listOf(
            "package.json",
            "README.md",
            "README",
            "build.gradle.kts",
            "settings.gradle.kts",
            "Cargo.toml",
            "pom.xml",
            "pubspec.yaml",
            "MainActivity.kt",
            "index.ts",
            "main.ts",
            "main.py",
        )

        preferred.firstNotNullOfOrNull { candidate ->
            File(root, candidate).takeIf { it.exists() && it.isFile }
        }?.let { return it }

        return root.walkTopDown()
            .maxDepth(4)
            .firstOrNull { file ->
                file.isFile &&
                    file.length() <= 2_000_000L &&
                    file.name !in setOf(".DS_Store") &&
                    !file.absolutePath.contains("${File.separator}.git${File.separator}")
            }
    }

    private fun File.isLikelyTextFile(): Boolean {
        val probe = inputStream().use { stream ->
            stream.readNBytes(2_048)
        }
        return probe.isLikelyText()
    }

    private fun ByteArray.isLikelyText(): Boolean {
        if (isEmpty()) return true
        if (any { it == 0.toByte() }) return false

        val suspicious = count { byte ->
            val value = byte.toInt() and 0xFF
            value < 0x09 || (value in 0x0E..0x1F)
        }
        return suspicious <= size / 8
    }

    private suspend fun ensureScopeAvailable(scope: ConfigScope): Boolean {
        if (scope == ConfigScope.Workspace) return true
        val project = selectedProject.value
        val hasLocalProject = project?.fsPath is FsPath.Local
        if (!hasLocalProject) {
            emitMessage("Project overrides require a local project root.")
        }
        return hasLocalProject
    }

    private fun applyEffectiveConfigToOpenTabs(config: EffectiveConfig) {
        _editorGroup.value.tabs.forEach { tab ->
            applyConfigToTab(tab, config)
        }
    }

    private fun applyConfigToTab(tab: EditorTab, config: EffectiveConfig) {
        val editorState = tab.editorState ?: return
        editorState.updateRenderConfig { current ->
            current.copy(
                fontSizeSp = config.editor.fontSize,
                tabWidth = config.editor.tabSize,
                ligatures = config.editor.ligatures,
            )
        }
    }

    private fun runSdkCatalogAction(
        entryId: String,
        action: SdkCatalogAction,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val session = SessionRegistry.registerSession(
                context = getApplication(),
                kind = BackendSessionKind.JOB,
                name = "sdk:${action.name.lowercase()}:$entryId",
            )
            try {
                distroService.runSdkCatalogAction(entryId, action)
            } finally {
                session.close()
            }
        }
    }

    private fun stepNeedsForegroundService(stepId: WizardStepId): Boolean {
        return stepId == WizardStepId.DistroInstalled ||
            stepId == WizardStepId.ToolchainBootstrapped
    }

    private companion object {
        const val SETTINGS_TAB_ID = "jcode://settings"
    }
}
