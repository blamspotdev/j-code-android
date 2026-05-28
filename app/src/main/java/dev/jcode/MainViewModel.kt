package dev.jcode

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.jcode.backend.BackendSessionKind
import dev.jcode.backend.SessionRegistry
import dev.jcode.core.config.ConfigScope
import dev.jcode.core.config.ConfigService
import dev.jcode.core.config.ConfigServiceLocator
import dev.jcode.core.config.EffectiveConfig
import dev.jcode.core.distro.DistroProfile
import dev.jcode.core.distro.DistroService
import dev.jcode.core.distro.DistroServiceLocator
import dev.jcode.core.distro.WizardStepId
import dev.jcode.feature.editor.pane.EditorGroup
import dev.jcode.feature.editor.pane.EditorTab
import dev.jcode.fs.FsKind
import dev.jcode.fs.FsNode
import dev.jcode.fs.FsPath
import dev.jcode.fs.Project
import dev.jcode.fs.Workspace
import dev.jcode.fs.WorkspaceManager
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

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val workspaceManager: WorkspaceManager = WorkspaceServiceLocator.workspaceManager(application)
    val configService: ConfigService = ConfigServiceLocator.configService()
    val distroService: DistroService = DistroServiceLocator.distroService(application)
    val currentWorkspace: StateFlow<Workspace?> = workspaceManager.currentWorkspace

    private val _showCreateProjectDialog = MutableStateFlow(false)
    val showCreateProjectDialog: StateFlow<Boolean> = _showCreateProjectDialog.asStateFlow()

    private val _selectedProjectId = MutableStateFlow<Long?>(null)
    val selectedProject: StateFlow<Project?> = currentWorkspace
        .combine(_selectedProjectId) { workspace, selectedProjectId ->
            val projects = workspace?.projects.orEmpty()
            projects.firstOrNull { it.id == selectedProjectId } ?: projects.firstOrNull()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _editorGroup = MutableStateFlow(EditorGroup.create())
    val editorGroup: StateFlow<EditorGroup> = _editorGroup.asStateFlow()

    val workspaceConfig = configService.workspaceConfig
    val projectConfig = configService.projectConfig
    val workspaceConfigError = configService.workspaceError
    val projectConfigError = configService.projectError
    val effectiveConfig: StateFlow<EffectiveConfig> = configService.effectiveConfig
    val environmentState = distroService.environmentState

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    init {
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

    fun requestCreateProject() {
        _showCreateProjectDialog.value = true
    }

    fun dismissCreateProjectDialog() {
        _showCreateProjectDialog.value = false
    }

    fun createProject(name: String) {
        viewModelScope.launch {
            val sanitizedName = name.trim().ifBlank { "untitled-project" }
            val project = workspaceManager.createProjectInDefaultLocation(sanitizedName)
            _selectedProjectId.value = project.id
            _showCreateProjectDialog.value = false
            emitMessage("Project '${project.name}' created.")
        }
    }

    fun removeProject(projectId: Long) {
        viewModelScope.launch {
            workspaceManager.removeProject(projectId)
            emitMessage("Project removed.")
        }
    }

    fun selectProject(projectId: Long) {
        _selectedProjectId.value = projectId
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
        viewModelScope.launch {
            distroService.refreshEnvironment()
        }
    }

    fun runEnvironmentStep(stepId: WizardStepId) {
        viewModelScope.launch {
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
        }
    }

    fun selectWizardDistro(profile: DistroProfile) {
        distroService.setSelectedDistro(profile)
    }

    fun ensureProjectBootstrapTab() {
        if (_editorGroup.value.tabs.isNotEmpty()) return
        val project = selectedProject.value ?: return
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
        tab.editorState.updateRenderConfig { current ->
            current.copy(
                fontSizeSp = config.editor.fontSize,
                tabWidth = config.editor.tabSize,
                ligatures = config.editor.ligatures,
            )
        }
    }
}
