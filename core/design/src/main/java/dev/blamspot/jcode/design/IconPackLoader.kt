package dev.blamspot.jcode.design

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/**
 * Reads an icon pack's index files into a [UiIconSet] / [FileIconSet].
 *
 * An icon pack is an ordinary extension whose payload is art plus an index. Where those indexes live
 * is `IconPackLayout`'s business, in `:feature:marketplace`; this reader is handed a resolved file.
 *
 * A pack may provide several sets of each kind — outlined and filled chrome, colour and monochrome
 * file badges — so [localId] distinguishes them when the index itself declares no `id:`, and every
 * id is qualified by the extension that provided it.
 *
 * Both indexes share a shape — identity, `defaults`, a `base` for where the art lives, `icons`
 * definitions and `aliases` — and differ only in what they map onto: a UI index keys its icons by
 * [JCodeIcon] slot, a file index keys them by an id its own `files`/`folders` rules point at.
 *
 * Nothing here throws: a malformed index yields null and a malformed entry is dropped, because an
 * icon pack is third-party content and a typo in one glyph should cost that glyph, not the app.
 */
object IconPackLoader {

    /**
     * The UI icon set at [index], parsed once and reused.
     *
     * Installed extensions are re-scanned on every install, update and refresh, and each scan asks
     * every icon pack for its sets again. Without this the answer would be re-derived — YAML parse
     * plus a hundred SVGs — for a set that has not changed since the last scan.
     */
    suspend fun uiIconSet(
        index: File,
        providerId: String,
        providerName: String,
        localId: String = index.parentFile?.name.orEmpty(),
    ): UiIconSet? = cached(uiSets, index, providerId) { loadUiIconSet(index, providerId, providerName, localId) }

    /** The file icon set at [index], parsed once and reused. See [uiIconSet]. */
    suspend fun fileIconSet(
        index: File,
        providerId: String,
        providerName: String,
        localId: String = index.parentFile?.name.orEmpty(),
    ): FileIconSet? =
        cached(fileSets, index, providerId) { loadFileIconSet(index, providerId, providerName, localId) }

    /**
     * The id a set is registered and remembered under.
     *
     * Always qualified by the extension that provided it: a pack naming its variants `outlined` and
     * `filled` is the obvious thing to do, so two installed packs would otherwise collide on both,
     * and the stored preference would follow whichever loaded first.
     */
    private fun qualify(providerId: String, declared: String?, localId: String): String {
        val local = declared?.takeIf { it.isNotBlank() }
            ?: localId.takeIf { it.isNotBlank() && it !in CONVENTIONAL_DIRS }
            ?: return providerId
        return "$providerId/$local"
    }

    /**
     * Directory names that mean "the pack's one set of this kind", not a variant. A pack with a
     * single set gets the extension's own id and name; only a pack with variants needs more.
     */
    private val CONVENTIONAL_DIRS = setOf("ui-icons", "files-icons")

    /**
     * Drops every parsed set and every decoded glyph.
     *
     * Used when a pack is uninstalled: its files are about to disappear, and holding decoded art
     * from a directory that no longer exists is both a leak and a way to keep showing an icon set
     * the user has just removed.
     */
    fun evict() {
        synchronized(uiSets) { uiSets.clear() }
        synchronized(fileSets) { fileSets.clear() }
        IconArtLoader.clear()
    }

    /**
     * Load the UI icon set at [index], decoding every slot it fills.
     *
     * A UI set is decoded eagerly: it fills at most one slot per [JCodeIcon] and every one of them is
     * on screen the moment the set is chosen, so paying for it once at selection beats the chrome
     * popping in glyph by glyph.
     */
    private suspend fun loadUiIconSet(
        index: File,
        providerId: String,
        providerName: String,
        localId: String = index.parentFile?.name.orEmpty(),
    ): UiIconSet? =
        withContext(Dispatchers.Default) {
            val root = readMapping(index) ?: return@withContext null
            val base = baseDir(index, root)
            val defaults = IconDefaults.from(root["defaults"], designSize = 24.dp, tinted = true)

            val slots = JCodeIcon.entries.associateBy { it.name.lowercase() }
            val entries = LinkedHashMap<JCodeIcon, IconEntry>()
            for ((key, raw) in (root["icons"] as? Map<*, *>)?.stringKeyed().orEmpty()) {
                val slot = slots[key.lowercase()] ?: continue
                entries[slot] = IconEntry.from(raw, base, defaults) ?: continue
            }
            // Aliases resolve against what the definitions produced, so `Continue: Run` costs no
            // second copy of the art and an alias to nothing is simply absent.
            for ((key, raw) in (root["aliases"] as? Map<*, *>)?.stringKeyed().orEmpty()) {
                val slot = slots[key.lowercase()] ?: continue
                val target = slots[raw?.toString()?.trim()?.lowercase() ?: continue] ?: continue
                entries[target]?.let { entries[slot] = it }
            }
            if (entries.isEmpty()) return@withContext null

            val art = LinkedHashMap<JCodeIcon, IconArt>()
            for ((slot, entry) in entries) {
                val decoded = IconArtLoader.load(entry.file, entry.designSize, entry.autoMirror) ?: continue
                art[slot] = decoded
            }
            if (art.isEmpty()) return@withContext null

            UiIconSet(
                id = qualify(providerId, root.str("id"), localId),
                name = root.str("name") ?: displayName(providerName, localId),
                description = root.str("description").orEmpty(),
                author = root.str("author") ?: "JCode",
                providerId = providerId,
                overrides = art,
                // An icon pack fills what it wants to restyle; the rest stays Material rather than
                // turning into the "unknown slot" circle.
                fallback = defaultUiIconSet,
            )
        }

    /**
     * Load the file icon set at [index].
     *
     * Unlike a UI set this stays lazy: a file pack can define hundreds of icons, of which a given
     * project shows a dozen, so definitions are resolved here and the art itself is decoded on first
     * use through [IconArtLoader].
     */
    private suspend fun loadFileIconSet(
        index: File,
        providerId: String,
        providerName: String,
        localId: String = index.parentFile?.name.orEmpty(),
    ): FileIconSet? =
        withContext(Dispatchers.Default) {
            val root = readMapping(index) ?: return@withContext null
            val base = baseDir(index, root)
            val defaults = IconDefaults.from(root["defaults"], designSize = 16.dp, tinted = false)

            val definitions = LinkedHashMap<String, FileIconDef>()
            for ((id, raw) in (root["icons"] as? Map<*, *>)?.stringKeyed().orEmpty()) {
                val entry = IconEntry.from(raw, base, defaults) ?: continue
                definitions[id] = FileIconDef(
                    id = id,
                    file = entry.file,
                    designSize = entry.designSize,
                    scale = entry.scale,
                    tinted = entry.tinted,
                )
            }
            for ((id, raw) in (root["aliases"] as? Map<*, *>)?.stringKeyed().orEmpty()) {
                val target = raw?.toString()?.trim() ?: continue
                definitions[target]?.let { definitions[id] = it.copy(id = id) }
            }
            if (definitions.isEmpty()) return@withContext null

            val fileRules = ruleTable(root["files"], definitions.keys) { it.icon }
            val folderRules = ruleTable(root["folders"], definitions.keys) { rule ->
                FolderIconIds(rule.icon, rule.openIcon?.takeIf { it in definitions })
            }
            val defaultsMap = (root["defaults"] as? Map<*, *>)?.stringKeyed().orEmpty()

            FileIconSet(
                id = qualify(providerId, root.str("id"), localId),
                name = root.str("name") ?: displayName(providerName, localId),
                description = root.str("description").orEmpty(),
                author = root.str("author") ?: "JCode",
                providerId = providerId,
                definitions = definitions,
                fileRules = fileRules,
                folderRules = folderRules,
                defaultFile = defaultsMap.str("file")?.takeIf { it in definitions },
                defaultFolder = defaultsMap.str("folder")?.takeIf { it in definitions },
                defaultFolderOpen = defaultsMap.str("folderOpen")?.takeIf { it in definitions },
            )
        }

    // --- rules --------------------------------------------------------------------------------

    private class RuleSpec(val icon: String, val openIcon: String?)

    /**
     * What a set is called when its index does not say.
     *
     * A pack with one set borrows the extension's own name; a pack with several has to distinguish
     * them, and the variant's directory is the only thing that does.
     */
    private fun displayName(providerName: String, localId: String): String =
        if (localId.isBlank() || localId in CONVENTIONAL_DIRS) providerName else "$providerName — $localId"

    /**
     * Turns a `files:`/`folders:` list into an [IconRuleTable].
     *
     * Every predicate a rule declares lands in its own bucket, so one rule may contribute a name, a
     * glob and three extensions; the table then answers by specificity rather than by which rule was
     * written first. A rule pointing at an icon the pack never defined is dropped.
     */
    private fun <V> ruleTable(
        raw: Any?,
        known: Set<String>,
        value: (RuleSpec) -> V,
    ): IconRuleTable<V> {
        val names = LinkedHashMap<String, V>()
        val globs = ArrayList<Pair<Regex, V>>()
        val patterns = ArrayList<Pair<Regex, V>>()
        val extensions = LinkedHashMap<String, V>()

        for (item in (raw as? List<*>).orEmpty()) {
            val rule = (item as? Map<*, *>)?.stringKeyed() ?: continue
            val icon = rule.str("icon")?.takeIf { it in known } ?: continue
            val resolved = value(RuleSpec(icon, rule.str("openIcon")))
            for (name in rule.strList("names")) names.putIfAbsent(name.lowercase(), resolved)
            for (glob in rule.strList("globs") + listOfNotNull(rule.str("glob"))) {
                globToRegex(glob)?.let { globs += it to resolved }
            }
            for (pattern in rule.strList("patterns") + listOfNotNull(rule.str("pattern"))) {
                runCatching { Regex(pattern) }.getOrNull()?.let { patterns += it to resolved }
            }
            for (extension in rule.strList("extensions")) {
                extensions.putIfAbsent(extension.trim().removePrefix(".").lowercase(), resolved)
            }
        }
        return IconRuleTable(names, globs, patterns, extensions)
    }

    /**
     * A shell glob as a whole-name regex. Matching is against a bare file name, so `*` and `?` never
     * need to stop at a path separator and `**` carries no extra meaning.
     */
    private fun globToRegex(glob: String): Regex? {
        val trimmed = glob.trim().takeIf { it.isNotEmpty() } ?: return null
        val pattern = buildString {
            for (ch in trimmed) {
                when (ch) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(ch.toString()))
                }
            }
        }
        return runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
    }

    // --- definitions --------------------------------------------------------------------------

    /** Values a definition inherits when it does not state its own. */
    private class IconDefaults(val designSize: Dp, val scale: Float, val tinted: Boolean, val autoMirror: Boolean) {
        companion object {
            fun from(raw: Any?, designSize: Dp, tinted: Boolean): IconDefaults {
                val map = (raw as? Map<*, *>)?.stringKeyed().orEmpty()
                return IconDefaults(
                    designSize = map.dp("size") ?: designSize,
                    scale = map.float("scale") ?: 1f,
                    tinted = map.tint() ?: tinted,
                    autoMirror = map["autoMirror"] == true,
                )
            }
        }
    }

    private class IconEntry(
        val file: File,
        val designSize: Dp,
        val scale: Float,
        val tinted: Boolean,
        val autoMirror: Boolean,
    ) {
        companion object {
            /** `slot: art.svg` or `slot: { file: art.svg, size: 20, tint: theme }`. */
            fun from(raw: Any?, base: File, defaults: IconDefaults): IconEntry? {
                val map = (raw as? Map<*, *>)?.stringKeyed()
                val path = (map?.str("file") ?: (raw as? String))?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return null
                val file = resolveArt(base, path) ?: return null
                return IconEntry(
                    file = file,
                    designSize = map?.dp("size") ?: defaults.designSize,
                    scale = map?.float("scale") ?: defaults.scale,
                    tinted = map?.tint() ?: defaults.tinted,
                    autoMirror = map?.get("autoMirror") as? Boolean ?: defaults.autoMirror,
                )
            }
        }
    }

    /**
     * An art file inside [base]. The path is confined to the pack: a definition cannot climb out of
     * its own directory to name a file elsewhere on the device, and only the decodable formats are
     * accepted so a mistyped path fails here rather than as a blank icon later.
     */
    private fun resolveArt(base: File, path: String): File? {
        val file = File(base, path)
        val canonicalBase = runCatching { base.canonicalPath }.getOrNull() ?: return null
        val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return null
        if (canonical != canonicalBase && !canonical.startsWith(canonicalBase + File.separator)) return null
        if (file.extension.lowercase() !in IconArtLoader.SUPPORTED) return null
        return file.takeIf { it.isFile }
    }

    /**
     * Where the pack's art lives: `base:` relative to the index, defaulting to the index's own
     * directory. That is what lets one reader serve both layouts — an index beside its icons needs
     * no `base:`, and a flat `ui-icons.yaml` at the root sets `base: media/icons`.
     */
    private fun baseDir(index: File, root: Map<String, Any?>): File {
        // `absoluteFile` so a relative index path still has a parent to fall back to.
        val parent = index.parentFile ?: index.absoluteFile.parentFile ?: return index
        val declared = root.str("base")?.trim()?.takeIf { it.isNotEmpty() } ?: return parent
        return File(parent, declared).takeIf { it.isDirectory } ?: parent
    }

    // --- caching ------------------------------------------------------------------------------

    private val uiSets = HashMap<String, Holder<UiIconSet>>()
    private val fileSets = HashMap<String, Holder<FileIconSet>>()

    /** Wrapper so "this index has no usable set in it" is remembered too, not re-derived each scan. */
    private class Holder<T>(val value: T?)

    private suspend fun <T> cached(
        store: HashMap<String, Holder<T>>,
        index: File,
        providerId: String,
        load: suspend () -> T?,
    ): T? {
        val key = stamp(index, providerId)
        synchronized(store) { store[key] }?.let { return it.value }
        val loaded = load()
        synchronized(store) {
            // Keyed by content stamp, so a reinstall lands under a new key; drop the old entries for
            // this index rather than accumulating one per version installed.
            store.keys.removeAll { it.substringBeforeLast('@') == "$providerId|${index.path}" }
            store[key] = Holder(loaded)
        }
        return loaded
    }

    /**
     * A key that changes when the pack does. The index's own modification time covers an edited
     * index; the directory's covers art added or removed beside it, which is what a reinstall does.
     * The provider is part of it because a set's id is qualified by it — the same file read for two
     * extensions is two different sets.
     */
    private fun stamp(index: File, providerId: String): String {
        val dir = index.parentFile?.lastModified() ?: 0L
        return "$providerId|${index.path}@${index.lastModified()}:$dir"
    }

    // --- yaml ---------------------------------------------------------------------------------

    private const val MAX_INDEX_BYTES = 2 * 1024 * 1024

    private fun readMapping(file: File): Map<String, Any?>? {
        if (!file.isFile || file.length() > MAX_INDEX_BYTES) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val loaded = runCatching {
            Load(LoadSettings.builder().setAllowDuplicateKeys(false).build()).loadFromReader(text.reader())
        }.getOrNull()
        return (loaded as? Map<*, *>)?.stringKeyed()
    }

    private fun Map<*, *>.stringKeyed(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for ((key, value) in this) out[key?.toString() ?: continue] = value
        return out
    }

    private fun Map<String, Any?>.str(key: String): String? =
        this[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    private fun Map<String, Any?>.strList(key: String): List<String> =
        (this[key] as? List<*>).orEmpty().mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }

    private fun Map<String, Any?>.float(key: String): Float? = str(key)?.toFloatOrNull()

    private fun Map<String, Any?>.dp(key: String): Dp? =
        float(key)?.takeIf { it > 0f && it <= MAX_DESIGN_SIZE }?.dp

    /** `tint: theme` recolours with the surrounding content colour; `tint: none` leaves the art alone. */
    private fun Map<String, Any?>.tint(): Boolean? = when (str("tint")?.lowercase()) {
        "theme", "content", "true", "on" -> true
        "none", "false", "off", "original" -> false
        else -> null
    }

    private const val MAX_DESIGN_SIZE = 512f
}
