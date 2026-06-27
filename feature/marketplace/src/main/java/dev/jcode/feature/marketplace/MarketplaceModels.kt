package dev.jcode.feature.marketplace

import java.io.File

/** Kind of extension a marketplace entry / installed package provides. */
enum class ExtensionType {
    Templates,
    Language,
    Unknown;

    companion object {
        fun from(raw: String?): ExtensionType = when (raw?.lowercase()) {
            "templates" -> Templates
            "language" -> Language
            else -> Unknown
        }
    }
}

/** One extension listed in the remote marketplace index (marketplace.yaml). */
data class MarketplaceEntry(
    val id: String,
    val name: String,
    val type: ExtensionType,
    /** The extension's git repo (https .git URL); the installer derives a codeload zip URL from it. */
    val repo: String,
)

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
    /** Best-effort runtime formatter command; `{{file}}` is the guest path. */
    val formatterCommand: String?,
    val indent: Int?,
    val completions: List<CompletionItem>,
    val helpers: List<HelperSnippet>,
)

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
