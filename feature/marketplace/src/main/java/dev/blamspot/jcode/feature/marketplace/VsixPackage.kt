package dev.blamspot.jcode.feature.marketplace

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reading a `.vsix` — the format VS Code extensions ship in.
 *
 * A `.vsix` is a plain ZIP holding an `extension.vsixmanifest` and the extension itself under
 * `extension/`, whose `package.json` is the manifest that matters. JCode installs one by
 * translating that manifest into its own `extension.yaml` ([toExtensionYaml]) and unpacking the
 * `extension/` subtree as the install directory, so the extension list, detail page, uninstall and
 * developer tools all work on a VSIX without knowing a second manifest format.
 *
 * JCode implements the slice of the VS Code API that webview-based extensions use. What an
 * extension *declares* is checked here at import time ([VsixCompatibility]) because declarations are
 * reliable; what it *calls* is checked at runtime by the API shim, which throws naming the missing
 * member — a bundled extension's calls go through a renamed import (`vscode_1.window…`), so
 * scanning its code for API use would be guesswork.
 */
object VsixPackage {

    /** Where the extension's own files live inside the archive. */
    const val PAYLOAD_PREFIX = "extension/"
    private const val VSIX_MANIFEST = "extension.vsixmanifest"

    /** The manifest that matters, inside the archive. */
    const val PACKAGE_JSON = PAYLOAD_PREFIX + "package.json"

    /** Marker written into an install directory that came from a `.vsix`. */
    const val VSIX_MARKER = ".jcode-vsix"

    /**
     * Contribution points JCode can surface today. Anything else is reported as unsupported rather
     * than being dropped quietly, so an import says up front what will not work.
     */
    val SUPPORTED_CONTRIBUTES = setOf(
        "commands",
        "configuration",
        "configurationDefaults",
        "menus",
        "submenus",
        "views",
        "viewsContainers",
    )

    /** True when [entries] look like a `.vsix` rather than a `.jext` or some other archive. */
    fun looksLikeVsix(entries: Set<String>): Boolean =
        entries.contains(PACKAGE_JSON) && entries.any { it == VSIX_MANIFEST || it.startsWith(PAYLOAD_PREFIX) }

    /** Where VS Code keeps the strings that `%placeholder%` manifest values refer to. */
    const val NLS_JSON = PAYLOAD_PREFIX + "package.nls.json"

    /**
     * Resolve VS Code's `%key%` manifest placeholders against `package.nls.json`.
     *
     * A localised extension puts `"description": "%extension.description%"` in its manifest and the
     * real text in a separate bundle, so showing the manifest verbatim would put the placeholder on
     * screen. An unresolvable key keeps its own name rather than becoming blank, which at least says
     * what was missing.
     */
    fun localize(value: String, strings: Map<String, String>): String {
        if (!value.startsWith("%") || !value.endsWith("%") || value.length < 3) return value
        val key = value.substring(1, value.length - 1)
        return strings[key] ?: key
    }

    /**
     * Make a command title fit to put on a button even when its string bundle is missing.
     *
     * [localize] leaves an unresolved placeholder as its own key, which is the honest answer but reads
     * badly as a label — `command.newSession.title` where "New Session" belongs. Packages do ship
     * without their bundle (the OpenChamber `.vsix` does), so the key is turned back into the words it
     * was made from. Anything already looking like a sentence is left alone.
     */
    private fun readableTitle(value: String): String {
        if (value.isBlank() || value.contains(' ') || !value.contains('.')) return value
        val parts = value.split('.').filter { it.isNotBlank() }
        val word = parts.lastOrNull { it != "title" && it != "label" } ?: return value
        return word.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercaseChar() }
    }

    /** Read `package.nls.json` into a flat key/value map; empty when the extension ships none. */
    fun parseNls(nlsJson: String?): Map<String, String> {
        if (nlsJson.isNullOrBlank()) return emptyMap()
        val json = runCatching { JSONObject(nlsJson) }.getOrNull() ?: return emptyMap()
        return json.keys().asSequence().mapNotNull { key ->
            // Newer bundles allow { "message": "...", "comment": [...] } per key.
            val direct = json.optString(key).takeIf { it.isNotBlank() }
            val message = json.optJSONObject(key)?.optString("message")?.takeIf { it.isNotBlank() }
            (message ?: direct)?.let { key to it }
        }.toMap()
    }

    /** Parse `extension/package.json`. Throws with a readable reason when the archive is not usable. */
    fun parse(packageJson: String, nlsJson: String? = null): VsixManifest {
        val json = runCatching { JSONObject(packageJson) }
            .getOrElse { error("$PACKAGE_JSON is not valid JSON") }
        val strings = parseNls(nlsJson)
        val publisher = json.optString("publisher").takeIf { it.isNotBlank() }
            ?: error("$PACKAGE_JSON has no \"publisher\"")
        val name = json.optString("name").takeIf { it.isNotBlank() }
            ?: error("$PACKAGE_JSON has no \"name\"")
        return VsixManifest(
            publisher = publisher,
            name = name,
            version = json.optString("version").takeIf { it.isNotBlank() } ?: "0.0.0",
            displayName = json.optString("displayName").takeIf { it.isNotBlank() }
                ?.let { localize(it, strings) } ?: name,
            description = localize(json.optString("description"), strings),
            main = json.optString("main").takeIf { it.isNotBlank() }?.removePrefix("./"),
            icon = json.optString("icon").takeIf { it.isNotBlank() }?.removePrefix("./"),
            engineRange = json.optJSONObject("engines")?.optString("vscode")?.takeIf { it.isNotBlank() },
            activationEvents = json.optJSONArray("activationEvents").toStringList(),
            contributeKeys = json.optJSONObject("contributes")?.keys()?.asSequence()?.toList().orEmpty(),
            settings = parseConfigurationSettings(json.optJSONObject("contributes"), strings),
        )
    }

    /**
     * Translate `contributes.configuration` into JCode [ExtensionSetting]s. VS Code allows the value
     * to be a single object or an array of them, each with a `properties` map keyed by the full
     * dotted setting id (e.g. `openchamber.apiUrl`) — that id is kept verbatim so it round-trips
     * through `getConfiguration(section).get(key)` on the host side.
     */
    private fun parseConfigurationSettings(contributes: JSONObject?, strings: Map<String, String>): List<ExtensionSetting> {
        val raw = contributes?.opt("configuration") ?: return emptyList()
        val blocks = when (raw) {
            is JSONArray -> (0 until raw.length()).mapNotNull { raw.optJSONObject(it) }
            is JSONObject -> listOf(raw)
            else -> emptyList()
        }
        val out = mutableListOf<ExtensionSetting>()
        for (block in blocks) {
            val props = block.optJSONObject("properties") ?: continue
            props.keys().forEach { key ->
                val schema = props.optJSONObject(key) ?: return@forEach
                val options = schema.optJSONArray("enum")
                    ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
                    .orEmpty()
                val type = when {
                    options.isNotEmpty() -> SettingType.Enum
                    else -> when (schema.optString("type")) {
                        "boolean" -> SettingType.Bool
                        "number", "integer" -> SettingType.Int
                        else -> SettingType.Str
                    }
                }
                val default = if (schema.has("default") && !schema.isNull("default")) {
                    schema.get("default").toString()
                } else {
                    null
                }
                val description = schema.optString("markdownDescription")
                    .ifBlank { schema.optString("description") }
                out += ExtensionSetting(
                    key = key,
                    label = readableTitle(key),
                    type = type,
                    default = default,
                    options = options,
                    description = localize(description, strings).takeIf { it.isNotBlank() },
                )
            }
        }
        return out
    }

    /**
     * The name to put on the extension's tab: the title of the container its view lives in.
     *
     * An extension's `displayName` is written for a marketplace listing — "Claude Code for VS Code",
     * "Codex – OpenAI's coding agent" — and putting that on a tab costs more width than the rest of
     * the tab strip together. VS Code never shows it there either: the activity bar is labelled from
     * `contributes.viewsContainers`, which is where an extension puts the short name it wants to be
     * known by. Falling back to a view's own `name` covers an extension that contributes a view to
     * someone else's container; null means it said nothing and the display name is all there is.
     */
    fun parseViewContainerTitle(packageJson: String, nlsJson: String? = null): String? {
        val contributes = runCatching { JSONObject(packageJson) }.getOrNull()?.optJSONObject("contributes")
            ?: return null
        val strings = parseNls(nlsJson)
        val containers = contributes.optJSONObject("viewsContainers")
        // The activity bar first: that is the label VS Code itself shows, so it is the one an
        // extension author wrote for this purpose.
        val groups = containers?.keys()?.asSequence()?.toList().orEmpty().sortedBy { it != "activitybar" }
        for (group in groups) {
            val list = containers?.optJSONArray(group) ?: continue
            for (i in 0 until list.length()) {
                val title = list.optJSONObject(i)?.optString("title").orEmpty()
                if (title.isNotBlank()) return localize(title, strings)
            }
        }
        val views = contributes.optJSONObject("views") ?: return null
        for (container in views.keys()) {
            val list = views.optJSONArray(container) ?: continue
            for (i in 0 until list.length()) {
                val name = list.optJSONObject(i)?.optString("name").orEmpty()
                if (name.isNotBlank()) return localize(name, strings)
            }
        }
        return null
    }

    /**
     * The actions belonging to a view's title bar, in declaration order.
     *
     * VS Code puts a view's own buttons in `contributes.menus["view/title"]`, each naming a command
     * declared in `contributes.commands` and scoped by a `when` clause to the view it belongs to.
     * That is the set worth surfacing: an extension's full command list is mostly editor-context and
     * palette entries that mean nothing next to a panel. An extension that declares no `view/title`
     * group gets no menu here — its commands live in the palette ([parsePaletteCommands]).
     *
     * [viewId] filters by `when`; matching is a substring test rather than a real expression
     * evaluator, which is enough for the `view == some.id` clauses this key is used with and cannot
     * wrongly *include* another view (ids are unique).
     */
    fun parseViewTitleActions(packageJson: String, nlsJson: String?, viewId: String?): List<VsixCommand> {
        val json = runCatching { JSONObject(packageJson) }.getOrNull() ?: return emptyList()
        val contributes = json.optJSONObject("contributes") ?: return emptyList()
        val strings = parseNls(nlsJson)

        val declared = LinkedHashMap<String, VsixCommand>()
        val commands = contributes.optJSONArray("commands")
        for (i in 0 until (commands?.length() ?: 0)) {
            val entry = commands?.optJSONObject(i) ?: continue
            val id = entry.optString("command").takeIf { it.isNotBlank() } ?: continue
            declared[id] = VsixCommand(
                id = id,
                title = readableTitle(localize(entry.optString("title"), strings)).ifBlank { id },
                // `icon` is either a codicon reference or a { light, dark } pair of image paths;
                // only the codicon can map onto a JCode icon, so a path is treated as no icon.
                icon = entry.optString("icon").takeIf { it.isNotBlank() },
            )
        }
        if (declared.isEmpty()) return emptyList()

        val titleEntries = contributes.optJSONObject("menus")?.optJSONArray("view/title")
        val ordered = (0 until (titleEntries?.length() ?: 0)).mapNotNull { i ->
            val entry = titleEntries?.optJSONObject(i) ?: return@mapNotNull null
            val command = declared[entry.optString("command")] ?: return@mapNotNull null
            val whenClause = entry.optString("when")
            if (viewId != null && whenClause.isNotBlank() && !whenClause.contains(viewId)) return@mapNotNull null
            val group = entry.optString("group")
            command to group
        }
            .sortedBy { (_, group) -> group.substringAfter('@', "").toIntOrNull() ?: Int.MAX_VALUE }
            .map { (command, _) -> command }
            .distinctBy { it.id }

        // Empty means empty. Falling back to every declared command turned the view's overflow menu
        // into the extension's whole command list — Claude Code contributes no `view/title` and got
        // all 26, including the pairs that share a title. What an extension does not put on its view
        // belongs in the command palette, which is where [parsePaletteCommands] puts it.
        return ordered
    }

    /**
     * The commands an extension offers the command palette.
     *
     * VS Code's rule, as far as it can be applied without when-clause evaluation: everything under
     * `contributes.commands`, minus what `menus.commandPalette` explicitly withholds with
     * `"when": "false"` — an extension's own way of saying a command is for a keybinding or another
     * menu and not for a person to pick. Anything else is offered; a `when` JCode cannot evaluate is
     * better answered by showing the command than by hiding it.
     *
     * Deduplicated by title, because an extension that registers the same action under two ids
     * (Claude Code's `claude-vscode.` and `claude-code.` pairs) would otherwise put two identical
     * rows in the palette, and neither would say which is which.
     */
    fun parsePaletteCommands(packageJson: String, nlsJson: String?): List<VsixCommand> {
        val json = runCatching { JSONObject(packageJson) }.getOrNull() ?: return emptyList()
        val contributes = json.optJSONObject("contributes") ?: return emptyList()
        val strings = parseNls(nlsJson)

        val paletteEntries = contributes.optJSONObject("menus")?.optJSONArray("commandPalette")
        val withheld = (0 until (paletteEntries?.length() ?: 0)).mapNotNull { i ->
            val entry = paletteEntries?.optJSONObject(i) ?: return@mapNotNull null
            entry.optString("command").takeIf { entry.optString("when").trim() == "false" }
        }.toSet()

        val commands = contributes.optJSONArray("commands")
        return (0 until (commands?.length() ?: 0)).mapNotNull { i ->
            val entry = commands?.optJSONObject(i) ?: return@mapNotNull null
            val id = entry.optString("command").takeIf { it.isNotBlank() && it !in withheld }
                ?: return@mapNotNull null
            val category = localize(entry.optString("category"), strings).trim()
            val title = readableTitle(localize(entry.optString("title"), strings)).ifBlank { id }
            VsixCommand(
                id = id,
                // VS Code shows "Category: Title"; most extensions fold the category into the title
                // instead (Claude Code does), and repeating it would read as "Claude Code: Claude
                // Code: Open".
                title = if (category.isNotBlank() && !title.startsWith(category)) "$category: $title" else title,
                icon = entry.optString("icon").takeIf { it.isNotBlank() },
            )
        }.distinctBy { it.title }
    }

    /** What JCode can and cannot honour in [manifest]. */
    fun compatibilityOf(manifest: VsixManifest): VsixCompatibility {
        val supported = manifest.contributeKeys.filter { it in SUPPORTED_CONTRIBUTES }
        val unsupported = manifest.contributeKeys.filterNot { it in SUPPORTED_CONTRIBUTES }
        val warnings = buildList {
            if (manifest.main == null) {
                add("Declares no \"main\", so there is no extension code to run.")
            }
            if (unsupported.isNotEmpty()) {
                add("JCode does not implement these contribution points: ${unsupported.joinToString(", ")}.")
            }
            if (manifest.contributeKeys.none { it == "views" || it == "viewsContainers" }) {
                add("Contributes no view, so it may have no visible surface in JCode.")
            }
        }
        return VsixCompatibility(
            supportedContributes = supported,
            unsupportedContributes = unsupported,
            warnings = warnings,
        )
    }

    /**
     * The JCode manifest for [manifest]. `entry.ui` is deliberately absent: a VS Code extension has
     * no static HTML entry — its webview HTML is produced by the extension host at runtime — so the
     * view is created through the host bridge rather than by pointing at a file.
     */
    fun toExtensionYaml(manifest: VsixManifest): String = buildString {
        appendLine("# Generated by JCode from a .vsix. Edits here are lost on reinstall.")
        appendLine("id: ${manifest.id.yaml()}")
        appendLine("name: ${manifest.displayName.yaml()}")
        appendLine("publisher: ${manifest.publisher.yaml()}")
        appendLine("version: ${manifest.version.yaml()}")
        appendLine("type: app")
        appendLine("description: ${manifest.description.yaml()}")
        // Declared so JCode can answer the extension host's questions about the editor — which file
        // is open, which project, which theme. Without it those calls are refused and the extension
        // falls back to whatever it last persisted, so it shows the wrong project. It stays a
        // declaration: the user can still revoke it from the extension's permissions.
        appendLine("api:")
        appendLine("  minApiVersion: 1")
        appendLine("  capabilities:")
        appendLine("    - workbench")
        if (manifest.settings.isNotEmpty()) {
            appendLine("settings:")
            manifest.settings.forEach { s ->
                appendLine("  - key: ${s.key.yaml()}")
                appendLine("    label: ${s.label.yaml()}")
                appendLine("    type: ${settingTypeYaml(s.type).yaml()}")
                s.default?.let { appendLine("    default: ${it.yaml()}") }
                s.description?.let { appendLine("    description: ${it.yaml()}") }
                if (s.options.isNotEmpty()) {
                    appendLine("    options:")
                    s.options.forEach { appendLine("      - ${it.yaml()}") }
                }
            }
        }
        if (manifest.icon != null) {
            appendLine("images:")
            appendLine("  icon: ${manifest.icon.yaml()}")
        }
        appendLine("vsix:")
        appendLine("  main: ${(manifest.main ?: "").yaml()}")
        appendLine("  engine: ${(manifest.engineRange ?: "").yaml()}")
        if (manifest.activationEvents.isNotEmpty()) {
            appendLine("  activationEvents:")
            manifest.activationEvents.forEach { appendLine("    - ${it.yaml()}") }
        }
    }

    /** Quote a scalar so any punctuation in it survives the YAML round-trip. */
    private fun String.yaml(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", " ").replace("\r", "") + "\""

    /** The `type:` token the settings parser ([SettingType.from]) reads back. */
    private fun settingTypeYaml(type: SettingType): String = when (type) {
        SettingType.Bool -> "bool"
        SettingType.Enum -> "enum"
        SettingType.Int -> "int"
        SettingType.Autocomplete -> "autocomplete"
        // A VSIX has no way to declare one -- `contributes.configuration` is values only -- so
        // nothing here ever produces it. Named rather than folded into `else` so adding a type
        // to the enum keeps failing here until somebody decides what a VSIX should say.
        SettingType.Action -> "action"
        SettingType.Str -> "str"
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
    }
}

/**
 * A command an extension declares, as offered to the user.
 *
 * [icon] is VS Code's `$(codicon-name)` reference, kept in that raw form because only the presenting
 * layer knows which of its own icons it can map one onto.
 */
data class VsixCommand(
    val id: String,
    val title: String,
    val icon: String?,
) {
    /** The bare codicon name (`add`), or null when the icon is an image path rather than a codicon. */
    val codicon: String? get() = icon?.trim()
        ?.takeIf { it.startsWith("$(") && it.endsWith(")") }
        ?.removeSurrounding("$(", ")")
        ?.takeIf { it.isNotBlank() }
}

/** The parts of a VS Code `package.json` that JCode acts on. */
data class VsixManifest(
    val publisher: String,
    val name: String,
    val version: String,
    val displayName: String,
    val description: String,
    /** Entry module for the extension host, relative to the install directory. */
    val main: String?,
    /** Icon path relative to the install directory. */
    val icon: String?,
    /** The `engines.vscode` range the extension claims, recorded but not enforced. */
    val engineRange: String?,
    val activationEvents: List<String>,
    val contributeKeys: List<String>,
    /** Settings translated from `contributes.configuration`, surfaced on JCode's Extension Settings
     *  page and delivered to the extension through `getConfiguration`. */
    val settings: List<ExtensionSetting> = emptyList(),
) {
    /** VS Code's identity for an extension, and the id JCode installs it under. */
    val id: String get() = "$publisher.$name"
}

/** What JCode can honour in a `.vsix`, reported before it is installed. */
data class VsixCompatibility(
    val supportedContributes: List<String>,
    val unsupportedContributes: List<String>,
    val warnings: List<String>,
) {
    /** True when nothing the extension declares is beyond what JCode implements. */
    val fullySupported: Boolean get() = unsupportedContributes.isEmpty() && warnings.isEmpty()
}
