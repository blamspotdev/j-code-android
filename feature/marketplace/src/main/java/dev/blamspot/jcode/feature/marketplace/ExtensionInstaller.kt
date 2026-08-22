package dev.blamspot.jcode.feature.marketplace

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.DataInputStream
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

    /**
     * Install from a local `.jext` file (Developer-options sideload). An UNSIGNED package is marked
     * `dev = true` (debuggable in the Extension Dev tools); a SIGNED one installs normally (not
     * debuggable — a signed package is production, per the marketplace signing policy). [signed]
     * reports which it was, so the caller can warn appropriately.
     */
    suspend fun installLocalJext(file: File, appVersion: String): Result<SideloadResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = file.readBytes()
                val signed = JextCrypto.isSignedJext(bytes)
                val ext = installFromJextBytes(bytes, expectedFingerprint = null, appVersion = appVersion, markDev = !signed)
                SideloadResult(ext, signed = signed)
            }
        }

    /** Outcome of a sideload: the installed extension plus whether the package was signed. */
    data class SideloadResult(val extension: InstalledExtension, val signed: Boolean)

    /**
     * Install a sideloaded package, choosing the pipeline from what is actually inside it rather
     * than from the file name — a file picked through SAF often arrives without a usable extension.
     */
    suspend fun installLocalPackage(file: File, appVersion: String): Result<SideloadOutcome> =
        withContext(Dispatchers.IO) {
            runCatching {
                // Only a .jext is read into memory: it is small, and verifying it means hashing its
                // whole byte range anyway. A .vsix can be hundreds of megabytes, so it is recognised
                // from a peek at the file and then installed by streaming.
                if (looksLikeVsixFile(file)) {
                    val result = installFromVsix(file)
                    SideloadOutcome.Vsix(result.extension, result.manifest, result.compatibility)
                } else {
                    val bytes = file.readBytes()
                    val signed = JextCrypto.isSignedJext(bytes)
                    val ext = installFromJextBytes(bytes, null, appVersion, markDev = !signed)
                    SideloadOutcome.Jext(ext, signed)
                }
            }
        }

    /** What a sideload turned out to be. */
    sealed interface SideloadOutcome {
        val extension: InstalledExtension

        data class Jext(override val extension: InstalledExtension, val signed: Boolean) : SideloadOutcome

        data class Vsix(
            override val extension: InstalledExtension,
            val manifest: VsixManifest,
            val compatibility: VsixCompatibility,
        ) : SideloadOutcome
    }

    /**
     * Install a VS Code extension package. The `extension/` subtree becomes the install directory
     * and the VS Code manifest is translated into an `extension.yaml`, so everything downstream —
     * the extension list, detail page, uninstall, developer tools — works without knowing that this
     * one arrived as a `.vsix`.
     *
     * A `.vsix` is unsigned third-party code, so it installs on the same footing as an unsigned
     * sideload: marked dev, and never mistaken for a verified marketplace package.
     */
    suspend fun installLocalVsix(file: File): Result<VsixInstallResult> =
        withContext(Dispatchers.IO) {
            runCatching { installFromVsix(file) }
        }

    /**
     * Install a `.vsix` fetched from an arbitrary absolute [url] — a custom provider's release asset.
     * Same footing as [installLocalVsix]: unsigned third-party code, marked dev. [openStream] already
     * accepts any absolute URL and [installFromVsix] is origin-agnostic, so this just bridges the
     * two; nothing here is tied to the marketplace [BASE_URL].
     */
    suspend fun installVsixFromUrl(url: String): Result<VsixInstallResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                // Staged through a file for the same reason a picked one is: a released .vsix is
                // routinely far larger than the heap it would otherwise have to fit in.
                val staged = File.createTempFile("vsix", ".vsix", appContext.cacheDir)
                try {
                    openStream(url).use { input ->
                        staged.outputStream().use { output -> input.copyTo(output, DOWNLOAD_BUFFER) }
                    }
                    installFromVsix(staged)
                } finally {
                    staged.delete()
                }
            }
        }

    /** Outcome of a `.vsix` install: what landed, and what JCode could not honour in it. */
    data class VsixInstallResult(
        val extension: InstalledExtension,
        val manifest: VsixManifest,
        val compatibility: VsixCompatibility,
    )

    /** Read a `.vsix` without installing it, to show what would happen. */
    suspend fun inspectVsix(file: File): Result<Pair<VsixManifest, VsixCompatibility>> =
        withContext(Dispatchers.IO) {
            runCatching {
                VsixArchive.open(file).use { archive ->
                    val manifest = VsixPackage.parse(archive.packageJson(), archive.nlsJson())
                    manifest to VsixPackage.compatibilityOf(manifest)
                }
            }
        }

    /**
     * True when [file] is a `.vsix`. A file picked through SAF often arrives without a usable name,
     * so this looks inside: first at the signed-`.jext` magic (which is not a zip at all), then for
     * the payload every `.vsix` has.
     */
    private fun looksLikeVsixFile(file: File): Boolean {
        val head = runCatching {
            ByteArray(JEXT_MAGIC_PEEK).also { DataInputStream(file.inputStream()).use { s -> s.readFully(it) } }
        }.getOrNull()
        if (head != null && JextCrypto.isSignedJext(head)) return false
        return runCatching { VsixArchive.open(file).use { VsixPackage.looksLikeVsix(it.names) } }
            .getOrDefault(false)
    }

    private fun installFromVsix(file: File): VsixInstallResult =
        VsixArchive.open(file).use { archive ->
            val manifest = VsixPackage.parse(archive.packageJson(), archive.nlsJson())
            val compatibility = VsixPackage.compatibilityOf(manifest)

            installRoot.mkdirs()
            val dest = File(installRoot, safeDirName(manifest.id))
            val tmp = File(installRoot, ".tmp-${safeDirName(manifest.id)}")
            tmp.deleteRecursively()
            tmp.mkdirs()
            // Only the extension's own subtree is installed; the archive's VS Code packaging metadata
            // is of no use once the manifest has been translated.
            val executables = runCatching {
                archive.extractPayload(tmp) { relative ->
                    // Never let a package supply its own markers — only the host writes those — and
                    // treat the generated manifest, not one in the archive, as the source of truth.
                    relative == DEV_MARKER || relative.endsWith("/$DEV_MARKER") ||
                        relative == VsixPackage.VSIX_MARKER || relative.endsWith("/${VsixPackage.VSIX_MARKER}") ||
                        relative == "extension.yaml"
                }
            }.onFailure {
                // These unpack to hundreds of megabytes, so running out of room part-way through is a
                // real outcome. Clear the debris rather than leave it holding the space a retry needs.
                tmp.deleteRecursively()
            }.getOrThrow()
            File(tmp, "extension.yaml").writeText(VsixPackage.toExtensionYaml(manifest))

            dest.deleteRecursively()
            if (!tmp.renameTo(dest)) {
                tmp.copyRecursively(dest, overwrite = true)
                tmp.deleteRecursively()
            }
            // Re-applied on the installed copy rather than trusted to survive the move: the fallback
            // above is a plain recursive copy, which carries bytes but not permissions, and an
            // extension that ships its own executable is dead the moment that bit is lost.
            executables.forEach { File(dest, it).setExecutable(true, false) }
            // Markers are written after the atomic swap so a package can never smuggle them in.
            runCatching { File(dest, VsixPackage.VSIX_MARKER).writeText(manifest.main.orEmpty()) }
            runCatching { File(dest, DEV_MARKER).writeText("vsix") }

            val installed = loadInstalled(dest) ?: error("could not read the manifest generated for ${manifest.id}")
            VsixInstallResult(installed, manifest, compatibility)
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

    /** Verify a .jext (integrity + compatibility), then unpack it under the install root. When
     *  [markDev] is set, drop a [DEV_MARKER] file so the install is flagged debuggable. */
    private fun installFromJextBytes(
        bytes: ByteArray,
        expectedFingerprint: String?,
        appVersion: String,
        markDev: Boolean = false,
    ): InstalledExtension {
        // Official packages ship signed (Ed25519) + encrypted (AES-256-GCM). Verify + decrypt to the
        // inner plain-.jext ZIP first; a plain (format-1) ZIP is passed through for backward compatibility.
        val zipBytes = if (JextCrypto.isSignedJext(bytes)) JextCrypto.openSignedJext(bytes) else bytes
        val files = readZipEntries(zipBytes)
        val manifestText = files[JEXT_MANIFEST]?.toString(Charsets.UTF_8)
            ?: error("not a .jext package (missing $JEXT_MANIFEST)")
        verifyManifest(JSONObject(manifestText), files, expectedFingerprint)

        // The header (install id + minJCodeVersion) now lives in extension.yaml; fall back to a legacy
        // extension.jehm for packages built before the header merge.
        val yamlMap = files["extension.yaml"]?.toString(Charsets.UTF_8)
            ?.let { runCatching { parseYamlMapping(it) }.getOrNull() }
        val jehmMap = files[JEHM_FILE]?.toString(Charsets.UTF_8)
            ?.let { runCatching { parseJehmHeader(it) }.getOrNull() }
        val uniqueName = yamlMap?.str("id") ?: yamlMap?.str("uniqueName") ?: jehmMap?.str("uniqueName")
            ?: error("package has no extension.yaml id (nor a legacy extension.jehm uniqueName)")
        requireCompatible(
            yamlMap?.str("minJCodeVersion") ?: jehmMap?.str("minJCodeVersion"),
            appVersion,
            yamlMap?.str("name") ?: jehmMap?.str("name") ?: uniqueName,
        )

        installRoot.mkdirs()
        val dest = File(installRoot, safeDirName(uniqueName))
        val tmp = File(installRoot, ".tmp-${safeDirName(uniqueName)}")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val tmpPath = tmp.canonicalPath + File.separator
        for ((rel, data) in files) {
            // Never let a package supply its own dev marker — only the host writes it (below), so a
            // crafted .jext can't self-elevate to a debuggable dev extension.
            if (rel == DEV_MARKER || rel.endsWith("/$DEV_MARKER")) continue
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
        // The dev marker is written AFTER the atomic swap (a re-extract wipes dest, so a re-sideload
        // via the make tool re-marks it each time). Package authors can't smuggle it in — it lives
        // outside the extracted file set.
        if (markDev) runCatching { File(dest, DEV_MARKER).writeText("sideloaded") }
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
        // A "dev pack" may bundle both languages and templates regardless of its primary type;
        // parse whatever it declares (missing sections resolve to empty).
        val templates = map.listOfAny("templates").mapNotNull { raw -> loadTemplate(dir, raw?.toString()) }
        val languages = parseLanguages(map, dir)
        val settings = map.listOfAny("settings").mapNotNull { raw ->
            val s = (raw as? Map<*, *>)?.toStringKeyMap() ?: return@mapNotNull null
            val key = s.str("key") ?: return@mapNotNull null
            ExtensionSetting(
                key = key,
                label = s.str("label") ?: key,
                type = SettingType.from(s.str("type")),
                default = s.str("default"),
                options = s.strList("options"),
                description = s.str("description"),
            )
        }
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
            nativeEntry = nativeEntry(dir),
            nativeClass = nativeHeader(dir).str("class"),
            nativeAbi = (nativeHeader(dir)["abi"] as? Number)?.toInt() ?: 0,
            nativeClaims = parseNativeClaims(nativeHeader(dir)),
            apiMinVersion = (map["api"] as? Map<*, *>)?.toStringKeyMap()?.str("minApiVersion")?.toIntOrNull() ?: 0,
            apiCapabilities = (map["api"] as? Map<*, *>)?.toStringKeyMap()?.strList("capabilities") ?: emptyList(),
            settings = settings,
            requires = parseDeps(map["requires"]),
            suggests = parseDeps(map["suggests"]),
            contributes = parseContributions(map["contributes"], dir),
            dev = File(dir, DEV_MARKER).exists(),
            shortName = vsixTabName(dir),
        )
    }

    /**
     * The tab name a `.vsix` declared for itself, read from the VS Code manifest kept in [dir].
     *
     * Read here rather than baked into the generated `extension.yaml` so an extension imported
     * before this existed gets its short name without being reinstalled — which, for a package
     * measured in hundreds of megabytes, is not a small ask.
     */
    private fun vsixTabName(dir: File): String? {
        val packageJson = File(dir, "package.json").takeIf { it.isFile && File(dir, VsixPackage.VSIX_MARKER).isFile }
            ?: return null
        return runCatching {
            VsixPackage.parseViewContainerTitle(
                packageJson.readText(),
                File(dir, "package.nls.json").takeIf { it.isFile }?.readText(),
            )
        }.getOrNull()
    }

    // The extension header (entry, images, …), read from extension.yaml overlaid on a legacy
    // extension.jehm (yaml wins) so both merged and pre-merge installs resolve correctly.
    private fun headerMap(dir: File): Map<String, Any?> {
        val jehm = File(dir, JEHM_FILE).takeIf { it.isFile }
            ?.let { runCatching { parseJehmHeader(it.readText()) }.getOrNull() } ?: emptyMap()
        val yaml = File(dir, "extension.yaml").takeIf { it.isFile }
            ?.let { runCatching { parseYamlMapping(it.readText()) }.getOrNull() } ?: emptyMap()
        return jehm + yaml
    }

    // The web-frontend HTML entry the header declares (entry.ui), if any. Used by App/DbManager types.
    private fun findWebUiEntry(dir: File): String? =
        (headerMap(dir)["entry"] as? Map<*, *>)?.toStringKeyMap()?.str("ui")
            ?.takeIf { it.isNotBlank() && File(dir, it).isFile }

    // The `entry.native` block, or empty. Read as a map so a package that declares only `entry.ui`
    // costs nothing and cannot half-declare a native entry.
    // `claims:` is a list because one designer can draw more than one kind of file, and the rules
    // for each differ: an Android layout is decided by its directory, a composable by its contents.
    private fun parseNativeClaims(header: Map<String, Any?>): List<NativeClaim> {
        val listed = (header["claims"] as? List<*>).orEmpty().mapNotNull { entry ->
            (entry as? Map<*, *>)?.toStringKeyMap()?.let { claim(it) }
        }
        if (listed.isNotEmpty()) return listed
        return claim(header)?.let { listOf(it) }.orEmpty()
    }

    private fun claim(map: Map<String, Any?>): NativeClaim? {
        val types = (map["fileTypes"] as? List<*>)
            .orEmpty().mapNotNull { it?.toString()?.trim()?.removePrefix(".")?.lowercase() }
            .filter { it.isNotEmpty() }
        val path = map.str("pathContains")?.takeIf { it.isNotBlank() }
        val contains = map.str("contains")?.takeIf { it.isNotBlank() }
        if (types.isEmpty() && path == null && contains == null) return null
        return NativeClaim(
            types,
            path,
            contains,
            map.str("opensInPreview")?.takeIf { it.isNotBlank() },
            map.str("label")?.takeIf { it.isNotBlank() },
            map.str("icon")?.takeIf { it.isNotBlank() },
        )
    }

    private fun nativeHeader(dir: File): Map<String, Any?> =
        ((headerMap(dir)["entry"] as? Map<*, *>)?.toStringKeyMap()?.get("native") as? Map<*, *>)
            ?.toStringKeyMap().orEmpty()

    // The native payload the header declares, if it is actually there. Deliberately NOT checked for
    // signature here: install-time is the wrong place, because an extension may legitimately be
    // sideloaded unsigned for development and only its *native* half is refused. The loader makes
    // that call, where it can say so to the user (see NativeExtensionLoader.resolve).
    private fun nativeEntry(dir: File): String? =
        nativeHeader(dir).str("apk")?.takeIf { it.isNotBlank() && File(dir, it).isFile }

    // The icon path the header declares (images.icon), else a conventional location; null if absent.
    private fun findIconFile(dir: File): File? {
        val declared = (headerMap(dir)["images"] as? Map<*, *>)?.toStringKeyMap()?.str("icon")
        return listOfNotNull(declared, "media/icon.png", "icon.png")
            .map { File(dir, it) }
            .firstOrNull { it.isFile }
    }

    private fun loadTemplate(extensionDir: File, id: String?): ProjectTemplate? {
        if (id.isNullOrBlank()) return null
        val templateDir = File(extensionDir, "templates/$id")
        val file = File(templateDir, "template.yaml").takeIf { it.isFile } ?: return null
        val map = runCatching { parseYamlMapping(file.readText()) }.getOrNull() ?: return null
        val recipe = map.listOfAny("recipe").mapNotNull { raw ->
            val step = (raw as? Map<*, *>)?.toStringKeyMap() ?: return@mapNotNull null
            // A step is a script beside the template, or shell inline. One of the two must be there;
            // a step that is neither is a typo, and running nothing silently would hide it.
            val script = step.str("script")?.takeIf { s -> File(templateDir, s).isFile }
            val run = step.str("run")
            if (script == null && run == null) return@mapNotNull null
            TemplateRecipeStep(
                label = step.str("label") ?: "Run",
                run = run?.trim().orEmpty(),
                script = script,
                workdir = step.str("workdir"),
            )
        }
        val inputs = map.listOfAny("inputs").mapNotNull { raw ->
            val input = (raw as? Map<*, *>)?.toStringKeyMap() ?: return@mapNotNull null
            val inputId = input.str("id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            TemplateInput(
                id = inputId,
                label = input.str("label") ?: inputId,
                type = input.str("type") ?: "select",
                options = input.listOfAny("options").mapNotNull { it?.toString() },
                optionsCommand = input.str("optionsCommand")?.trim().orEmpty(),
                default = input.str("default"),
            )
        }
        return ProjectTemplate(
            dir = templateDir,
            id = map.str("id") ?: id,
            name = map.str("name") ?: id,
            description = map.str("description") ?: "",
            requires = map.listOfAny("requires").mapNotNull { it?.toString()?.takeIf(String::isNotBlank) },
            inputs = inputs,
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
        return ExtensionDeps(
            sdks = ids("sdks"),
            lsps = ids("lsps"),
            dbg = ids("dbg") + ids("debuggers"),
            extensions = ids("extensions"),
        )
    }

    /**
     * One entry of a header list: the thing itself, or the id of a file that holds it.
     *
     * `templates:` has always named ids and loaded `templates/<id>/template.yaml`; this is the same
     * move for the other lists, so `extension.yaml` can be a header — what the extension is and what
     * it brings — while the substance sits in files of its own. An inline map still works, because a
     * two-line language rule does not need a directory.
     */
    private fun entryMap(dir: File, raw: Any?, folder: String, fileName: String): Map<String, Any?>? =
        when (raw) {
            is Map<*, *> -> raw.toStringKeyMap()
            is String -> raw.takeIf { it.isNotBlank() }
                ?.let { id -> File(dir, "$folder/$id/$fileName").takeIf { it.isFile } to id }
                ?.let { (file, id) ->
                    val parsed = file?.let { f -> runCatching { parseYamlMapping(f.readText()) }.getOrNull() }
                    // The id came from the header, so a file that omits it is still identified.
                    parsed?.let { if (it["id"] == null) it + ("id" to id) else it }
                }
            else -> null
        }

    /** A path as one shell word. Extension directories are app-controlled, but a quote in a name
     *  would still break the command it lands in. */
    private fun shellQuote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    /** The directory an id-referenced entry was loaded from, for resolving its scripts. */
    private fun entryDir(dir: File, raw: Any?, folder: String): File? =
        (raw as? String)?.takeIf { it.isNotBlank() }?.let { File(dir, "$folder/$it") }

    /**
     * A shell command, from `<name>Command`/`command` inline or a `.sh` named by `<name>Script`/
     * `script` beside the entry.
     *
     * A script runs as a file rather than as text pulled into a string: the extension directory is
     * bound into the runtime at the same absolute path, so the shell reports a real filename and
     * line when something in it fails.
     */
    private fun commandOf(
        map: Map<String, Any?>,
        owner: File?,
        commandKey: String = "command",
        scriptKey: String = "script",
    ): String {
        val script = map.str(scriptKey)?.takeIf { it.isNotBlank() }
        if (script != null && owner != null) {
            val file = File(owner, script)
            if (file.isFile) return "sh " + shellQuote(file.absolutePath)
        }
        return map.str(commandKey)?.trim().orEmpty()
    }

    /**
     * A run preset's command, with a script step told where it is running.
     *
     * A preset's `{{projectDir}}`/`{{file}}`/`{{dir}}` are filled in at launch, not at parse time —
     * they depend on the project and the open file. A script cannot have them substituted into it
     * and still be the file that runs, so they are handed over as environment variables whose values
     * are the same placeholders: the launcher substitutes them on its way past, exactly as it does
     * for an inline command.
     */
    private fun presetCommand(map: Map<String, Any?>, owner: File?): String {
        val resolved = commandOf(map, owner)
        if (!resolved.startsWith("sh ")) return resolved
        return "JCODE_PROJECT_DIR='{{projectDir}}' JCODE_FILE='{{file}}' JCODE_DIR='{{dir}}' " + resolved
    }

    private fun parseContributions(raw: Any?, dir: File): ExtensionContributions {
        val map = (raw as? Map<*, *>)?.toStringKeyMap() ?: return ExtensionContributions.EMPTY
        fun actions(key: String): List<ContributedAction> =
            map.listOfAny(key).mapNotNull { item ->
                val a = (item as? Map<*, *>)?.toStringKeyMap() ?: return@mapNotNull null
                val id = a.str("id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                ContributedAction(
                    id = id,
                    label = a.str("label") ?: id,
                    icon = a.str("icon"),
                    fileExtensions = a.listOfAny("fileExtensions")
                        .mapNotNull { it?.toString()?.trim()?.removePrefix(".")?.lowercase()?.takeIf(String::isNotBlank) },
                    targets = a.listOfAny("targets")
                        .mapNotNull { it?.toString()?.trim()?.lowercase()?.takeIf { t -> t == "file" || t == "directory" } },
                )
            }
        val runPresets = map.listOfAny("runConfigPresets").mapNotNull { item ->
            val p = entryMap(dir, item, "presets", "preset.yaml") ?: return@mapNotNull null
            val presetDir = entryDir(dir, item, "presets")
            val id = p.str("id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            // `requires` is the list of globs that must ALL be present; accept a single `match` string
            // as shorthand for a one-file preset.
            val requires = (p.strList("requires") + listOfNotNull(p.str("match")?.takeIf(String::isNotBlank)))
                .distinct()
            if (requires.isEmpty()) return@mapNotNull null
            // `terminals: [{label,command}]`; accept a single top-level `command` as a one-terminal
            // shorthand (labelled by `terminalLabel`).
            val terminals = p.listOfAny("terminals").mapNotNull { t ->
                val tm = (t as? Map<*, *>)?.toStringKeyMap() ?: return@mapNotNull null
                val cmd = presetCommand(tm, presetDir).takeIf(String::isNotBlank) ?: return@mapNotNull null
                RunPresetTerminal(label = tm.str("label") ?: "Run", command = cmd)
            }.ifEmpty {
                presetCommand(p, presetDir).takeIf(String::isNotBlank)
                    ?.let { listOf(RunPresetTerminal(p.str("terminalLabel") ?: "Run", it)) }
                    .orEmpty()
            }
            if (terminals.isEmpty()) return@mapNotNull null
            RunConfigPreset(
                id = id,
                label = p.str("label") ?: id,
                requires = requires,
                terminals = terminals,
                // Tolerate YAML ints, quoted ints, and floats (5173.0) alike.
                readyPort = p.str("readyPort")?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() } ?: 0,
                kind = RunPresetKind.from(p.str("kind")),
            )
        }
        val debugEngines = map.listOfAny("debugEngines").mapNotNull { item ->
            val e = entryMap(dir, item, "engines", "engine.yaml") ?: return@mapNotNull null
            val engineDir = entryDir(dir, item, "engines")
            val id = e.str("id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val adapter = commandOf(e, engineDir, "adapterCommand", "adapterScript")
                .takeIf(String::isNotBlank) ?: return@mapNotNull null
            dev.blamspot.jcode.core.distro.DebugEngineEntry(
                id = id,
                category = e.str("category") ?: "Extension",
                name = e.str("name") ?: id,
                description = e.str("description").orEmpty(),
                installCommand = commandOf(e, engineDir, "installCommand", "installScript"),
                verifyCommand = commandOf(e, engineDir, "verifyCommand", "verifyScript"),
                uninstallCommand = commandOf(e, engineDir, "uninstallCommand", "uninstallScript"),
                adapterCommand = adapter,
                transport = e.str("transport")?.takeIf { it == "tcp" } ?: "stdio",
                debugType = e.str("debugType").orEmpty(),
                updateCheckCommand = commandOf(e, engineDir, "updateCheckCommand", "updateCheckScript"),
                languageIds = e.strList("languageIds"),
                extensions = e.listOfAny("extensions")
                    .mapNotNull { it?.toString()?.trim()?.lowercase()?.takeIf(String::isNotBlank) }
                    .map { if (it.startsWith(".")) it else ".$it" },
                requiredSdks = e.strList("requiredSdks"),
            )
        }
        return ExtensionContributions(
            editorStartActions = actions("editorStartActions"),
            drawerActions = actions("drawerActions"),
            editorContextActions = actions("editorContextActions"),
            explorerContextActions = actions("explorerContextActions"),
            explorerDecorations = map["explorerDecorations"] == true || map.str("explorerDecorations") == "true",
            runConfigPresets = runPresets,
            debugEngines = debugEngines,
        )
    }

    // A `type: language` extension may declare a single `language:` block (legacy) or a `languages:`
    // array (a pack bundling several languages, e.g. HTML/XML/YAML). Both yield a list of packs.
    private fun parseLanguages(map: Map<String, Any?>, dir: File): List<LanguagePack> {
        val multi = map.listOfAny("languages").mapNotNull { raw ->
            entryMap(dir, raw, "languages", "language.yaml")?.let(::parseOneLanguage)
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
        const val DEV_MARKER = ".jcode-dev"

        /** Enough of a file's head to tell a signed `.jext` from a plain zip without reading either. */
        const val JEXT_MAGIC_PEEK = 16
        const val DOWNLOAD_BUFFER = 1 shl 16
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
