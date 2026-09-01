package dev.blamspot.jcode.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * One icon a [FileIconSet] can draw, and how the host should draw it.
 *
 * [designSize] is the grid the art was drawn on, not the size it appears at: the host decides that.
 * It matters for a raster, where it is the intrinsic size, and for an SVG with no `viewBox`.
 */
@Immutable
data class FileIconDef(
    val id: String,
    val file: File,
    val designSize: Dp = 16.dp,
    /**
     * Multiplier on the host's icon size. A pack whose art carries less padding than the built-in
     * glyphs can shrink slightly rather than looking oversized next to everything else.
     */
    val scale: Float = 1f,
    /**
     * Whether the host recolours this icon with the surrounding content colour. File type icons are
     * usually multi-colour and want to be left alone, which is why this defaults to off — but a
     * monochrome pack that should follow the theme sets `tint: theme` and gets that instead.
     */
    val tinted: Boolean = false,
)

/**
 * Which icon a file or folder gets, by name.
 *
 * There are no built-in file icon sets: JCode draws files and folders with two [JCodeIcon] slots
 * from the active [UiIconSet], and a set here replaces them wholesale. Sets come from icon-pack
 * extensions — see the loader in `IconPackLoader`.
 */
@Immutable
class FileIconSet(
    val id: String,
    val name: String,
    val description: String,
    val author: String = "JCode",
    /** Id of the extension this set came from. */
    val providerId: String? = null,
    private val definitions: Map<String, FileIconDef>,
    private val fileRules: IconRuleTable<String>,
    private val folderRules: IconRuleTable<FolderIconIds>,
    private val defaultFile: String? = null,
    private val defaultFolder: String? = null,
    private val defaultFolderOpen: String? = null,
) {
    /** How many icons this set defines. Shown in Settings so a set is recognisable before selecting it. */
    val iconCount: Int get() = definitions.size

    /**
     * The icon for [name], or null when this set has nothing for it and the host should fall back to
     * its own glyph.
     *
     * Resolution is memoized: the Explorer asks the same question for the same row on every
     * recomposition and on every frame of a scroll, and the answer only changes when the set does.
     */
    fun resolve(name: String, isDirectory: Boolean, isExpanded: Boolean = false): FileIconDef? {
        val key = when {
            !isDirectory -> "f:$name"
            isExpanded -> "o:$name"
            else -> "d:$name"
        }
        synchronized(resolved) { resolved[key] }?.let { return it.value }
        val def = definitions[iconIdFor(name, isDirectory, isExpanded)]
        synchronized(resolved) { resolved[key] = Memo(def) }
        return def
    }

    private fun iconIdFor(name: String, isDirectory: Boolean, isExpanded: Boolean): String? {
        if (!isDirectory) return fileRules.match(name) ?: defaultFile
        val matched = folderRules.match(name)
        if (isExpanded) {
            return matched?.open ?: matched?.closed ?: defaultFolderOpen ?: defaultFolder
        }
        return matched?.closed ?: defaultFolder
    }

    /** Wrapper so a memoized "this set has nothing for that name" is not re-resolved every frame. */
    private class Memo(val value: FileIconDef?)

    private val resolved = object : LinkedHashMap<String, Memo>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Memo>): Boolean =
            size > MAX_RESOLVED
    }

    private companion object {
        /** Comfortably more than one screenful of rows, so a scroll never evicts what it is showing. */
        const val MAX_RESOLVED = 512
    }
}

/** A folder's pair of icons: the one it shows closed, and the one it shows expanded. */
@Immutable
data class FolderIconIds(val closed: String, val open: String? = null)

/**
 * The name-matching half of a [FileIconSet].
 *
 * Rules are bucketed by how specific they are rather than kept in declaration order, so a pack can
 * list its rules in whatever order reads best and still get the answer everyone expects: an exact
 * name beats a glob, a glob beats a regex, and anything beats a bare extension. Within one bucket
 * the first declaration wins.
 */
@Immutable
class IconRuleTable<V>(
    private val names: Map<String, V> = emptyMap(),
    private val globs: List<Pair<Regex, V>> = emptyList(),
    private val patterns: List<Pair<Regex, V>> = emptyList(),
    private val extensions: Map<String, V> = emptyMap(),
) {
    fun match(name: String): V? {
        val lower = name.lowercase()
        names[lower]?.let { return it }
        globs.firstOrNull { (regex, _) -> regex.matches(lower) }?.let { return it.second }
        patterns.firstOrNull { (regex, _) -> regex.containsMatchIn(name) }?.let { return it.second }
        return extensionMatch(lower)
    }

    /**
     * Longest compound extension first, so `.d.ts` reaches a TypeScript-declaration icon before the
     * plain `.ts` one — and a pack that only defines `ts` still matches.
     */
    private fun extensionMatch(lower: String): V? {
        if (extensions.isEmpty()) return null
        var dot = lower.indexOf('.')
        // A leading dot is part of the name (`.gitignore`), not the start of an extension.
        if (dot == 0) dot = lower.indexOf('.', 1)
        while (dot in 0 until lower.length - 1) {
            extensions[lower.substring(dot + 1)]?.let { return it }
            dot = lower.indexOf('.', dot + 1)
        }
        return null
    }
}

/** The chosen file icon set, or null when files and folders use the [UiIconSet]'s own glyphs. */
val LocalFileIconSet = staticCompositionLocalOf<FileIconSet?> { null }

object FileIconSetRegistry {
    /** The stored id meaning "no file icon set" — JCode's own folder/file glyphs. */
    const val NONE_ID: String = ""

    fun byId(id: String?, installed: List<FileIconSet>): FileIconSet? =
        id?.takeIf { it.isNotBlank() }?.let { wanted -> installed.firstOrNull { it.id == wanted } }
}

/** A file icon that is ready to draw. */
@Immutable
private data class ResolvedFileIcon(val art: IconArt, val tinted: Boolean, val scale: Float)

/**
 * The active file icon set's art for [name], or null when there is none (no set selected, no rule
 * matched, or the art has not finished decoding).
 *
 * Decoding runs off the main thread and its result is cached by [IconArtLoader], so a row costs one
 * asynchronous pass the first time its type is seen and a map lookup afterwards.
 */
@Composable
private fun rememberFileIcon(name: String, isDirectory: Boolean, isExpanded: Boolean = false): ResolvedFileIcon? {
    val set = LocalFileIconSet.current ?: return null
    val def = remember(set, name, isDirectory, isExpanded) {
        set.resolve(name, isDirectory, isExpanded)
    } ?: return null
    // Seed from the cache so an already-decoded icon draws on the first frame rather than flashing
    // the fallback glyph on every scroll.
    val art by produceState(IconArtLoader.peek(def.file), def) {
        if (value == null) value = IconArtLoader.load(def.file, def.designSize)
    }
    return art?.let { ResolvedFileIcon(it, def.tinted, def.scale) }
}

/**
 * The icon for a file or folder row: the active [FileIconSet]'s art where it has some, and the
 * active [UiIconSet]'s [fallback] glyph everywhere else.
 *
 * Every place JCode lists a file goes through here, so turning a pack on changes all of them at once
 * and turning it off puts every one of them back.
 */
@Composable
fun FileTypeIcon(
    name: String,
    isDirectory: Boolean,
    size: Dp,
    modifier: Modifier = Modifier,
    isExpanded: Boolean = false,
    fallback: JCodeIcon = if (isDirectory) JCodeIcon.Folder else JCodeIcon.Output,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = null,
) {
    val resolved = rememberFileIcon(name, isDirectory, isExpanded)
    if (resolved == null) {
        Icon(
            painter = jcIcon(fallback),
            contentDescription = contentDescription,
            tint = tint,
            modifier = modifier.size(size),
        )
        return
    }
    val painter = resolved.art.painter()
    val drawn = modifier.size(size * resolved.scale)
    if (resolved.tinted) {
        Icon(painter = painter, contentDescription = contentDescription, tint = tint, modifier = drawn)
    } else {
        // Not an `Icon`: a multi-colour file badge tinted to the surrounding content colour is a
        // flat silhouette, which is exactly what a file icon pack exists to avoid.
        Image(
            painter = painter,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = drawn,
        )
    }
}
