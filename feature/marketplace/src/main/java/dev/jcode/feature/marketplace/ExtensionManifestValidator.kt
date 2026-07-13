package dev.jcode.feature.marketplace

import java.io.File

/** One problem found in an extension's manifest, ranked by [severity]. */
data class ManifestIssue(
    val severity: Severity,
    val message: String,
    /** Optional dotted path into the manifest, e.g. "contributes.runConfigPresets[0].requires". */
    val path: String? = null,
) {
    enum class Severity { Error, Warning, Info }
}

/**
 * Best-effort linter for a sideloaded extension's `extension.yaml`, surfaced in the Extension Dev
 * tools. It reconciles the already-parsed [InstalledExtension] against the raw manifest (to catch
 * typo'd top-level keys the parser silently drops) and checks the fields extension authors most
 * often get wrong. Not a schema validator — it errs toward actionable warnings over completeness.
 */
object ExtensionManifestValidator {

    private val KNOWN_TOP_LEVEL = setOf(
        "id", "name", "publisher", "author", "authors", "type", "version", "description",
        "longDescription", "shortDescription", "samples", "templates", "language", "languages",
        "settings", "api", "requires", "suggests", "contributes", "entry", "images",
        "minJCodeVersion", "targetJCodeVersion", "category", "subcategory",
    )
    private val KNOWN_CAPABILITIES = setOf("api", "exec", "fs", "config", "workbench", "service")

    fun validate(ext: InstalledExtension, hostApiVersion: Int): List<ManifestIssue> {
        val issues = mutableListOf<ManifestIssue>()
        fun err(msg: String, path: String? = null) = issues.add(ManifestIssue(ManifestIssue.Severity.Error, msg, path))
        fun warn(msg: String, path: String? = null) = issues.add(ManifestIssue(ManifestIssue.Severity.Warning, msg, path))
        fun info(msg: String, path: String? = null) = issues.add(ManifestIssue(ManifestIssue.Severity.Info, msg, path))

        // --- identity ---
        if (ext.version.isNullOrBlank()) warn("No `version` — updates and marketplace publishing need one.", "version")
        if (ext.name == ext.id) warn("`name` equals `id` — set a human-readable display name.", "name")
        if (ext.type == ExtensionType.Unknown) {
            warn("Unrecognized `type` — falls back to a generic extension (no type-specific surfaces).", "type")
        }

        // --- unknown top-level keys (typos that silently drop a whole section) ---
        val rawMap = runCatching { parseYamlMapping(File(ext.dir, "extension.yaml").readText()) }.getOrNull()
        rawMap?.keys?.forEach { key ->
            if (key !in KNOWN_TOP_LEVEL) warn("Unknown top-level key `$key` — ignored (typo?).", key)
        }
        // A typo'd entry.ui resolves to webUiEntry=null (the installer only sets it when the file
        // exists), so check the RAW value against disk to actually catch the broken path.
        val rawUi = ((rawMap?.get("entry") as? Map<*, *>)?.get("ui") as? String)?.takeIf { it.isNotBlank() }
        if (rawUi != null && !File(ext.dir, rawUi).isFile) {
            err("`entry.ui` points at `$rawUi` which doesn't exist in the package.", "entry.ui")
        }

        // --- API ---
        if (ext.apiMinVersion > hostApiVersion) {
            err("`api.minApiVersion` ${ext.apiMinVersion} > this JCode's v$hostApiVersion — won't run here.", "api.minApiVersion")
        }
        ext.apiCapabilities.forEach { cap ->
            if (cap !in KNOWN_CAPABILITIES) {
                warn("Unknown API capability `$cap` (known: ${KNOWN_CAPABILITIES.joinToString(", ")}).", "api.capabilities")
            }
        }

        // --- languages ---
        ext.languages.forEachIndexed { i, lang ->
            if (lang.fileExtensions.isEmpty()) {
                warn("Language `${lang.languageId}` declares no file `extensions` — it can't match any file.", "languages[$i].extensions")
            }
        }

        // --- run presets ---
        ext.contributes.runConfigPresets.forEachIndexed { i, preset ->
            val base = "contributes.runConfigPresets[$i]"
            if (preset.requires.isEmpty()) err("Preset `${preset.id}` has no `requires` globs — never offered.", "$base.requires")
            if (preset.terminals.isEmpty()) err("Preset `${preset.id}` has no `terminals`.", "$base.terminals")
            preset.requires.forEach { glob ->
                if (runCatching { globToRegexOrNull(glob) }.getOrNull() == null) {
                    warn("Preset `${preset.id}`: glob `$glob` is invalid.", "$base.requires")
                }
            }
            // {{fileN}}/{{dirN}} beyond the number of required files never resolves. Scan for the
            // {{…}} tokens by hand and match the inner name with a BRACE-FREE regex — a literal
            // brace in the pattern throws PatternSyntaxException on Android's regex engine.
            val maxIndex = preset.requires.size
            val innerRe = Regex("^(?:file|dir)(\\d+)$")
            preset.terminals.forEach { term ->
                val cmd = term.command
                var i = cmd.indexOf("{{")
                while (i >= 0) {
                    val end = cmd.indexOf("}}", i + 2)
                    if (end < 0) break
                    val inner = cmd.substring(i + 2, end)
                    innerRe.matchEntire(inner)?.groupValues?.get(1)?.toIntOrNull()?.let { n ->
                        if (n < 1 || n > maxIndex) {
                            warn("Preset `${preset.id}`: `{{$inner}}` has no matching require (there are $maxIndex).", "$base.terminals")
                        }
                    }
                    i = cmd.indexOf("{{", end + 2)
                }
            }
        }

        // --- deps (informational) ---
        val allDeps = ext.requires.sdks + ext.requires.lsps + ext.requires.dbg
        if (allDeps.isNotEmpty()) info("Requires toolchains: ${allDeps.joinToString(", ")} (installed with the extension).", "requires")

        return issues.sortedBy { it.severity.ordinal }
    }

    // A globstar-aware glob compile that returns null on failure (mirrors ProjectRunner's globToRegex
    // rules: `**`→any, `*`→within-segment, `?`→one char). Used only to flag un-compilable globs.
    private fun globToRegexOrNull(glob: String): Regex? = runCatching {
        val sb = StringBuilder()
        var i = 0
        while (i < glob.length) {
            when {
                glob.startsWith("**", i) -> { sb.append(".*"); i += if (glob.getOrNull(i + 2) == '/') 3 else 2 }
                glob[i] == '*' -> { sb.append("[^/]*"); i++ }
                glob[i] == '?' -> { sb.append("[^/]"); i++ }
                else -> { sb.append(Regex.escape(glob[i].toString())); i++ }
            }
        }
        Regex(sb.toString())
    }.getOrNull()
}
