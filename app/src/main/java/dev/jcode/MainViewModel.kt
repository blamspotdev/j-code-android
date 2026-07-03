package dev.jcode

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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
import dev.jcode.core.editor.Caret
import dev.jcode.core.buffer.EditTx
import dev.jcode.core.config.EffectiveConfig
import dev.jcode.core.distro.DistroProfile
import dev.jcode.core.distro.DistroWizardProgress
import dev.jcode.core.distro.DebugEngineAction
import dev.jcode.core.distro.DebugEngineCatalog
import dev.jcode.core.distro.LspCatalogAction
import dev.jcode.core.distro.LspServerCatalog
import dev.jcode.debug.DebugController
import dev.jcode.core.distro.SdkCatalogAction
import dev.jcode.core.distro.DistroService
import dev.jcode.core.distro.DistroServiceLocator
import dev.jcode.core.lsp.Diagnostic as LspDiagnostic
import dev.jcode.core.lsp.DiagnosticSeverity as LspDiagnosticSeverity
import dev.jcode.core.resource.ResourceManager
import dev.jcode.core.resource.ResourceManagerLocator
import dev.jcode.design.ThemeMode
import dev.jcode.feature.editor.pane.EditorGroup
import dev.jcode.feature.editor.pane.EditorPageKind
import dev.jcode.feature.editor.pane.EditorTab
import dev.jcode.feature.marketplace.BundledExtensionSpec
import dev.jcode.feature.marketplace.ExtensionActivation
import dev.jcode.feature.marketplace.InstalledExtension
import dev.jcode.feature.marketplace.languageFor
import dev.jcode.feature.marketplace.MarketplaceEntry
import dev.jcode.feature.marketplace.MarketplaceServiceLocator
import dev.jcode.feature.marketplace.ProjectTemplate
import dev.jcode.feature.marketplace.TemplateCatalog
import dev.jcode.feature.marketplace.TemplateScaffolder
import dev.jcode.fs.DEFAULT_SHARED_PROJECTS_ROOT
import dev.jcode.workbench.SetupTerminalRunner
import dev.jcode.fs.FsKind
import dev.jcode.fs.FsNode
import dev.jcode.fs.FsPath
import dev.jcode.fs.Project
import dev.jcode.fs.ProjectKind
import dev.jcode.fs.RecentEntity
import dev.jcode.fs.Workspace
import dev.jcode.fs.WorkspaceCrumb
import dev.jcode.fs.WorkspaceManager
import dev.jcode.fs.WorkspaceNodeType
import dev.jcode.fs.WorkspaceServiceLocator
import dev.jcode.run.ProjectRunner
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Version of the JCode <-> extension request/event API served by this app build. Bump when route
 *  families or envelope semantics change; extensions gate on it via their manifest's api.minApiVersion. */
const val EXTENSION_API_VERSION = 1

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
    private val extensionInstaller = MarketplaceServiceLocator.extensionInstaller(application)
    val scaffoldState = templateScaffolder.state

    /** Shared "Setup" terminal in the right drawer that runs toolchain installs and scaffolds. */
    val setupTerminalRunner = SetupTerminalRunner(appContext, distroService)

    init {
        TerminalSessionHost.manager(appContext).onTaskComplete = setupTerminalRunner::handleTaskComplete
        distroService.interactiveCatalogRunner = { label, script, timeoutMs ->
            setupTerminalRunner.run(label, script, workdir = null, asUser = "root", timeoutMs = timeoutMs)
        }
        templateScaffolder.interactiveExec = { label, command, workdir, timeoutMs ->
            setupTerminalRunner.run(
                label = label,
                script = command,
                workdir = workdir,
                asUser = distroService.environmentState.value.runtime.user,
                timeoutMs = timeoutMs,
            )
        }
    }

    private val _templates = MutableStateFlow<List<ProjectTemplate>>(emptyList())
    val templates: StateFlow<List<ProjectTemplate>> = _templates.asStateFlow()

    /** Extensions currently installed under the app's install root (templates + language packs). */
    private val _installedExtensions = MutableStateFlow<List<InstalledExtension>>(emptyList())
    val installedExtensions: StateFlow<List<InstalledExtension>> = _installedExtensions.asStateFlow()

    /** Extensions available in the remote marketplace index (populated on demand). */
    private val _marketplaceEntries = MutableStateFlow<List<MarketplaceEntry>>(emptyList())
    val marketplaceEntries: StateFlow<List<MarketplaceEntry>> = _marketplaceEntries.asStateFlow()

    /** True while a marketplace fetch / install is in flight. */
    private val _marketplaceBusy = MutableStateFlow(false)
    val marketplaceBusy: StateFlow<Boolean> = _marketplaceBusy.asStateFlow()

    /** Per-extension install phase ("Installing…", "Installing required tools…", "Verifying…"),
     *  keyed by extension id, shown on the detail chip and list rows while an install runs. */
    private val _extensionInstallPhases = MutableStateFlow<Map<String, String>>(emptyMap())
    val extensionInstallPhases: StateFlow<Map<String, String>> = _extensionInstallPhases.asStateFlow()

    private fun setExtensionPhase(id: String, phase: String?) {
        _extensionInstallPhases.value =
            if (phase == null) _extensionInstallPhases.value - id
            else _extensionInstallPhases.value + (id to phase)
    }

    /** Re-scan installed extensions and refresh the available templates. */
    fun refreshInstalledExtensions() {
        viewModelScope.launch(Dispatchers.IO) {
            val installed = runCatching { extensionInstaller.installed() }.getOrDefault(emptyList())
            _installedExtensions.value = installed
            // Any extension may contribute templates (a language/dev pack can bundle them too).
            // Always offer an "Empty Project" first — a blank folder that needs no extension.
            val fromExtensions = installed.flatMap { it.templates }
            val emptyOption = fromExtensions.filter { it.id == "empty" }.ifEmpty {
                listOf(ProjectTemplate(id = "empty", name = "Empty Project", description = "A blank project folder — no scaffolding."))
            }
            _templates.value = emptyOption + fromExtensions.filter { it.id != "empty" }
        }
    }

    /** Fetch the remote marketplace index (extensions available to install). */
    fun refreshMarketplace() {
        viewModelScope.launch {
            _marketplaceBusy.value = true
            extensionInstaller.fetchIndex()
                .onSuccess { _marketplaceEntries.value = it.entries }
                .onFailure { _messages.tryEmit("Marketplace: ${it.message ?: "failed to load"}") }
            _marketplaceBusy.value = false
        }
    }

    fun installExtension(entry: MarketplaceEntry) {
        viewModelScope.launch {
            _marketplaceBusy.value = true
            try {
                installExtensionResolvingDeps(entry, visiting = mutableSetOf())
            } finally {
                _marketplaceBusy.value = false
                _extensionInstallPhases.value = emptyMap()
            }
            refreshInstalledExtensions()
        }
    }

    /**
     * Install [entry] after everything it `requires` (extensions recursively, then toolchains and
     * language servers via the catalogs). Any required dependency that fails to install aborts the
     * whole install. `suggests` stays manual.
     */
    private suspend fun installExtensionResolvingDeps(
        entry: MarketplaceEntry,
        visiting: MutableSet<String>,
    ): Boolean {
        if (!visiting.add(entry.id)) return true

        if (!entry.requires.isEmpty) setExtensionPhase(entry.id, "Installing required tools…")

        for (depId in entry.requires.extensions) {
            if (depId in visiting) continue
            if (_installedExtensions.value.any { it.id == depId }) continue
            val depEntry = _marketplaceEntries.value.firstOrNull { it.id == depId }
            if (depEntry == null) {
                _messages.tryEmit("${entry.name}: required extension '$depId' isn't in the marketplace — install aborted.")
                return false
            }
            _messages.tryEmit("Installing required extension: ${depEntry.name}…")
            if (!installExtensionResolvingDeps(depEntry, visiting)) {
                _messages.tryEmit("${entry.name}: required extension ${depEntry.name} failed — install aborted.")
                return false
            }
        }

        for (sdkId in entry.requires.sdks) {
            if (sdkId in distroService.sdkCatalogState.value.installedEntryIds) continue
            _messages.tryEmit("Installing required toolchain: $sdkId…")
            if (!installRequiredSdk(sdkId)) {
                val reason = distroService.sdkCatalogState.value.errorMessage ?: "install failed"
                _messages.tryEmit("${entry.name}: required toolchain '$sdkId' — $reason Install aborted.")
                return false
            }
        }

        for (lspId in entry.requires.lsps) {
            if (lspId in distroService.lspCatalogState.value.installedEntryIds) continue
            _messages.tryEmit("Installing required language server: $lspId…")
            if (!installRequiredLsp(lspId)) {
                val reason = distroService.lspCatalogState.value.errorMessage ?: "install failed"
                _messages.tryEmit("${entry.name}: required language server '$lspId' — $reason Install aborted.")
                return false
            }
        }

        setExtensionPhase(entry.id, "Installing…")
        val result = extensionInstaller.install(entry, BuildConfig.VERSION_NAME)
            .onFailure { _messages.tryEmit("Install failed: ${it.message ?: "error"}") }
        if (result.isFailure) {
            setExtensionPhase(entry.id, null)
            return false
        }

        setExtensionPhase(entry.id, "Verifying…")
        val present = runCatching { extensionInstaller.installed() }
            .getOrDefault(emptyList())
            .any { it.id == entry.id }
        setExtensionPhase(entry.id, null)
        if (!present) {
            _messages.tryEmit("${entry.name}: installed but not detected on disk — install failed.")
            return false
        }
        _messages.tryEmit("Installed ${entry.name}")
        return true
    }

    private suspend fun installRequiredSdk(entryId: String): Boolean = withContext(Dispatchers.IO) {
        val session = SessionRegistry.registerSession(
            context = getApplication(),
            kind = BackendSessionKind.JOB,
            name = "sdk:install:$entryId",
        )
        try {
            distroService.runSdkCatalogAction(entryId, SdkCatalogAction.Install)
        } finally {
            session.close()
        }
        entryId in distroService.sdkCatalogState.value.installedEntryIds
    }

    private suspend fun installRequiredLsp(entryId: String): Boolean = withContext(Dispatchers.IO) {
        val session = SessionRegistry.registerSession(
            context = getApplication(),
            kind = BackendSessionKind.JOB,
            name = "lsp:install:$entryId",
        )
        try {
            distroService.runLspCatalogAction(entryId, LspCatalogAction.Install)
        } finally {
            session.close()
        }
        entryId in distroService.lspCatalogState.value.installedEntryIds
    }

    /**
     * Install every SDK in [requiredSdks] (and their transitive SDK requirements) before the caller
     * installs the tool itself. Already-installed SDKs are skipped; the first failure aborts and
     * returns false so the tool install can be skipped too.
     */
    private suspend fun installRequiredSdks(requiredSdks: List<String>, forName: String): Boolean {
        val visiting = mutableSetOf<String>()
        for (sdkId in requiredSdks) {
            if (!resolveAndInstallSdk(sdkId, forName, visiting)) return false
        }
        return true
    }

    private suspend fun resolveAndInstallSdk(
        sdkId: String,
        forName: String,
        visiting: MutableSet<String>,
    ): Boolean {
        if (sdkId in distroService.sdkCatalogState.value.installedEntryIds) return true
        if (!visiting.add(sdkId)) return true
        val entry = distroService.sdkCatalogState.value.entries.firstOrNull { it.id == sdkId }
        for (dep in entry?.requiredSdks.orEmpty()) {
            if (!resolveAndInstallSdk(dep, forName, visiting)) return false
        }
        _messages.tryEmit("Installing required toolchain: ${entry?.name ?: sdkId}…")
        if (!installRequiredSdk(sdkId)) {
            val reason = distroService.sdkCatalogState.value.errorMessage ?: "install failed"
            _messages.tryEmit("$forName: required toolchain '${entry?.name ?: sdkId}' — $reason Install aborted.")
            return false
        }
        return true
    }

    private suspend fun runCatalogInstall(kind: String, entryId: String, block: suspend () -> Unit) {
        val session = SessionRegistry.registerSession(
            context = getApplication(),
            kind = BackendSessionKind.JOB,
            name = "$kind:install:$entryId",
        )
        try {
            block()
        } finally {
            session.close()
        }
    }

    fun uninstallExtension(id: String) {
        clearExtensionActivation(id)
        viewModelScope.launch(Dispatchers.IO) {
            extensionInstaller.uninstall(id)
            refreshInstalledExtensions()
        }
    }

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

    private val confirmCloseRunningKey = booleanPreferencesKey("perf_confirm_close_running")

    /** When true (default), closing a project/workspace with a running terminal program, an active
     *  Build & Run, or a live debug session prompts for confirmation before killing them. */
    val confirmCloseRunning: StateFlow<Boolean> = uiPreferences.data
        .map { prefs -> prefs[confirmCloseRunningKey] ?: true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setConfirmCloseRunning(enabled: Boolean) {
        viewModelScope.launch { uiPreferences.edit { it[confirmCloseRunningKey] = enabled } }
    }

    private val autoCloseIdleKey = booleanPreferencesKey("perf_auto_close_idle_terminals")

    /** When true, terminals sitting idle at the prompt (no foreground program, no I/O) past
     *  [idleTimeoutMinutes] are closed automatically to free their proot process trees + memory. */
    val autoCloseIdleTerminals: StateFlow<Boolean> = uiPreferences.data
        .map { prefs -> prefs[autoCloseIdleKey] ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setAutoCloseIdleTerminals(enabled: Boolean) {
        viewModelScope.launch { uiPreferences.edit { it[autoCloseIdleKey] = enabled } }
    }

    private val idleTimeoutMinKey = intPreferencesKey("perf_idle_timeout_minutes")

    /** Idle-terminal auto-close threshold in minutes (5…120). Default 30. */
    val idleTimeoutMinutes: StateFlow<Int> = uiPreferences.data
        .map { prefs -> (prefs[idleTimeoutMinKey] ?: 30).coerceIn(5, 120) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 30)

    fun setIdleTimeoutMinutes(minutes: Int) {
        viewModelScope.launch { uiPreferences.edit { it[idleTimeoutMinKey] = minutes.coerceIn(5, 120) } }
    }

    private val maxTerminalSessionsKey = intPreferencesKey("perf_max_terminal_sessions")

    /** Max concurrent terminal instances the "+" button will open (1…24). Default 12. */
    val maxTerminalSessions: StateFlow<Int> = uiPreferences.data
        .map { prefs -> (prefs[maxTerminalSessionsKey] ?: 12).coerceIn(1, 24) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 12)

    fun setMaxTerminalSessions(count: Int) {
        viewModelScope.launch { uiPreferences.edit { it[maxTerminalSessionsKey] = count.coerceIn(1, 24) } }
    }

    private val hideStatusBarWithKeyboardKey = booleanPreferencesKey("hide_status_bar_with_keyboard")

    /** When true, the system status bar is hidden while the soft keyboard is up (more room to edit). */
    val hideStatusBarWithKeyboard: StateFlow<Boolean> = uiPreferences.data
        .map { prefs -> prefs[hideStatusBarWithKeyboardKey] ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setHideStatusBarWithKeyboard(enabled: Boolean) {
        viewModelScope.launch {
            uiPreferences.edit { prefs -> prefs[hideStatusBarWithKeyboardKey] = enabled }
        }
    }

    private val hideTabCloseButtonKey = booleanPreferencesKey("hide_tab_close_button")

    /** When true, the "×" close button is hidden on editor + terminal tabs to avoid accidental
     *  closes; a tab is then closed via its long-press menu. */
    val hideTabCloseButton: StateFlow<Boolean> = uiPreferences.data
        .map { prefs -> prefs[hideTabCloseButtonKey] ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setHideTabCloseButton(enabled: Boolean) {
        viewModelScope.launch {
            uiPreferences.edit { prefs -> prefs[hideTabCloseButtonKey] = enabled }
        }
    }

    private val editorDragMovesCursorKey = booleanPreferencesKey("editor_drag_moves_cursor")

    /** When true, a one-finger drag on the editor moves the text cursor (the view follows) instead of
     *  scrolling the content; long-press text selection is unaffected. */
    val editorDragMovesCursor: StateFlow<Boolean> = uiPreferences.data
        .map { prefs -> prefs[editorDragMovesCursorKey] ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setEditorDragMovesCursor(enabled: Boolean) {
        viewModelScope.launch {
            uiPreferences.edit { prefs -> prefs[editorDragMovesCursorKey] = enabled }
        }
    }

    private val editorCursorDragVerticalKey = intPreferencesKey("editor_cursor_drag_vertical_level")
    private val editorCursorDragHorizontalKey = intPreferencesKey("editor_cursor_drag_horizontal_level")

    /** "Drag to move cursor" sensitivity per axis: 1 (slow/precise) … 5 (fast). Default 2. */
    val editorCursorDragVerticalLevel: StateFlow<Int> = uiPreferences.data
        .map { prefs -> (prefs[editorCursorDragVerticalKey] ?: 2).coerceIn(1, 5) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 2)

    val editorCursorDragHorizontalLevel: StateFlow<Int> = uiPreferences.data
        .map { prefs -> (prefs[editorCursorDragHorizontalKey] ?: 2).coerceIn(1, 5) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 2)

    fun setEditorCursorDragVerticalLevel(level: Int) {
        viewModelScope.launch {
            uiPreferences.edit { prefs -> prefs[editorCursorDragVerticalKey] = level.coerceIn(1, 5) }
        }
    }

    fun setEditorCursorDragHorizontalLevel(level: Int) {
        viewModelScope.launch {
            uiPreferences.edit { prefs -> prefs[editorCursorDragHorizontalKey] = level.coerceIn(1, 5) }
        }
    }

    private val restoreLastSessionKey = booleanPreferencesKey("restore_last_session")

    /** When true (default), the last open workspace/project + editor tabs (with unsaved changes) are
     *  reopened on launch, surviving a force-close or swipe-away from Recents. */
    val restoreLastSession: StateFlow<Boolean> = uiPreferences.data
        .map { prefs -> prefs[restoreLastSessionKey] ?: true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setRestoreLastSession(enabled: Boolean) {
        viewModelScope.launch {
            uiPreferences.edit { prefs -> prefs[restoreLastSessionKey] = enabled }
        }
        // Opting out should not leave a stale session blob behind on disk.
        if (!enabled) viewModelScope.launch(Dispatchers.IO) { sessionStore.clear() }
    }

    private val sessionStore = SessionStore(appContext)

    /** Suppresses the project bootstrap file while a session restore is deciding/opening tabs, so the
     *  restored tabs aren't raced by a freshly bootstrapped file. Cleared when restore settles. */
    @Volatile
    private var suppressBootstrap = true

    /** Gate that keeps incremental saves from clobbering the stored session before restore has read it. */
    @Volatile
    private var sessionSaveEnabled = false

    private val sessionSaveSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private fun scheduleSessionSave() {
        sessionSaveSignal.tryEmit(Unit)
    }

    /** Events pushed to the live extension WebView as `window.JCode._onEvent(name, jsonString)`.
     *  Declared BEFORE the init block: its collector emits during ViewModel construction. */
    private val _extensionEvents = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 16)
    val extensionEvents: SharedFlow<Pair<String, String>> = _extensionEvents

    private val themeBundleKey = stringPreferencesKey("theme_bundle_id")

    /** App-wide selected theme bundle id; empty resolves to the default bundle. */
    val themeBundleId: StateFlow<String> = uiPreferences.data
        .map { prefs -> prefs[themeBundleKey].orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    fun setThemeBundle(id: String) {
        viewModelScope.launch {
            uiPreferences.edit { prefs -> prefs[themeBundleKey] = id }
        }
    }

    private val iconBundleKey = stringPreferencesKey("icon_bundle_id")

    /** App-wide selected icon bundle id; empty resolves to the default (Material) bundle. */
    val iconBundleId: StateFlow<String> = uiPreferences.data
        .map { prefs -> prefs[iconBundleKey].orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    fun setIconBundle(id: String) {
        viewModelScope.launch {
            uiPreferences.edit { prefs -> prefs[iconBundleKey] = id }
        }
    }


    private val formatterKey = stringPreferencesKey("formatter_id")

    /** App-wide formatter selection; "builtin" is the built-in formatter. Formatter extensions
     *  (type: formatter) appear as options once installed. */
    val formatterId: StateFlow<String> = uiPreferences.data
        .map { prefs -> prefs[formatterKey]?.takeIf { it.isNotBlank() } ?: "builtin" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "builtin")

    fun setFormatter(id: String) {
        viewModelScope.launch {
            uiPreferences.edit { prefs -> prefs[formatterKey] = id }
        }
    }

    // "Open web previews in" — which browser Build & Run / URL taps use. A global default plus a
    // per-project override (device-local: which browser apps exist is not portable project config).
    /** Installed browser apps, discovered once (the set rarely changes within a session). */
    val installedBrowsers: List<dev.jcode.design.BrowserApp> by lazy { dev.jcode.run.ProjectRunner.installedBrowsers(appContext) }

    private val webPreviewBrowserKey = stringPreferencesKey("web_preview_browser")

    /** Global default: SYSTEM (device default) | ASK (chooser) | a browser package name. */
    val webPreviewBrowser: StateFlow<String> = uiPreferences.data
        .map { prefs -> prefs[webPreviewBrowserKey]?.takeIf { it.isNotBlank() } ?: "SYSTEM" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "SYSTEM")

    fun setWebPreviewBrowser(choice: String) {
        viewModelScope.launch { uiPreferences.edit { it[webPreviewBrowserKey] = choice } }
    }

    private val webPreviewBrowserProjectsKey = stringPreferencesKey("web_preview_browser_projects")

    /** Per-project overrides keyed by project id; absent = inherit the global default. */
    val webPreviewBrowserProjects: StateFlow<Map<String, String>> = uiPreferences.data
        .map { prefs -> parseStringMap(prefs[webPreviewBrowserProjectsKey]) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun setProjectWebPreviewBrowser(projectKey: String, choice: String) {
        viewModelScope.launch {
            uiPreferences.edit { prefs ->
                val obj = runCatching { JSONObject(prefs[webPreviewBrowserProjectsKey] ?: "{}") }.getOrDefault(JSONObject())
                // Blank/INHERIT means "follow the global default" — drop the override to keep the blob minimal.
                if (choice.isBlank()) obj.remove(projectKey) else obj.put(projectKey, choice)
                prefs[webPreviewBrowserProjectsKey] = obj.toString()
            }
        }
    }

    private fun parseStringMap(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(json)
            buildMap { obj.keys().forEach { k -> put(k, obj.optString(k)) } }
        }.getOrDefault(emptyMap())
    }

    // Per-extension activation mode (auto-start / on-demand / manual), keyed by extension id. Stored as a
    // small JSON object (only non-default entries) in one preference, mirroring how SessionStore persists
    // structured data with org.json. Absent id => ExtensionActivation.Default (OnDemand).
    private val extensionActivationKey = stringPreferencesKey("extension_activation_modes")

    val extensionActivations: StateFlow<Map<String, ExtensionActivation>> = uiPreferences.data
        .map { prefs -> parseActivations(prefs[extensionActivationKey]) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Installed extensions whose contributions are currently allowed to apply (i.e. not set to Manual).
     *  Drives language-pack resolution for highlighting/completions/formatting so Manual truly disables. */
    val activeLanguageExtensions: StateFlow<List<InstalledExtension>> =
        combine(installedExtensions, extensionActivations) { exts, modes ->
            exts.filter { (modes[it.id] ?: ExtensionActivation.Default) != ExtensionActivation.Manual }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun parseActivations(json: String?): Map<String, ExtensionActivation> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(json)
            buildMap {
                obj.keys().forEach { key -> put(key, ExtensionActivation.from(obj.optString(key))) }
            }
        }.getOrDefault(emptyMap())
    }

    fun setExtensionActivation(id: String, mode: ExtensionActivation) {
        viewModelScope.launch {
            uiPreferences.edit { prefs ->
                val obj = runCatching { JSONObject(prefs[extensionActivationKey] ?: "{}") }.getOrDefault(JSONObject())
                if (mode == ExtensionActivation.Default) obj.remove(id) else obj.put(id, mode.name)
                prefs[extensionActivationKey] = obj.toString()
            }
        }
    }

    private fun clearExtensionActivation(id: String) {
        viewModelScope.launch {
            uiPreferences.edit { prefs ->
                val obj = runCatching { JSONObject(prefs[extensionActivationKey] ?: "{}") }.getOrDefault(JSONObject())
                obj.remove(id)
                prefs[extensionActivationKey] = obj.toString()
            }
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
    val lspCatalogState = distroService.lspCatalogState
    val debugCatalogState = distroService.debugCatalogState
    val autoSetupProgress = distroService.autoSetupProgress

    private val debugController = DebugController(distroService, viewModelScope)
    val debugState = debugController.state
    val debugCallStack = debugController.callStack
    val debugVariables = debugController.variables
    val debugOutput = debugController.output
    val debugLocation = debugController.currentLocation

    /** Breakpoints per host file path -> set of 0-based lines. Toggled from the editor gutter. */
    private val _breakpoints = MutableStateFlow<Map<String, Set<Int>>>(emptyMap())
    val breakpoints: StateFlow<Map<String, Set<Int>>> = _breakpoints.asStateFlow()

    /** Toggle a breakpoint at [line] (0-based) in the file at host [path]. */
    fun toggleBreakpoint(path: String, line: Int) {
        val next = _breakpoints.value.toMutableMap()
        val lines = next[path]?.toMutableSet() ?: mutableSetOf()
        if (!lines.add(line)) lines.remove(line)
        if (lines.isEmpty()) next.remove(path) else next[path] = lines
        _breakpoints.value = next
        debugController.onBreakpointsChanged(path, next[path].orEmpty())
    }

    /** Start debugging the given host file with a matching debug engine (debugpy for .py, …). */
    fun startDebug(hostPath: String) {
        debugController.startDebug(hostPath, deriveProjectDir(hostPath), _breakpoints.value)
    }

    private fun deriveProjectDir(hostPath: String): String {
        val marker = "/JCode/projects/"
        val i = hostPath.indexOf(marker)
        if (i < 0) return java.io.File(hostPath).parent ?: hostPath
        val name = hostPath.substring(i + marker.length).substringBefore('/')
        return hostPath.substring(0, i + marker.length) + name
    }

    fun debugContinue() = debugController.resume()
    fun debugStepOver() = debugController.stepOver()
    fun debugStepInto() = debugController.stepInto()
    fun debugStepOut() = debugController.stepOut()
    fun debugStop() = debugController.stop()
    fun debugEvaluate(expression: String, onResult: (String?) -> Unit) =
        debugController.evaluate(expression, onResult)

    // ---- Issues (diagnostics bus) producers ----

    private val diagnosticsBus = dev.jcode.core.lsp.LspModule.diagnosticsBus

    init {
        // .jcode YAML config parse errors are real project issues; mirror them onto the bus.
        viewModelScope.launch {
            configService.workspaceError.collect { err ->
                publishConfigDiagnostic("config-workspace", configService.workspaceConfigPath, err)
            }
        }
        viewModelScope.launch {
            configService.projectError.collect { err ->
                publishConfigDiagnostic("config-project", configService.projectConfigPath, err)
            }
        }
    }

    private fun publishConfigDiagnostic(source: String, path: String?, error: String?) {
        if (error == null || path == null) {
            diagnosticsBus.clearSource(source)
            return
        }
        // snakeyaml errors carry "line N, column M" (1-based); default to the file top otherwise.
        val pos = Regex("""line (\d+), column (\d+)""").find(error)
        val line = ((pos?.groupValues?.get(1)?.toIntOrNull() ?: 1) - 1).coerceAtLeast(0)
        val col = ((pos?.groupValues?.get(2)?.toIntOrNull() ?: 1) - 1).coerceAtLeast(0)
        val message = error.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: error
        diagnosticsBus.updateSourceDiagnostics(
            source,
            mapOf(
                path to listOf(
                    LspDiagnostic(line, col, line, col, LspDiagnosticSeverity.ERROR, message, "jcode-config", null),
                ),
            ),
        )
    }

    /**
     * On-open/on-save syntax check for the flagship script languages: a one-shot compile inside the
     * distro (no artifacts written), its errors published to the Issues bus. Silent no-op when the
     * runtime isn't ready, the file lives outside /workspace, or the language has no checker.
     */
    private fun queueSyntaxCheck(file: File) {
        val projectsRoot = DEFAULT_SHARED_PROJECTS_ROOT.trimEnd('/')
        if (!file.path.startsWith("$projectsRoot/")) return
        val guest = hostToGuestPath(file)
        val command = when (file.extension.lowercase()) {
            "py", "pyw" ->
                "python3 -c \"import sys; compile(open(sys.argv[1]).read(), sys.argv[1], 'exec')\" '$guest'"
            "js", "mjs", "cjs" ->
                "command -v node >/dev/null 2>&1 || exit 0; node --check '$guest'"
            else -> return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { distroService.exec(command, timeoutMs = 30_000L) }.getOrNull() ?: return@launch
            if (result.internalError != null) return@launch // runtime not ready: leave issues untouched
            val diagnostics = if (result.succeeded) emptyList() else parseSyntaxErrors(result.stderr, guest)
            diagnosticsBus.updateDiagnostics("syntax", file.path, diagnostics)
        }
    }

    /** Pull (line, message) out of python/node syntax-error stderr for [guestPath]. */
    private fun parseSyntaxErrors(stderr: String, guestPath: String): List<LspDiagnostic> {
        if (stderr.isBlank()) return emptyList()
        val lines = stderr.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.toList()
        // python: File "<path>", line N — node: <path>:N at the very first line.
        val lineNo = lines.firstNotNullOfOrNull { l ->
            Regex("""File "${Regex.escape(guestPath)}", line (\d+)""").find(l)?.groupValues?.get(1)?.toIntOrNull()
        } ?: lines.firstNotNullOfOrNull { l ->
            Regex("""^${Regex.escape(guestPath)}:(\d+)""").find(l)?.groupValues?.get(1)?.toIntOrNull()
        } ?: 1
        val message = lines.lastOrNull { Regex("""^\w*(Error|Warning)\b""").containsMatchIn(it) }
            ?: lines.last()
        return listOf(
            LspDiagnostic(
                startLine = (lineNo - 1).coerceAtLeast(0),
                startCol = 0,
                endLine = (lineNo - 1).coerceAtLeast(0),
                endCol = 0,
                severity = LspDiagnosticSeverity.ERROR,
                message = message.take(300),
                source = "syntax",
                code = null,
            ),
        )
    }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    /** Emitted when a file is opened from the terminal, so the shell can surface the editor. */
    private val _bringEditorToFront = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val bringEditorToFront = _bringEditorToFront.asSharedFlow()

    // Extensions shipped inside the APK (assets/builtin-extensions/) and installed on first run.
    private val builtinExtensions = listOf(
        BundledExtensionSpec(
            assetPath = "builtin-extensions/jcode.lang.markup-1.0.0.jext",
            uniqueName = "jcode.lang.markup",
            version = "1.0.0",
        ),
        BundledExtensionSpec(
            assetPath = "builtin-extensions/jcode.lang.stylesheet-1.0.0.jext",
            uniqueName = "jcode.lang.stylesheet",
            version = "1.0.0",
        ),
        // NOTE: manager extensions (SQL Client, VM Manager) are NOT bundled — they pull in
        // libraries/binaries at runtime and are distributed via the marketplace, not baked into the app.
    )

    init {
        viewModelScope.launch {
            runCatching {
                extensionInstaller.ensureBundledExtensionsInstalled(builtinExtensions, BuildConfig.VERSION_NAME)
            }
            refreshInstalledExtensions()
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
                currentWorkspace,
                selectedProject,
                effectiveConfig.map { it.distro }.distinctUntilChanged(),
            ) { workspace, project, distro ->
                Triple(workspace, project, distro)
            }.collectLatest { (workspace, project, distro) ->
                withContext(Dispatchers.IO) {
                    val projectHostPath = (project?.fsPath as? FsPath.Local)?.file?.absolutePath
                    // Bind every local project of the workspace so a multi-repo extension (Source Control)
                    // can reach each repo at its guest target; the selected project stays the primary bind.
                    val projectBinds = workspace?.projects.orEmpty().mapNotNull { p ->
                        ((p.fsPath as? FsPath.Local)?.file?.absolutePath)?.let { it to p.distroBindTarget }
                    }
                    distroService.updateRuntimeConfig(
                        distroConfig = distro,
                        projectHostPath = projectHostPath,
                        projectTargetPath = project?.distroBindTarget,
                        projectBinds = projectBinds,
                    )
                    distroService.refreshEnvironment()
                }
            }
        }

        // Restore the previous session (workspace/project + tabs + unsaved edits) on cold launch, then
        // enable incremental persistence. Runs once per process: init fires only when the ViewModel is
        // freshly built, which is exactly the cold-launch / post-process-death case we restore for.
        viewModelScope.launch { restoreSessionOnLaunch() }

        // Debounced session writer: coalesce bursts of edits/tab changes into one disk write.
        viewModelScope.launch {
            sessionSaveSignal.collectLatest {
                delay(700)
                persistSession()
            }
        }
        // Structural changes (tab open/close/switch, dirty-dot flips) and project/workspace switches.
        viewModelScope.launch { editorGroup.collect { scheduleSessionSave() } }
        viewModelScope.launch { selectedProject.collect { scheduleSessionSave() } }

        // Push the focused-file context to the live extension WebView (Extension API `activeFile`
        // event); deduped so tab-internal churn doesn't spam the bridge.
        viewModelScope.launch {
            var last: String? = null
            editorGroup.collect {
                val json = activeFileEventJson()
                if (json != last) {
                    last = json
                    _extensionEvents.tryEmit("activeFile" to json)
                }
            }
        }
    }

    data class NewItemRequest(
        val name: String,
        val isWorkspace: Boolean,
        val templateId: String?,
        /** Values the user picked for the template's declared inputs, keyed by input id. */
        val inputs: Map<String, String> = emptyMap(),
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
                clearEditorTabs()
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

            // The scaffold runs in the background Setup terminal (right drawer) — close the dialog
            // now instead of blocking on a modal. Hold a JOB session so Android does not kill the
            // long-running npm/dotnet steps while the app is backgrounded.
            _showNewItemDialog.value = false
            emitMessage("Setting up '${project.name}' in the Setup terminal…")
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
                        inputs = request.inputs,
                    ),
                )
                emitMessage(
                    if (ok) "Project '${project.name}' ready."
                    else "Project '${project.name}' scaffold failed: " +
                        (scaffoldState.value.errorMessage ?: "see the Setup terminal."),
                )
                templateScaffolder.reset()
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
                    workspaceManager.enterFolderAsWorkspace(resolved) != null -> clearEditorTabs()

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
                clearEditorTabs()
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

    /** Recently opened folders (most-recent first) for the empty-editor "Recent" list. */
    val recents: StateFlow<List<RecentEntity>> = workspaceManager.recents

    /** Re-open a recent folder; [openExternalFolder] enters it as a workspace or opens it as a project. */
    fun openRecent(recent: RecentEntity) {
        val path = when (recent.kind) {
            ProjectKind.Local -> FsPath.Local(File(recent.uri))
            ProjectKind.Saf -> FsPath.Saf(Uri.parse(recent.uri))
        }
        openExternalFolder(path)
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
            viewModelScope.launch {
                clearEditorTabs()
                workspaceManager.enterWorkspaceFolder(project)
            }
        } else {
            _selectedProjectId.value = project.id
        }
    }

    /** Leave the current User Workspace and return to the Default Workspace (the first crumb). */
    fun closeWorkspace() {
        val defaultId = breadcrumb.value.firstOrNull()?.id ?: return
        viewModelScope.launch {
            clearEditorTabs()
            workspaceManager.navigateToWorkspace(defaultId)
            // currentWorkspace is DB-derived; wait for it to reflect the switch so the immediate
            // session save records the default workspace, not the one just closed.
            withTimeoutOrNull(2_000) { currentWorkspace.first { it?.id == defaultId } }
            persistSession()
        }
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
            persistSession()
            emitMessage("Project closed.")
        }
    }

    private fun clearEditorTabs() {
        val tabs = _editorGroup.value.tabs
        tabs.filterNot { it.isPage }.forEach {
            it.editorState?.close()
            untrackDirty(it.id)
        }
        // Page tabs (e.g. Settings) are app-level, not project content: keep them across project switches.
        _editorGroup.value = tabs.filter { it.isPage }
            .fold(EditorGroup.create()) { group, tab -> group.withTabAdded(tab) }
        diagnosticsBus.clearSource("syntax")
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
        // IO: switches the active distro and re-derives its state, which can exec into the rootfs.
        viewModelScope.launch(Dispatchers.IO) {
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

    fun selectWizardDistro(profile: DistroProfile) {
        distroService.setSelectedDistro(profile)
    }

    fun deferFirstRunEnvironmentSetup() {
        viewModelScope.launch {
            distroService.setFirstRunSetupDeferred(true)
        }
    }

    /** Storage permission was granted mid-onboarding: re-anchor storage on the shared /JCode root. */
    fun onStorageAccessGranted() {
        viewModelScope.launch {
            workspaceManager.refreshStorageRoots()
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

    fun installSdkCatalogEntry(entryId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val entry = distroService.sdkCatalogState.value.entries.firstOrNull { it.id == entryId }
            if (entry != null && !installRequiredSdks(entry.requiredSdks, entry.name)) return@launch
            runCatalogInstall("sdk", entryId) {
                distroService.runSdkCatalogAction(entryId, SdkCatalogAction.Install)
            }
        }
    }

    fun verifySdkCatalogEntry(entryId: String) {
        runSdkCatalogAction(entryId, SdkCatalogAction.Verify)
    }

    fun uninstallSdkCatalogEntry(entryId: String) {
        runSdkCatalogAction(entryId, SdkCatalogAction.Uninstall)
    }

    fun installLspCatalogEntry(entryId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val entry = LspServerCatalog.findById(entryId)
            if (entry != null && !installRequiredSdks(entry.requiredSdks, entry.name)) return@launch
            runCatalogInstall("lsp", entryId) {
                distroService.runLspCatalogAction(entryId, LspCatalogAction.Install)
            }
        }
    }

    fun verifyLspCatalogEntry(entryId: String) {
        runLspCatalogAction(entryId, LspCatalogAction.Verify)
    }

    fun uninstallLspCatalogEntry(entryId: String) {
        runLspCatalogAction(entryId, LspCatalogAction.Uninstall)
    }

    fun installDebugEngine(entryId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val entry = DebugEngineCatalog.findById(entryId)
            if (entry != null && !installRequiredSdks(entry.requiredSdks, entry.name)) return@launch
            runCatalogInstall("debug", entryId) {
                distroService.runDebugEngineCatalogAction(entryId, DebugEngineAction.Install)
            }
        }
    }

    fun verifyDebugEngine(entryId: String) {
        runDebugCatalogAction(entryId, DebugEngineAction.Verify)
    }

    fun uninstallDebugEngine(entryId: String) {
        runDebugCatalogAction(entryId, DebugEngineAction.Uninstall)
    }

    private var lastBootstrappedProjectId: Long? = null

    fun ensureProjectBootstrapTab() {
        // While a session restore is in flight, don't open the bootstrap file — restore handles the tabs.
        if (suppressBootstrap) return
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
        // A trailing `:line` or `:line:col` (1-based, as compilers/grep emit) becomes the focus target.
        var pathPart = raw
        var line: Int? = null
        var column: Int? = null
        Regex("""^(.*?):(\d+)(?::(\d+))?$""").find(raw)?.let { m ->
            pathPart = m.groupValues[1]
            line = m.groupValues[2].toIntOrNull()
            column = m.groupValues[3].toIntOrNull()
        }
        val file = resolveHostFile(pathPart) ?: return
        if (!file.isFile) return
        _bringEditorToFront.tryEmit(Unit)
        viewModelScope.launch { openLocalFile(file, line, column) }
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

    fun openEnvironmentPage() {
        val existing = _editorGroup.value.tabs.firstOrNull { it.pageKind == EditorPageKind.Environment }
        if (existing != null) {
            _editorGroup.value = _editorGroup.value.withActiveTabChanged(existing.id)
            return
        }
        val tab = EditorTab.page(ENVIRONMENT_TAB_ID, "Environment", EditorPageKind.Environment)
        _editorGroup.value = _editorGroup.value.withTabAdded(tab)
    }

    /** Open (or focus) an installed extension's bundled web frontend as a full-screen in-editor page. */
    fun openExtensionAppPage(extensionId: String) {
        openDetailPage(EXT_APP_PREFIX + extensionId, EditorPageKind.ExtensionApp) {
            _installedExtensions.value.firstOrNull { it.id == extensionId }?.name ?: "App"
        }
    }

    /** Open an extension's web frontend at a named view (loaded as `#view`) as a full editor page and
     *  bring the editor to front — used by the `workbench.openView` Extension API. */
    fun openExtensionViewPage(extensionId: String, view: String) {
        _bringEditorToFront.tryEmit(Unit)
        val ext = _installedExtensions.value.firstOrNull { it.id == extensionId }
        val viewLabel = when (view) { "github" -> "GitHub"; "" -> null; else -> view.replaceFirstChar { it.uppercaseChar() } }
        openDetailPage(EXT_APP_PREFIX + extensionId + "#" + view, EditorPageKind.ExtensionApp) {
            listOfNotNull(ext?.name, viewLabel).joinToString(" · ").ifBlank { "View" }
        }
    }

    /** The current global git identity (name, email) from the runtime, or empty strings. */
    suspend fun getGitIdentity(): Pair<String, String> {
        val name = distroService.exec("git config --global --get user.name 2>/dev/null", user = "root").stdout.trim()
        val email = distroService.exec("git config --global --get user.email 2>/dev/null", user = "root").stdout.trim()
        return name to email
    }

    /** Set the global git identity in the runtime (author of all commits). */
    fun setGitIdentity(name: String, email: String) {
        viewModelScope.launch {
            fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
            distroService.exec(
                "git config --global user.name ${q(name)} && git config --global user.email ${q(email)}",
                user = "root",
            )
        }
    }

    /** Run a command in the Linux runtime for an extension frontend; returns a JSON result payload.
     *  Runs as root: manager extensions (SQL Client, VM Manager) need privilege to install/run software. */
    suspend fun runtimeExecJson(command: String, timeoutMs: Long): String {
        val result = distroService.exec(command, timeoutMs = timeoutMs, user = "root")
        return JSONObject().apply {
            put("stdout", result.stdout)
            put("stderr", result.stderr)
            put("exitCode", result.exitCode ?: -1)
            result.internalError?.let { put("error", it) }
        }.toString()
    }

    // --- Extension API v1: versioned request envelope + pushed events over the WebView bridge ---
    // Requests arrive as {"type":"family.verb","payload":{...}} via JCodeNative.request and are
    // answered through window.JCode._onResult as {"ok":true,"data":{}} | {"ok":false,"error":""}.
    // Route families ("exec", "fs", "workbench") are capability-gated: an extension must declare a
    // family under its manifest's api.capabilities AND the user must not have revoked it.

    /** Per-extension capability DENIALS (granted-by-default), keyed by extension id — same
     *  org.json-blob DataStore pattern as [extensionActivationKey]. */
    private val extensionCapabilityKey = stringPreferencesKey("extension_capability_denials")

    val extensionCapabilityDenials: StateFlow<Map<String, Set<String>>> = uiPreferences.data
        .map { prefs -> parseCapabilityDenials(prefs[extensionCapabilityKey]) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private fun parseCapabilityDenials(json: String?): Map<String, Set<String>> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(json)
            buildMap {
                obj.keys().forEach { ext ->
                    val arr = obj.optJSONArray(ext) ?: return@forEach
                    put(ext, buildSet { for (i in 0 until arr.length()) add(arr.optString(i)) })
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun setExtensionCapability(extensionId: String, capability: String, granted: Boolean) {
        viewModelScope.launch {
            uiPreferences.edit { prefs ->
                val obj = runCatching { JSONObject(prefs[extensionCapabilityKey] ?: "{}") }.getOrDefault(JSONObject())
                val denied = buildSet {
                    obj.optJSONArray(extensionId)?.let { arr -> for (i in 0 until arr.length()) add(arr.optString(i)) }
                }.toMutableSet()
                if (granted) denied.remove(capability) else denied.add(capability)
                if (denied.isEmpty()) obj.remove(extensionId)
                else obj.put(extensionId, org.json.JSONArray().apply { denied.forEach { put(it) } })
                prefs[extensionCapabilityKey] = obj.toString()
            }
        }
    }

    private fun capabilityGranted(ext: InstalledExtension, capability: String): Boolean =
        capability in ext.apiCapabilities &&
            capability !in (extensionCapabilityDenials.value[ext.id] ?: emptySet())

    // Per-extension "keep running in background": whether an extension's chat/app WebView survives
    // its panel closing. ENABLED-by-default; stored as the set of ids the user has DISABLED (same
    // org.json-array-in-one-key pattern as the other per-extension settings).
    private val extensionKeepAliveKey = stringPreferencesKey("extension_keepalive_disabled")

    val extensionKeepAliveDisabled: StateFlow<Set<String>> = uiPreferences.data
        .map { prefs -> parseIdSet(prefs[extensionKeepAliveKey]) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private fun parseIdSet(json: String?): Set<String> {
        if (json.isNullOrBlank()) return emptySet()
        return runCatching {
            val arr = org.json.JSONArray(json)
            buildSet { for (i in 0 until arr.length()) add(arr.optString(i)) }
        }.getOrDefault(emptySet())
    }

    fun setExtensionKeepAlive(extensionId: String, enabled: Boolean) {
        viewModelScope.launch {
            uiPreferences.edit { prefs ->
                val disabled = parseIdSet(prefs[extensionKeepAliveKey]).toMutableSet()
                if (enabled) disabled.remove(extensionId) else disabled.add(extensionId)
                prefs[extensionKeepAliveKey] =
                    org.json.JSONArray().apply { disabled.forEach { put(it) } }.toString()
            }
        }
    }

    // Generic per-extension settings (the manifest `settings:` block), keyed extensionId -> {key: value}.
    // Stored as one nested JSON object; the config.* API + the settings screen read/write through here.
    private val extensionSettingsKey = stringPreferencesKey("extension_settings")

    val extensionSettings: StateFlow<Map<String, Map<String, String>>> = uiPreferences.data
        .map { prefs -> parseExtensionSettings(prefs[extensionSettingsKey]) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private fun parseExtensionSettings(json: String?): Map<String, Map<String, String>> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(json)
            buildMap {
                root.keys().forEach { extId ->
                    val obj = root.optJSONObject(extId) ?: return@forEach
                    put(extId, buildMap { obj.keys().forEach { k -> put(k, obj.optString(k)) } })
                }
            }
        }.getOrDefault(emptyMap())
    }

    /** A setting's saved value, or its manifest default, or "". Used by config.* (caller-scoped). */
    private fun resolvedExtensionSetting(ext: InstalledExtension, key: String): String {
        extensionSettings.value[ext.id]?.get(key)?.let { return it }
        return ext.settings.firstOrNull { it.key == key }?.default ?: ""
    }

    /** Same resolution, looked up by id — for the settings screen, which has no [InstalledExtension]. */
    fun extensionSettingValue(extensionId: String, key: String): String {
        extensionSettings.value[extensionId]?.get(key)?.let { return it }
        return installedExtensions.value.firstOrNull { it.id == extensionId }
            ?.settings?.firstOrNull { it.key == key }?.default ?: ""
    }

    fun setExtensionSetting(extensionId: String, key: String, value: String) {
        viewModelScope.launch {
            uiPreferences.edit { prefs ->
                val root = runCatching { JSONObject(prefs[extensionSettingsKey] ?: "{}") }.getOrDefault(JSONObject())
                val obj = root.optJSONObject(extensionId) ?: JSONObject()
                obj.put(key, value)
                root.put(extensionId, obj)
                prefs[extensionSettingsKey] = root.toString()
            }
            // Notify the live extension WebView so it can reload without a manual refresh.
            _extensionEvents.tryEmit(
                "config" to JSONObject().put("extensionId", extensionId).put("key", key).put("value", value).toString(),
            )
        }
    }

    /** The `activeFile` event/query payload: guest (/workspace) path of the focused file tab, or {}. */
    private fun activeFileEventJson(): String {
        val tab = _editorGroup.value.tabs.firstOrNull { it.id == _editorGroup.value.activeTabId && !it.isPage }
        return JSONObject().apply {
            if (tab != null) {
                put("path", hostToGuestPath(tab.filePath))
                put("name", tab.filePath.name)
                put("dirty", tab.isDirty)
            }
        }.toString()
    }

    /**
     * Host file → the guest path the runtime actually mounts it at. A file inside a workspace project
     * resolves through that project's bind (host dir → distroBindTarget), so nested multi-project
     * workspaces map correctly (e.g. /…/multii/beta/x → /workspace/beta/x, which is where exec.run
     * mounts it — not hostToGuestPath's whole-root guess). Files outside any project fall back to the
     * whole projects-root → /workspace mapping (what the interactive terminal binds), else the raw path.
     */
    private fun hostToGuestPath(file: File): String {
        val p = file.absolutePath
        currentWorkspace.value?.projects.orEmpty().forEach { proj ->
            val projHost = (proj.fsPath as? FsPath.Local)?.file?.absolutePath?.trimEnd('/') ?: return@forEach
            if (p == projHost || p.startsWith("$projHost/")) {
                return proj.distroBindTarget.trimEnd('/') + p.removePrefix(projHost)
            }
        }
        val root = DEFAULT_SHARED_PROJECTS_ROOT.trimEnd('/')
        return if (p.startsWith(root)) "/workspace" + p.removePrefix(root) else p
    }

    /** Entry point for JCodeNative.request — validates the envelope, version, and capability. */
    suspend fun extensionApiRequest(ext: InstalledExtension, envelopeJson: String): String {
        val req = runCatching { JSONObject(envelopeJson) }.getOrNull()
            ?: return apiError("malformed request envelope")
        val type = req.optString("type")
        val payload = req.optJSONObject("payload") ?: JSONObject()
        if (ext.apiMinVersion > EXTENSION_API_VERSION) {
            return apiError("extension requires API v${ext.apiMinVersion}; this JCode has v$EXTENSION_API_VERSION")
        }
        val family = type.substringBefore('.')
        // "service.*" (long-lived runtime servers) is an exec-level privilege — gate it on "exec".
        val requiredCapability = if (family == "service") "exec" else family
        if (family != "api" && !capabilityGranted(ext, requiredCapability)) {
            return apiError("capability '$requiredCapability' is not declared by ${ext.id} or was revoked by the user")
        }
        // An extension opening one of ITS OWN views in the editor — needs the caller id, which the
        // stateless dispatch below doesn't have.
        if (type == "workbench.openView") {
            openExtensionViewPage(ext.id, payload.optString("view"))
            return apiOk(JSONObject())
        }
        // Per-extension settings (config.*) are scoped to the caller — resolved from its declared
        // `settings:` defaults overlaid with the user's saved values. Also handled here for ext.id.
        if (family == "config") {
            return when (type) {
                "config.all" -> apiOk(
                    JSONObject().apply {
                        ext.settings.forEach { s -> put(s.key, resolvedExtensionSetting(ext, s.key)) }
                    },
                )
                "config.get" -> apiOk(JSONObject().put("value", resolvedExtensionSetting(ext, payload.optString("key"))))
                "config.set" -> {
                    val key = payload.optString("key")
                    if (key.isBlank()) apiError("key required")
                    else {
                        setExtensionSetting(ext.id, key, payload.opt("value")?.toString().orEmpty())
                        apiOk(JSONObject())
                    }
                }
                else -> apiError("unknown request type: $type")
            }
        }
        return runCatching { dispatchExtensionApi(type, payload) }
            .getOrElse { apiError(it.message ?: "internal error") }
    }

    private suspend fun dispatchExtensionApi(type: String, p: JSONObject): String = when (type) {
        "api.hello" -> apiOk(
            JSONObject()
                .put("apiVersion", EXTENSION_API_VERSION)
                .put("jcodeVersion", BuildConfig.VERSION_NAME),
        )

        "exec.run" -> {
            val command = p.optString("command")
            require(command.isNotBlank()) { "command required" }
            val timeout = p.optLong("timeoutMs", 60_000L)
            val workdir = p.optString("workdir").ifBlank { null }
            val user = p.optString("user").ifBlank { "root" }
            val env = p.optJSONObject("env")
                ?.let { o -> buildMap { o.keys().forEach { put(it, o.optString(it)) } } }
                .orEmpty()
            val r = if (workdir != null) {
                distroService.exec(command, workdir = workdir, env = env, timeoutMs = timeout, user = user)
            } else {
                distroService.exec(command, env = env, timeoutMs = timeout, user = user)
            }
            apiOk(
                JSONObject().apply {
                    put("stdout", r.stdout)
                    put("stderr", r.stderr)
                    put("exitCode", r.exitCode ?: -1)
                    r.internalError?.let { put("error", it) }
                },
            )
        }

        "fs.read" -> {
            val f = resolveHostFile(p.optString("path"))
            require(f != null && f.isFile) { "not a readable file: ${p.optString("path")}" }
            require(f.length() <= 2_000_000) { "file too large for fs.read (>2MB); use exec.run" }
            apiOk(JSONObject().put("content", f.readText()))
        }

        "fs.write" -> {
            val f = resolveHostFile(p.optString("path"))
            require(f != null) { "unresolvable path: ${p.optString("path")}" }
            f.parentFile?.mkdirs()
            f.writeText(p.optString("content"))
            apiOk(JSONObject())
        }

        "fs.list" -> {
            val f = resolveHostFile(p.optString("path"))
            require(f != null && f.isDirectory) { "not a directory: ${p.optString("path")}" }
            val entries = org.json.JSONArray()
            f.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { c ->
                entries.put(
                    JSONObject()
                        .put("name", c.name)
                        .put("dir", c.isDirectory)
                        .put("size", if (c.isFile) c.length() else 0),
                )
            }
            apiOk(JSONObject().put("entries", entries))
        }

        "workbench.openFile" -> {
            val f = resolveHostFile(p.optString("path"))
            require(f != null && f.isFile) { "not an openable file: ${p.optString("path")}" }
            _bringEditorToFront.tryEmit(Unit)
            openLocalFile(f, p.optInt("line", 0).takeIf { it > 0 }, p.optInt("column", 0).takeIf { it > 0 })
            apiOk(JSONObject())
        }

        "workbench.notify" -> {
            emitMessage(p.optString("message").take(300))
            apiOk(JSONObject())
        }

        "workbench.openUrl" -> {
            val url = p.optString("url")
            require(url.startsWith("http://") || url.startsWith("https://")) { "http(s) URLs only" }
            ProjectRunner.openInBrowser(appContext, url, "")
            apiOk(JSONObject())
        }

        "service.start" -> {
            val id = p.optString("id").ifBlank { "service" }
            val command = p.optString("command")
            require(command.isNotBlank()) { "command required" }
            val started = startRuntimeService(
                id = id,
                command = command,
                user = p.optString("user").ifBlank { "root" },
                extraPath = p.optString("extraPath"),
            )
            apiOk(JSONObject().put("running", started).put("id", id))
        }

        "service.stop" -> {
            stopRuntimeService(p.optString("id").ifBlank { "service" })
            apiOk(JSONObject())
        }

        "service.status" -> {
            val id = p.optString("id").ifBlank { "service" }
            apiOk(JSONObject().put("running", runtimeServices[id]?.isAlive == true).put("id", id))
        }

        "workbench.activeFile" -> apiOk(JSONObject(activeFileEventJson()))

        "workbench.projectInfo" -> apiOk(
            JSONObject().apply {
                selectedProject.value?.let { proj ->
                    put("name", proj.name)
                    // distroBindTarget is the guest path the project is actually mounted at for exec.run
                    // (hostToGuestPath assumes the terminal's whole-root bind, which exec does not use).
                    if (proj.fsPath is FsPath.Local) put("path", proj.distroBindTarget)
                }
                currentWorkspace.value?.let { put("workspace", it.name) }
            },
        )

        // Every project folder in the open workspace, as guest mount paths — lets a multi-repo extension
        // (e.g. Source Control) enumerate and switch between repositories. Each project's guest path is its
        // distroBindTarget (where the runtime mounts it); SAF projects have no local dir and are skipped.
        "workbench.workspaceFolders" -> apiOk(
            JSONObject().apply {
                val folders = org.json.JSONArray()
                currentWorkspace.value?.projects.orEmpty().forEach { proj ->
                    if (proj.fsPath is FsPath.Local) {
                        folders.put(
                            JSONObject()
                                .put("name", proj.name)
                                .put("path", proj.distroBindTarget),
                        )
                    }
                }
                put("folders", folders)
                currentWorkspace.value?.let { put("workspace", it.name) }
            },
        )

        else -> throw IllegalArgumentException("unknown request type: $type")
    }

    private fun apiOk(data: JSONObject): String =
        JSONObject().put("ok", true).put("data", data).toString()

    private fun apiError(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()

    // Long-lived runtime services (e.g. the opencode agent server behind the OpenChamber Chat UI).
    // A one-shot exec.run can't host a server: proot --kill-on-exit reaps the tree the moment the
    // launcher exec returns. spawnDapProcess gives a piped proot Process the JVM holds open, so the
    // server survives until we destroy() it (which reaps its tree). Keyed by service id.
    private val runtimeServices = ConcurrentHashMap<String, Process>()

    /** Start (or confirm running) a long-lived server inside the runtime. Idempotent per [id]. */
    fun startRuntimeService(id: String, command: String, user: String = "root", extraPath: String = ""): Boolean {
        runtimeServices[id]?.let { if (it.isAlive) return true else runtimeServices.remove(id) }
        val process = distroService.spawnDapProcess(
            command = command,
            userOverride = user,
            extraPath = extraPath,
        ) ?: return false
        runtimeServices[id] = process
        // Drain stdout/stderr so the OS pipe buffers never fill and block the server. Kept as daemon
        // threads (mirrors DebugController's stderr drainer); output is discarded (server has its own log).
        Thread { runCatching { process.inputStream.bufferedReader().forEachLine { } } }
            .apply { isDaemon = true }.start()
        Thread { runCatching { process.errorStream.bufferedReader().forEachLine { } } }
            .apply { isDaemon = true }.start()
        return true
    }

    fun stopRuntimeService(id: String) {
        runtimeServices.remove(id)?.let { runCatching { it.destroy() } }
    }

    /** Reap every runtime service (called on project/workspace close and ViewModel teardown). */
    fun stopAllRuntimeServices() {
        runtimeServices.values.forEach { runCatching { it.destroy() } }
        runtimeServices.clear()
    }

    override fun onCleared() {
        stopAllRuntimeServices()
        super.onCleared()
    }

    /** Open (or focus) the Extension Permissions manager as an in-editor page. */
    fun openExtensionPermissionsPage() {
        val existing = _editorGroup.value.tabs.firstOrNull { it.pageKind == EditorPageKind.ExtensionPermissions }
        if (existing != null) {
            _editorGroup.value = _editorGroup.value.withActiveTabChanged(existing.id)
            return
        }
        val tab = EditorTab.page(EXT_PERMISSIONS_TAB_ID, "Extension Permissions", EditorPageKind.ExtensionPermissions)
        _editorGroup.value = _editorGroup.value.withTabAdded(tab)
    }

    /** Open the detail page for a single SDK entry. Reuses one SDK-detail tab (replaces any other). */
    fun openSdkDetailPage(entryId: String) {
        openDetailPage(SDK_DETAIL_PREFIX + entryId, EditorPageKind.SdkDetail) {
            distroService.sdkCatalogState.value.entries.firstOrNull { it.id == entryId }?.name ?: entryId
        }
    }

    /** Open the detail page for a single language server. Reuses one LSP-detail tab (replaces any other). */
    fun openLspDetailPage(entryId: String) {
        openDetailPage(LSP_DETAIL_PREFIX + entryId, EditorPageKind.LspDetail) {
            distroService.lspCatalogState.value.entries.firstOrNull { it.id == entryId }?.name ?: entryId
        }
    }

    /** Open/focus a per-type detail tab: focus if already open, else replace any other tab of [kind]. */
    private fun openDetailPage(tabId: String, kind: EditorPageKind, title: () -> String) {
        var group = _editorGroup.value
        val existing = group.tabs.firstOrNull { it.id == tabId }
        if (existing != null) {
            _editorGroup.value = group.withActiveTabChanged(existing.id)
            return
        }
        group.tabs.filter { it.pageKind == kind }.forEach { group = group.withTabRemoved(it.id) }
        _editorGroup.value = group.withTabAdded(EditorTab.page(tabId, title(), kind))
    }

    /** Full status re-check (installed + update-available) for the SDK catalog; runs async, no-op if already running. */
    fun checkSdkStatuses() {
        viewModelScope.launch(Dispatchers.IO) { distroService.checkSdkStatuses() }
    }

    /** Full status re-check (installed + update-available) for the LSP catalog; runs async, no-op if already running. */
    fun checkLspStatuses() {
        viewModelScope.launch(Dispatchers.IO) { distroService.checkLspStatuses() }
    }

    fun checkDebugEngineStatuses() {
        viewModelScope.launch(Dispatchers.IO) { distroService.checkDebugEngineStatuses() }
    }

    /**
     * Re-install (upgrade) every SDK / language server / debug engine the last check flagged as
     * updatable, then re-check so the "Update available" markers clear. Deps are already present, so
     * this re-runs each tool's install command directly through the shared Setup terminal.
     */
    fun updateAllToolchains() {
        viewModelScope.launch(Dispatchers.IO) {
            val session = SessionRegistry.registerSession(
                context = getApplication(),
                kind = BackendSessionKind.JOB,
                name = "toolchains:update-all",
            )
            try {
                distroService.sdkCatalogState.value.updatableEntryIds.toList().forEach {
                    distroService.runSdkCatalogAction(it, SdkCatalogAction.Install)
                }
                distroService.lspCatalogState.value.updatableEntryIds.toList().forEach {
                    distroService.runLspCatalogAction(it, LspCatalogAction.Install)
                }
                distroService.debugCatalogState.value.updatableEntryIds.toList().forEach {
                    distroService.runDebugEngineCatalogAction(it, DebugEngineAction.Install)
                }
            } finally {
                session.close()
            }
            distroService.checkSdkStatuses()
            distroService.checkLspStatuses()
            distroService.checkDebugEngineStatuses()
        }
    }

    /** Open the detail page for a single debug engine. Reuses one debug-detail tab (replaces any other). */
    fun openDebugEngineDetailPage(entryId: String) {
        openDetailPage(DEBUG_ENGINE_DETAIL_PREFIX + entryId, EditorPageKind.DebugEngineDetail) {
            distroService.debugCatalogState.value.entries.firstOrNull { it.id == entryId }?.name ?: entryId
        }
    }

    /** Open the detail page for a single extension. Reuses one extension-detail tab (replaces any other). */
    fun openExtensionDetailPage(extensionId: String) {
        openDetailPage(EXT_DETAIL_PREFIX + extensionId, EditorPageKind.ExtensionDetail) {
            _installedExtensions.value.firstOrNull { it.id == extensionId }?.name
                ?: _marketplaceEntries.value.firstOrNull { it.id == extensionId }?.name
                ?: extensionId
        }
    }

    private val _runConfigVersion = MutableStateFlow(0)
    /** Bumped when a run config is saved so the Run panel re-reads `.jcode/run.yaml`. */
    val runConfigVersion: StateFlow<Int> = _runConfigVersion.asStateFlow()

    /** Open the structured Build & Run configuration page for [project]. Reuses one config tab. */
    fun openRunConfigPage(project: Project) {
        openDetailPage(RUN_CONFIG_PREFIX + project.id, EditorPageKind.RunConfig) { "Run: ${project.name}" }
    }

    /** Persist a project's Build & Run config to its `.jcode/run.yaml`. */
    fun saveRunConfig(project: Project, config: dev.jcode.core.config.RunConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { dev.jcode.run.ProjectRunner.saveRunConfig(project, config) }
                .onSuccess {
                    _runConfigVersion.value++
                    _messages.tryEmit("Saved run config for ${project.name}")
                }
                .onFailure { _messages.tryEmit("Failed to save run config: ${it.message ?: "error"}") }
        }
    }

    fun selectEditorTab(tabId: String) {
        _editorGroup.value = _editorGroup.value.withActiveTabChanged(tabId)
        requestSyncOpenFilesFromDisk()
    }

    fun closeEditorTab(tabId: String) {
        val existing = _editorGroup.value.tabs.firstOrNull { it.id == tabId }
        existing?.editorState?.close()
        untrackDirty(tabId)
        diskSignatures.remove(tabId)
        _editorGroup.value = _editorGroup.value.withTabRemoved(tabId)
    }

    /** Per-tab collectors that mirror each editor's dirty flag onto its [EditorTab] for the tab dot. */
    private val dirtyJobs = mutableMapOf<String, Job>()

    private fun trackDirty(tab: EditorTab) {
        val state = tab.editorState ?: return
        val tabId = tab.id
        dirtyJobs.remove(tabId)?.cancel()
        dirtyJobs[tabId] = viewModelScope.launch {
            launch {
                state.dirty.collect { dirty ->
                    val current = _editorGroup.value.tabs.firstOrNull { it.id == tabId } ?: return@collect
                    if (current.isDirty != dirty) {
                        _editorGroup.value = _editorGroup.value.withTabUpdated(current.copy(isDirty = dirty))
                    }
                }
            }
            // Content edits that keep the tab dirty don't change the EditorGroup, so persist the unsaved
            // buffer on snapshot changes too (debounced) to capture in-progress typing before a kill.
            launch {
                state.snapshot.collect { scheduleSessionSave() }
            }
        }
    }

    private fun untrackDirty(tabId: String) {
        dirtyJobs.remove(tabId)?.cancel()
    }

    /**
     * A clean tab mirrors the file on disk, so when an external writer (e.g. an agent in the terminal)
     * changes the file we reload it. Detection is by a (lastModified, size) signature rather than
     * FileObserver: proot/terminal writes happen in another mount namespace and don't fire the app's
     * inotify, but [File.lastModified]/[File.length] still reflect the ext4 inode. The signature is
     * captured at open and after every save/discard, so our own writes never trigger a reload.
     */
    private data class DiskSignature(val lastModified: Long, val size: Long)

    private val diskSignatures = ConcurrentHashMap<String, DiskSignature>()
    private val syncMutex = Mutex()
    private var lastReloadNoticeAt = 0L

    private fun File.diskSignatureOrNull(): DiskSignature? =
        if (exists() && isFile) DiskSignature(lastModified(), length()) else null

    /** Foreground re-sync trigger, driven by a RESUMED loop in JCodeApp and on tab switch. */
    fun requestSyncOpenFilesFromDisk() {
        viewModelScope.launch { syncOpenFilesFromDisk() }
    }

    /** Reload every clean, local, still-text tab whose file changed on disk since we last read it. */
    private suspend fun syncOpenFilesFromDisk() {
        if (!syncMutex.tryLock()) return // a sync is already running; the next tick re-sweeps everything
        try {
            val reloaded = mutableListOf<String>()
            for (tab in _editorGroup.value.tabs) {
                val state = tab.editorState ?: continue   // page tab
                if (tab.isDirty) continue                 // fast skip; replaceAll re-checks atomically
                val file = tab.filePath
                if (file.path.isBlank()) continue         // SAF / non-file source
                val signature = withContext(Dispatchers.IO) { file.diskSignatureOrNull() }
                    ?: continue                           // deleted on disk: keep the open copy
                val known = diskSignatures[tab.id]
                if (known == null) {                      // first sight: establish a baseline
                    diskSignatures[tab.id] = signature
                    continue
                }
                if (known == signature) continue          // unchanged
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { workspaceManager.fsFor(FsPath.Local(file)).read(FsPath.Local(file)) }.getOrNull()
                } ?: continue
                // If the file changed again while we were reading, defer: leave the known signature so the
                // next tick re-detects and loads the stabilized content (stored signature stays == buffer).
                val afterRead = withContext(Dispatchers.IO) { file.diskSignatureOrNull() }
                if (afterRead != signature) continue
                if (!bytes.isLikelyText()) {              // became binary/too large: keep the open copy
                    diskSignatures[tab.id] = signature    // but mark this version seen
                    continue
                }
                // replaceAll atomically re-checks dirty on the editor's single writer, so a keystroke that
                // landed during our read aborts the reload (returns false) instead of being clobbered.
                if (state.replaceAll(bytes.toString(Charsets.UTF_8), onlyIfClean = true)) {
                    diskSignatures[tab.id] = signature
                    reloaded += tab.title
                }
            }
            if (reloaded.isNotEmpty()) {
                val now = System.currentTimeMillis()
                if (now - lastReloadNoticeAt > RELOAD_NOTICE_THROTTLE_MS) {
                    lastReloadNoticeAt = now
                    emitMessage(
                        if (reloaded.size == 1) "Reloaded ${reloaded.first()} from disk"
                        else "Reloaded ${reloaded.size} files from disk"
                    )
                }
            }
        } finally {
            syncMutex.unlock()
        }
    }

    /** Save the active editor tab's buffer to disk (Ctrl+S / top-bar Save). */
    fun saveActiveTab() {
        _editorGroup.value.activeTab?.let { saveTab(it) }
    }

    /** Save every open tab with unsaved changes (Save button long-press → Save all). */
    fun saveAllTabs() {
        val dirty = _editorGroup.value.tabs.filter { it.isDirty && it.editorState != null }
        if (dirty.isEmpty()) {
            viewModelScope.launch { emitMessage("No unsaved changes") }
            return
        }
        dirty.forEach { saveTab(it) }
    }

    /** Undo the last edit in the active editor (Save button long-press → Undo). */
    fun undoActiveTab() {
        _editorGroup.value.activeTab?.editorState?.undoManager?.undo()
    }

    /** Redo the last undone edit in the active editor (Save button long-press → Redo). */
    fun redoActiveTab() {
        _editorGroup.value.activeTab?.editorState?.undoManager?.redo()
    }

    /** Reload the active tab from disk, throwing away its unsaved edits (Save long-press → Discard). */
    fun discardActiveTab() {
        val tab = _editorGroup.value.activeTab ?: return
        val state = tab.editorState ?: return
        if (!tab.isDirty) return
        val file = tab.filePath
        if (file.path.isBlank()) {
            viewModelScope.launch { emitMessage("Can't discard \"${tab.title}\": unsupported file source") }
            return
        }
        viewModelScope.launch {
            val bytes = runCatching {
                withContext(Dispatchers.IO) { workspaceManager.fsFor(FsPath.Local(file)).read(FsPath.Local(file)) }
            }.getOrElse {
                emitMessage("Failed to discard ${tab.title}: ${it.message ?: "error"}")
                return@launch
            }
            state.replaceAll(bytes.toString(Charsets.UTF_8)) // force: discard intentionally drops edits
            withContext(Dispatchers.IO) { file.diskSignatureOrNull() }?.let { diskSignatures[tab.id] = it }
            emitMessage("Discarded changes in ${tab.title}")
        }
    }

    /** Format the active tab with the built-in formatter, honoring its language pack's rules. */
    fun formatActiveTab() {
        val tab = _editorGroup.value.activeTab ?: return
        val state = tab.editorState ?: run {
            viewModelScope.launch { emitMessage("Nothing to format") }
            return
        }
        val name = tab.filePath.name
        val lang = activeLanguageExtensions.value.firstNotNullOfOrNull { ext -> ext.languageFor(name) }
        viewModelScope.launch {
            val snap = state.snapshot.value
            val original = snap.readRangeAsUtf16(0, snap.byteLength)
            val formatted = dev.jcode.editor.CodeFormatter.format(original, lang)
            if (formatted == original) {
                emitMessage("Already formatted")
                return@launch
            }
            state.applyEdit(EditTx.replace(0, snap.byteLength, formatted))
            val newLen = state.snapshot.value.byteLength
            val caret = (state.carets.value.firstOrNull()?.head ?: 0).coerceIn(0, newLen)
            state.setSelection(listOf(Caret(caret, caret)))
            emitMessage("Formatted ${tab.title}")
        }
    }

    private fun saveTab(tab: EditorTab) {
        val state = tab.editorState ?: return // page tab: nothing to persist
        val file = tab.filePath
        if (file.path.isBlank()) {
            viewModelScope.launch { emitMessage("Can't save \"${tab.title}\": unsupported file source") }
            return
        }
        // Snapshots are immutable, so capturing the reference now lets us both write its bytes and
        // detect whether newer edits landed during the async write (so we don't clear a stale dirty).
        val snapshot = state.snapshot.value
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val bytes = snapshot.readRange(0, snapshot.byteLength)
                workspaceManager.fsFor(FsPath.Local(file)).write(FsPath.Local(file), bytes)
            }.onSuccess {
                if (state.snapshot.value === snapshot) state.markClean()
                file.diskSignatureOrNull()?.let { diskSignatures[tab.id] = it }
                emitMessage("Saved ${tab.title}")
                queueSyntaxCheck(file)
            }.onFailure {
                emitMessage("Failed to save ${tab.title}: ${it.message ?: "error"}")
            }
        }
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

    private suspend fun openLocalFile(file: File, line: Int? = null, column: Int? = null) {
        val stableId = file.absolutePath
        val existing = _editorGroup.value.tabs.firstOrNull { it.id == stableId }
        if (existing != null) {
            _editorGroup.value = _editorGroup.value.withActiveTabChanged(existing.id)
            existing.editorState?.requestRevealAt(line, column)
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
        // Set the reveal before the tab is shown so the view applies it as soon as it attaches.
        tab.editorState?.requestRevealAt(line, column)
        trackDirty(tab)
        file.diskSignatureOrNull()?.let { diskSignatures[stableId] = it }
        _editorGroup.value = _editorGroup.value.withTabAdded(tab)
        queueSyntaxCheck(file)
    }

    /** Convert a 1-based (line, optional column) to the editor's 0-based reveal request. */
    private fun dev.jcode.core.editor.EditorState.requestRevealAt(line: Int?, column: Int?) {
        if (line == null) return
        requestReveal((line - 1).coerceAtLeast(0), ((column ?: 1) - 1).coerceAtLeast(0))
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
        trackDirty(tab)
        _editorGroup.value = _editorGroup.value.withTabAdded(tab)
    }

    // --- Session restore: reopen the last workspace/project + tabs (with unsaved edits) on launch. ---

    private suspend fun restoreSessionOnLaunch() {
        try {
            val enabled = uiPreferences.data.map { it[restoreLastSessionKey] ?: true }.first()
            if (!enabled) return
            val record = withContext(Dispatchers.IO) { sessionStore.load() } ?: return

            // Workspace: only switch if it still exists (a dangling id would blank the workbench).
            val targetWs = record.workspaceId
            if (targetWs != null && targetWs != currentWorkspace.value?.id &&
                workspaceManager.workspaceExists(targetWs)
            ) {
                workspaceManager.openWorkspace(targetWs)
            }
            // Wait for the workspace (and its project list) to load so the project selection sticks.
            val workspace = withTimeoutOrNull(4_000) {
                currentWorkspace.first { it != null && (targetWs == null || it.id == targetWs) }
            }

            // Project: only select if it still belongs to the restored workspace.
            val targetProj = record.projectId
            if (targetProj != null && workspace?.projects?.any { it.id == targetProj } == true) {
                _selectedProjectId.value = targetProj
            }

            // Tabs: open each surviving file; recover unsaved buffers where the file (or its folder) remains.
            for (t in record.tabs) {
                val file = File(t.filePath)
                if (t.dirty && t.bufferFileName != null) {
                    val text = withContext(Dispatchers.IO) { sessionStore.readBuffer(t.bufferFileName) }
                    val parentExists = file.parentFile?.isDirectory == true
                    if (text != null && (file.isFile || parentExists)) {
                        openRestoredDirtyTab(file, t.id, text)
                    }
                } else if (file.isFile) {
                    openLocalFile(file)
                }
            }

            // Active tab (only if it survived as one of the restored tabs).
            record.activeTabId?.let { id ->
                if (_editorGroup.value.tabs.any { it.id == id }) {
                    _editorGroup.value = _editorGroup.value.withActiveTabChanged(id)
                }
            }
        } finally {
            suppressBootstrap = false
            sessionSaveEnabled = true
            // If restore opened no file tab (opt-out, no saved session, or every file was missing), fall
            // back to the normal project bootstrap. The composition's bootstrap effect may have already
            // fired and no-op'd while suppressed; this covers the case where the project is ready now.
            if (_editorGroup.value.tabs.none { !it.isPage }) {
                ensureProjectBootstrapTab()
            }
        }
    }

    /** Reopen a tab that had unsaved edits: load the file (or an empty buffer if it's gone) then re-apply
     *  the recovered text as an edit so the tab opens dirty and a save rewrites the file. */
    private suspend fun openRestoredDirtyTab(file: File, stableId: String, text: String) {
        if (_editorGroup.value.tabs.any { it.id == stableId }) return
        val tab = EditorTab.create(file, stableId)
        applyConfigToTab(tab, effectiveConfig.value)
        trackDirty(tab)
        file.diskSignatureOrNull()?.let { diskSignatures[stableId] = it }
        // Add to the group first so trackDirty's collector can mirror the dirty flag once we edit below.
        _editorGroup.value = _editorGroup.value.withTabAdded(tab)
        tab.editorState?.let { state ->
            state.applyEdit(EditTx.replace(0, state.snapshot.value.byteLength, text))
        }
    }

    /** Persist the current workbench (open workspace/project + file tabs + unsaved buffers). Debounced. */
    private suspend fun persistSession() {
        if (!sessionSaveEnabled) return
        val group = _editorGroup.value
        val fileTabs = group.tabs.filter {
            !it.isPage && it.editorState != null && it.filePath.path.isNotBlank()
        }
        val wsId = currentWorkspace.value?.id
        val projId = _selectedProjectId.value
        withContext(Dispatchers.IO) {
            val tabRecords = fileTabs.map { tab ->
                val state = tab.editorState!!
                if (tab.isDirty) {
                    val snap = state.snapshot.value
                    val name = sessionStore.writeBuffer(tab.id, snap.readRangeAsUtf16(0, snap.byteLength))
                    SessionTabRecord(tab.id, tab.filePath.absolutePath, dirty = true, bufferFileName = name)
                } else {
                    SessionTabRecord(tab.id, tab.filePath.absolutePath, dirty = false, bufferFileName = null)
                }
            }
            sessionStore.saveManifest(
                SessionRecord(
                    workspaceId = wsId,
                    projectId = projId,
                    activeTabId = group.activeTabId?.takeIf { id -> fileTabs.any { it.id == id } },
                    tabs = tabRecords,
                )
            )
            sessionStore.pruneBuffers(tabRecords.mapNotNull { it.bufferFileName }.toSet())
        }
    }

    /** Best-effort synchronous-ish flush from the Activity's onStop (covers graceful backgrounding). */
    fun flushSessionNow() {
        viewModelScope.launch { persistSession() }
    }

    private suspend fun emitMessage(message: String) {
        _messages.emit(message)
        // Persist transient toasts in the Output channel too (errors flagged for coloring).
        val isError = message.contains("Failed", ignoreCase = true) ||
            message.contains("Can't", ignoreCase = true) ||
            message.contains("error", ignoreCase = true)
        OutputLog.append(message, if (isError) OutputKind.Error else OutputKind.Info)
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

    private fun runLspCatalogAction(
        entryId: String,
        action: LspCatalogAction,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val session = SessionRegistry.registerSession(
                context = getApplication(),
                kind = BackendSessionKind.JOB,
                name = "lsp:${action.name.lowercase()}:$entryId",
            )
            try {
                distroService.runLspCatalogAction(entryId, action)
            } finally {
                session.close()
            }
        }
    }

    private fun runDebugCatalogAction(
        entryId: String,
        action: DebugEngineAction,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val session = SessionRegistry.registerSession(
                context = getApplication(),
                kind = BackendSessionKind.JOB,
                name = "debug:${action.name.lowercase()}:$entryId",
            )
            try {
                distroService.runDebugEngineCatalogAction(entryId, action)
            } finally {
                session.close()
            }
        }
    }

    companion object {
        private const val RELOAD_NOTICE_THROTTLE_MS = 4_000L
        const val SETTINGS_TAB_ID = "jcode://settings"
        const val ENVIRONMENT_TAB_ID = "jcode://environment"
        const val SDK_DETAIL_PREFIX = "jcode://sdk/"
        const val LSP_DETAIL_PREFIX = "jcode://lsp/"
        const val DEBUG_ENGINE_DETAIL_PREFIX = "jcode://debug-engine/"
        const val EXT_DETAIL_PREFIX = "jcode://ext/"
        const val EXT_APP_PREFIX = "jcode://ext-app/"
        const val EXT_PERMISSIONS_TAB_ID = "jcode://ext-permissions"
        const val RUN_CONFIG_PREFIX = "jcode://run-config/"
    }
}
