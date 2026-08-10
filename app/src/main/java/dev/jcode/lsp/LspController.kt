package dev.jcode.lsp

import dev.jcode.core.distro.DistroService
import dev.jcode.core.distro.LspCatalogEntry
import dev.jcode.core.distro.LspServerCatalog
import dev.jcode.core.distro.WorkspaceHostPaths
import dev.jcode.core.lsp.CompletionResult
import dev.jcode.core.lsp.DiagnosticsBus
import dev.jcode.core.lsp.LocationResult
import dev.jcode.core.lsp.LspServerDescriptor
import dev.jcode.core.lsp.LspSession
import dev.jcode.core.lsp.LspState
import dev.jcode.core.lsp.ProcessLspTransport
import dev.jcode.core.lsp.TextEditResult
import dev.jcode.core.lsp.WorkspaceEditResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** What the status bar shows for one running language server. */
data class LspServerStatus(
    val serverId: String,
    val name: String,
    val root: String,
    val state: LspState,
    val detail: String?,
)

/**
 * Owns the running language servers and routes the editor's requests to them. Mirrors
 * [dev.jcode.debug.DebugController], but a debug session is singular and user-started while language
 * servers start implicitly and several run at once — so sessions are keyed by (server, project root)
 * and shared by every open file that resolves to the same pair.
 *
 * Which server handles a file, how it is launched and what marks its project root all come from
 * [LspServerCatalog]; nothing here is per-language.
 *
 * [documentText] reads the current text of an open document. Passing a reader rather than caching
 * the text keeps a second copy of every open buffer out of memory — the editor already holds it.
 */
class LspController(
    private val distroService: DistroService,
    private val scope: CoroutineScope,
    private val diagnosticsBus: DiagnosticsBus,
    private val documentText: (hostPath: String) -> String?,
    private val openDocumentPaths: () -> List<String>,
) {

    private data class SessionKey(val serverId: String, val root: String)

    private class Managed(
        val session: LspSession,
        val descriptor: LspServerDescriptor,
        val key: SessionKey,
    ) {
        var diagnosticsJob: Job? = null
        var stateJob: Job? = null
        @Volatile var lastStderr: String? = null
    }

    private class OpenDocument(val key: SessionKey, val uri: String, val languageId: String) {
        // Atomic: a document opened while its session is still handshaking is sent by whichever of
        // documentOpened and the READY replay gets there first, and only one of them may win.
        val opened = AtomicBoolean(false)
        val version = AtomicInteger(1)
        var changeJob: Job? = null
    }

    private val startMutex = Mutex()
    private val sessions = ConcurrentHashMap<SessionKey, Managed>()
    private val documents = ConcurrentHashMap<String, OpenDocument>()
    private val promptedServerIds = ConcurrentHashMap.newKeySet<String>()

    private val _servers = MutableStateFlow<List<LspServerStatus>>(emptyList())
    val servers: StateFlow<List<LspServerStatus>> = _servers.asStateFlow()

    /** Emitted once per server when an opened file needs one that is not installed. */
    private val _missingServer = MutableSharedFlow<LspCatalogEntry>(extraBufferCapacity = 8)
    val missingServer: SharedFlow<LspCatalogEntry> = _missingServer.asSharedFlow()

    /** Applied when a server pushes a `workspace/applyEdit`; returns whether the edit was applied. */
    var onApplyEdit: ((WorkspaceEditResult) -> Boolean)? = null

    init {
        // Installing a server from the prompt has to affect the file that triggered the prompt, which
        // is already open — otherwise the user installs and nothing visibly happens until they close
        // and reopen the tab.
        scope.launch {
            distroService.lspCatalogState
                .map { it.installedEntryIds }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    promptedServerIds.clear()
                    openDocumentPaths().forEach { path -> documentOpened(path) }
                }
        }
    }

    // ---- document lifecycle ---------------------------------------------------------------------

    /** Binds an opened editor tab to a session, starting one if this is the first file that needs it. */
    suspend fun documentOpened(hostPath: String) {
        val managed = ensureSession(hostPath) ?: return
        val extension = File(hostPath).extensionWithDot() ?: return
        val document = documents.getOrPut(hostPath) {
            OpenDocument(
                key = managed.key,
                uri = managed.session.hostToDistroUri(hostPath),
                languageId = managed.descriptor.languageIdFor(extension),
            )
        }
        // A session that is still handshaking replays its documents on READY (see startSession).
        if (managed.session.state.value == LspState.READY) sendDidOpen(managed, hostPath, document)
    }

    /**
     * Debounced full-document sync. Keystrokes arrive one snapshot at a time; a server re-analyses
     * the whole file on every notification, so coalescing is what keeps typing responsive.
     */
    fun documentChanged(hostPath: String) {
        val document = documents[hostPath] ?: return
        document.changeJob?.cancel()
        document.changeJob = scope.launch {
            delay(CHANGE_DEBOUNCE_MS)
            val managed = sessions[document.key] ?: return@launch
            if (!document.opened.get() || managed.session.state.value != LspState.READY) return@launch
            val text = documentText(hostPath) ?: return@launch
            runCatching { managed.session.didChange(document.uri, document.version.incrementAndGet(), text) }
        }
    }

    fun documentSaved(hostPath: String) {
        val document = documents[hostPath] ?: return
        val managed = sessions[document.key] ?: return
        scope.launch {
            // Flush any pending debounced edit first, so the server saves what is actually on disk.
            document.changeJob?.cancel()
            if (!document.opened.get() || managed.session.state.value != LspState.READY) return@launch
            val text = documentText(hostPath) ?: return@launch
            runCatching {
                managed.session.didChange(document.uri, document.version.incrementAndGet(), text)
                managed.session.didSave(document.uri, text)
            }
        }
    }

    fun documentClosed(hostPath: String) {
        val document = documents.remove(hostPath) ?: return
        document.changeJob?.cancel()
        val managed = sessions[document.key] ?: return
        scope.launch {
            if (document.opened.get()) runCatching { managed.session.didClose(document.uri) }
            // The last file for a server is worth reclaiming: these processes are heavy (jdtls holds
            // a JVM, rust-analyzer an index) and a phone has no room for idle ones.
            if (documents.values.none { it.key == document.key }) stopSession(document.key)
        }
    }

    /** Tears every session down — a different project's files never belong to the same server root. */
    fun shutdownAll() {
        documents.values.forEach { it.changeJob?.cancel() }
        documents.clear()
        sessions.keys.toList().forEach { stopSession(it) }
        promptedServerIds.clear()
    }

    // ---- language features ----------------------------------------------------------------------

    suspend fun completions(hostPath: String, line: Int, character: Int): List<CompletionResult> =
        withReadySession(hostPath, emptyList()) { session, uri -> session.completion(uri, line, character) }

    suspend fun hover(hostPath: String, line: Int, character: Int): String? =
        withReadySession(hostPath, null) { session, uri -> session.hover(uri, line, character) }

    suspend fun definition(hostPath: String, line: Int, character: Int): List<LocationResult> =
        withReadySession(hostPath, emptyList()) { session, uri -> session.definition(uri, line, character) }

    suspend fun references(hostPath: String, line: Int, character: Int): List<LocationResult> =
        withReadySession(hostPath, emptyList()) { session, uri -> session.references(uri, line, character) }

    suspend fun rename(
        hostPath: String,
        line: Int,
        character: Int,
        newName: String,
    ): WorkspaceEditResult? =
        withReadySession(hostPath, null) { session, uri -> session.rename(uri, line, character, newName) }

    suspend fun formatting(hostPath: String, tabSize: Int, insertSpaces: Boolean): List<TextEditResult> =
        withReadySession(hostPath, emptyList()) { session, uri -> session.formatting(uri, tabSize, insertSpaces) }

    /** Whether a ready server for [hostPath] advertises the capability behind [action]. */
    fun supports(hostPath: String, action: LspFeature): Boolean {
        val (managed, _) = readySession(hostPath) ?: return false
        return when (action) {
            LspFeature.Definition -> managed.session.supportsDefinition
            LspFeature.References -> managed.session.supportsReferences
            LspFeature.Rename -> managed.session.supportsRename
            LspFeature.Formatting -> managed.session.supportsFormatting
            LspFeature.Completion -> managed.session.supportsCompletion
            LspFeature.Hover -> managed.session.supportsHover
        }
    }

    /** The status of the server backing [hostPath], for the status bar and error surfacing. */
    fun statusFor(hostPath: String): LspServerStatus? {
        val document = documents[hostPath] ?: return null
        val managed = sessions[document.key] ?: return null
        return managed.toStatus()
    }

    private suspend fun <T> withReadySession(
        hostPath: String,
        fallback: T,
        block: suspend (LspSession, String) -> T,
    ): T {
        val (managed, document) = readySession(hostPath) ?: return fallback
        return runCatching { block(managed.session, document.uri) }.getOrDefault(fallback)
    }

    private fun readySession(hostPath: String): Pair<Managed, OpenDocument>? {
        val document = documents[hostPath] ?: return null
        val managed = sessions[document.key] ?: return null
        if (managed.session.state.value != LspState.READY) return null
        return managed to document
    }

    // ---- sessions -------------------------------------------------------------------------------

    private suspend fun ensureSession(hostPath: String): Managed? {
        if (distroService.environmentState.value.distroInstalled != true) {
            log("no session: the Linux environment is not installed")
            return null
        }
        val extension = File(hostPath).extensionWithDot() ?: return null
        val descriptor = LspServerDescriptor.findForExtension(extension)
        if (descriptor == null) {
            log("no session: no catalog server handles $extension")
            return null
        }
        if (descriptor.id !in distroService.lspCatalogState.value.installedEntryIds) {
            promptInstall(descriptor.id)
            return null
        }
        val root = detectRoot(hostPath, descriptor.rootDetectors)
        if (root == null) {
            log("no session: $extension file is outside the workspace")
            return null
        }
        val key = SessionKey(descriptor.id, root)
        return startMutex.withLock {
            val existing = sessions[key]
            if (existing != null && existing.session.state.value != LspState.ERROR) return@withLock existing
            if (existing != null) stopSession(key)
            startSession(descriptor, key)
        }
    }

    private fun startSession(descriptor: LspServerDescriptor, key: SessionKey): Managed {
        val session = LspSession(descriptor, key.root) { command ->
            distroService.spawnStdioProcess(
                command = command,
                workdir = WorkspaceHostPaths.hostToGuest(key.root),
            )?.let { process ->
                ProcessLspTransport(process) { line -> sessions[key]?.lastStderr = line }
            }
        }
        val managed = Managed(session, descriptor, key)
        sessions[key] = managed

        session.onApplyEdit = { edit ->
            onApplyEdit?.invoke(session.parseWorkspaceEdit(edit)) ?: false
        }
        val source = diagnosticsSource(key)
        managed.diagnosticsJob = scope.launch {
            session.diagnostics.collect { diagnosticsBus.updateSourceDiagnostics(source, it) }
        }
        managed.stateJob = scope.launch {
            session.state.collect { state ->
                publishStatus()
                when (state) {
                    LspState.READY -> log("${descriptor.id} ready in ${key.root.substringAfterLast('/')}")
                    LspState.ERROR -> log(
                        "${descriptor.id} failed: ${session.errorMessage ?: "unknown"}" +
                            (managed.lastStderr?.let { " | $it" } ?: ""),
                    )
                    else -> Unit
                }
            }
        }
        scope.launch {
            log("starting ${descriptor.id} in ${key.root.substringAfterLast('/')}")
            session.start(session.hostToDistroUri(key.root))
            if (session.state.value != LspState.READY) return@launch
            // Documents opened while the handshake was in flight were held back; send them now.
            documents.entries
                .filter { it.value.key == key && !it.value.opened.get() }
                .forEach { (path, document) -> sendDidOpen(managed, path, document) }
        }
        publishStatus()
        return managed
    }

    private suspend fun sendDidOpen(managed: Managed, hostPath: String, document: OpenDocument) {
        if (!document.opened.compareAndSet(false, true)) return
        val text = documentText(hostPath)
        if (text == null) {
            document.opened.set(false)
            return
        }
        runCatching {
            managed.session.didOpen(document.uri, document.languageId, document.version.get(), text)
        }.onFailure { document.opened.set(false) }
    }

    private fun stopSession(key: SessionKey) {
        val managed = sessions.remove(key) ?: return
        managed.diagnosticsJob?.cancel()
        managed.stateJob?.cancel()
        diagnosticsBus.clearSource(diagnosticsSource(key))
        managed.session.close()
        documents.entries.removeAll { it.value.key == key }
        publishStatus()
    }

    /**
     * The nearest ancestor holding one of the server's root markers, bounded by the projects root.
     * Falls back to the project directory itself. Files outside the workspace return null: proot only
     * binds the projects root, so a server could not read them anyway.
     */
    private fun detectRoot(hostPath: String, detectors: List<String>): String? {
        val projectsRoot = File(WorkspaceHostPaths.projectsRoot).absolutePath.trimEnd('/')
        val file = File(hostPath).absoluteFile
        if (!file.path.startsWith("$projectsRoot/")) return null
        var directory: File? = file.parentFile
        var project: File? = null
        while (directory != null && directory.path.startsWith("$projectsRoot/")) {
            project = directory
            if (detectors.any { File(directory, it).exists() }) return directory.path
            directory = directory.parentFile
        }
        return project?.path
    }

    /**
     * The installed set is read from DataStore asynchronously, so on a cold launch the restored tabs
     * routinely ask before it lands — every server looks absent for a moment. Re-checking after a
     * grace period is what keeps that from accusing installed servers of being missing; the
     * `installedEntryIds` collector separately re-runs `documentOpened` once the real set arrives.
     */
    private fun promptInstall(serverId: String) {
        if (!promptedServerIds.add(serverId)) return
        scope.launch {
            // Wait for the catalog to actually report itself loaded. Timing cannot substitute for
            // this: on a cold launch the environment is probed first, so the installed set stays
            // empty AND quiet for ~10s, which is indistinguishable from a fresh device with nothing
            // installed. The timeout is a backstop, not the mechanism.
            withTimeoutOrNull(CATALOG_LOAD_TIMEOUT_MS) {
                distroService.lspCatalogState.first { it.loaded }
            }
            if (serverId in distroService.lspCatalogState.value.installedEntryIds) {
                promptedServerIds.remove(serverId)
                return@launch
            }
            log("no session: $serverId is not installed")
            LspServerCatalog.findById(serverId)?.let { _missingServer.emit(it) }
        }
    }

    /** Keyed by root as well as server so two projects sharing a server cannot clobber each other. */
    private fun diagnosticsSource(key: SessionKey): String = "lsp:${key.serverId}:${key.root}"

    private fun publishStatus() {
        _servers.value = sessions.values.map { it.toStatus() }.sortedBy { it.name }
    }

    private fun Managed.toStatus(): LspServerStatus = LspServerStatus(
        serverId = descriptor.id,
        name = LspServerCatalog.findById(descriptor.id)?.name ?: descriptor.id,
        root = key.root,
        state = session.state.value,
        detail = session.errorMessage ?: lastStderr,
    )

    /** ".kt" for "Main.kt"; null for a file with no extension. */
    private fun File.extensionWithDot(): String? {
        val dot = name.lastIndexOf('.')
        return if (dot < 0 || dot == name.length - 1) null else name.substring(dot)
    }

    /** Session lifecycle is otherwise invisible: a server that never starts just produces no features. */
    private fun log(message: String) {
        android.util.Log.i(TAG, message)
    }

    private companion object {
        const val TAG = "LspController"
        const val CHANGE_DEBOUNCE_MS = 400L
        const val CATALOG_LOAD_TIMEOUT_MS = 60_000L
    }
}

/** The capabilities the editor gates its UI on. */
enum class LspFeature { Definition, References, Rename, Formatting, Completion, Hover }
