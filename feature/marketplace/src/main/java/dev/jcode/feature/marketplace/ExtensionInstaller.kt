package dev.jcode.feature.marketplace

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/**
 * Runtime marketplace client + on-device extension store.
 *
 * Extensions are installed ONLY from a compiled `.jext` package (a zip; see the JEXT spec). The
 * marketplace index lists each extension's `.jext` path + fingerprint; install downloads it, verifies
 * the package fingerprint (`.jext-manifest.json`) and the `minJCodeVersion` from `extension.jehm`,
 * then unpacks it under `filesDir/extensions/<uniqueName>/`. The app reads each extension's
 * `extension.yaml` from there.
 */
class ExtensionInstaller internal constructor(context: Context) {
    private val appContext = context.applicationContext
    private val installRoot = File(appContext.filesDir, "extensions")

    /** Browse the remote marketplace index. Only entries that ship a `.jext` are installable. */
    suspend fun fetchIndex(): Result<MarketplaceIndex> = withContext(Dispatchers.IO) {
        runCatching {
            val map = parseYamlMapping(httpGetString(INDEX_URL))
            val entries = map.listOfAny("extensions").mapNotNull { raw ->
                val entry = (raw as? Map<*, *>)?.toStringKeyMap() ?: return@mapNotNull null
                val id = entry.str("uniqueName") ?: entry.str("id") ?: return@mapNotNull null
                val jext = entry.str("jext") ?: return@mapNotNull null // .jext-only marketplace
                val fingerprint = (entry["fingerprint"] as? Map<*, *>)?.toStringKeyMap()?.str("value")
                    ?: entry.str("fingerprint")
                // Only the marketplace-published `icon:` path (dist/icons/…) is fetchable; the
                // per-package `images.icon` points inside the .jext and isn't a usable URL.
                val iconUrl = entry.str("icon")?.let { if (it.startsWith("http")) it else BASE_URL + it }
                MarketplaceEntry(
                    id = id,
                    name = entry.str("name") ?: id,
                    author = entry.str("publisher") ?: entry.str("author"),
                    authors = entry.strList("authors"),
                    type = ExtensionType.from(entry.str("type")),
                    category = entry.str("category"),
                    subcategory = entry.str("subcategory"),
                    version = entry.str("version"),
                    jext = jext,
                    fingerprint = fingerprint,
                    minJCodeVersion = entry.str("minJCodeVersion"),
                    targetJCodeVersion = entry.str("targetJCodeVersion"),
                    iconUrl = iconUrl,
                    description = entry.str("shortDescription") ?: entry.str("description"),
                    longDescription = entry.str("longDescription"),
                    samples = parseSamples(entry["samples"]),
                    requires = parseDeps(entry["requires"]),
                    suggests = parseDeps(entry["suggests"]),
                )
            }
            MarketplaceIndex(map.str("name") ?: "JCode Marketplace", map.str("version"), entries)
        }
    }

    /** Download the entry's `.jext`, verify it, and install — replacing any previous copy. */
    suspend fun install(entry: MarketplaceEntry, appVersion: String): Result<InstalledExtension> =
        withContext(Dispatchers.IO) {
            runCatching {
                val jextPath = entry.jext ?: error("${entry.name} has no .jext package")
                requireCompatible(entry.minJCodeVersion, appVersion, entry.name)
                val bytes = openStream(BASE_URL + jextPath).use { it.readBytes() }
                installFromJextBytes(bytes, expectedFingerprint = entry.fingerprint, appVersion = appVersion)
            }
        }

    /** Install from a local `.jext` file (sideload). */
    suspend fun installLocalJext(file: File, appVersion: String): Result<InstalledExtension> =
        withContext(Dispatchers.IO) {
            runCatching { installFromJextBytes(file.readBytes(), expectedFingerprint = null, appVersion = appVersion) }
        }

    /**
     * Install extensions bundled in the APK assets (e.g. `builtin-extensions/foo.jext`) that aren't
     * present yet, or whose bundled version is newer than the installed copy. Best-effort and
     * idempotent — safe to call on every launch; reuses the same verify + extract pipeline as a
     * marketplace install.
     */
    suspend fun ensureBundledExtensionsInstalled(specs: List<BundledExtensionSpec>, appVersion: String) =
        withContext(Dispatchers.IO) {
            for (spec in specs) {
                runCatching {
                    val installedDir = File(installRoot, safeDirName(spec.uniqueName))
                    val needsInstall = !isInstalled(spec.uniqueName) ||
                        (spec.version != null && compareVersions(spec.version, installedVersionOf(installedDir)) > 0)
                    if (needsInstall) {
                        val bytes = appContext.assets.open(spec.assetPath).use { it.readBytes() }
                        installFromJextBytes(bytes, expectedFingerprint = null, appVersion = appVersion)
                    }
                }
            }
        }

    private fun installedVersionOf(dir: File): String {
        val map = runCatching { parseYamlMapping(File(dir, "extension.yaml").readText()) }.getOrNull()
        return map?.str("version") ?: "0.0.0"
    }

    /** Verify a .jext (integrity + compatibility), then unpack it under the install root. */
    private fun installFromJextBytes(
        bytes: ByteArray,
        expectedFingerprint: String?,
        appVersion: String,
    ): InstalledExtension {
        val files = readZipEntries(bytes)
        val manifestText = files[JEXT_MANIFEST]?.toString(Charsets.UTF_8)
            ?: error("not a .jext package (missing $JEXT_MANIFEST)")
        verifyManifest(JSONObject(manifestText), files, expectedFingerprint)

        val jehmText = files[JEHM_FILE]?.toString(Charsets.UTF_8)
            ?: error("not a .jext package (missing $JEHM_FILE)")
        val header = parseJehmHeader(jehmText)
        val uniqueName = header.str("uniqueName") ?: error("$JEHM_FILE missing uniqueName")
        requireCompatible(header.str("minJCodeVersion"), appVersion, header.str("name") ?: uniqueName)

        installRoot.mkdirs()
        val dest = File(installRoot, safeDirName(uniqueName))
        val tmp = File(installRoot, ".tmp-${safeDirName(uniqueName)}")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val tmpPath = tmp.canonicalPath + File.separator
        for ((rel, data) in files) {
            val outFile = File(tmp, rel)
            if (!outFile.canonicalPath.startsWith(tmpPath)) continue // zip-slip guard
            outFile.parentFile?.mkdirs()
            outFile.writeBytes(data)
        }
        dest.deleteRecursively()
        if (!tmp.renameTo(dest)) {
            tmp.copyRecursively(dest, overwrite = true)
            tmp.deleteRecursively()
        }
        return loadInstalled(dest) ?: error("Installed package has no valid extension.yaml")
    }

    private fun requireCompatible(minVersion: String?, appVersion: String, name: String) {
        if (minVersion.isNullOrBlank()) return
        if (compareVersions(appVersion, minVersion) < 0) {
            error("$name requires JCode $minVersion or newer (you have $appVersion)")
        }
    }

    // Verify every listed file's SHA-256 and the order-independent package fingerprint. The fingerprint
    // is recomputed over the manifest's file list IN ORDER, matching how `jext pack` produced it.
    private fun verifyManifest(manifest: JSONObject, files: Map<String, ByteArray>, expectedFingerprint: String?) {
        val arr = manifest.optJSONArray("files") ?: error(".jext manifest has no files[]")
        val pairs = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            o.getString("path") to o.getString("sha256")
        }
        for ((path, expected) in pairs) {
            val data = files[path] ?: error(".jext is missing a listed file: $path")
            if (sha256Hex(data) != expected) error(".jext checksum mismatch for $path")
        }
        val recomputed = sha256Hex(pairs.joinToString("\n") { "${it.first}\t${it.second}" }.toByteArray(Charsets.UTF_8))
        val declared = manifest.optJSONObject("fingerprint")?.optString("value")?.takeIf { it.isNotBlank() }
        if (declared != null && declared != recomputed) error(".jext fingerprint does not match its contents")
        if (!expectedFingerprint.isNullOrBlank() && expectedFingerprint != recomputed) {
            error(".jext fingerprint does not match the marketplace index (possible tampering)")
        }
    }

    // Parse only the YAML frontmatter of an extension.jehm (between the leading and next "---").
    private fun parseJehmHeader(text: String): Map<String, Any?> {
        val t = text.removePrefix("﻿")
        val m = Regex("^---\\r?\\n(.*?)\\r?\\n---", setOf(RegexOption.DOT_MATCHES_ALL)).find(t)
            ?: error("$JEHM_FILE: missing YAML frontmatter")
        return parseYamlMapping(m.groupValues[1])
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
        val languages = if (type == ExtensionType.Language) parseLanguages(map) else emptyList()
        return InstalledExtension(
            id = id,
            name = map.str("name") ?: id,
            author = map.str("publisher") ?: map.str("author"),
            authors = map.strList("authors"),
            type = type,
            version = map.str("version"),
            description = map.str("description") ?: "",
            dir = dir,
            longDescription = map.str("longDescription"),
            samples = parseSamples(map["samples"]),
            templates = templates,
            languages = languages,
            iconFile = findIconFile(dir),
            webUiEntry = findWebUiEntry(dir),
        )
    }

    // The web-frontend HTML entry the .jehm declares (entry.ui), if any. Used by App/DbManager types.
    private fun findWebUiEntry(dir: File): String? =
        File(dir, JEHM_FILE).takeIf { it.isFile }
            ?.let { runCatching { parseJehmHeader(it.readText()) }.getOrNull() }
            ?.let { (it["entry"] as? Map<*, *>)?.toStringKeyMap()?.str("ui") }
            ?.takeIf { it.isNotBlank() && File(dir, it).isFile }

    // The icon path the .jehm declares (images.icon), else a conventional location; null if absent.
    private fun findIconFile(dir: File): File? {
        val declared = File(dir, JEHM_FILE).takeIf { it.isFile }
            ?.let { runCatching { parseJehmHeader(it.readText()) }.getOrNull() }
            ?.let { (it["images"] as? Map<*, *>)?.toStringKeyMap()?.str("icon") }
        return listOfNotNull(declared, "media/icon.png", "icon.png")
            .map { File(dir, it) }
            .firstOrNull { it.isFile }
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

    private fun parseSamples(raw: Any?): List<CodeSample> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val s = (item as? Map<*, *>)?.toStringKeyMap() ?: return@mapNotNull null
            val code = s.str("code") ?: return@mapNotNull null
            CodeSample(
                title = s.str("title") ?: "Sample",
                description = s.str("description"),
                code = code,
                language = s.str("language"),
            )
        }
    }

    private fun parseDeps(raw: Any?): ExtensionDeps {
        val map = (raw as? Map<*, *>)?.toStringKeyMap() ?: return ExtensionDeps.EMPTY
        fun ids(key: String): List<String> =
            map.listOfAny(key).mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
        return ExtensionDeps(sdks = ids("sdks"), lsps = ids("lsps"), extensions = ids("extensions"))
    }

    // A `type: language` extension may declare a single `language:` block (legacy) or a `languages:`
    // array (a pack bundling several languages, e.g. HTML/XML/YAML). Both yield a list of packs.
    private fun parseLanguages(map: Map<String, Any?>): List<LanguagePack> {
        val multi = map.listOfAny("languages").mapNotNull { raw ->
            (raw as? Map<*, *>)?.toStringKeyMap()?.let(::parseOneLanguage)
        }
        if (multi.isNotEmpty()) return multi
        val single = (map["language"] as? Map<*, *>)?.toStringKeyMap()?.let(::parseOneLanguage)
        return listOfNotNull(single)
    }

    private fun parseOneLanguage(lang: Map<String, Any?>): LanguagePack? {
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

    // Read every file entry of a .jext (files live at the zip root — no top-dir stripping).
    private fun readZipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    out[entry.name.replace('\\', '/')] = zip.readBytes()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return out
    }

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun safeDirName(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private companion object {
        const val BASE_URL = "https://raw.githubusercontent.com/blamspotdev/j-code-marketplace/main/"
        const val INDEX_URL = BASE_URL + "marketplace.yaml"
        const val JEHM_FILE = "extension.jehm"
        const val JEXT_MANIFEST = ".jext-manifest.json"
    }
}

/** An extension packaged inside the APK assets, to be installed on first run. */
data class BundledExtensionSpec(
    /** Path under `app/src/main/assets/`, e.g. `builtin-extensions/jcode.lang.markup-1.0.0.jext`. */
    val assetPath: String,
    /** The extension's uniqueName (install id), used to detect whether it's already installed. */
    val uniqueName: String,
    /** Bundled version; when set and newer than the installed copy, the bundle is re-installed. */
    val version: String? = null,
)

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

/** A YAML list coerced to non-blank strings (e.g. `authors: [jcode, alice]`). Empty when absent. */
internal fun Map<String, Any?>.strList(key: String): List<String> =
    listOfAny(key).mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
