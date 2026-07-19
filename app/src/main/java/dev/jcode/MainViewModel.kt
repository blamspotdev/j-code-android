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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.jcode.design.BottomBarVisibility
import dev.jcode.design.ExtraKeysVisibility
import dev.jcode.design.SettingsDefaults
import dev.jcode.design.TabColoring
import dev.jcode.design.TabMaxSize
import dev.jcode.design.ThemeMode
import dev.jcode.design.VolumeKeyAction
import dev.jcode.design.randomTabColor
import dev.jcode.design.tabColorFromHex
import dev.jcode.design.tabColorToHex
import dev.jcode.editor.SyntaxHighlighter
import dev.jcode.feature.editor.pane.EditorGroup
import dev.jcode.feature.editor.pane.EditorPageKind
import dev.jcode.feature.editor.pane.EditorTab
import dev.jcode.feature.marketplace.BundledExtensionSpec
import dev.jcode.feature.marketplace.ExtensionActivation
import dev.jcode.feature.marketplace.ExtensionDeps
import dev.jcode.feature.marketplace.InstalledExtension
import dev.jcode.feature.marketplace.languageFor
import dev.jcode.feature.marketplace.MarketplaceEntry
import dev.jcode.feature.marketplace.MarketplaceServiceLocator
import dev.jcode.feature.marketplace.ProjectTemplate
import dev.jcode.feature.marketplace.TemplateCatalog
import dev.jcode.feature.marketplace.TemplateScaffolder
import dev.jcode.workbench.SetupTerminalRunner
import dev.jcode.fs.FsKind
import dev.jcode.fs.FsNode
import dev.jcode.fs.FsPath
import dev.jcode.fs.copyFolderToLocal
import dev.jcode.fs.copyLocalTreeToDocumentTree
import dev.jcode.fs.folderDisplayName
import dev.jcode.fs.Project
import dev.jcode.fs.ProjectKind
import dev.jcode.fs.RecentEntity
import dev.jcode.fs.scanFolderForImport
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Version of the JCode <-> extension request/event API served by this app build. Bump when route
 *  families or envelope semantics change; extensions gate on it via their manifest's api.minApiVersion. */
const val EXTENSION_API_VERSION = 1

/** Upper bound on per-push explorer decoration/submodule entries — a runaway or malicious extension
 *  must not be able to grow the decoration maps without limit. */
const val MAX_EXPLORER_DECORATIONS = 20_000

/**
 * Process-singleton holder for the app-level UI-preferences DataStore. A DataStore must be created
 * exactly once per file per process; constructing a second one (e.g. when MainViewModel is rebuilt
 * after the Activity is recreated) throws "multiple DataStores active for the same file".
 */
/** (lastModified, size) fingerprint used to detect external edits to an open file. */
private data class DiskSignature(val lastModified: Long, val size: Long)

/** Result of probing + preparing a file to open, computed off the main thread (see openLocalFile). */
private sealed interface OpenPrep {
    data object Missing : OpenPrep
    data object Image : OpenPrep
    data object Binary : OpenPrep
    data class Text(val tab: EditorTab, val signature: DiskSignature?) : OpenPrep
}

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

/** The user's choice in the "unsaved changes" prompt shown when closing editor tabs. */
enum class EditorCloseChoice { SAVE, DISCARD, CLOSE_SAVED, CANCEL }

/** Drives the editor "unsaved changes" dialog: the titles of the dirty tabs about to be closed. */
data class PendingEditorClose(val dirtyTitles: List<String>)

/** Live state of an external-folder import, driving the progress modal. During [ImportPhase.Scanning]
 *  the total is unknown ([total] == 0 → indeterminate); during [ImportPhase.Copying], [done]/[total]
 *  files gives a determinate bar. */
enum class ImportPhase { Scanning, Copying }

data class ImportProgress(
    val label: String,
    val phase: ImportPhase,
    val done: Int = 0,
    val total: Int = 0,
)

/** A folder awaiting the Project/Workspace choice: opened in place on managed storage, or copied into
 *  /sources and awaiting adoption. [label] names it in the dialog. */
sealed interface PendingFolderType {
    val label: String

    data class OpenInPlace(val path: FsPath) : PendingFolderType {
        override val label: String get() = path.displayName
    }

    data class AdoptStaged(val staged: File) : PendingFolderType {
        override val label: String get() = staged.name
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

    private val _runTerminalCompletions = MutableSharedFlow<Pair<String, Int>>(extraBufferCapacity = 16)

    /** (sessionId, exitCode) for every terminal task that reported completion via OSC 7713. The
     *  workbench consumes this to mark a run finished (done/killed) when the command bound to its
     *  terminal exits — a run config binds to its single command's process. */
    val runTerminalCompletions: SharedFlow<Pair<String, Int>> = _runTerminalCompletions.asSharedFlow()

    init {
        TerminalSessionHost.manager(appContext).onTaskComplete = { sessionId, payload ->
            setupTerminalRunner.handleTaskComplete(sessionId, payload)
            _runTerminalCompletions.tryEmit(sessionId to (payload.substringAfter(';').trim().toIntOrNull() ?: -1))
        }
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
            // Gate the Extension Dev log to dev (unsigned sideloaded) extensions only.
            dev.jcode.workbench.ExtensionDevLog.devIds = installed.filter { it.dev }.map { it.id }.toSet()
            // Any extension may contribute templates (a language/dev pack can bundle them too).
            // Always offer an "Empty Project" first — a blank folder that needs no extension.
            val fromExtensions = installed.flatMap { it.templates }
            val emptyOption = fromExtensions.filter { it.id == "empty" }.ifEmpty {
                listOf(ProjectTemplate(id = "empty", name = "Empty Project", description = "A blank project folder — no scaffolding."))
            }
            _templates.value = emptyOption + fromExtensions.filter { it.id != "empty" }
        }
    }

    /**
     * Sideload an extension from a picked `.jext` [uri] (Developer options). Streams it to a cache
     * file, installs it (unsigned → marked debuggable; signed → installed normally), refreshes, and
     * reports the outcome. See [ExtensionInstaller.installLocalJext].
     */
    fun sideloadExtension(uri: android.net.Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File.createTempFile("sideload", ".jext", appContext.cacheDir)
                    try {
                        appContext.contentResolver.openInputStream(uri)?.use { input ->
                            tmp.outputStream().use { input.copyTo(it) }
                        } ?: error("cannot open the selected file")
                        extensionInstaller.installLocalJext(tmp, BuildConfig.VERSION_NAME).getOrThrow()
                    } finally {
                        tmp.delete()
                    }
                }
            }
            result
                .onSuccess { r ->
                    refreshInstalledExtensions()
                    emitMessage(
                        if (r.signed) "Installed '${r.extension.name}' (signed — not debuggable)."
                        else "Loaded '${r.extension.name}' (unsigned dev extension).",
                    )
                }
                .onFailure { emitMessage("Sideload failed: ${it.message ?: "invalid .jext"}") }
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

        // A code UPDATE/REINSTALL of an already-installed extension must never be blocked on
        // (re)installing its toolchains: the extension is already present, and its required SDKs/LSPs
        // (e.g. Node + npm-based language servers) may legitimately be uninstallable in the current
        // runtime. Only a FRESH install resolves the index-declared `requires` up front with
        // abort-on-fail; for an update we land the new package and let the post-install pass below
        // attempt any missing toolchains best-effort, so the code update always applies.
        val freshInstall = !extensionInstaller.isInstalled(entry.id)
        if (freshInstall && !entry.requires.isEmpty) {
            setExtensionPhase(entry.id, "Installing required tools…")
            // Deps the marketplace index declared for this entry — resolved before install; a failure
            // aborts because the extension asked for them up front.
            if (!resolveRequires(entry.name, entry.requires, visiting, abortOnFail = true)) return false
        }

        setExtensionPhase(entry.id, "Installing…")
        val result = extensionInstaller.install(entry, BuildConfig.VERSION_NAME)
            .onFailure { _messages.tryEmit("Install failed: ${it.message ?: "error"}") }
        if (result.isFailure) {
            setExtensionPhase(entry.id, null)
            return false
        }

        setExtensionPhase(entry.id, "Verifying…")
        val installedEntry = runCatching { extensionInstaller.installed() }
            .getOrDefault(emptyList())
            .firstOrNull { it.id == entry.id }
        if (installedEntry == null) {
            setExtensionPhase(entry.id, null)
            _messages.tryEmit("${entry.name}: installed but not detected on disk — install failed.")
            return false
        }
        _messages.tryEmit("Installed ${entry.name}")

        // The marketplace index currently omits `requires`, so the pre-install pass above sees nothing
        // for most extensions. Re-resolve from the freshly-installed manifest (the authoritative
        // source) to pull chained extensions (e.g. Android Dev Pack → Kotlin) and toolchains the index
        // left out. Non-fatal: the extension is already installed and usable on its own.
        if (!installedEntry.requires.isEmpty) {
            setExtensionPhase(entry.id, "Installing required tools…")
            resolveRequires(entry.name, installedEntry.requires, visiting, abortOnFail = false)
        }
        setExtensionPhase(entry.id, null)
        return true
    }

    /**
     * Install everything [deps] names that isn't already present: required extensions (recursively),
     * then toolchain SDKs and language servers via the catalogs. With [abortOnFail] true a failure
     * returns false so the caller rolls back its own install; with false, failures are surfaced but
     * skipped so an already-installed extension isn't undone over an optional-in-practice dependency.
     */
    private suspend fun resolveRequires(
        sourceName: String,
        deps: ExtensionDeps,
        visiting: MutableSet<String>,
        abortOnFail: Boolean,
    ): Boolean {
        for (depId in deps.extensions) {
            if (depId in visiting) continue
            if (_installedExtensions.value.any { it.id == depId }) continue
            val depEntry = _marketplaceEntries.value.firstOrNull { it.id == depId }
            if (depEntry == null) {
                _messages.tryEmit("$sourceName: required extension '$depId' isn't in the marketplace.")
                if (abortOnFail) return false else continue
            }
            _messages.tryEmit("Installing required extension: ${depEntry.name}…")
            if (!installExtensionResolvingDeps(depEntry, visiting)) {
                _messages.tryEmit("$sourceName: required extension ${depEntry.name} failed.")
                if (abortOnFail) return false
            }
        }
        for (sdkId in deps.sdks) {
            if (sdkId in distroService.sdkCatalogState.value.installedEntryIds) continue
            _messages.tryEmit("Installing required toolchain: $sdkId…")
            if (!installRequiredSdk(sdkId)) {
                val reason = distroService.sdkCatalogState.value.errorMessage ?: "install failed"
                _messages.tryEmit("$sourceName: required toolchain '$sdkId' — $reason")
                if (abortOnFail) return false
            }
        }
        for (lspId in deps.lsps) {
            if (lspId in distroService.lspCatalogState.value.installedEntryIds) continue
            _messages.tryEmit("Installing required language server: $lspId…")
            if (!installRequiredLsp(lspId)) {
                val reason = distroService.lspCatalogState.value.errorMessage ?: "install failed"
                _messages.tryEmit("$sourceName: required language server '$lspId' — $reason")
                if (abortOnFail) return false
            }
        }
        for (dbgId in deps.dbg) {
            if (dbgId in distroService.debugCatalogState.value.installedEntryIds) continue
            _messages.tryEmit("Installing required debugger: $dbgId…")
            if (!installRequiredDbg(dbgId)) {
                val reason = distroService.debugCatalogState.value.errorMessage ?: "install failed"
                _messages.tryEmit("$sourceName: required debugger '$dbgId' — $reason")
                if (abortOnFail) return false
            }
        }
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

    private suspend fun installRequiredDbg(entryId: String): Boolean = withContext(Dispatchers.IO) {
        val entry = DebugEngineCatalog.findById(entryId)
        if (entry != null && !installRequiredSdks(entry.requiredSdks, entry.name)) return@withContext false
        val session = SessionRegistry.registerSession(
            context = getApplication(),
            kind = BackendSessionKind.JOB,
            name = "dbg:install:$entryId",
        )
        try {
            distroService.runDebugEngineCatalogAction(entryId, DebugEngineAction.Install)
        } finally {
            session.close()
        }
        entryId in distroService.debugCatalogState.value.installedEntryIds
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

    /** After a folder is opened as a project while a User Workspace is open (e.g. from a clone in the
     *  SCM extension), the registered project awaiting an open-vs-add choice. */
    private val _postClonePrompt = MutableStateFlow<Project?>(null)
    val postClonePrompt: StateFlow<Project?> = _postClonePrompt.asStateFlow()

    /** A folder awaiting a Project/Workspace choice (it has no `.jcode` type yet) — either opened in
     *  place on managed storage, or already copied into /sources staging and awaiting adoption. */
    private val _openFolderTypePrompt = MutableStateFlow<PendingFolderType?>(null)
    val openFolderTypePrompt: StateFlow<PendingFolderType?> = _openFolderTypePrompt.asStateFlow()

    /** Non-null while an off-ext4 folder is being scanned/copied into /sources — drives the modal. */
    private val _importProgress = MutableStateFlow<ImportProgress?>(null)
    val importProgress: StateFlow<ImportProgress?> = _importProgress.asStateFlow()

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

    /** Serialize the app-preferences DataStore for the Settings > Backup "Export settings" action. */
    suspend fun exportSettingsJson(): String = SettingsBackup.export(uiPreferences)

    /** Apply a previously-exported settings document; returns how many settings were restored. */
    suspend fun importSettingsJson(document: String): Int = SettingsBackup.import(uiPreferences, document)

    private val _envBackupStatus = MutableStateFlow<String?>(null)
    /** Non-null while an environment backup/restore runs; the message drives a modal progress dialog. */
    val envBackupStatus = _envBackupStatus.asStateFlow()

    /** Pack the active environment's rootfs to a SAF-created .tar.gz [uri]. */
    fun backupEnvironmentTo(uri: android.net.Uri) {
        if (_envBackupStatus.value != null) return
        viewModelScope.launch {
            _envBackupStatus.value = "Preparing backup…"
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(uri)?.use { os ->
                        distroService.packSelectedEnvironment(os) { files, bytes ->
                            _envBackupStatus.value = "Backing up… $files files (${bytes / (1024 * 1024)} MB)"
                        }
                    } ?: error("Could not open the destination file")
                }
            }.getOrDefault(false)
            _messages.tryEmit(if (ok) "Environment backed up." else "Environment backup failed.")
            _envBackupStatus.value = null
        }
    }

    /** Restore the active environment's rootfs from a SAF-picked .tar.gz [uri], replacing it. */
    fun restoreEnvironmentFrom(uri: android.net.Uri) {
        if (_envBackupStatus.value != null) return
        viewModelScope.launch {
            _envBackupStatus.value = "Restoring environment…"
            val ok = runCatching {
                val tmp = withContext(Dispatchers.IO) {
                    val t = java.io.File(appContext.cacheDir, "env-restore-${System.nanoTime()}.tar.gz")
                    appContext.contentResolver.openInputStream(uri)?.use { input ->
                        t.outputStream().use { input.copyTo(it, 1 shl 16) }
                    } ?: error("Could not read the backup file")
                    t
                }
                val restored = distroService.restoreSelectedEnvironment(tmp)
                withContext(Dispatchers.IO) { tmp.delete() }
                restored
            }.getOrDefault(false)
            _messages.tryEmit(if (ok) "Environment restored." else "Environment restore failed.")
            _envBackupStatus.value = null
        }
    }

    /**
     * Restore the environment from a backup during ONBOARDING: copy the picked .tar.gz to cache, arm the
     * DistroInstalled step to restore from it (instead of downloading), then run the full setup pipeline
     * so proot / jcode-user / smoke-test still run and produce a working environment. Progress shows in
     * the onboarding "Setup log" and finishes with the normal "Done" completion.
     */
    fun restoreEnvironmentOnboarding(uri: android.net.Uri) {
        viewModelScope.launch {
            val tmp = withContext(Dispatchers.IO) {
                runCatching {
                    val t = java.io.File(appContext.cacheDir, "env-restore-onboarding-${System.nanoTime()}.tar.gz")
                    appContext.contentResolver.openInputStream(uri)?.use { input ->
                        t.outputStream().use { input.copyTo(it, 1 shl 16) }
                    } ?: error("Could not read the backup file")
                    t
                }.getOrNull()
            }
            if (tmp == null) {
                _messages.tryEmit("Could not read the backup file.")
                return@launch
            }
            distroService.setPendingRestoreTarball(tmp)
            runAutoSetup()
        }
    }

    private val hardwareAccelerationKey = booleanPreferencesKey("perf_hardware_acceleration")

    /** GPU-accelerated window rendering (default on). Applied by MainActivity at window creation, so
     *  changes take effect on the next app start. The value is mirrored into synchronous
     *  SharedPreferences because the window flag must be read before any async storage is available. */
    val hardwareAcceleration: StateFlow<Boolean> = uiPreferences.data
        .map { prefs -> prefs[hardwareAccelerationKey] ?: SettingsDefaults.HARDWARE_ACCELERATION }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsDefaults.HARDWARE_ACCELERATION)

    fun setHardwareAcceleration(enabled: Boolean) {
        getApplication<Application>()
            .getSharedPreferences(MainActivity.UI_STARTUP_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(MainActivity.KEY_HW_ACCELERATION, enabled).apply()
        viewModelScope.launch { uiPreferences.edit { it[hardwareAccelerationKey] = enabled } }
    }

    // Explorer "hide files at the project root" preference: a mode + the user's newline-separated
    // by-line pattern list. The injected (.gitignore) list lives in hiddenInjected below.
    private val explorerHiddenModeKey = stringPreferencesKey("explorer_hidden_root_mode")
    val explorerHiddenMode: StateFlow<dev.jcode.design.ExplorerHiddenMode> = uiPreferences.data
        .map { prefs ->
            runCatching { dev.jcode.design.ExplorerHiddenMode.valueOf(prefs[explorerHiddenModeKey] ?: "") }
                .getOrDefault(SettingsDefaults.HIDDEN_ROOT_MODE)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsDefaults.HIDDEN_ROOT_MODE)

    fun setExplorerHiddenMode(mode: dev.jcode.design.ExplorerHiddenMode) {
        viewModelScope.launch { uiPreferences.edit { it[explorerHiddenModeKey] = mode.name } }
    }

    private val explorerExcludeEffectKey = stringPreferencesKey("explorer_exclude_effect")
    val explorerExcludeEffect: StateFlow<dev.jcode.design.ExplorerExcludeEffect> = uiPreferences.data
        .map { prefs ->
            runCatching { dev.jcode.design.ExplorerExcludeEffect.valueOf(prefs[explorerExcludeEffectKey] ?: "") }
                .getOrDefault(SettingsDefaults.EXCLUDE_EFFECT)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsDefaults.EXCLUDE_EFFECT)

    fun setExplorerExcludeEffect(effect: dev.jcode.design.ExplorerExcludeEffect) {
        viewModelScope.launch { uiPreferences.edit { it[explorerExcludeEffectKey] = effect.name } }
    }

    private val explorerHiddenPatternsKey = stringPreferencesKey("explorer_hidden_root_patterns")
    val explorerHiddenPatterns: StateFlow<String> = uiPreferences.data
        .map { prefs -> prefs[explorerHiddenPatternsKey] ?: SettingsDefaults.HIDDEN_ROOT_PATTERNS }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsDefaults.HIDDEN_ROOT_PATTERNS)

    fun setExplorerHiddenPatterns(raw: String) {
        viewModelScope.launch { uiPreferences.edit { it[explorerHiddenPatternsKey] = raw } }
    }

    private val respectCutoutKey = booleanPreferencesKey("respect_device_cutout")

    /** Whether to keep content out of the display cutout (notch/punch-hole). Mirrored to synchronous
     *  SharedPreferences so MainActivity applies the window cutout mode on the first frame; JCodeShell
     *  also applies it live (window.attributes is mutable, unlike the hardware-acceleration flag). */
    val respectDeviceCutout: StateFlow<Boolean> = uiPreferences.data
        .map { prefs -> prefs[respectCutoutKey] ?: SettingsDefaults.RESPECT_DEVICE_CUTOUT }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsDefaults.RESPECT_DEVICE_CUTOUT)

    fun setRespectDeviceCutout(enabled: Boolean) {
        getApplication<Application>()
            .getSharedPreferences(MainActivity.UI_STARTUP_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(MainActivity.KEY_RESPECT_CUTOUT, enabled).apply()
        viewModelScope.launch { uiPreferences.edit { it[respectCutoutKey] = enabled } }
    }

    private val tabColoringKey = stringPreferencesKey("tab_coloring")

    /** App-level (Global) editor-tab coloring default; a project/workspace .jcode can override it. */
    val tabColoringMode: StateFlow<TabColoring> = uiPreferences.data
        .map { prefs ->
            prefs[tabColoringKey]?.let { runCatching { TabColoring.valueOf(it) }.getOrNull() }
                ?: SettingsDefaults.TAB_COLORING
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsDefaults.TAB_COLORING)

    fun setTabColoringMode(mode: TabColoring) {
        viewModelScope.launch { uiPreferences.edit { it[tabColoringKey] = mode.name } }
    }

    private val tabMaxSizeKey = stringPreferencesKey("tab_max_size")

    /** Max width of editor/terminal tabs before the title middle-ellipsizes (Small/Medium/Large). */
    val tabMaxSize: StateFlow<TabMaxSize> = uiPreferences.data
        .map { prefs ->
            prefs[tabMaxSizeKey]?.let { runCatching { TabMaxSize.valueOf(it) }.getOrNull() }
                ?: SettingsDefaults.TAB_MAX_SIZE
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsDefaults.TAB_MAX_SIZE)

    fun setTabMaxSize(size: TabMaxSize) {
        viewModelScope.launch { uiPreferences.edit { it[tabMaxSizeKey] = size.name } }
    }

    private val confirmCloseRunningKey = booleanPreferencesKey("perf_confirm_close_running")

    /** When true (default), closing a project/workspace with a running terminal program, an active
     *  Build & Run, or a live debug session prompts for confirmation before killing them. */
    val confirmCloseRunning: StateFlow<Boolean> = uiPreferences.data
        .map { prefs -> prefs[confirmCloseRunningKey] ?: SettingsDefaults.CONFIRM_CLOSE_RUNNING }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsDefaults.CONFIRM_CLOSE_RUNNING)

    fun setConfirmCloseRunning(enabled: Boolean) {
        viewModelScope.launch { uiPreferences.edit { it[confirmCloseRunningKey] = enabled } }
    }

    private val exitOnSwipeAwayKey = booleanPreferencesKey(EXIT_ON_SWIPE_AWAY_KEY)

    /** When true (default on), swiping JCode off the Android recents screen tears down the Linux
     *  runtime (terminals, runs, VMs) and exits the process entirely — handled in
     *  [BackendService.onTaskRemoved], which reads the mirrored [exitOnSwipeAwayEnabled]. */
    val exitOnSwipeAway: StateFlow<Boolean> = uiPreferences.data
        .map { prefs -> prefs[exitOnSwipeAwayKey] ?: SettingsDefaults.EXIT_ON_SWIPE_AWAY }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsDefaults.EXIT_ON_SWIPE_AWAY)

    fun setExitOnSwipeAway(enabled: Boolean) {
        exitOnSwipeAwayEnabled = enabled
        viewModelScope.launch { uiPreferences.edit { it[exitOnSwipeAwayKey] = enabled } }
    }

    init {
        // Keep the static mirror BackendService reads in sync, and register the runtime teardown it
        // runs on a swipe-away exit. (Placed after the property declarations it references.)
        runtimeTeardown = { runCatching { stopAllRuntimeServices() } }
        sessionFlushBlocking = { runCatching { runBlocking { persistSession() } } }
        viewModelScope.launch { exitOnSwipeAway.collect { exitOnSwipeAwayEnabled = it } }
    }

    private val autoCloseIdleKey = booleanPreferencesKey("perf_auto_close_idle_terminals")

    /** When true, terminals sitting idle at the prompt (no foreground program, no I/O) past
     *  [idleTimeoutMinutes] are closed automatically to free their proot process trees + memory. */
    val autoCloseIdleTerminals: StateFlow<Boolean> = uiPreferences.data
        .map { prefs -> prefs[autoCloseIdleKey] ?: SettingsDefaults.AUTO_CLOSE_IDLE_TERMINALS }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsDefaults.AUTO_CLOSE_IDLE_TERMINALS)

    fun setAutoCloseIdleTerminals(enabled: Boolean) {
        viewModelScope.launch { uiPreferences.edit { it[autoCloseIdleKey] = enabled } }
    }

    private val idleTimeoutMinKey = intPreferencesKey("perf_idle_timeout_minutes")

    /** Idle-terminal auto-close threshold in minutes (5…120). Default 30. */
    val idleTimeoutMinutes: StateFlow<Int> = uiPreferences.data
        .map { prefs -> (prefs[idleTimeoutMinKey] ?: SettingsDefaults.IDLE_TIMEOUT_MINUTES).coerceIn(5, 120) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsDefaults.IDLE_TIMEOUT_MINUTES)

    fun setIdleTimeoutMinutes(minutes: Int) {
        viewModelScope.launch { uiPreferences.edit { it[idleTimeoutMinKey] = minutes.coerceIn(5, 120) } }
    }

    private val maxTerminalSessionsKey = intPreferencesKey("perf_max_terminal_sessions")

    /** Max concurrent terminal instances the "+" button will open (1…24). Default 12. */
    val maxTerminalSessions: StateFlow<Int> = uiPreferences.data
        .map { prefs -> (prefs[maxTerminalSessionsKey] ?: SettingsDefaults.MAX_TERMINAL_SESSIONS).coerceIn(1, 24) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsDefaults.MAX_TERMINAL_SESSIONS)

    fun setMaxTerminalSessions(count: Int) {
        viewModelScope.launch { uiPreferences.edit { it[maxTerminalSessionsKey] = count.coerceIn(1, 24) } }
    }

    private val hideStatusBarWithKeyboardKey = booleanPreferencesKey("hide_status_bar_with_keyboard")

    /** When true, the system status bar is hidden while the soft keyboard is up (more room to edit). */
    val hideStatusBarWithKeyboard: StateFlow<Boolean> = uiPreferences.data
        .map { prefs -> prefs[hideStatusBarWithKeyboardKey] ?: SettingsDefaults.HIDE_STATUS_BAR_WITH_KEYBOARD }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsDefaults.HIDE_STATUS_BAR_WITH_KEYBOARD)

    fun setHideStatusBarWithKeyboard(enabled: Boolean) {
        viewModelScope.launch {
            uiPreferences.edit { prefs -> prefs[hideStatusBarWithKeyboardKey] = enabled }
        }
    }

    private val extraKeysPortraitKey = stringPreferencesKey("extra_keys_portrait")
    private val extraKeysLandscapeKey = stringPreferencesKey("extra_keys_landscape")

    /** Per-orientation visibility of the Termux-style extra-keys row (Esc/Tab/Ctrl/arrows…) shown
     *  above the keyboard while the terminal or editor is focused. Defaults: portrait = with the
     *  soft keyboard, landscape = hidden (little vertical room). */
    val extraKeysPortrait: StateFlow<ExtraKeysVisibility> = uiPreferences.data
        .map { prefs -> prefs[extraKeysPortraitKey].toExtraKeysVisibility(SettingsDefaults.EXTRA_KEYS_PORTRAIT) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsDefaults.EXTRA_KEYS_PORTRAIT)

    val extraKeysLandscape: StateFlow<ExtraKeysVisibility> = uiPreferences.data
        .map { prefs -> prefs[extraKeysLandscapeKey].toExtraKeysVisibility(SettingsDefaults.EXTRA_KEYS_LANDSCAPE) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsDefaults.EXTRA_KEYS_LANDSCAPE)

    fun setExtraKeysPortrait(mode: ExtraKeysVisibility) {
        viewModelScope.launch {
            uiPreferences.edit { prefs -> prefs[extraKeysPortraitKey] = mode.name }
        }
    }

    fun setExtraKeysLandscape(mode: ExtraKeysVisibility) {
        viewModelScope.launch {
            uiPreferences.edit { prefs -> prefs[extraKeysLandscapeKey] = mode.name }
        }
    }

    private fun String?.toExtraKeysVisibility(default: ExtraKeysVisibility): ExtraKeysVisibility =
        this?.let { runCatching { ExtraKeysVisibility.valueOf(it) }.getOrNull() } ?: default

    private val editorFontKey = stringPreferencesKey("editor_font")
    private val terminalFontKey = stringPreferencesKey("terminal_font")

    /** Selected monospace font id for the editor / terminal (see [MonoFontCatalog]). A saved id for a
     *  font that was removed from the catalog (e.g. the retired "System monospace") falls back to the
     *  default. */
    val editorFontId: StateFlow<String> = uiPreferences.data
        .map { prefs ->
            prefs[editorFontKey]?.ifBlank { null }?.takeUnless { it == MonoFontCatalog.RETIRED_SYSTEM }
                ?: MonoFontCatalog.EDITOR_DEFAULT
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MonoFontCatalog.EDITOR_DEFAULT)

    val terminalFontId: StateFlow<String> = uiPreferences.data
        .map { prefs ->
            prefs[terminalFontKey]?.ifBlank { null }?.takeUnless { it == MonoFontCatalog.RETIRED_SYSTEM }
                ?: MonoFontCatalog.TERMINAL_DEFAULT
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MonoFontCatalog.TERMINAL_DEFAULT)

    fun setEditorFont(id: String) {
        viewModelScope.launch { uiPreferences.edit { it[editorFontKey] = id } }
    }

    fun setTerminalFont(id: String) {
        viewModelScope.launch { uiPreferences.edit { it[terminalFontKey] = id } }
    }

    // --- Environment (distro) fonts ----------------------------------------------------------------
    // Monospace fonts installed in the Linux runtime (DejaVu Sans Mono ships with the base image;
    // users apt-install FiraCode / Nerd Fonts) discovered via fontconfig and loaded straight from the
    // rootfs on ext4. Offered in the editor/terminal font pickers next to the built-ins. Ids are
    // prefixed [MonoFontCatalog.ENV_PREFIX]: "env:default" follows `fc-match monospace`; "env:fam:<f>"
    // is a specific installed family.
    data class EnvironmentFont(val id: String, val name: String, val path: String)

    private val _environmentFonts = MutableStateFlow<List<EnvironmentFont>>(emptyList())
    val environmentFonts: StateFlow<List<EnvironmentFont>> = _environmentFonts.asStateFlow()

    init {
        // Discover once the rootfs is installed (root exec is enough — no need for the jcode user);
        // re-discover if the selected distro changes.
        viewModelScope.launch {
            distroService.environmentState
                .map { it.distroInstalled == true }
                .distinctUntilChanged()
                .collect { installed -> if (installed) refreshEnvironmentFonts() }
        }
    }

    /** Re-scan the runtime's installed monospace fonts (no-op until the rootfs is installed). Cheap
     *  enough to call whenever the font settings open, so newly apt-installed fonts appear without a
     *  restart. */
    fun refreshEnvironmentFonts() {
        viewModelScope.launch(Dispatchers.IO) {
            if (distroService.environmentState.value.distroInstalled != true) return@launch
            val fonts = runCatching { discoverEnvironmentFonts() }
                .onFailure { android.util.Log.w("JCode", "environment font discovery failed", it) }
                .getOrDefault(emptyList())
            if (fonts != _environmentFonts.value) _environmentFonts.value = fonts
        }
    }

    private suspend fun discoverEnvironmentFonts(): List<EnvironmentFont> {
        val rootfs = distroService.activeRootfsPath()
        fun hostFile(guestPath: String): File? {
            val p = guestPath.trim()
            if (p.isEmpty() || !p.startsWith("/")) return null
            return File(rootfs, p.removePrefix("/")).takeIf { it.isFile && it.canRead() }
        }
        // Enumerate mono/charcell families: "spacing|family|style|file" ('|' dodges tab-escaping through
        // the proot shell; fontconfig still interprets the trailing \n).
        val listCmd = "command -v fc-list >/dev/null 2>&1 && " +
            "fc-list -f '%{spacing}|%{family}|%{style}|%{file}\\n' : 2>/dev/null || true"
        val byFamily = LinkedHashMap<String, EnvironmentFont>()
        distroService.exec(listCmd, user = "root", raw = true).stdout.lineSequence().forEach { line ->
            val parts = line.split('|')
            if (parts.size < 4) return@forEach
            if ((parts[0].trim().toIntOrNull() ?: 0) < 100) return@forEach // fontconfig: mono=100, charcell=110
            val family = parts[1].substringBefore(',').trim().ifBlank { return@forEach }
            val host = hostFile(parts[3]) ?: return@forEach
            val style = parts[2].substringBefore(',').trim()
            val isRegular = style.isEmpty() || style.equals("Regular", true) ||
                style.equals("Book", true) || style.equals("Normal", true) || style.equals("Medium", true)
            if (isRegular || family !in byFamily) {
                byFamily[family] = EnvironmentFont("${MonoFontCatalog.ENV_PREFIX}fam:$family", family, host.absolutePath)
            }
        }
        val out = mutableListOf<EnvironmentFont>()
        // The environment's default monospace (follows fontconfig), shown first as "Distro monospace".
        val matchCmd = "command -v fc-match >/dev/null 2>&1 && fc-match -f '%{file}' monospace 2>/dev/null || true"
        hostFile(distroService.exec(matchCmd, user = "root", raw = true).stdout)
            ?.let { out.add(EnvironmentFont("${MonoFontCatalog.ENV_PREFIX}default", "Distro monospace", it.absolutePath)) }
        out.addAll(byFamily.values.sortedBy { it.name.lowercase() })
        return out
    }

    private val bottomStatusBarKey = stringPreferencesKey("bottom_status_bar")

    /** Visibility of the workbench's bottom status bar (branch/distro/caret). Default: hide it while
     *  the soft keyboard is up. */
    val bottomStatusBar: StateFlow<BottomBarVisibility> = uiPreferences.data
        .map { prefs ->
            prefs[bottomStatusBarKey]?.let { runCatching { BottomBarVisibility.valueOf(it) }.getOrNull() }
                ?: SettingsDefaults.BOTTOM_STATUS_BAR
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsDefaults.BOTTOM_STATUS_BAR)

    fun setBottomStatusBar(mode: BottomBarVisibility) {
        viewModelScope.launch {
            uiPreferences.edit { prefs -> prefs[bottomStatusBarKey] = mode.name }
        }
    }

    private val volumeUpActionKey = stringPreferencesKey("volume_up_action")
    private val volumeDownActionKey = stringPreferencesKey("volume_down_action")

    /** Action bound to the hardware Volume Up button; [VolumeKeyAction.SystemDefault] = normal volume. */
    val volumeUpAction: StateFlow<VolumeKeyAction> = uiPreferences.data
        .map { prefs ->
            prefs[volumeUpActionKey]?.let { runCatching { VolumeKeyAction.valueOf(it) }.getOrNull() }
                ?: SettingsDefaults.VOLUME_UP_ACTION
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsDefaults.VOLUME_UP_ACTION)

    /** Action bound to the hardware Volume Down button; [VolumeKeyAction.SystemDefault] = normal volume. */
    val volumeDownAction: StateFlow<VolumeKeyAction> = uiPreferences.data
        .map { prefs ->
            prefs[volumeDownActionKey]?.let { runCatching { VolumeKeyAction.valueOf(it) }.getOrNull() }
                ?: SettingsDefaults.VOLUME_DOWN_ACTION
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsDefaults.VOLUME_DOWN_ACTION)

    fun setVolumeUpAction(action: VolumeKeyAction) {
        viewModelScope.launch { uiPreferences.edit { it[volumeUpActionKey] = action.name } }
    }

    fun setVolumeDownAction(action: VolumeKeyAction) {
        viewModelScope.launch { uiPreferences.edit { it[volumeDownActionKey] = action.name } }
    }

    // Built-in Command Palette commands the user switched off (Settings → Command Palette); stored
    // as a JSON array of command ids so the default (everything enabled) needs no migration.
    private val paletteDisabledKey = stringPreferencesKey("palette_disabled_commands")

    val paletteDisabledCommands: StateFlow<Set<String>> = uiPreferences.data
        .map { prefs ->
            runCatching {
                val arr = org.json.JSONArray(prefs[paletteDisabledKey] ?: "[]")
                buildSet { for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let(::add) }
            }.getOrDefault(emptySet())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun setPaletteCommandEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            uiPreferences.edit { prefs ->
                val current = runCatching {
                    val arr = org.json.JSONArray(prefs[paletteDisabledKey] ?: "[]")
                    buildSet { for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let(::add) }
                }.getOrDefault(emptySet())
                val next = if (enabled) current - id else current + id
                prefs[paletteDisabledKey] = org.json.JSONArray(next.toList()).toString()
            }
        }
    }

    /** Volume-key actions that must run in the Compose layer (focused-pane arrows/scroll, command
     *  palette). Undo/Redo are invoked directly by the Activity. Collected by JCodeShell. */
    private val _volumeKeyAction = MutableSharedFlow<VolumeKeyAction>(extraBufferCapacity = 8)
    val volumeKeyAction = _volumeKeyAction.asSharedFlow()

    fun emitVolumeKeyAction(action: VolumeKeyAction) {
        _volumeKeyAction.tryEmit(action)
    }

    private val hideTabCloseButtonKey = booleanPreferencesKey("hide_tab_close_button")

    /** When true, the "×" close button is hidden on editor + terminal tabs to avoid accidental
     *  closes; a tab is then closed via its long-press menu. */
    val hideTabCloseButton: StateFlow<Boolean> = uiPreferences.data
        .map { prefs -> prefs[hideTabCloseButtonKey] ?: SettingsDefaults.HIDE_TAB_CLOSE_BUTTON }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsDefaults.HIDE_TAB_CLOSE_BUTTON)

    fun setHideTabCloseButton(enabled: Boolean) {
        viewModelScope.launch {
            uiPreferences.edit { prefs -> prefs[hideTabCloseButtonKey] = enabled }
        }
    }

    private val markdownWrapPortraitKey = booleanPreferencesKey("markdown_wrap_portrait")

    /** Markdown preview: wrap to the viewport in portrait (default). When off, portrait previews
     *  lay out at landscape width (the screen height, honoring the cutout setting) and pan sideways. */
    val markdownWrapPortrait: StateFlow<Boolean> = uiPreferences.data
        .map { prefs -> prefs[markdownWrapPortraitKey] ?: SettingsDefaults.MARKDOWN_WRAP_PORTRAIT }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsDefaults.MARKDOWN_WRAP_PORTRAIT)

    fun setMarkdownWrapPortrait(enabled: Boolean) {
        viewModelScope.launch {
            uiPreferences.edit { prefs -> prefs[markdownWrapPortraitKey] = enabled }
        }
    }

    private val developerOptionsKey = booleanPreferencesKey("developer_options")

    /** Developer options (off by default): reveals extension-authoring tools — the Extension Dev
     *  right-drawer tab and unsigned `.jext` sideloading. */
    val developerOptions: StateFlow<Boolean> = uiPreferences.data
        .map { prefs -> prefs[developerOptionsKey] ?: SettingsDefaults.DEVELOPER_OPTIONS }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsDefaults.DEVELOPER_OPTIONS)

    fun setDeveloperOptions(enabled: Boolean) {
        viewModelScope.launch {
            uiPreferences.edit { prefs -> prefs[developerOptionsKey] = enabled }
        }
    }

    private val editorDragMovesCursorKey = booleanPreferencesKey("editor_drag_moves_cursor")

    /** When true, a one-finger drag on the editor moves the text cursor (the view follows) instead of
     *  scrolling the content; long-press text selection is unaffected. */
    val editorDragMovesCursor: StateFlow<Boolean> = uiPreferences.data
        .map { prefs -> prefs[editorDragMovesCursorKey] ?: SettingsDefaults.EDITOR_DRAG_MOVES_CURSOR }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsDefaults.EDITOR_DRAG_MOVES_CURSOR)

    fun setEditorDragMovesCursor(enabled: Boolean) {
        viewModelScope.launch {
            uiPreferences.edit { prefs -> prefs[editorDragMovesCursorKey] = enabled }
        }
    }

    private val editorCursorDragVerticalKey = intPreferencesKey("editor_cursor_drag_vertical_level")
    private val editorCursorDragHorizontalKey = intPreferencesKey("editor_cursor_drag_horizontal_level")

    /** "Drag to move cursor" sensitivity per axis: 1 (slow/precise) … 5 (fast). Default 2. */
    val editorCursorDragVerticalLevel: StateFlow<Int> = uiPreferences.data
        .map { prefs -> (prefs[editorCursorDragVerticalKey] ?: SettingsDefaults.CURSOR_DRAG_LEVEL).coerceIn(1, 5) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsDefaults.CURSOR_DRAG_LEVEL)

    val editorCursorDragHorizontalLevel: StateFlow<Int> = uiPreferences.data
        .map { prefs -> (prefs[editorCursorDragHorizontalKey] ?: SettingsDefaults.CURSOR_DRAG_LEVEL).coerceIn(1, 5) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsDefaults.CURSOR_DRAG_LEVEL)

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
        .map { prefs -> prefs[restoreLastSessionKey] ?: SettingsDefaults.RESTORE_LAST_SESSION }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsDefaults.RESTORE_LAST_SESSION)

    fun setRestoreLastSession(enabled: Boolean) {
        viewModelScope.launch {
            uiPreferences.edit { prefs -> prefs[restoreLastSessionKey] = enabled }
        }
        // Opting out should not leave a stale session blob behind on disk.
        if (!enabled) viewModelScope.launch(Dispatchers.IO) { sessionStore.clear() }
    }

    private val sessionStore = SessionStore(appContext)

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
        val projectsRoot = dev.jcode.core.distro.WorkspaceHostPaths.projectsRoot.trimEnd('/')
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

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    /** Latest GitHub-release check result, or null until the first successful check. */
    val updateInfo = _updateInfo.asStateFlow()
    private val _updateChecking = MutableStateFlow(false)
    val updateChecking = _updateChecking.asStateFlow()

    /** Query GitHub for the latest release and update [updateInfo]. Runs once on startup and on
     *  demand from Settings; a failed/offline check leaves the previous result untouched. */
    fun checkForUpdate() {
        if (_updateChecking.value) return
        viewModelScope.launch {
            _updateChecking.value = true
            try {
                UpdateChecker.check()?.let { _updateInfo.value = it }
            } finally {
                _updateChecking.value = false
            }
        }
    }

    init {
        checkForUpdate()
    }

    /** Emitted when a file is opened from the terminal, so the shell can surface the editor. */
    private val _bringEditorToFront = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val bringEditorToFront = _bringEditorToFront.asSharedFlow()

    // Extensions shipped inside the APK (assets/builtin-extensions/) and installed on first run.
    private val builtinExtensions = listOf(
        BundledExtensionSpec(
            assetPath = "builtin-extensions/jcode.lang.markup-1.0.1.jext",
            uniqueName = "jcode.lang.markup",
            version = "1.0.1",
        ),
        BundledExtensionSpec(
            assetPath = "builtin-extensions/jcode.lang.stylesheet-1.0.1.jext",
            uniqueName = "jcode.lang.stylesheet",
            version = "1.0.1",
        ),
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

    // ---- Extension-contributed shell actions (e.g. the SCM extension's Clone / Remote Repo) ----

    /** An action an installed extension contributes to a host surface; tapping opens that extension's
     *  own view (route == the action id) in the editor area. */
    data class ShellContribution(
        val extId: String,
        val id: String,
        val label: String,
        val icon: String? = null,
        val fileExtensions: List<String> = emptyList(),
        val targets: List<String> = emptyList(),
    )

    /** Actions active extensions contribute to the empty-editor start screen (their required tools met). */
    val contributedEditorStartActions: StateFlow<List<ShellContribution>> =
        combine(installedExtensions, extensionActivations, distroService.sdkCatalogState) { exts, acts, sdk ->
            availableContributions(exts, acts, sdk) { it.editorStartActions }
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000L), emptyList())

    /** Actions active extensions contribute to the left-drawer "Open Folder" dropdown. */
    val contributedDrawerActions: StateFlow<List<ShellContribution>> =
        combine(installedExtensions, extensionActivations, distroService.sdkCatalogState) { exts, acts, sdk ->
            availableContributions(exts, acts, sdk) { it.drawerActions }
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000L), emptyList())

    /** Actions active extensions contribute to the editor's long-press context menu. The shell filters
     *  them per active file via [ShellContribution.fileExtensions]. */
    val contributedEditorContextActions: StateFlow<List<ShellContribution>> =
        combine(installedExtensions, extensionActivations, distroService.sdkCatalogState) { exts, acts, sdk ->
            availableContributions(exts, acts, sdk) { it.editorContextActions }
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000L), emptyList())

    /** Actions active extensions contribute to the explorer's file/folder context menu. The explorer
     *  filters them per row via [ShellContribution.fileExtensions] and [ShellContribution.targets]. */
    val contributedExplorerContextActions: StateFlow<List<ShellContribution>> =
        combine(installedExtensions, extensionActivations, distroService.sdkCatalogState) { exts, acts, sdk ->
            availableContributions(exts, acts, sdk) { it.explorerContextActions }
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000L), emptyList())

    /** Run-config presets active extensions contribute (required toolchains met), offered on the
     *  Configure Run page when the project contains all of a preset's required files. */
    val contributedRunConfigPresets: StateFlow<List<ProjectRunner.ExtensionRunPreset>> =
        combine(installedExtensions, extensionActivations, distroService.sdkCatalogState) { exts, acts, sdk ->
            exts.filter { (acts[it.id] ?: ExtensionActivation.Default) != ExtensionActivation.Manual }
                .filter { ext -> ext.requires.sdks.all { it in sdk.installedEntryIds } }
                .flatMap { ext -> ext.contributes.runConfigPresets.map { ProjectRunner.ExtensionRunPreset(ext.name, it) } }
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000L), emptyList())

    private fun availableContributions(
        exts: List<InstalledExtension>,
        acts: Map<String, ExtensionActivation>,
        sdk: dev.jcode.core.distro.SdkCatalogState,
        surface: (dev.jcode.feature.marketplace.ExtensionContributions) -> List<dev.jcode.feature.marketplace.ContributedAction>,
    ): List<ShellContribution> =
        exts.filter { (acts[it.id] ?: ExtensionActivation.Default) != ExtensionActivation.Manual }
            .filter { ext -> ext.requires.sdks.all { it in sdk.installedEntryIds } }
            .flatMap { ext ->
                surface(ext.contributes).map { ShellContribution(ext.id, it.id, it.label, it.icon, it.fileExtensions, it.targets) }
            }
            .distinctBy { it.extId + ":" + it.id }

    /** Open the extension view a contributed action points at (route == the action id). */
    fun openContributedView(c: ShellContribution) {
        openExtensionViewPage(c.extId, c.id, c.label)
    }

    /** The last editor context-menu tap per extension, pulled (once) by a freshly-opened extension
     *  page via `workbench.pendingContextAction` — WebViews created by the tap miss the pushed event. */
    private val pendingContextActions = ConcurrentHashMap<String, String>()

    /** Deliver an editor context-menu tap exactly once, then open/focus the extension's view at the
     *  action's route: if that view is already open its live WebView gets a pushed `contextAction`
     *  event; otherwise the payload is stashed for the freshly-created page to pull on boot. */
    fun handleEditorContextAction(c: ShellContribution, word: String) {
        val group = _editorGroup.value
        val tab = group.tabs.firstOrNull { it.id == group.activeTabId && !it.isPage }
        val json = JSONObject().apply {
            put("extensionId", c.extId)
            put("actionId", c.id)
            put("word", word)
            if (tab != null) {
                put("path", hostToGuestPath(tab.filePath))
                put("name", tab.filePath.name)
                put("dirty", tab.isDirty)
            }
        }.toString()
        val viewTabId = EXT_APP_PREFIX + c.extId + "#" + c.id
        if (group.tabs.any { it.id == viewTabId }) {
            _extensionEvents.tryEmit("contextAction" to json)
        } else {
            pendingContextActions[c.extId] = json
        }
        openExtensionViewPage(c.extId, c.id, c.label)
    }

    /** Extension ids with a live background web host (the persistent SCM WebView) — those get
     *  explorer taps as a pushed `explorerAction` event instead of a page open. Registered by the
     *  compose layer that owns the host WebViews. */
    val liveExtensionHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Deliver an explorer file/folder context-menu tap. An extension with a live background host
     *  handles it silently via the pushed `explorerAction` event. When its host is still booting,
     *  stash the tap for the boot-time pull instead. Extensions without a background host get the
     *  editor pattern — stash for pull on boot and open the extension's view at the action's route. */
    fun handleExplorerContextAction(c: ShellContribution, hostPath: File, isDirectory: Boolean) {
        val json = JSONObject().apply {
            put("extensionId", c.extId)
            put("actionId", c.id)
            put("path", hostToGuestPath(hostPath))
            put("name", hostPath.name)
            put("isDirectory", isDirectory)
        }.toString()
        val hostExpected = installedExtensions.value.firstOrNull { it.id == c.extId }?.contributes?.explorerDecorations == true
        when {
            c.extId in liveExtensionHosts -> _extensionEvents.tryEmit("explorerAction" to json)
            hostExpected -> pendingContextActions[c.extId] = json
            else -> {
                val viewTabId = EXT_APP_PREFIX + c.extId + "#" + c.id
                if (_editorGroup.value.tabs.any { it.id == viewTabId }) {
                    _extensionEvents.tryEmit("contextAction" to json)
                } else {
                    pendingContextActions[c.extId] = json
                }
                openExtensionViewPage(c.extId, c.id, c.label)
            }
        }
    }

    /** "Something under the project changed on disk" hint (saves, explorer file ops, explorer
     *  Refresh) so decoration-pushing extensions re-run their status checks (debounced ext-side). */
    fun notifyWorkspaceFilesChanged() {
        _extensionEvents.tryEmit("filesChanged" to "{}")
    }

    /** Resolve the post-open prompt shown after a folder was opened inside a User Workspace. */
    fun resolvePostClone(open: Boolean) {
        val project = _postClonePrompt.value ?: return
        _postClonePrompt.value = null
        if (open) _selectedProjectId.value = project.id
        else viewModelScope.launch { emitMessage("Added '${project.name}' to the workspace.") }
    }

    /** Host side of the guest /sources mount — the staging dir extensions materialize folders into
     *  (e.g. the SCM extension's clones) before handing them over via `workbench.addFolder`. */
    private fun sourcesRoot(): File = dev.jcode.core.distro.WorkspaceHostPaths.sourcesRoot(appContext.filesDir)

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return String.format(java.util.Locale.US, if (value >= 100) "%.0f %s" else "%.1f %s", value, units[unit])
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
                // Missing template requirements (SDK-catalog ids, e.g. android-sdk) install first —
                // the Setup terminal's mutex serializes install → scaffold as one visible flow, so
                // an Android project's SDK is present before its first build instead of failing later.
                val missing = template.requires.filterNot { it in distroService.sdkCatalogState.value.installedEntryIds }
                if (missing.isNotEmpty()) {
                    emitMessage("Installing ${missing.size} required toolchain(s) for '${project.name}'…")
                    if (!installRequiredSdks(template.requires, project.name)) {
                        emitMessage("Project '${project.name}' scaffold cancelled: required toolchains missing.")
                        templateScaffolder.reset()
                        return@use
                    }
                }
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
     * Open a folder the user picked. Picks already on app-private ext4 storage (the "JCode Projects"
     * DocumentsProvider, or an existing project dir) open in place. Anything else — primary storage,
     * cloud, third-party SAF — is copied onto /sources first (the runtime can't bind-mount it
     * otherwise); [importExternalFolder] scans it and refuses an empty or oversized folder.
     */
    fun openExternalFolder(path: FsPath) {
        viewModelScope.launch {
            val resolved = workspaceManager.resolveManageable(path)
            if (workspaceManager.isManagedRoot(resolved)) {
                emitMessage("That folder is the JCode Projects root — pick a project or workspace folder inside it.")
                return@launch
            }
            if (workspaceManager.isOnManagedStorage(resolved)) {
                openManagedFolder(resolved)
            } else {
                importExternalFolder(resolved)
            }
        }
    }

    private suspend fun openManagedFolder(resolved: FsPath) {
        when {
            workspaceManager.isWorkspaceFolder(resolved) &&
                workspaceManager.enterFolderAsWorkspace(resolved) != null -> clearEditorTabs()

            workspaceManager.folderNeedsType(resolved) ->
                _openFolderTypePrompt.value = PendingFolderType.OpenInPlace(resolved)

            else -> {
                resetDefaultWorkspaceProject()
                val project = workspaceManager.addFolder(resolved)
                _selectedProjectId.value = project.id
                emitMessage("Opened '${project.name}'.")
            }
        }
    }

    /** Scan an off-ext4 pick and, if it isn't empty and fits, copy it into /sources then adopt it. A
     *  progress modal follows the scan then the copy (see [_importProgress]). */
    private suspend fun importExternalFolder(source: FsPath) {
        val label = folderDisplayName(appContext, source)
        _importProgress.value = ImportProgress(label, ImportPhase.Scanning)
        val staged = try {
            val scan = runCatching { scanFolderForImport(appContext, source) }.getOrElse {
                emitMessage("Couldn't read '$label': ${it.message ?: "unknown error"}.")
                return
            }
            if (scan.fileCount == 0) {
                emitMessage("'$label' is empty — nothing to import.")
                return
            }
            val sources = sourcesRoot().apply { mkdirs() }
            if (scan.totalBytes + IMPORT_FREE_SPACE_HEADROOM_BYTES > sources.usableSpace) {
                emitMessage(
                    "'$label' is too large to import — needs ${formatBytes(scan.totalBytes)}, " +
                        "only ${formatBytes(sources.usableSpace)} free.",
                )
                return
            }
            if (!saveAllDirtyAwait()) {
                emitMessage("Save or close open files before importing a folder.")
                return
            }
            _importProgress.value = ImportProgress(label, ImportPhase.Copying, done = 0, total = scan.fileCount)
            runCatching {
                copyIntoSources(source, label) { done ->
                    _importProgress.value = ImportProgress(label, ImportPhase.Copying, done, scan.fileCount)
                }
            }.getOrElse {
                emitMessage("Import failed: ${it.message ?: "unknown error"}.")
                return
            }
        } finally {
            _importProgress.value = null
        }
        val stagedPath = FsPath.Local(staged)
        when {
            workspaceManager.isWorkspaceFolder(stagedPath) ->
                adoptStagedGuarded(staged, WorkspaceNodeType.Workspace)?.let { emitMessage("Imported Workspace '${it.name}'.") }

            workspaceManager.folderNeedsType(stagedPath) ->
                _openFolderTypePrompt.value = PendingFolderType.AdoptStaged(staged)

            else ->
                adoptStagedGuarded(staged, WorkspaceNodeType.Project)?.let { emitMessage("Imported '${it.name}'.") }
        }
    }

    /** [adoptStagedFolder] with the save-guard re-checked immediately before adoption: the import copy
     *  can run long enough for the user to dirty an open buffer, and adoption clears editor tabs without
     *  saving. On an unsaveable buffer the staged copy is discarded (already messaged) and null returned. */
    private suspend fun adoptStagedGuarded(staged: File, nodeType: WorkspaceNodeType): Project? {
        if (!saveAllDirtyAwait()) {
            emitMessage("Save or close open files before importing a folder.")
            withContext(Dispatchers.IO) { staged.deleteRecursively() }
            return null
        }
        return adoptStagedFolder(staged, nodeType)
    }

    /** Copy [source] into a hidden /sources temp dir, then atomically rename to a unique final name. */
    private suspend fun copyIntoSources(source: FsPath, label: String, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val sources = sourcesRoot().apply { mkdirs() }
        // Reclaim temp dirs orphaned by a crash mid-copy (safe: only our own ".import-" prefix).
        sources.listFiles { f -> f.isDirectory && f.name.startsWith(".import-") }?.forEach { it.deleteRecursively() }
        val tmp = File(sources, ".import-${System.nanoTime()}")
        try {
            copyFolderToLocal(appContext, source, sources, tmp.name, onProgress)
            val base = workspaceManager.sanitizedFolderName(label)
            var dest = File(sources, base)
            var suffix = 1
            while (dest.exists()) dest = File(sources, "$base-${suffix++}")
            if (!tmp.renameTo(dest)) error("Cannot place the imported folder in staging")
            dest
        } catch (t: Throwable) {
            tmp.deleteRecursively()
            throw t
        }
    }

    /**
     * Move a staged /sources folder into the active workspace (or the Default Workspace) as [nodeType],
     * switching/entering as needed. Shared by the SCM clone bridge (`workbench.addFolder`) and the
     * SAF-import path. The caller must have flushed unsaved buffers first (adoption clears editor tabs).
     */
    private suspend fun adoptStagedFolder(staged: File, nodeType: WorkspaceNodeType): Project {
        val defId = workspaceManager.ensureDefaultWorkspaceId()
        return when (nodeType) {
            WorkspaceNodeType.Workspace -> {
                clearEditorTabs()
                workspaceManager.restoreWorkspace(defId)
                val node = workspaceManager.adoptFolderIn(defId, staged, WorkspaceNodeType.Workspace)
                workspaceManager.enterWorkspaceFolder(node)
                node
            }

            WorkspaceNodeType.Project -> {
                val targetWs = workspaceManager.currentWorkspace.value?.id ?: defId
                workspaceManager.restoreWorkspace(targetWs)
                if (targetWs == defId) resetDefaultWorkspaceProject()
                val project = workspaceManager.adoptFolderIn(targetWs, staged, WorkspaceNodeType.Project)
                _selectedProjectId.value = project.id
                project
            }
        }
    }

    fun resolveOpenFolderType(isWorkspace: Boolean) {
        val pending = _openFolderTypePrompt.value ?: return
        _openFolderTypePrompt.value = null
        val nodeType = if (isWorkspace) WorkspaceNodeType.Workspace else WorkspaceNodeType.Project
        viewModelScope.launch {
            when (pending) {
                is PendingFolderType.OpenInPlace -> {
                    val path = pending.path
                    if (isWorkspace && workspaceManager.enterFolderAsWorkspace(path) != null) {
                        clearEditorTabs()
                        return@launch
                    }
                    resetDefaultWorkspaceProject()
                    val project = workspaceManager.addFolderWithType(path, nodeType)
                    _selectedProjectId.value = project.id
                    emitMessage("Opened ${if (isWorkspace) "Workspace" else "Project"} '${project.name}'.")
                }

                is PendingFolderType.AdoptStaged -> {
                    if (!pending.staged.isDirectory) {
                        emitMessage("The imported folder is no longer available.")
                        return@launch
                    }
                    adoptStagedGuarded(pending.staged, nodeType)?.let {
                        emitMessage("Imported ${if (isWorkspace) "Workspace" else "Project"} '${it.name}'.")
                    }
                }
            }
        }
    }

    fun dismissOpenFolderPrompt() {
        val pending = _openFolderTypePrompt.value
        _openFolderTypePrompt.value = null
        // An imported-but-unclassified folder would otherwise linger in /sources; drop it on cancel.
        if (pending is PendingFolderType.AdoptStaged) {
            viewModelScope.launch(Dispatchers.IO) { pending.staged.deleteRecursively() }
        }
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

    /** Copy a local project/recent dir out to a user-picked SAF folder ("Export to storage") so it's
     *  browsable in a file manager / other apps. A one-shot snapshot: `node_modules` (large and
     *  regenerable) is skipped. Projects otherwise live on app-private ext4 (see WorkspaceHostPaths);
     *  the DocumentsProvider gives a live view, this gives a real on-disk copy. A null/non-directory
     *  [source] (e.g. a SAF project with no local dir) is rejected with a message. */
    fun exportDirTo(name: String, source: File?, dest: Uri) {
        if (source == null || !source.isDirectory) {
            viewModelScope.launch { emitMessage("Export failed: '$name' isn't a local folder.") }
            return
        }
        viewModelScope.launch {
            emitMessage("Exporting '$name'…")
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            runCatching { copyLocalTreeToDocumentTree(appContext, source, dest, "$name-$stamp") }
                .onSuccess { emitMessage("Exported $it file(s) from '$name'.") }
                .onFailure { emitMessage("Export failed: ${it.message ?: "unknown error"}") }
        }
    }

    // A null value clears the override: the field is dropped from the scope's .jcode YAML and the
    // effective value falls back down the project -> workspace -> defaults chain.
    fun updateEditorFontSize(scope: ConfigScope, fontSize: Float?) {
        viewModelScope.launch {
            if (!ensureScopeAvailable(scope)) return@launch
            configService.updateEditorConfig(scope) { it.copy(fontSize = fontSize?.coerceIn(8f, 72f)) }
        }
    }

    fun updateEditorTabSize(scope: ConfigScope, tabSize: Int?) {
        viewModelScope.launch {
            if (!ensureScopeAvailable(scope)) return@launch
            configService.updateEditorConfig(scope) { it.copy(tabSize = tabSize?.coerceIn(1, 16)) }
        }
    }

    fun updateEditorMinimap(scope: ConfigScope, enabled: Boolean?) {
        viewModelScope.launch {
            if (!ensureScopeAvailable(scope)) return@launch
            configService.updateEditorConfig(scope) { it.copy(minimap = enabled) }
        }
    }

    fun updateEditorLigatures(scope: ConfigScope, enabled: Boolean?) {
        viewModelScope.launch {
            if (!ensureScopeAvailable(scope)) return@launch
            configService.updateEditorConfig(scope) { it.copy(ligatures = enabled) }
        }
    }

    /** Project/workspace .jcode override of the tab-coloring mode ([TabColoring] name; null inherits). */
    fun updateEditorTabColoring(scope: ConfigScope, value: String?) {
        viewModelScope.launch {
            if (!ensureScopeAvailable(scope)) return@launch
            configService.updateEditorConfig(scope) { it.copy(tabColoring = value) }
        }
    }

    // ---- Editor tab colors ------------------------------------------------------------------------
    // Effective mode = .jcode override (project<-workspace) if set, else the app-level default.
    val effectiveTabColoring: StateFlow<TabColoring> =
        combine(effectiveConfig, tabColoringMode) { eff, global ->
            eff.editor.tabColoring?.let { runCatching { TabColoring.valueOf(it) }.getOrNull() } ?: global
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsDefaults.TAB_COLORING)

    private val _editorTabColors = MutableStateFlow<Map<String, Color>>(emptyMap())
    /** Absolute-file-path -> accent color for the currently open file tabs (empty when Disabled). */
    val editorTabColors: StateFlow<Map<String, Color>> = _editorTabColors.asStateFlow()

    // Session caches so a generated color is stable across recomposes and matches what gets persisted.
    private val ephemeralTabColors = mutableMapOf<String, Int>() // absPath -> argb (Random mode)
    private val sessionGenFile = mutableMapOf<String, Int>()     // relPath -> argb (RandomRemember, pre-persist)
    private val sessionGenDir = mutableMapOf<String, Int>()      // relDir -> argb (DirectoryBased, pre-persist)

    private data class TabColorInputs(
        val group: EditorGroup,
        val project: Project?,
        val mode: TabColoring,
        val storedFiles: Map<String, String>,
        val storedDirs: Map<String, String>,
    )

    init {
        viewModelScope.launch {
            combine(
                _editorGroup,
                selectedProject,
                effectiveTabColoring,
                configService.projectConfig,
            ) { group, project, mode, cfg ->
                TabColorInputs(group, project, mode, cfg?.tabColors.orEmpty(), cfg?.tabDirColors.orEmpty())
            }.collect { recomputeTabColors(it) }
        }
    }

    private var lastColorRoot: String? = null

    private suspend fun recomputeTabColors(inp: TabColorInputs) {
        val root = (inp.project?.fsPath as? FsPath.Local)?.file
        // The session caches hold project-relative scratch colors; drop them on project switch so a
        // same-named file in another project doesn't reuse the previous project's generated color.
        val rootPath = root?.absolutePath
        if (rootPath != lastColorRoot) {
            lastColorRoot = rootPath
            ephemeralTabColors.clear()
            sessionGenFile.clear()
            sessionGenDir.clear()
        }
        if (inp.mode == TabColoring.Disabled || root == null) {
            if (_editorTabColors.value.isNotEmpty()) _editorTabColors.value = emptyMap()
            return
        }
        val out = LinkedHashMap<String, Color>()
        var newFiles: MutableMap<String, String>? = null
        var newDirs: MutableMap<String, String>? = null
        for (tab in inp.group.tabs) {
            if (tab.isPage) continue
            val abs = tab.filePath.path
            val rel = relativeTabKey(root, tab.filePath) ?: continue
            val stored = inp.storedFiles[rel]
            if (stored != null) {
                tabColorFromHex(stored)?.let { out[abs] = it }
                continue
            }
            when (inp.mode) {
                TabColoring.RandomRemember -> {
                    val argb = sessionGenFile.getOrPut(rel) { randomTabColor().toArgb() }
                    out[abs] = Color(argb)
                    (newFiles ?: LinkedHashMap<String, String>().also { newFiles = it })[rel] = tabColorToHex(Color(argb))
                }
                TabColoring.Random -> {
                    out[abs] = Color(ephemeralTabColors.getOrPut(abs) { randomTabColor().toArgb() })
                }
                TabColoring.DirectoryBased -> {
                    val dir = rel.substringBeforeLast('/', "")
                    val storedDir = inp.storedDirs[dir]
                    if (storedDir != null) {
                        tabColorFromHex(storedDir)?.let { out[abs] = it }
                    } else {
                        val argb = sessionGenDir.getOrPut(dir) { randomTabColor().toArgb() }
                        out[abs] = Color(argb)
                        (newDirs ?: LinkedHashMap<String, String>().also { newDirs = it })[dir] = tabColorToHex(Color(argb))
                    }
                }
                TabColoring.Disabled -> Unit
            }
        }
        _editorTabColors.value = out
        // Persist freshly generated colors once; updateProjectTabColorMaps no-ops if unchanged, so the
        // reload this triggers re-enters here with the entries already stored and stops. A write
        // failure must not tear down the collector, so swallow it (colors just stay session-only).
        if (newFiles != null || newDirs != null) {
            runCatching {
                configService.updateProjectTabColorMaps { files, dirs ->
                    (files + newFiles.orEmpty()) to (dirs + newDirs.orEmpty())
                }
            }
        }
    }

    /** Manually set (hex) or clear (null) a file tab's color; persists to the project .jcode. */
    fun setTabColor(tabId: String, hex: String?) {
        val tab = _editorGroup.value.tabs.find { it.id == tabId } ?: return
        viewModelScope.launch {
            if (!ensureScopeAvailable(ConfigScope.Project)) return@launch
            val root = (selectedProject.value?.fsPath as? FsPath.Local)?.file ?: return@launch
            val rel = relativeTabKey(root, tab.filePath)
                ?: run { emitMessage("Can't color a file outside the project"); return@launch }
            configService.updateProjectTabColorMaps { files, dirs ->
                (if (hex == null) files - rel else files + (rel to hex)) to dirs
            }
        }
    }

    private fun relativeTabKey(root: java.io.File, file: java.io.File): String? =
        runCatching { file.relativeTo(root).invariantSeparatorsPath }
            .getOrNull()?.takeIf { it.isNotEmpty() && !it.startsWith("..") }

    fun setThemeMode(mode: ThemeMode?, scope: ConfigScope = ConfigScope.Workspace) {
        viewModelScope.launch {
            if (!ensureScopeAvailable(scope)) return@launch
            configService.updateThemeConfig(scope) { it.copy(id = mode?.configId) }
            // A reset must clear the override wherever it lives: a (hand-written) project
            // theme.id wins the effective resolution, so leaving it would make the reset a
            // visible no-op. Only touched when present, so no project .jcode is created.
            if (mode == null && scope == ConfigScope.Workspace &&
                projectConfig.value?.theme?.id != null && ensureScopeAvailable(ConfigScope.Project)
            ) {
                configService.updateThemeConfig(ConfigScope.Project) { it.copy(id = null) }
            }
        }
    }

    fun updateExplorerViewMode(scope: ConfigScope, viewMode: String?) {
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

    private val _systemPackagesUpdating = MutableStateFlow(false)

    /** True while an opt-in `apt-get update && upgrade` is running (drives the Settings button state). */
    val systemPackagesUpdating: StateFlow<Boolean> = _systemPackagesUpdating.asStateFlow()

    /** Opt-in "Update system packages": runs `apt-get update && apt-get upgrade` (self-healing, streamed
     *  to the Setup terminal). Deliberately never automatic — an upgrade can be large/slow under proot. */
    fun updateSystemPackages() {
        if (_systemPackagesUpdating.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _systemPackagesUpdating.value = true
            val session = SessionRegistry.registerSession(
                context = getApplication(),
                kind = BackendSessionKind.JOB,
                name = "environment:update-packages",
            )
            try {
                _messages.tryEmit("Updating system packages… (see the Setup terminal for progress)")
                val result = distroService.updateSystemPackages()
                _messages.tryEmit(
                    if (result.succeeded) {
                        "System packages up to date."
                    } else {
                        val reason = result.internalError
                            ?: result.stderr.lineSequence().firstOrNull { it.isNotBlank() }
                            ?: "see the Setup terminal"
                        "Package update failed: $reason"
                    },
                )
            } finally {
                session.close()
                _systemPackagesUpdating.value = false
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
        val projectsRoot = dev.jcode.core.distro.WorkspaceHostPaths.projectsRoot.trimEnd('/')
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

    /** Save a clipboard image pasted into the terminal under the active project and return its guest
     *  /workspace path (or null if there's no open project or the write fails). Called on the main
     *  thread from the terminal paste action; images are small (screenshots) so the write is inline. */
    fun savePastedImage(uri: android.net.Uri): String? {
        val projectDir = (selectedProject.value?.fsPath as? FsPath.Local)?.file ?: return null
        return runCatching {
            val dir = File(projectDir, ".jcode/pasted-images").apply { mkdirs() }
            val file = File(dir, "paste-${System.currentTimeMillis()}.png")
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                val bitmap = android.graphics.BitmapFactory.decodeStream(input) ?: return null
                file.outputStream().use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                }
            } ?: return null
            dev.jcode.core.distro.WorkspaceHostPaths.hostToGuest(file.absolutePath)
        }.getOrNull()
    }

    /** Resolve a template input's live options by running its optionsCommand in the runtime; returns
     *  the distinct non-blank stdout lines (e.g. installed .NET SDK monikers), or empty on any failure
     *  or when the runtime isn't ready — the caller then falls back to the template's static options. */
    suspend fun runTemplateOptionsCommand(command: String): List<String> {
        if (command.isBlank() || !distroService.isRuntimeReady()) return emptyList()
        return runCatching {
            distroService.exec(command, user = "root", raw = true).stdout
                .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
        }.getOrDefault(emptyList())
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
    fun openExtensionViewPage(extensionId: String, view: String, title: String? = null) {
        _bringEditorToFront.tryEmit(Unit)
        val ext = _installedExtensions.value.firstOrNull { it.id == extensionId }
        val viewLabel = when (view) { "github" -> "GitHub"; "" -> null; else -> view.replaceFirstChar { it.uppercaseChar() } }
        openDetailPage(EXT_APP_PREFIX + extensionId + "#" + view, EditorPageKind.ExtensionApp) {
            title?.takeIf { it.isNotBlank() } ?: listOfNotNull(ext?.name, viewLabel).joinToString(" · ").ifBlank { "View" }
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

    // Per-project "hidden files (by-injected)" — the SCM extension pushes each project's .gitignore
    // patterns here (workbench.setHiddenInjected); the Explorer merges them per the hide mode. Kept
    // separate from extension_settings and the user's by-line list so injected entries stay separable.
    private val hiddenInjectedKey = stringPreferencesKey("explorer_hidden_injected")

    val hiddenInjected: StateFlow<Map<String, List<String>>> = uiPreferences.data
        .map { prefs -> parseHiddenInjected(prefs[hiddenInjectedKey]) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private fun parseHiddenInjected(json: String?): Map<String, List<String>> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(json)
            buildMap {
                root.keys().forEach { pid ->
                    val arr = root.optJSONArray(pid) ?: return@forEach
                    put(pid, (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() })
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun setHiddenInjected(projectId: String, patterns: List<String>) {
        viewModelScope.launch {
            uiPreferences.edit { prefs ->
                val root = runCatching { JSONObject(prefs[hiddenInjectedKey] ?: "{}") }.getOrDefault(JSONObject())
                root.put(projectId, org.json.JSONArray(patterns))
                prefs[hiddenInjectedKey] = root.toString()
            }
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
        // Whole projects-root -> /workspace (prefix-boundary safe: won't rewrite a sibling like
        // "<root>X"); returns p unchanged if it isn't under the projects root.
        return dev.jcode.core.distro.WorkspaceHostPaths.hostToGuest(p)
    }

    /** [hostToGuestPath]'s inverse plus the matched project: a guest path in the PER-PROJECT bind view
     *  (what exec.run mounts, so what extensions report) → the project it lives in and its host path.
     *  resolveHostFile is wrong here — it inverts the terminal's whole-root view, which diverges from
     *  the bind view for nested workspaces and sanitized bind names. */
    private fun guestToHostInProject(guestPath: String): Pair<Project, File>? {
        val g = guestPath.trimEnd('/')
        currentWorkspace.value?.projects.orEmpty().forEach { proj ->
            val bind = proj.distroBindTarget.trimEnd('/')
            val projHost = (proj.fsPath as? FsPath.Local)?.file ?: return@forEach
            if (g == bind || g.startsWith("$bind/")) {
                return proj to File(projHost.absolutePath.trimEnd('/') + g.removePrefix(bind))
            }
        }
        return null
    }

    /** Per-file VCS decorations pushed by extensions (`workbench.setExplorerDecorations`), keyed
     *  projectId → repo guest root → decoration set. In-memory only: git status is recomputed by the
     *  extension on every project open, so persisting would only ever show stale letters. */
    private val _explorerScmDecorations = MutableStateFlow<Map<String, Map<String, ScmRepoDecorations>>>(emptyMap())

    /** Flattened per-project view for the explorer: host absolute path → status / submodule set. */
    val explorerScmDecorations: StateFlow<Map<String, ScmProjectDecorations>> =
        _explorerScmDecorations.map { byProject ->
            byProject.mapValues { (_, repos) ->
                ScmProjectDecorations(
                    status = repos.values.flatMap { it.status.entries }.associate { it.key to it.value },
                    submodules = repos.values.flatMap { it.submodules }.toSet(),
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    data class ScmRepoDecorations(val status: Map<String, String>, val submodules: Set<String>)
    data class ScmProjectDecorations(val status: Map<String, String>, val submodules: Set<String>)

    /** Drop all pushed decorations — called when the providing extension's host is torn down
     *  (uninstall/update/project switch), so the explorer never shows frozen stale badges. */
    fun clearExplorerScmDecorations() {
        _explorerScmDecorations.value = emptyMap()
    }

    /** Entry point for JCodeNative.request. Logs the request/response to the Extension Dev tools when
     *  the caller is a dev (unsigned sideloaded) extension, then delegates to [extensionApiRequestImpl]. */
    suspend fun extensionApiRequest(ext: InstalledExtension, envelopeJson: String): String {
        val isDevExt = dev.jcode.workbench.ExtensionDevLog.isDev(ext.id)
        if (isDevExt) dev.jcode.workbench.ExtensionDevLog.log(
            dev.jcode.workbench.ExtensionDevLogEntry.Kind.Request, ext.id, "→ $envelopeJson",
        )
        val result = extensionApiRequestImpl(ext, envelopeJson)
        if (isDevExt) {
            val ok = runCatching { JSONObject(result).optBoolean("ok", true) }.getOrDefault(true)
            dev.jcode.workbench.ExtensionDevLog.log(
                if (ok) dev.jcode.workbench.ExtensionDevLogEntry.Kind.Response
                else dev.jcode.workbench.ExtensionDevLogEntry.Kind.Error,
                ext.id, "← $result",
            )
        }
        return result
    }

    private suspend fun extensionApiRequestImpl(ext: InstalledExtension, envelopeJson: String): String {
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
            openExtensionViewPage(ext.id, payload.optString("view"), payload.optString("title").ifBlank { null })
            return apiOk(JSONObject())
        }
        // The last editor context-menu tap targeting this extension, if any — consumed on read. Pages
        // opened BY the tap pull it on boot; already-open pages get the pushed `contextAction` event.
        if (type == "workbench.pendingContextAction") {
            val pending = pendingContextActions.remove(ext.id)
            return apiOk(JSONObject().apply { if (pending != null) put("action", JSONObject(pending)) })
        }
        // Explorer decorations are writable only by extensions that declare the contribution — the
        // generic `workbench` capability must not let any extension forge another's VCS badges.
        if (type == "workbench.setExplorerDecorations" && !ext.contributes.explorerDecorations) {
            return apiError("extension does not contribute explorerDecorations")
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
        return runCatching { dispatchExtensionApi(type, payload, ext.id) }
            .getOrElse { apiError(it.message ?: "internal error") }
    }

    private suspend fun dispatchExtensionApi(type: String, p: JSONObject, callerExtId: String): String = when (type) {
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
                distroService.exec(command, workdir = workdir, env = env, timeoutMs = timeout, user = user, raw = true)
            } else {
                distroService.exec(command, env = env, timeoutMs = timeout, user = user, raw = true)
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
            // Honor the user's global web-preview browser choice (was hardcoded to SYSTEM).
            val choice = webPreviewBrowser.value
            if (choice == dev.jcode.design.WebPreviewBrowsers.BUILTIN) {
                dev.jcode.workbench.BuiltinBrowser.requestOpen(url)
            } else {
                ProjectRunner.openInBrowser(appContext, url, choice)
            }
            apiOk(JSONObject())
        }

        // Register a top-level folder (already created on disk under the guest /workspace mount) as a
        // Project and open it. `destinationId` chooses the target workspace: "default"/absent = the
        // Default Workspace (opens immediately), a workspace id = that User Workspace (switches to it
        // and prompts open-vs-add). Absent id registers under whatever workspace is currently open.
        "workbench.openFolder" -> {
            val name = p.optString("name").trim()
            require(name.isNotBlank()) { "name required" }
            val sanitized = workspaceManager.sanitizedFolderName(name)
            val defId = workspaceManager.ensureDefaultWorkspaceId()
            val destId = p.optString("destinationId").trim()
            val currentId = workspaceManager.currentWorkspace.value?.id ?: defId
            val targetWs = when {
                destId.isBlank() -> currentId
                destId == "default" -> defId
                else -> destId.toLongOrNull()?.takeIf { workspaceManager.workspaceExists(it) } ?: defId
            }
            // Opening clears the current editor tabs; flush unsaved buffers first and refuse rather
            // than silently discarding work a dialog never guarded.
            require(saveAllDirtyAwait()) { "unsaved changes could not be saved — save or close open files first" }
            // Make the target the current context (rebuilds the breadcrumb: Default root + target) so
            // resetDefaultWorkspaceProject's single-slot check and project selection resolve correctly.
            workspaceManager.restoreWorkspace(targetWs)
            if (targetWs == defId) {
                resetDefaultWorkspaceProject()
                val project = workspaceManager.createNodeIn(defId, sanitized, WorkspaceNodeType.Project, null)
                _selectedProjectId.value = project.id
                apiOk(JSONObject().put("name", project.name).put("opened", true))
            } else {
                val project = workspaceManager.createNodeIn(targetWs, sanitized, WorkspaceNodeType.Project, null)
                _postClonePrompt.value = project
                apiOk(JSONObject().put("name", project.name).put("opened", false))
            }
        }

        // Adopt a folder an extension materialized under the guest /sources staging mount (e.g. the
        // SCM extension's clones) into the workbench. `type` "project" moves it into the current
        // workspace and opens it; "workspace" moves it into the projects root, stamps it as a User
        // Workspace, and enters it (top-level folders become projects). The folder physically leaves
        // /sources, so the staging dir only ever holds not-yet-classified material.
        //
        // Omitting `type` means "you decide": a folder carrying its own `.jcode` type (a repo that
        // ships one) is adopted as what it declares, and one that declares nothing is left staged and
        // reported as `needsType` — the host has no dialog for this, so the extension that staged the
        // folder asks and calls back with an explicit type. Discarding is likewise the extension's
        // business: it just deletes the staged folder in the runtime.
        "workbench.addFolder" -> {
            val guest = p.optString("path").trim().trimEnd('/')
            val prefix = dev.jcode.core.distro.WorkspaceHostPaths.SOURCES_GUEST + "/"
            require(guest.startsWith(prefix)) { "path must be under ${dev.jcode.core.distro.WorkspaceHostPaths.SOURCES_GUEST}" }
            val name = guest.removePrefix(prefix)
            require(name.isNotBlank() && !name.contains('/') && !name.startsWith(".")) {
                "path must name a top-level sources folder"
            }
            val staged = File(sourcesRoot(), name)
            require(staged.isDirectory) { "no staged folder named '$name'" }
            val stagedPath = FsPath.Local(staged)
            val requested = p.optString("type").trim()
            val nodeType = when {
                requested.equals("workspace", ignoreCase = true) -> WorkspaceNodeType.Workspace
                requested.equals("project", ignoreCase = true) -> WorkspaceNodeType.Project
                workspaceManager.folderNeedsType(stagedPath) -> null
                workspaceManager.isWorkspaceFolder(stagedPath) -> WorkspaceNodeType.Workspace
                else -> WorkspaceNodeType.Project
            }
            when (nodeType) {
                null -> apiOk(JSONObject().put("needsType", true))
                else -> {
                    // Adoption clears the current editor tabs; flush unsaved buffers first and refuse
                    // rather than silently discarding work a dialog never guarded.
                    require(saveAllDirtyAwait()) { "unsaved changes could not be saved — save or close open files first" }
                    val node = adoptStagedFolder(staged, nodeType)
                    apiOk(JSONObject().put("name", node.name).put("workspace", nodeType == WorkspaceNodeType.Workspace))
                }
            }
        }

        // Close the editor tab hosting the caller's own `#view` page (from workbench.openView), so an
        // extension can dismiss its UI once the flow it drives is finished.
        "workbench.closeView" -> {
            val tabId = EXT_APP_PREFIX + callerExtId + "#" + p.optString("view").trim()
            val existed = _editorGroup.value.tabs.any { it.id == tabId }
            closeTabsNow(setOf(tabId), activate = null)
            apiOk(JSONObject().put("closed", existed))
        }

        "service.start" -> {
            val id = p.optString("id").ifBlank { "service" }
            val command = p.optString("command")
            require(command.isNotBlank()) { "command required" }
            val started = startRuntimeService(
                extId = callerExtId,
                id = id,
                command = command,
                user = p.optString("user").ifBlank { "root" },
                extraPath = p.optString("extraPath"),
            )
            apiOk(JSONObject().put("running", started).put("id", id))
        }

        "service.stop" -> {
            stopRuntimeService(callerExtId, p.optString("id").ifBlank { "service" })
            apiOk(JSONObject())
        }

        "service.status" -> {
            val id = p.optString("id").ifBlank { "service" }
            apiOk(JSONObject().put("running", runtimeServices[svcKey(callerExtId, id)]?.isAlive == true).put("id", id))
        }

        "workbench.activeFile" -> apiOk(JSONObject(activeFileEventJson()))

        // The SCM extension injects a project's .gitignore patterns as the Explorer's "by-injected"
        // hide list. `path` is the project/repo guest path; matched to a project by its bind target.
        "workbench.setHiddenInjected" -> {
            val guestPath = p.optString("path").trim().trimEnd('/')
            val arr = p.optJSONArray("patterns")
            val patterns = if (arr != null) (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() } else emptyList()
            val proj = currentWorkspace.value?.projects.orEmpty().firstOrNull { pr ->
                val g = pr.distroBindTarget.trimEnd('/')
                guestPath == g || guestPath.startsWith("$g/") || g.startsWith("$guestPath/")
            }
            if (proj != null) setHiddenInjected(proj.id.toString(), patterns)
            apiOk(JSONObject().put("matched", proj != null))
        }

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

        // Per-file VCS decorations for the Explorer (status letters + submodule dirs). `path` is a repo
        // guest root; entry paths are relative to it. One push replaces that repo's whole slice under
        // its project, so a repo going clean clears its letters.
        "workbench.setExplorerDecorations" -> {
            val repoGuest = p.optString("path").trim().trimEnd('/')
            val resolved = if (repoGuest.isNotEmpty() && !repoGuest.split('/').contains("..")) {
                guestToHostInProject(repoGuest)
            } else {
                null
            }
            if (resolved != null) {
                val (proj, repoHost) = resolved
                val repoRoot = repoHost.absolutePath.trimEnd('/')
                fun hostOf(rel: String): String? = rel.trim().trim('/').takeIf { it.isNotEmpty() && !it.split('/').contains("..") }
                    ?.let { "$repoRoot/$it" }
                val status = buildMap {
                    val arr = p.optJSONArray("decorations") ?: org.json.JSONArray()
                    for (i in 0 until minOf(arr.length(), MAX_EXPLORER_DECORATIONS)) {
                        val d = arr.optJSONObject(i) ?: continue
                        val code = d.optString("status").trim().takeIf { it.isNotEmpty() } ?: continue
                        hostOf(d.optString("path"))?.let { put(it, code.take(2)) }
                    }
                }
                val submodules = buildSet {
                    val arr = p.optJSONArray("submodules") ?: org.json.JSONArray()
                    for (i in 0 until minOf(arr.length(), MAX_EXPLORER_DECORATIONS)) {
                        hostOf(arr.optString(i))?.let(::add)
                    }
                }
                _explorerScmDecorations.update { byProject ->
                    val pid = proj.id.toString()
                    val repos = byProject[pid].orEmpty() + (repoGuest to ScmRepoDecorations(status, submodules))
                    byProject + (pid to repos)
                }
            }
            apiOk(JSONObject().put("matched", resolved != null))
        }

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
    // server survives until we destroy() it (which reaps its tree). Keyed by "<extId> <serviceId>"
    // (extension ids never contain spaces) so services can be listed + reaped per owning extension
    // for the Task Manager "Background extensions" section.
    private val runtimeServices = ConcurrentHashMap<String, Process>()

    private fun svcKey(extId: String, id: String) = "$extId $id"

    /** Start (or confirm running) a long-lived server inside the runtime. Idempotent per (ext, id). */
    fun startRuntimeService(extId: String, id: String, command: String, user: String = "root", extraPath: String = ""): Boolean {
        val key = svcKey(extId, id)
        runtimeServices[key]?.let { if (it.isAlive) return true else runtimeServices.remove(key) }
        val process = distroService.spawnDapProcess(
            command = command,
            userOverride = user,
            extraPath = extraPath,
        ) ?: return false
        runtimeServices[key] = process
        // Drain stdout/stderr so the OS pipe buffers never fill and block the server. Kept as daemon
        // threads (mirrors DebugController's stderr drainer); output is discarded (server has its own log).
        Thread { runCatching { process.inputStream.bufferedReader().forEachLine { } } }
            .apply { isDaemon = true }.start()
        Thread { runCatching { process.errorStream.bufferedReader().forEachLine { } } }
            .apply { isDaemon = true }.start()
        return true
    }

    fun stopRuntimeService(extId: String, id: String) {
        runtimeServices.remove(svcKey(extId, id))?.let { runCatching { it.destroy() } }
    }

    /** Destroy every runtime service started by [extId] (proot --kill-on-exit reaps each tree). */
    private fun reapExtensionServices(extId: String) {
        val prefix = "$extId "
        runtimeServices.keys.filter { it.startsWith(prefix) }.forEach { key ->
            runtimeServices.remove(key)?.let { runCatching { it.destroy() } }
        }
    }

    /** Reap every runtime service (called on project/workspace close and ViewModel teardown). */
    fun stopAllRuntimeServices() {
        runtimeServices.values.forEach { runCatching { it.destroy() } }
        runtimeServices.clear()
    }

    // --- Background extensions (Task Manager "Background extensions" section) -----------------------
    // A "background extension" is one running a persistent WebView host (the SCM sidebar or OpenChamber
    // Chat) and/or a service.start server. Stop reaps its services and tears down its host; the SCM
    // host re-attaches whenever a project is open, so it is additionally SUSPENDED to stay down until
    // the user starts it again (the Chat host restarts on its own when the tab is reopened).

    data class BackgroundExtensionInfo(
        val id: String,
        val name: String,
        val hasHost: Boolean,
        val serviceCount: Int,
        val suspended: Boolean,
    )

    private val _suspendedBackgroundExtensions = MutableStateFlow<Set<String>>(emptySet())

    /** SCM hosts the user stopped; the shell gates SCM host re-attach on this so it stays down. */
    val suspendedBackgroundExtensions: StateFlow<Set<String>> = _suspendedBackgroundExtensions.asStateFlow()

    /** Snapshot of every extension doing background work, for the Task Manager (polled, not reactive). */
    fun backgroundExtensionSnapshot(): List<BackgroundExtensionInfo> {
        val scmIds = dev.jcode.workbench.ScmWebViewHolder.ids().toSet()
        val chatIds = dev.jcode.workbench.AgentChatWebViewHolder.ids().toSet()
        val serviceCounts = runtimeServices.keys.groupingBy { it.substringBefore(' ') }.eachCount()
        val suspended = _suspendedBackgroundExtensions.value
        val names = installedExtensions.value.associate { it.id to it.name }
        return (scmIds + chatIds + serviceCounts.keys + suspended).map { id ->
            BackgroundExtensionInfo(
                id = id,
                name = names[id] ?: id,
                hasHost = id in scmIds || id in chatIds,
                serviceCount = serviceCounts[id] ?: 0,
                suspended = id in suspended,
            )
        }.sortedBy { it.name.lowercase() }
    }

    /** Stop a background extension: reap its runtime services and tear down its host. A live SCM host
     *  is suspended (it would otherwise re-attach on the next project open); a Chat host is destroyed
     *  and simply restarts when the user reopens the Chat tab. Runs on the main thread (WebView.destroy). */
    fun stopBackgroundExtension(id: String) {
        viewModelScope.launch(Dispatchers.Main) {
            reapExtensionServices(id)
            if (id in liveExtensionHosts || dev.jcode.workbench.ScmWebViewHolder.get(id) != null) {
                _suspendedBackgroundExtensions.update { it + id }
                liveExtensionHosts.remove(id)
                clearExplorerScmDecorations()
                // The shell's ScmBackgroundHost tears the WebView down once scmHostExt drops to null.
            }
            if (dev.jcode.workbench.AgentChatWebViewHolder.get(id) != null) {
                dev.jcode.workbench.AgentChatWebViewHolder.destroy(id)
            }
        }
    }

    /** Resume a stopped SCM host: lift the suspension so it re-attaches on the next recomposition. */
    fun startBackgroundExtension(id: String) {
        _suspendedBackgroundExtensions.update { it - id }
    }

    override fun onCleared() {
        stopAllRuntimeServices()
        super.onCleared()
    }

    /** Open (or focus) the Extension Settings page (per-extension settings + permissions). */
    fun openExtensionPermissionsPage() {
        val existing = _editorGroup.value.tabs.firstOrNull { it.pageKind == EditorPageKind.ExtensionPermissions }
        if (existing != null) {
            _editorGroup.value = _editorGroup.value.withActiveTabChanged(existing.id)
            return
        }
        val tab = EditorTab.page(EXT_PERMISSIONS_TAB_ID, "Extension Settings", EditorPageKind.ExtensionPermissions)
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

    /**
     * Open (or focus) the single built-in browser editor tab. The URL is carried by
     * [dev.jcode.workbench.BuiltinBrowser] (set via its `requestOpen`), so callers that already
     * requested a navigation only need to surface the tab.
     */
    fun openBrowserTab() {
        val group = _editorGroup.value
        val existing = group.tabs.firstOrNull { it.id == BROWSER_TAB_ID }
        _editorGroup.value = if (existing != null) {
            group.withActiveTabChanged(existing.id)
        } else {
            group.withTabAdded(EditorTab.page(BROWSER_TAB_ID, "Browser", EditorPageKind.Browser))
        }
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
    /** Open the run-config editor for [index] (null = a new config). */
    fun openRunConfigPage(project: Project, index: Int?) {
        val suffix = index?.toString() ?: "new"
        openDetailPage(RUN_CONFIG_PREFIX + project.id + "#" + suffix, EditorPageKind.RunConfig) {
            if (index == null) "New run — ${project.name}" else "Run: ${project.name}"
        }
    }

    /** Open the build-task editor for [index] (null = a new task). */
    fun openBuildConfigPage(project: Project, index: Int?) {
        val suffix = index?.toString() ?: "new"
        openDetailPage(BUILD_CONFIG_PREFIX + project.id + "#" + suffix, EditorPageKind.BuildConfig) {
            if (index == null) "New build — ${project.name}" else "Build: ${project.name}"
        }
    }

    /** Close every open config-editor tab whose id starts with [prefix] (positional indices shift on
     *  insert/delete, so a stale tab must not be left bound to a moved config). */
    private fun closeConfigEditorTabs(prefix: String) {
        val ids = _editorGroup.value.tabs.map { it.id }.filter { it.startsWith(prefix) }.toSet()
        if (ids.isNotEmpty()) closeTabsNow(ids, activate = null)
    }

    /** Upsert one run config at [index] (null appends) into the project's `.jcode/run.yaml`. */
    fun saveRunConfig(project: Project, index: Int?, config: dev.jcode.core.config.RunConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { dev.jcode.run.ProjectRunner.upsertRun(project, index, config) }
                .onSuccess {
                    _runConfigVersion.value++
                    // A "New" tab holds index=null forever, so re-saving would keep appending; close it
                    // after the first save (re-open via Configure to edit the now-saved config in place).
                    if (index == null) withContext(Dispatchers.Main) { closeTabsNow(setOf(RUN_CONFIG_PREFIX + project.id + "#new"), activate = null) }
                    _messages.tryEmit("Saved run config for ${project.name}")
                }
                .onFailure { _messages.tryEmit("Failed to save run config: ${it.message ?: "error"}") }
        }
    }

    /** Append all of [configs] to the project's `.jcode/run.yaml` in one write (a framework file-pick
     *  in the "Add run config" dialog creates every config that file offers at once). */
    fun saveRunConfigs(project: Project, configs: List<dev.jcode.core.config.RunConfig>) {
        if (configs.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { dev.jcode.run.ProjectRunner.upsertRuns(project, configs) }
                .onSuccess {
                    _runConfigVersion.value++
                    _messages.tryEmit("Saved run config for ${project.name}")
                }
                .onFailure { _messages.tryEmit("Failed to save run config: ${it.message ?: "error"}") }
        }
    }

    /** Upsert one build task at [index] (null appends). */
    fun saveBuildConfig(project: Project, index: Int?, config: dev.jcode.core.config.BuildConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { dev.jcode.run.ProjectRunner.upsertBuild(project, index, config) }
                .onSuccess {
                    _runConfigVersion.value++
                    if (index == null) withContext(Dispatchers.Main) { closeTabsNow(setOf(BUILD_CONFIG_PREFIX + project.id + "#new"), activate = null) }
                    _messages.tryEmit("Saved build task for ${project.name}")
                }
                .onFailure { _messages.tryEmit("Failed to save build task: ${it.message ?: "error"}") }
        }
    }

    fun deleteRunConfig(project: Project, index: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { dev.jcode.run.ProjectRunner.deleteRun(project, index) }.onSuccess {
                _runConfigVersion.value++
                // Every later config shifts down one, so any open run-config editor's index is now stale.
                withContext(Dispatchers.Main) { closeConfigEditorTabs(RUN_CONFIG_PREFIX + project.id + "#") }
                _messages.tryEmit("Deleted run config")
            }
        }
    }

    fun deleteBuildConfig(project: Project, index: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { dev.jcode.run.ProjectRunner.deleteBuild(project, index) }.onSuccess {
                _runConfigVersion.value++
                withContext(Dispatchers.Main) { closeConfigEditorTabs(BUILD_CONFIG_PREFIX + project.id + "#") }
                _messages.tryEmit("Deleted build task")
            }
        }
    }

    /** Launch the DAP debugger on [config]'s debug entry (a guest source path). */
    fun startDebugForConfig(config: dev.jcode.core.config.RunConfig) {
        val entry = config.debugEntry.trim()
        if (entry.isBlank()) {
            _messages.tryEmit("Set a debug entry for '${config.name}' in Configure to debug it.")
            return
        }
        val host = guestToHostInProject(entry)?.second?.absolutePath
        if (host == null) {
            _messages.tryEmit("Debug entry not found: $entry")
            return
        }
        startDebug(host)
    }

    fun selectEditorTab(tabId: String) {
        _editorGroup.value = _editorGroup.value.withActiveTabChanged(tabId)
        requestSyncOpenFilesFromDisk()
    }

    /** Flip a file tab between the source editor and its rendered preview (Markdown). */
    fun toggleTabPreview(tabId: String) {
        val current = _editorGroup.value.tabs.firstOrNull { it.id == tabId } ?: return
        if (current.editorState == null) return
        _editorGroup.value = _editorGroup.value.withTabUpdated(current.copy(previewMode = !current.previewMode))
    }

    // Closing tabs with unsaved changes routes through a Save/Discard/Close-Saved prompt first; the
    // dialog (shown in JCodeApp) reads this and calls back into [resolveEditorClose].
    private val _pendingEditorClose = MutableStateFlow<PendingEditorClose?>(null)
    val pendingEditorClose: StateFlow<PendingEditorClose?> = _pendingEditorClose.asStateFlow()
    // The (target tab ids, tab to activate afterward) captured while the prompt is up.
    private var pendingEditorCloseTargets: Pair<Set<String>, String?>? = null

    fun closeEditorTab(tabId: String) = requestCloseEditorTabs(setOf(tabId), activate = null)

    /** Toggle a tab's pinned flag and re-sort so pinned tabs lead (stable — relative order kept). */
    fun toggleEditorTabPinned(tabId: String) {
        val group = _editorGroup.value
        val tab = group.tabs.firstOrNull { it.id == tabId } ?: return
        val reordered = group.tabs
            .map { if (it.id == tabId) it.copy(pinned = !tab.pinned) else it }
            .sortedBy { !it.pinned }
        _editorGroup.value = group.copy(tabs = reordered)
    }

    /** Close every tab except [tabId] and any pinned tabs; [tabId] becomes active. */
    fun closeOtherEditorTabs(tabId: String) {
        val targets = _editorGroup.value.tabs.filter { it.id != tabId && !it.pinned }.mapTo(HashSet()) { it.id }
        requestCloseEditorTabs(targets, activate = tabId)
    }

    /** Close every unpinned tab positioned to the right of [tabId]; [tabId] becomes active. */
    fun closeEditorTabsToRight(tabId: String) {
        val tabs = _editorGroup.value.tabs
        val anchor = tabs.indexOfFirst { it.id == tabId }
        if (anchor < 0) return
        val targets = tabs.filterIndexed { index, tab -> index > anchor && !tab.pinned }.mapTo(HashSet()) { it.id }
        requestCloseEditorTabs(targets, activate = tabId)
    }

    /** Close [ids] immediately unless some carry unsaved changes, in which case raise the prompt. */
    private fun requestCloseEditorTabs(ids: Set<String>, activate: String?) {
        val targets = _editorGroup.value.tabs.filter { it.id in ids }
        if (targets.isEmpty()) return
        val dirty = targets.filter { it.isDirty }
        if (dirty.isEmpty()) {
            closeTabsNow(ids, activate)
            return
        }
        pendingEditorCloseTargets = ids to activate
        _pendingEditorClose.value = PendingEditorClose(dirtyTitles = dirty.map { it.title })
    }

    /** Resolve the unsaved-changes prompt for the pending tab-close set. */
    fun resolveEditorClose(choice: EditorCloseChoice) {
        val (ids, activate) = pendingEditorCloseTargets ?: run { _pendingEditorClose.value = null; return }
        _pendingEditorClose.value = null
        pendingEditorCloseTargets = null
        when (choice) {
            EditorCloseChoice.CANCEL -> Unit // keep everything open
            EditorCloseChoice.DISCARD -> closeTabsNow(ids, activate)
            EditorCloseChoice.CLOSE_SAVED -> {
                val clean = _editorGroup.value.tabs.filter { it.id in ids && !it.isDirty }.mapTo(HashSet()) { it.id }
                closeTabsNow(clean, activate)
            }
            EditorCloseChoice.SAVE -> viewModelScope.launch {
                val dirty = _editorGroup.value.tabs.filter { it.id in ids && it.isDirty && it.editorState != null }
                dirty.forEach { saveTabAwait(it) }
                // Close every target tab now clean; any that couldn't be saved (e.g. no path) stay open.
                val closable = _editorGroup.value.tabs.filter { it.id in ids && !it.isDirty }.mapTo(HashSet()) { it.id }
                closeTabsNow(closable, activate)
            }
        }
    }

    private fun closeTabsNow(ids: Set<String>, activate: String?) {
        if (ids.isEmpty()) return
        val group = _editorGroup.value
        val doomed = group.tabs.filter { it.id in ids }
        if (doomed.isEmpty()) return
        doomed.forEach { tab ->
            tab.editorState?.close()
            untrackDirty(tab.id)
            diskSignatures.remove(tab.id)
        }
        val remaining = group.tabs.filterNot { it.id in ids }
        val newActive = when {
            activate != null && remaining.any { it.id == activate } -> activate
            remaining.any { it.id == group.activeTabId } -> group.activeTabId
            else -> remaining.lastOrNull()?.id
        }
        _editorGroup.value = group.copy(tabs = remaining, activeTabId = newActive)
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
        if (tab.editorState == null) return // page tab: nothing to persist
        viewModelScope.launch { if (saveTabAwait(tab)) emitMessage("Saved ${tab.title}") }
    }

    /**
     * Write [tab] to disk, suspending until the write finishes; returns true if the tab is now clean.
     * Emits only on failure so callers can report success as a per-file toast or a batch summary.
     */
    private suspend fun saveTabAwait(tab: EditorTab): Boolean {
        val state = tab.editorState ?: return false // page tab: nothing to persist
        val file = tab.filePath
        if (file.path.isBlank()) {
            emitMessage("Can't save \"${tab.title}\": unsupported file source")
            return false
        }
        // Snapshots are immutable, so capturing the reference now lets us both write its bytes and
        // detect whether newer edits landed during the write (so we don't clear a stale dirty).
        val snapshot = state.snapshot.value
        return runCatching {
            withContext(Dispatchers.IO) {
                val bytes = snapshot.readRange(0, snapshot.byteLength)
                workspaceManager.fsFor(FsPath.Local(file)).write(FsPath.Local(file), bytes)
            }
            // A keystroke landing mid-write mints a newer snapshot: the write succeeded but the tab is
            // dirty again, so don't clear dirty and report the tab as still-unsaved (not a false "Saved").
            val clean = state.snapshot.value === snapshot
            if (clean) state.markClean()
            withContext(Dispatchers.IO) { file.diskSignatureOrNull() }?.let { diskSignatures[tab.id] = it }
            queueSyntaxCheck(file)
            notifyWorkspaceFilesChanged()
            clean
        }.getOrElse {
            emitMessage("Failed to save ${tab.title}: ${it.message ?: "error"}")
            false
        }
    }

    /**
     * Save every dirty tab and suspend until all writes finish — the close-guard uses this so a
     * workspace/project switch only proceeds once unsaved buffers are safely on disk. Returns true only
     * if every tab is now clean; false if any couldn't be saved (e.g. a SAF/blank-path tab) so the
     * caller can avoid discarding unsaved work.
     */
    suspend fun saveAllDirtyAwait(): Boolean {
        val dirty = _editorGroup.value.tabs.filter { it.isDirty && it.editorState != null }
        if (dirty.isEmpty()) return true
        var saved = 0
        dirty.forEach { if (saveTabAwait(it)) saved++ }
        if (saved > 0) emitMessage(if (saved == 1) "Saved 1 file" else "Saved $saved files")
        return saved == dirty.size
    }

    private suspend fun openLocalFile(file: File, line: Int? = null, column: Int? = null) {
        val stableId = file.absolutePath
        val existing = _editorGroup.value.tabs.firstOrNull { it.id == stableId }
        if (existing != null) {
            _editorGroup.value = _editorGroup.value.withActiveTabChanged(existing.id)
            existing.editorState?.requestRevealAt(line, column)
            return
        }

        // Probe the file, read it, and build the buffer OFF the main thread — for a large file (or
        // a burst of restored tabs at launch) the read + piece-table build is the jank. The buffer
        // isn't shared until the tab is published below, so building it on IO is safe.
        val prep = withContext(Dispatchers.IO) {
            when {
                !file.exists() || !file.isFile -> OpenPrep.Missing
                // Images open in the built-in viewer. Checked before the text probe so .svg (which
                // is text) renders rather than opening as XML source.
                file.extension.lowercase() in IMAGE_EXTENSIONS -> OpenPrep.Image
                !file.isLikelyTextFile() -> OpenPrep.Binary
                else -> OpenPrep.Text(EditorTab.create(file, stableId), file.diskSignatureOrNull())
            }
        }
        when (prep) {
            OpenPrep.Missing -> emitMessage("File no longer exists: ${file.name}")
            OpenPrep.Image -> _editorGroup.value = _editorGroup.value.withTabAdded(
                EditorTab.page(stableId, file.name, EditorPageKind.ImageViewer),
            )
            OpenPrep.Binary -> emitMessage("Binary preview is not implemented yet for ${file.name}.")
            is OpenPrep.Text -> {
                val tab = prep.tab
                applyConfigToTab(tab, effectiveConfig.value)
                // Set the reveal before the tab is shown so the view applies it on attach.
                tab.editorState?.requestRevealAt(line, column)
                trackDirty(tab)
                prep.signature?.let { diskSignatures[stableId] = it }
                _editorGroup.value = _editorGroup.value.withTabAdded(tab)
                queueSyntaxCheck(file)
            }
        }
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

        if (node.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS) {
            _editorGroup.value = _editorGroup.value.withTabAdded(
                EditorTab.page(stableId, node.name, EditorPageKind.ImageViewer),
            )
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
                workspaceManager.restoreWorkspace(targetWs)
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
                if (t.preview) {
                    val restored = _editorGroup.value.tabs.firstOrNull { it.id == t.id }
                    if (restored?.editorState != null && SyntaxHighlighter.isMarkdownFile(restored.filePath.name)) {
                        _editorGroup.value = _editorGroup.value.withTabUpdated(restored.copy(previewMode = true))
                    }
                }
                if (t.pinned) {
                    val restored = _editorGroup.value.tabs.firstOrNull { it.id == t.id }
                    if (restored != null) {
                        _editorGroup.value = _editorGroup.value.withTabUpdated(restored.copy(pinned = true))
                    }
                }
            }

            // Active tab (only if it survived as one of the restored tabs).
            record.activeTabId?.let { id ->
                if (_editorGroup.value.tabs.any { it.id == id }) {
                    _editorGroup.value = _editorGroup.value.withActiveTabChanged(id)
                }
            }
        } finally {
            sessionSaveEnabled = true
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
                    SessionTabRecord(
                        tab.id, tab.filePath.absolutePath,
                        dirty = true, bufferFileName = name, preview = tab.previewMode, pinned = tab.pinned,
                    )
                } else {
                    SessionTabRecord(
                        tab.id, tab.filePath.absolutePath,
                        dirty = false, bufferFileName = null, preview = tab.previewMode, pinned = tab.pinned,
                    )
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
        /** Free space to leave on app storage after importing an off-ext4 folder into /sources. */
        private const val IMPORT_FREE_SPACE_HEADROOM_BYTES = 64L * 1024 * 1024

        /** DataStore key + a synchronous mirror of "close app on swipe-away", so [BackendService]
         *  can decide in onTaskRemoved without a blocking DataStore read. */
        const val EXIT_ON_SWIPE_AWAY_KEY = "exit_on_swipe_away"

        @Volatile
        var exitOnSwipeAwayEnabled: Boolean = true

        /** Runtime teardown (destroy proot run/VM/LSP/DAP service procs) invoked on a swipe-away exit;
         *  set by the live ViewModel. Terminals are reaped separately via [TerminalSessionHost]. */
        @Volatile
        var runtimeTeardown: (() -> Unit)? = null

        /** Blocking session persist invoked right before the process is killed on a swipe-away / "Stop &
         *  close" exit, so unsaved editor buffers reach disk before [android.os.Process.killProcess]
         *  races the async [flushSessionNow]. Set by the live ViewModel; no-op if the UI never started. */
        @Volatile
        var sessionFlushBlocking: (() -> Unit)? = null

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
        const val BUILD_CONFIG_PREFIX = "jcode://build-config/"
        /** Stable id of the single built-in browser editor tab (see [openBrowserPage]). */
        const val BROWSER_TAB_ID = "jcode://browser"
        /** Extensions routed to the built-in image viewer instead of the text editor. */
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico")
    }
}
