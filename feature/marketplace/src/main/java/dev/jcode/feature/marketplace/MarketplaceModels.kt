package dev.jcode.feature.marketplace

import java.io.File

/** Kind of extension a marketplace entry / installed package provides. */
enum class ExtensionType {
    Templates,
    Language,
    Formatter,
    Unknown;

    companion object {
        fun from(raw: String?): ExtensionType = when (raw?.lowercase()) {
            "templates" -> Templates
            "language" -> Language
            "formatter" -> Formatter
            else -> Unknown
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

/** One extension listed in the remote marketplace index (marketplace.yaml). */
data class MarketplaceEntry(
    val id: String,
    val name: String,
    val type: ExtensionType,
    val category: String?,
    val subcategory: String?,
    /** Latest published version, used to detect updates against an installed copy. */
    val version: String?,
    /** The extension's git repo (https .git URL); the installer derives a codeload zip URL from it. */
    val repo: String,
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

/** An extension that has been downloaded and unpacked under the app's install root. */
data class InstalledExtension(
    val id: String,
    val name: String,
    val type: ExtensionType,
    val version: String?,
    val description: String,
    val dir: File,
    val templates: List<ProjectTemplate> = emptyList(),
    val language: LanguagePack? = null,
)
