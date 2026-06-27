package dev.jcode.feature.marketplace

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/**
 * Runtime marketplace client + on-device extension store.
 *
 * Extensions are installed by downloading their GitHub repo as a zip (codeload) and unpacking it
 * under `filesDir/extensions/<id>/`; the app reads each extension's `extension.yaml` from there.
 * The marketplace index lists each extension's repo URL, so the app only needs the index to browse
 * and the per-extension repo to install.
 */
class ExtensionInstaller internal constructor(context: Context) {
    private val appContext = context.applicationContext
    private val installRoot = File(appContext.filesDir, "extensions")

    /** Browse the remote marketplace index. */
    suspend fun fetchIndex(): Result<MarketplaceIndex> = withContext(Dispatchers.IO) {
        runCatching {
            val map = parseYamlMapping(httpGetString(INDEX_URL))
            val entries = map.listOfAny("extensions").mapNotNull { raw ->
                val entry = (raw as? Map<*, *>)?.toStringKeyMap() ?: return@mapNotNull null
                val id = entry.str("id") ?: return@mapNotNull null
                val repo = entry.str("repo") ?: return@mapNotNull null
                MarketplaceEntry(
                    id = id,
                    name = entry.str("name") ?: id,
                    type = ExtensionType.from(entry.str("type")),
                    repo = repo,
                )
            }
            MarketplaceIndex(map.str("name") ?: "JCode Marketplace", map.str("version"), entries)
        }
    }

    /** Download + unpack an extension, replacing any previous install. */
    suspend fun install(entry: MarketplaceEntry): Result<InstalledExtension> = withContext(Dispatchers.IO) {
        runCatching {
            installRoot.mkdirs()
            val dest = File(installRoot, safeDirName(entry.id))
            val tmp = File(installRoot, ".tmp-${safeDirName(entry.id)}")
            tmp.deleteRecursively()
            tmp.mkdirs()
            openStream(codeloadZipUrl(entry.repo)).use { input -> extractZipStrippingTop(input, tmp) }
            dest.deleteRecursively()
            if (!tmp.renameTo(dest)) {
                tmp.copyRecursively(dest, overwrite = true)
                tmp.deleteRecursively()
            }
            loadInstalled(dest) ?: error("Installed extension has no valid extension.yaml")
        }
    }

    fun uninstall(id: String) {
        File(installRoot, safeDirName(id)).deleteRecursively()
    }

    fun isInstalled(id: String): Boolean = File(installRoot, safeDirName(id)).resolve("extension.yaml").isFile

    /** All currently-installed extensions. */
    fun installed(): List<InstalledExtension> =
        installRoot.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".tmp-") }
            ?.mapNotNull { loadInstalled(it) }
            ?.sortedBy { it.name }
            ?: emptyList()

    // --- parsing -----------------------------------------------------------------------------

    private fun loadInstalled(dir: File): InstalledExtension? {
        val manifest = File(dir, "extension.yaml").takeIf { it.isFile } ?: return null
        val map = runCatching { parseYamlMapping(manifest.readText()) }.getOrNull() ?: return null
        val id = map.str("id") ?: return null
        val type = ExtensionType.from(map.str("type"))
        val templates = if (type == ExtensionType.Templates) {
            map.listOfAny("templates").mapNotNull { raw -> loadTemplate(dir, raw?.toString()) }
        } else {
            emptyList()
        }
        val language = if (type == ExtensionType.Language) parseLanguage(map) else null
        return InstalledExtension(
            id = id,
            name = map.str("name") ?: id,
            type = type,
            version = map.str("version"),
            description = map.str("description") ?: "",
            dir = dir,
            templates = templates,
            language = language,
        )
    }

    private fun loadTemplate(extensionDir: File, id: String?): ProjectTemplate? {
        if (id.isNullOrBlank()) return null
        val file = File(extensionDir, "templates/$id/template.yaml").takeIf { it.isFile } ?: return null
        val map = runCatching { parseYamlMapping(file.readText()) }.getOrNull() ?: return null
        val recipe = map.listOfAny("recipe").mapNotNull { raw ->
            val step = (raw as? Map<*, *>)?.toStringKeyMap() ?: return@mapNotNull null
            val run = step.str("run") ?: return@mapNotNull null
            TemplateRecipeStep(
                label = step.str("label") ?: "Run",
                run = run.trim(),
                workdir = step.str("workdir"),
            )
        }
        return ProjectTemplate(
            id = map.str("id") ?: id,
            name = map.str("name") ?: id,
            description = map.str("description") ?: "",
            requires = map.listOfAny("requires").mapNotNull { it?.toString()?.takeIf(String::isNotBlank) },
            recipe = recipe,
        )
    }

    private fun parseLanguage(map: Map<String, Any?>): LanguagePack? {
        val lang = (map["language"] as? Map<*, *>)?.toStringKeyMap() ?: return null
        val comment = (lang["comment"] as? Map<*, *>)?.toStringKeyMap()
        val formatter = (lang["formatter"] as? Map<*, *>)?.toStringKeyMap()
        val completions = lang.listOfAny("completions").mapNotNull { raw ->
            val c = (raw as? Map<*, *>)?.toStringKeyMap() ?: return@mapNotNull null
            val label = c.str("label") ?: return@mapNotNull null
            CompletionItem(label, c.str("detail") ?: "", c.str("insert") ?: label)
        }
        val helpers = lang.listOfAny("helpers").mapNotNull { raw ->
            val h = (raw as? Map<*, *>)?.toStringKeyMap() ?: return@mapNotNull null
            val title = h.str("title") ?: return@mapNotNull null
            HelperSnippet(title, h.str("snippet") ?: "")
        }
        fun wordSet(key: String): Set<String> =
            lang.listOfAny(key).mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }.toSet()
        return LanguagePack(
            languageId = lang.str("id") ?: return null,
            fileExtensions = lang.listOfAny("extensions").mapNotNull { it?.toString()?.takeIf(String::isNotBlank) },
            lineComment = comment?.str("line"),
            blockCommentStart = comment?.str("blockStart"),
            blockCommentEnd = comment?.str("blockEnd"),
            stringDelimiters = lang.listOfAny("strings").mapNotNull { it?.toString()?.takeIf(String::isNotEmpty) },
            keywords = wordSet("keywords"),
            types = wordSet("types"),
            indent = (formatter?.get("indent") as? Number)?.toInt(),
            trimTrailingWhitespace = (formatter?.get("trimTrailingWhitespace") as? Boolean) ?: true,
            insertFinalNewline = (formatter?.get("insertFinalNewline") as? Boolean) ?: true,
            formatterCommand = formatter?.str("command"),
            completions = completions,
            helpers = helpers,
        )
    }

    // --- networking / io ---------------------------------------------------------------------

    private fun httpGetString(url: String): String = openStream(url).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun openStream(url: String): InputStream {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "JCode")
        }
        if (conn.responseCode !in 200..299) {
            val code = conn.responseCode
            conn.disconnect()
            error("HTTP $code for $url")
        }
        return conn.inputStream
    }

    private fun extractZipStrippingTop(input: InputStream, destDir: File) {
        val destPath = destDir.canonicalPath + File.separator
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                // codeload zips nest everything under "<repo>-<ref>/"; drop that leading segment.
                val rel = entry.name.substringAfter('/', "")
                if (rel.isNotBlank()) {
                    val outFile = File(destDir, rel)
                    if (outFile.canonicalPath.startsWith(destPath)) {
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun codeloadZipUrl(repo: String): String {
        val slug = repo.removePrefix("https://github.com/").removeSuffix("/").removeSuffix(".git")
        return "https://codeload.github.com/$slug/zip/refs/heads/main"
    }

    private fun safeDirName(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private companion object {
        const val INDEX_URL =
            "https://raw.githubusercontent.com/janrick123/j-code-marketplace/main/marketplace.yaml"
    }
}

// --- shared YAML helpers --------------------------------------------------------------------

internal fun parseYamlMapping(text: String): Map<String, Any?> {
    val load = Load(LoadSettings.builder().setAllowDuplicateKeys(false).build())
    val loaded = load.loadFromReader(text.reader()) ?: return emptyMap()
    return (loaded as? Map<*, *>)?.toStringKeyMap() ?: emptyMap()
}

internal fun Map<*, *>.toStringKeyMap(): Map<String, Any?> {
    val result = LinkedHashMap<String, Any?>()
    for ((key, value) in this) {
        result[key?.toString() ?: continue] = value
    }
    return result
}

internal fun Map<String, Any?>.str(key: String): String? = when (val value = this[key]) {
    null -> null
    is String -> value.takeIf { it.isNotBlank() }
    else -> value.toString().takeIf { it.isNotBlank() }
}

internal fun Map<String, Any?>.listOfAny(key: String): List<Any?> = this[key] as? List<Any?> ?: emptyList()
