package dev.jcode.core.config

import android.os.FileObserver
import java.io.File
import java.util.LinkedHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.common.FlowStyle

const val WORKSPACE_CONFIG_FILE_NAME = ".jcode-workspace.yaml"

/**
 * Returns the relative path to the project config file for a given folder name.
 * Format: `.jcode/{folder_name}.yaml`
 */
fun projectConfigRelativePath(folderName: String): String = ".jcode/$folderName.yaml"

/**
 * YAML-backed config service with live file watching for workspace and project config files.
 * Invalid YAML never replaces the last known-good config.
 */
class ConfigService {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loader = Load(
        LoadSettings.builder()
            .setAllowDuplicateKeys(false)
            .build()
    )
    private val dumper = Dump(
        DumpSettings.builder()
            .setDefaultFlowStyle(FlowStyle.BLOCK)
            .setIndent(2)
            .setSplitLines(false)
            .build()
    )

    private var workspaceConfigFile: File? = null
    private var projectConfigFile: File? = null

    /** Host path of the bound workspace config file (for diagnostics), or null when unbound. */
    val workspaceConfigPath: String? get() = workspaceConfigFile?.absolutePath

    /** Host path of the bound project config file (for diagnostics), or null when unbound. */
    val projectConfigPath: String? get() = projectConfigFile?.absolutePath
    private var workspaceWatcher: Job? = null
    private var projectWatcher: Job? = null
    private var workspaceDocument: Map<String, Any?> = emptyMap()
    private var projectDocument: Map<String, Any?> = emptyMap()

    private val _workspaceConfig = MutableStateFlow<WorkspaceConfig?>(null)
    val workspaceConfig: StateFlow<WorkspaceConfig?> = _workspaceConfig.asStateFlow()

    private val _projectConfig = MutableStateFlow<ProjectConfig?>(null)
    val projectConfig: StateFlow<ProjectConfig?> = _projectConfig.asStateFlow()

    private val _workspaceError = MutableStateFlow<String?>(null)
    val workspaceError: StateFlow<String?> = _workspaceError.asStateFlow()

    private val _projectError = MutableStateFlow<String?>(null)
    val projectError: StateFlow<String?> = _projectError.asStateFlow()

    private val _effectiveConfig = MutableStateFlow(computeEffective(null, null))
    val effectiveConfig: StateFlow<EffectiveConfig> = _effectiveConfig.asStateFlow()

    suspend fun bindLocalConfigFiles(
        workspaceRoot: File?,
        projectRoot: File?,
    ) {
        workspaceWatcher?.cancel()
        projectWatcher?.cancel()

        workspaceConfigFile = workspaceRoot?.let { File(it, WORKSPACE_CONFIG_FILE_NAME) }
        projectConfigFile = projectRoot?.let { File(it, projectConfigRelativePath(it.name)) }

        reloadWorkspaceConfig()
        reloadProjectConfig()

        workspaceWatcher = watchFile(workspaceConfigFile) { reloadWorkspaceConfig() }
        projectWatcher = watchFile(projectConfigFile) { reloadProjectConfig() }
    }

    suspend fun ensureConfigFile(scope: ConfigScope): File? {
        val file = when (scope) {
            ConfigScope.Workspace -> workspaceConfigFile
            ConfigScope.Project -> projectConfigFile
        } ?: return null

        withContext(Dispatchers.IO) {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                val initialYaml = when (scope) {
                    ConfigScope.Workspace -> serializeWorkspaceConfig(sampleWorkspaceConfig())
                    ConfigScope.Project -> {
                        val seed = LinkedHashMap<String, Any?>()
                        seed.putAll(projectDocument)
                        if (!seed.containsKey("name")) {
                            // Derive folder name from the config file path: .jcode/{folder_name}.yaml
                            val folderName = file.nameWithoutExtension
                            seed["name"] = folderName
                        }
                        serializeToYaml(seed)
                    }
                }
                file.writeText(initialYaml)
            }
        }
        return file
    }

    suspend fun updateEditorConfig(
        scope: ConfigScope,
        update: (EditorConfig) -> EditorConfig,
    ) {
        when (scope) {
            ConfigScope.Workspace -> {
                val current = _workspaceConfig.value ?: defaultWorkspaceConfig()
                saveWorkspaceConfig(current.copy(editor = normalizeEditorConfig(update(current.editor))))
            }

            ConfigScope.Project -> {
                val current = _projectConfig.value ?: defaultProjectConfig()
                saveProjectConfig(current.copy(editor = normalizeEditorConfig(update(current.editor ?: EditorConfig()))))
            }
        }
    }

    suspend fun updateThemeConfig(
        scope: ConfigScope,
        update: (ThemeConfig) -> ThemeConfig,
    ) {
        when (scope) {
            ConfigScope.Workspace -> {
                val current = _workspaceConfig.value ?: defaultWorkspaceConfig()
                saveWorkspaceConfig(current.copy(theme = update(current.theme)))
            }

            ConfigScope.Project -> {
                val current = _projectConfig.value ?: defaultProjectConfig()
                saveProjectConfig(current.copy(theme = update(current.theme ?: ThemeConfig())))
            }
        }
    }

    suspend fun updateExplorerConfig(
        scope: ConfigScope,
        update: (ExplorerConfig) -> ExplorerConfig,
    ) {
        when (scope) {
            ConfigScope.Workspace -> {
                val current = _workspaceConfig.value ?: defaultWorkspaceConfig()
                saveWorkspaceConfig(current.copy(explorer = update(current.explorer)))
            }

            ConfigScope.Project -> {
                val current = _projectConfig.value ?: defaultProjectConfig()
                saveProjectConfig(current.copy(explorer = update(current.explorer ?: ExplorerConfig())))
            }
        }
    }

    fun serializeWorkspaceConfig(config: WorkspaceConfig): String {
        return serializeToYaml(mergeWorkspaceDocument(config))
    }

    fun serializeProjectConfig(config: ProjectConfig): String {
        return serializeToYaml(mergeProjectDocument(config))
    }

    fun defaultWorkspaceConfig(): WorkspaceConfig = WorkspaceConfig()

    fun defaultProjectConfig(): ProjectConfig = ProjectConfig()

    fun close() {
        workspaceWatcher?.cancel()
        projectWatcher?.cancel()
        scope.cancel()
    }

    private suspend fun saveWorkspaceConfig(config: WorkspaceConfig) {
        val file = ensureConfigFile(ConfigScope.Workspace) ?: return
        val document = mergeWorkspaceDocument(config)
        writeYaml(file, serializeToYaml(document))
        workspaceDocument = document
        _workspaceConfig.value = config
        _workspaceError.value = null
        publishEffective()
    }

    private suspend fun saveProjectConfig(config: ProjectConfig) {
        val file = ensureConfigFile(ConfigScope.Project) ?: return
        val document = mergeProjectDocument(config)
        writeYaml(file, serializeToYaml(document))
        projectDocument = document
        _projectConfig.value = config
        _projectError.value = null
        publishEffective()
    }

    private suspend fun writeYaml(file: File, yaml: String) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        file.writeText(yaml)
    }

    private suspend fun reloadWorkspaceConfig() {
        val file = workspaceConfigFile
        if (file == null || !file.exists()) {
            workspaceDocument = emptyMap()
            _workspaceConfig.value = null
            _workspaceError.value = null
            publishEffective()
            return
        }

        runCatching {
            val yaml = withContext(Dispatchers.IO) { file.readText() }
            val parsed = parseWorkspaceDocument(yaml)
            workspaceDocument = parsed.document
            _workspaceConfig.value = parsed.config
            _workspaceError.value = null
            publishEffective()
        }.onFailure { error ->
            _workspaceError.value = "Workspace config is invalid: ${error.message ?: "Unable to parse YAML"}"
            publishEffective()
        }
    }

    private suspend fun reloadProjectConfig() {
        val file = projectConfigFile
        if (file == null || !file.exists()) {
            projectDocument = emptyMap()
            _projectConfig.value = null
            _projectError.value = null
            publishEffective()
            return
        }

        runCatching {
            val yaml = withContext(Dispatchers.IO) { file.readText() }
            val parsed = parseProjectDocument(yaml)
            projectDocument = parsed.document
            _projectConfig.value = parsed.config
            _projectError.value = null
            publishEffective()
        }.onFailure { error ->
            _projectError.value = "Project config is invalid: ${error.message ?: "Unable to parse YAML"}"
            publishEffective()
        }
    }

    private fun publishEffective() {
        _effectiveConfig.value = computeEffective(_workspaceConfig.value, _projectConfig.value)
    }

    private fun parseWorkspaceDocument(yaml: String): ParsedConfig<WorkspaceConfig> {
        val document = loadDocument(yaml)
        return ParsedConfig(
            config = WorkspaceConfig(
                editor = parseEditorConfig(document.map("editor")),
                files = parseFilesConfig(document.map("files")),
                explorer = parseExplorerConfig(document.map("explorer")),
                search = parseSearchConfig(document.map("search")),
                git = parseGitConfig(document.map("git")),
                terminal = parseTerminalConfig(document.map("terminal")),
                languages = parseLanguages(document.map("languages")),
                extensions = parseExtensionsConfig(document.map("extensions")),
                theme = parseThemeConfig(document.map("theme")),
                distro = parseDistroConfig(document.map("distro")),
            ),
            document = document,
        )
    }

    private fun parseProjectDocument(yaml: String): ParsedConfig<ProjectConfig> {
        val document = loadDocument(yaml)
        return ParsedConfig(
            config = ProjectConfig(
                name = document.string("name"),
                type = document.string("type"),
                template = document.string("template"),
                editor = parseEditorConfig(document.map("editor")).nullIfEmpty(),
                files = parseFilesConfig(document.map("files")).nullIfEmpty(),
                explorer = parseExplorerConfig(document.map("explorer")).nullIfEmpty(),
                search = parseSearchConfig(document.map("search")).nullIfEmpty(),
                git = parseGitConfig(document.map("git")).nullIfEmpty(),
                terminal = parseTerminalConfig(document.map("terminal")).nullIfEmpty(),
                languages = parseLanguages(document.map("languages")),
                extensions = parseExtensionsConfig(document.map("extensions")).nullIfEmpty(),
                theme = parseThemeConfig(document.map("theme")).nullIfEmpty(),
                distro = parseDistroConfig(document.map("distro")).nullIfEmpty(),
            ),
            document = document,
        )
    }

    private fun loadDocument(yaml: String): Map<String, Any?> {
        if (yaml.isBlank()) {
            return emptyMap()
        }
        val loaded = loader.loadFromString(yaml) ?: return emptyMap()
        require(loaded is Map<*, *>) { "Expected a YAML mapping at the document root." }
        return loaded.toStringKeyMap()
    }

    private fun parseEditorConfig(map: Map<String, Any?>): EditorConfig {
        return normalizeEditorConfig(
            EditorConfig(
                fontSize = map.float("fontSize"),
                tabSize = map.int("tabSize"),
                insertSpaces = map.boolean("insertSpaces"),
                wordWrap = map.boolean("wordWrap"),
                minimap = map.boolean("minimap"),
                formatOnSave = map.boolean("formatOnSave"),
                ligatures = map.boolean("ligatures"),
                aggressiveAutocorrectKill = map.boolean("aggressiveAutocorrectKill"),
            )
        )
    }

    private fun parseFilesConfig(map: Map<String, Any?>): FilesConfig = FilesConfig(
        exclude = map.stringList("exclude"),
        watcherExclude = map.stringList("watcherExclude"),
    )

    private fun parseExplorerConfig(map: Map<String, Any?>): ExplorerConfig = ExplorerConfig(
        viewMode = map.string("viewMode"),
    )

    private fun parseSearchConfig(map: Map<String, Any?>): SearchConfig = SearchConfig(
        exclude = map.stringList("exclude"),
    )

    private fun parseGitConfig(map: Map<String, Any?>): GitConfig = GitConfig(
        autoFetch = map.boolean("autoFetch"),
    )

    private fun parseTerminalConfig(map: Map<String, Any?>): TerminalConfig {
        val shell = map.map("shell")
        return TerminalConfig(
            shell = if (shell.isEmpty()) null else ShellConfig(linux = shell.string("linux")),
        )
    }

    private fun parseLanguages(map: Map<String, Any?>): Map<String, LanguageConfig> {
        return map.entries.associateNotNull { (key, value) ->
            val section = (value as? Map<*, *>)?.toStringKeyMap() ?: return@associateNotNull null
            key to LanguageConfig(
                formatter = section.string("formatter"),
                lspId = section.string("lspId"),
            )
        }
    }

    private fun parseExtensionsConfig(map: Map<String, Any?>): ExtensionsConfig = ExtensionsConfig(
        allowed = map.stringListOrNull("allowed"),
    )

    private fun parseThemeConfig(map: Map<String, Any?>): ThemeConfig = ThemeConfig(
        id = map.string("id"),
    )

    private fun parseDistroConfig(map: Map<String, Any?>): DistroConfig = DistroConfig(
        id = map.string("id"),
        bind = map.list("bind").mapNotNull { item ->
            val entry = (item as? Map<*, *>)?.toStringKeyMap() ?: return@mapNotNull null
            val host = entry.string("host") ?: return@mapNotNull null
            val target = entry.string("target") ?: return@mapNotNull null
            BindMount(host = host, target = target)
        },
        user = map.string("user"),
    )

    private fun mergeWorkspaceDocument(config: WorkspaceConfig): Map<String, Any?> {
        val root = LinkedHashMap<String, Any?>()
        root.putAll(workspaceDocument)
        root.mergeSection("editor", config.editor.toYamlMap())
        root.mergeSection("files", config.files.toYamlMap())
        root.mergeSection("explorer", config.explorer.toYamlMap())
        root.mergeSection("search", config.search.toYamlMap())
        root.mergeSection("git", config.git.toYamlMap())
        root.mergeSection("terminal", config.terminal.toYamlMap())
        root.mergeSection("languages", config.languages.toYamlMap())
        root.mergeSection("extensions", config.extensions.toYamlMap())
        root.mergeSection("theme", config.theme.toYamlMap())
        root.mergeSection("distro", config.distro.toYamlMap())
        return root
    }

    private fun mergeProjectDocument(config: ProjectConfig): Map<String, Any?> {
        val root = LinkedHashMap<String, Any?>()
        root.putAll(projectDocument)
        config.name?.let { root["name"] = it }
        config.type?.let { root["type"] = it }
        config.template?.let { root["template"] = it }
        root.mergeSection("editor", config.editor?.toYamlMap().orEmpty())
        root.mergeSection("files", config.files?.toYamlMap().orEmpty())
        root.mergeSection("explorer", config.explorer?.toYamlMap().orEmpty())
        root.mergeSection("search", config.search?.toYamlMap().orEmpty())
        root.mergeSection("git", config.git?.toYamlMap().orEmpty())
        root.mergeSection("terminal", config.terminal?.toYamlMap().orEmpty())
        root.mergeSection("languages", config.languages.toYamlMap())
        root.mergeSection("extensions", config.extensions?.toYamlMap().orEmpty())
        root.mergeSection("theme", config.theme?.toYamlMap().orEmpty())
        root.mergeSection("distro", config.distro?.toYamlMap().orEmpty())
        return root
    }

    private fun sampleWorkspaceConfig(): WorkspaceConfig {
        val effective = effectiveConfig.value.editor
        return WorkspaceConfig(
            editor = EditorConfig(
                fontSize = effective.fontSize,
                tabSize = effective.tabSize,
                ligatures = effective.ligatures,
                wordWrap = effective.wordWrap,
            )
        )
    }

    private fun computeEffective(
        workspace: WorkspaceConfig?,
        project: ProjectConfig?,
    ): EffectiveConfig {
        val defaults = WorkspaceConfig()

        val wsEditor = workspace?.editor
        val prjEditor = project?.editor
        val editor = EffectiveEditorConfig(
            fontSize = prjEditor?.fontSize ?: wsEditor?.fontSize ?: defaults.editor.fontSize ?: 14f,
            tabSize = prjEditor?.tabSize ?: wsEditor?.tabSize ?: defaults.editor.tabSize ?: 4,
            insertSpaces = prjEditor?.insertSpaces ?: wsEditor?.insertSpaces ?: defaults.editor.insertSpaces ?: true,
            wordWrap = prjEditor?.wordWrap ?: wsEditor?.wordWrap ?: defaults.editor.wordWrap ?: false,
            minimap = prjEditor?.minimap ?: wsEditor?.minimap ?: defaults.editor.minimap ?: true,
            formatOnSave = prjEditor?.formatOnSave ?: wsEditor?.formatOnSave ?: defaults.editor.formatOnSave ?: false,
            ligatures = prjEditor?.ligatures ?: wsEditor?.ligatures ?: defaults.editor.ligatures ?: true,
            aggressiveAutocorrectKill = prjEditor?.aggressiveAutocorrectKill
                ?: wsEditor?.aggressiveAutocorrectKill
                ?: defaults.editor.aggressiveAutocorrectKill
                ?: false,
        )

        val wsFiles = workspace?.files
        val prjFiles = project?.files
        val files = EffectiveFilesConfig(
            exclude = prjFiles?.exclude ?: wsFiles?.exclude ?: defaults.files.exclude,
            watcherExclude = prjFiles?.watcherExclude ?: wsFiles?.watcherExclude ?: defaults.files.watcherExclude,
        )

        val wsExplorer = workspace?.explorer
        val prjExplorer = project?.explorer
        val explorer = EffectiveExplorerConfig(
            viewMode = prjExplorer?.viewMode ?: wsExplorer?.viewMode ?: defaults.explorer.viewMode ?: "Tree",
        )

        val wsSearch = workspace?.search
        val prjSearch = project?.search
        val search = EffectiveSearchConfig(
            exclude = prjSearch?.exclude ?: wsSearch?.exclude ?: defaults.search.exclude,
        )

        val wsGit = workspace?.git
        val prjGit = project?.git
        val git = EffectiveGitConfig(
            autoFetch = prjGit?.autoFetch ?: wsGit?.autoFetch ?: defaults.git.autoFetch ?: true,
        )

        val wsTerminal = workspace?.terminal
        val prjTerminal = project?.terminal
        val terminal = EffectiveTerminalConfig(
            shellLinux = prjTerminal?.shell?.linux ?: wsTerminal?.shell?.linux ?: defaults.terminal.shell?.linux,
        )

        val languages = buildMap {
            putAll(workspace?.languages.orEmpty())
            putAll(project?.languages.orEmpty())
        }

        val wsExt = workspace?.extensions
        val prjExt = project?.extensions
        val extensions = EffectiveExtensionsConfig(
            allowed = prjExt?.allowed ?: wsExt?.allowed ?: defaults.extensions.allowed,
        )

        val wsTheme = workspace?.theme
        val prjTheme = project?.theme
        val theme = EffectiveThemeConfig(
            id = prjTheme?.id ?: wsTheme?.id ?: defaults.theme.id ?: "dark",
        )

        val wsDistro = workspace?.distro
        val prjDistro = project?.distro
        val distro = EffectiveDistroConfig(
            id = prjDistro?.id ?: wsDistro?.id ?: defaults.distro.id ?: "ubuntu",
            bind = prjDistro?.bind ?: wsDistro?.bind ?: defaults.distro.bind,
            user = prjDistro?.user ?: wsDistro?.user ?: defaults.distro.user ?: "jcode",
        )

        return EffectiveConfig(
            editor = editor,
            files = files,
            explorer = explorer,
            search = search,
            git = git,
            terminal = terminal,
            languages = languages,
            extensions = extensions,
            theme = theme,
            distro = distro,
        )
    }

    private fun serializeToYaml(document: Map<String, Any?>): String {
        return dumper.dumpToString(document.ifEmpty { linkedMapOf<String, Any?>() })
    }

    private fun watchFile(file: File?, onChange: suspend () -> Unit): Job? {
        val parent = file?.parentFile ?: return null
        val targetName = file.name
        return scope.launch {
            callbackFlow {
                val observer = object : FileObserver(parent, CREATE or DELETE or MOVED_TO or MOVED_FROM or CLOSE_WRITE) {
                    override fun onEvent(event: Int, path: String?) {
                        if (path == targetName) {
                            trySend(Unit)
                        }
                    }
                }
                observer.startWatching()
                awaitClose { observer.stopWatching() }
            }.collectLatest {
                onChange()
            }
        }
    }

    private fun normalizeEditorConfig(config: EditorConfig): EditorConfig {
        return config.copy(
            fontSize = config.fontSize?.coerceIn(8f, 72f),
            tabSize = config.tabSize?.coerceIn(1, 16),
        )
    }
}

private data class ParsedConfig<T>(
    val config: T,
    val document: Map<String, Any?>,
)

private fun Map<*, *>.toStringKeyMap(): Map<String, Any?> {
    val result = LinkedHashMap<String, Any?>()
    for ((key, value) in this) {
        result[key?.toString() ?: continue] = value
    }
    return result
}

private fun Map<String, Any?>.map(key: String): Map<String, Any?> {
    return (this[key] as? Map<*, *>)?.toStringKeyMap().orEmpty()
}

private fun Map<String, Any?>.string(key: String): String? {
    return (this[key] as? String)?.takeIf { it.isNotBlank() }
}

private fun Map<String, Any?>.boolean(key: String): Boolean? {
    return when (val value = this[key]) {
        is Boolean -> value
        is String -> value.toBooleanStrictOrNull()
        else -> null
    }
}

private fun Map<String, Any?>.int(key: String): Int? {
    return when (val value = this[key]) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }
}

private fun Map<String, Any?>.float(key: String): Float? {
    return when (val value = this[key]) {
        is Number -> value.toFloat()
        is String -> value.toFloatOrNull()
        else -> null
    }
}

private fun Map<String, Any?>.list(key: String): List<Any?> {
    return this[key] as? List<Any?> ?: emptyList()
}

private fun Map<String, Any?>.stringList(key: String): List<String> {
    return list(key).mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
}

private fun Map<String, Any?>.stringListOrNull(key: String): List<String>? {
    val values = stringList(key)
    return values.takeIf { it.isNotEmpty() }
}

private fun EditorConfig.nullIfEmpty(): EditorConfig? {
    return if (
        fontSize == null &&
        tabSize == null &&
        insertSpaces == null &&
        wordWrap == null &&
        minimap == null &&
        formatOnSave == null &&
        ligatures == null &&
        aggressiveAutocorrectKill == null
    ) {
        null
    } else {
        this
    }
}

private fun FilesConfig.nullIfEmpty(): FilesConfig? {
    return if (exclude.isEmpty() && watcherExclude.isEmpty()) null else this
}

private fun ExplorerConfig.nullIfEmpty(): ExplorerConfig? {
    return if (viewMode == null) null else this
}

private fun SearchConfig.nullIfEmpty(): SearchConfig? {
    return if (exclude.isEmpty()) null else this
}

private fun GitConfig.nullIfEmpty(): GitConfig? {
    return if (autoFetch == null) null else this
}

private fun TerminalConfig.nullIfEmpty(): TerminalConfig? {
    return if (shell?.linux == null) null else this
}

private fun ExtensionsConfig.nullIfEmpty(): ExtensionsConfig? {
    return if (allowed.isNullOrEmpty()) null else this
}

private fun ThemeConfig.nullIfEmpty(): ThemeConfig? {
    return if (id == null) null else this
}

private fun DistroConfig.nullIfEmpty(): DistroConfig? {
    return if (id == null && bind.isEmpty() && user == null) null else this
}

private fun EditorConfig.toYamlMap(): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
    fontSize?.let { this["fontSize"] = it }
    tabSize?.let { this["tabSize"] = it }
    insertSpaces?.let { this["insertSpaces"] = it }
    wordWrap?.let { this["wordWrap"] = it }
    minimap?.let { this["minimap"] = it }
    formatOnSave?.let { this["formatOnSave"] = it }
    ligatures?.let { this["ligatures"] = it }
    aggressiveAutocorrectKill?.let { this["aggressiveAutocorrectKill"] = it }
}

private fun FilesConfig.toYamlMap(): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
    if (exclude.isNotEmpty()) this["exclude"] = exclude
    if (watcherExclude.isNotEmpty()) this["watcherExclude"] = watcherExclude
}

private fun ExplorerConfig.toYamlMap(): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
    viewMode?.let { this["viewMode"] = it }
}

private fun SearchConfig.toYamlMap(): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
    if (exclude.isNotEmpty()) this["exclude"] = exclude
}

private fun GitConfig.toYamlMap(): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
    autoFetch?.let { this["autoFetch"] = it }
}

private fun TerminalConfig.toYamlMap(): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
    shell?.linux?.let { this["shell"] = linkedMapOf("linux" to it) }
}

private fun Map<String, LanguageConfig>.toYamlMap(): Map<String, Any?> = entries.associateNotNull { (key, value) ->
    val yaml = linkedMapOf<String, Any?>().apply {
        value.formatter?.let { this["formatter"] = it }
        value.lspId?.let { this["lspId"] = it }
    }
    key to yaml.takeIf { it.isNotEmpty() }
}

private fun ExtensionsConfig.toYamlMap(): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
    allowed?.takeIf { it.isNotEmpty() }?.let { this["allowed"] = it }
}

private fun ThemeConfig.toYamlMap(): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
    id?.let { this["id"] = it }
}

private fun DistroConfig.toYamlMap(): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
    id?.let { this["id"] = it }
    if (bind.isNotEmpty()) {
        this["bind"] = bind.map { mount ->
            linkedMapOf(
                "host" to mount.host,
                "target" to mount.target,
            )
        }
    }
    user?.let { this["user"] = it }
}

private fun MutableMap<String, Any?>.mergeSection(
    key: String,
    section: Map<String, Any?>,
) {
    if (section.isEmpty()) {
        remove(key)
    } else {
        this[key] = section
    }
}

private inline fun <K, V, R : Any> Iterable<Map.Entry<K, V>>.associateNotNull(
    transform: (Map.Entry<K, V>) -> Pair<K, R?>?,
): Map<K, R> {
    val destination = LinkedHashMap<K, R>()
    for (entry in this) {
        val mapped = transform(entry) ?: continue
        val value = mapped.second ?: continue
        destination[mapped.first] = value
    }
    return destination
}
