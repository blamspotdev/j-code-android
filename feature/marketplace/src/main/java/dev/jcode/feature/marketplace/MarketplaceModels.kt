package dev.jcode.feature.marketplace

import java.io.File

/** Kind of extension a marketplace entry / installed package provides. */
enum class ExtensionType {
    Templates,
    Language,
    Formatter,
    /** Ships a web frontend ("Manage" UI), e.g. a runtime/tool manager like the VM Manager. */
    App,
    /** Like [App], but its UI is a database manager surfaced under the "DB Managers" drawer. */
    DbManager,
    /** Like [App], but its UI is a source-control manager surfaced in the left-drawer "SCM" panel. */
    Scm,
    /** Like [App], but its UI is a virtual-machine manager surfaced in the left-drawer "VM" panel. */
    Vm,
    Unknown;

    companion object {
        fun from(raw: String?): ExtensionType = when (raw?.lowercase()) {
            "templates" -> Templates
            "language" -> Language
            "formatter" -> Formatter
            "app", "tool", "runtime" -> App
            "dbmanager", "db-manager", "database" -> DbManager
            "scm", "source-control", "sourcecontrol", "vcs" -> Scm
            "vm", "vmmanager", "vm-manager", "virtualmachine", "virtualization" -> Vm
            else -> Unknown
        }
    }
}

/**
 * When an installed extension's contributions (e.g. a language pack's highlighting, completions, and
 * formatting) are allowed to turn on. [OnDemand] is the default; [Manual] disables the extension.
 */
enum class ExtensionActivation {
    /** Active from launch — always on. */
    AutoStart,
    /** Active when relevant (e.g. a file the extension supports is open). The default. */
    OnDemand,
    /** Disabled — the extension's features stay off until the mode is changed. */
    Manual;

    companion object {
        val Default = OnDemand

        fun from(raw: String?): ExtensionActivation = when (raw?.lowercase()) {
            "autostart", "auto", "auto-start" -> AutoStart
            "manual" -> Manual
            "ondemand", "on-demand" -> OnDemand
            else -> Default
        }
    }
}

/** Things an extension requires or suggests be installed (ids). */
data class ExtensionDeps(
    val sdks: List<String> = emptyList(),
    val lsps: List<String> = emptyList(),
    val extensions: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = sdks.isEmpty() && lsps.isEmpty() && extensions.isEmpty()

    companion object {
        val EMPTY = ExtensionDeps()
    }
}

/** A code/config sample shown on an extension's detail page. */
data class CodeSample(
    val title: String,
    val description: String? = null,
    val code: String,
    val language: String? = null,
)

/** One extension listed in the remote marketplace index (marketplace.yaml). */
data class MarketplaceEntry(
    /** Globally-unique reverse-DNS install id (the .jehm `uniqueName`). */
    val id: String,
    val name: String,
    /** Publisher / author / channel that published this extension (back-compat single author). */
    val author: String? = null,
    /** All authors, ordered; the first is the primary author, the rest are co-authors. Empty = fall back to [author]. */
    val authors: List<String> = emptyList(),
    val type: ExtensionType,
    val category: String?,
    val subcategory: String?,
    /** Latest published version, used to detect updates against an installed copy. */
    val version: String?,
    /** Path to the compiled .jext within the marketplace repo, e.g. "dist/jcode.lang.csharp-0.2.0.jext". */
    val jext: String?,
    /** Expected package fingerprint (sha256) from the index; verified against the downloaded .jext. */
    val fingerprint: String? = null,
    /** Lowest JCode app version that can run this extension. */
    val minJCodeVersion: String? = null,
    /** JCode version this extension was built/tested against. */
    val targetJCodeVersion: String? = null,
    /** Absolute URL of the marketplace-published icon, shown before install. Null if none. */
    val iconUrl: String? = null,
    /** One-line summary shown in the compact row. */
    val description: String? = null,
    /** Full description shown on the detail page. */
    val longDescription: String? = null,
    /** Usage samples shown on the detail page. */
    val samples: List<CodeSample> = emptyList(),
    val requires: ExtensionDeps = ExtensionDeps.EMPTY,
    val suggests: ExtensionDeps = ExtensionDeps.EMPTY,
)

/** Compare dotted versions ("0.2.0" vs "0.1.3"); missing parts count as 0. */
fun compareVersions(a: String, b: String): Int {
    val pa = a.trim().split('.')
    val pb = b.trim().split('.')
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val na = pa.getOrNull(i)?.toIntOrNull() ?: 0
        val nb = pb.getOrNull(i)?.toIntOrNull() ?: 0
        if (na != nb) return na - nb
    }
    return 0
}

/** True when [latest] is a strictly newer version than [installed]. */
fun isUpdateAvailable(latest: String?, installed: String?): Boolean {
    if (latest.isNullOrBlank()) return false
    if (installed.isNullOrBlank()) return false
    return compareVersions(latest, installed) > 0
}

/** The remote marketplace index. */
data class MarketplaceIndex(
    val name: String,
    val version: String?,
    val entries: List<MarketplaceEntry>,
)

/** A coding suggestion offered by a language pack. `$1`/`$0` mark tab stops. */
data class CompletionItem(
    val label: String,
    val detail: String,
    val insert: String,
)

/** A named helper snippet. */
data class HelperSnippet(
    val title: String,
    val snippet: String,
)

/** The editor support a `type: language` extension provides. */
data class LanguagePack(
    val languageId: String,
    val fileExtensions: List<String>,
    // Syntax (for highlighting):
    val lineComment: String?,
    val blockCommentStart: String?,
    val blockCommentEnd: String?,
    val stringDelimiters: List<String>,
    val keywords: Set<String>,
    val types: Set<String>,
    // Formatting (the "basic formatting definitions" the built-in formatter consumes):
    val indent: Int?,
    val trimTrailingWhitespace: Boolean,
    val insertFinalNewline: Boolean,
    /** Best-effort external formatter command; `{{file}}` is the guest path. */
    val formatterCommand: String?,
    val completions: List<CompletionItem>,
    val helpers: List<HelperSnippet>,
) {
    fun matchesFile(name: String): Boolean {
        val lower = name.lowercase()
        return fileExtensions.any { lower.endsWith(it.lowercase()) }
    }
}

/** The control type for a user-configurable extension setting (from the manifest `settings:` block). */
enum class SettingType {
    /** On/off switch. */
    Bool,
    /** One of a fixed set of [ExtensionSetting.options]. */
    Enum,
    /** Free-form integer. */
    Int,
    /** Free-form text. The default when a type is missing or unrecognized. */
    Str;

    companion object {
        fun from(raw: String?): SettingType = when (raw?.lowercase()) {
            "bool", "boolean", "toggle" -> Bool
            "enum", "select", "choice" -> Enum
            "int", "integer", "number" -> Int
            else -> Str
        }
    }
}

/** One user-configurable option an extension declares in its manifest `settings:` block. */
data class ExtensionSetting(
    val key: String,
    val label: String,
    val type: SettingType,
    /** Default value (as a string); null when the manifest omits it. */
    val default: String? = null,
    /** Allowed values for [SettingType.Enum]. */
    val options: List<String> = emptyList(),
    val description: String? = null,
)

/** An extension that has been downloaded and unpacked under the app's install root. */
data class InstalledExtension(
    val id: String,
    val name: String,
    /** Publisher / author / channel that published this extension (back-compat single author). */
    val author: String? = null,
    /** All authors, ordered; the first is the primary author, the rest are co-authors. Empty = fall back to [author]. */
    val authors: List<String> = emptyList(),
    val type: ExtensionType,
    val version: String?,
    val description: String,
    val dir: File,
    val longDescription: String? = null,
    val samples: List<CodeSample> = emptyList(),
    val templates: List<ProjectTemplate> = emptyList(),
    /** Language packs this extension provides. A pack may bundle several (e.g. a markup pack). */
    val languages: List<LanguagePack> = emptyList(),
    /** The extension's icon file inside [dir], if it shipped one. */
    val iconFile: File? = null,
    /** Relative path (from [dir]) to the extension's web-frontend HTML entry, e.g. "www/index.html". */
    val webUiEntry: String? = null,
    /** Lowest JCode extension-API version this extension needs (0 = legacy exec-only bridge). */
    val apiMinVersion: Int = 0,
    /** Capability families this extension declares it uses (e.g. "exec", "fs", "workbench"). */
    val apiCapabilities: List<String> = emptyList(),
    /** User-configurable settings this extension declares (surfaced generically in app Settings). */
    val settings: List<ExtensionSetting> = emptyList(),
    /** Toolchains/extensions this extension requires (installed with it) or suggests. */
    val requires: ExtensionDeps = ExtensionDeps.EMPTY,
    val suggests: ExtensionDeps = ExtensionDeps.EMPTY,
)

/** The first bundled language that claims [fileName] (by file extension), or null. */
fun InstalledExtension.languageFor(fileName: String): LanguagePack? =
    languages.firstOrNull { it.matchesFile(fileName) }

/** True if this extension ships a web-frontend ("Manage" / DB-manager) UI that resolves on disk. */
val InstalledExtension.hasWebUi: Boolean get() = webUiFile != null

/** The extension's web-frontend HTML entry file inside [dir], or null if it doesn't ship one. */
val InstalledExtension.webUiFile: File?
    get() = webUiEntry?.let { File(dir, it) }?.takeIf { it.isFile }

/** The primary author: the first of [authors], or the legacy single [author], or "unknown". */
val MarketplaceEntry.primaryAuthor: String get() = authors.firstOrNull() ?: author ?: "unknown"

/** Co-authors beyond the primary one (empty for single-author / legacy extensions). */
val MarketplaceEntry.otherAuthors: List<String> get() = authors.drop(1)

/** The primary author: the first of [authors], or the legacy single [author], or "unknown". */
val InstalledExtension.primaryAuthor: String get() = authors.firstOrNull() ?: author ?: "unknown"

/** Co-authors beyond the primary one (empty for single-author / legacy extensions). */
val InstalledExtension.otherAuthors: List<String> get() = authors.drop(1)
