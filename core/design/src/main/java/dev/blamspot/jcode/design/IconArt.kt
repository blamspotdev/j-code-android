package dev.blamspot.jcode.design

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One drawn icon, whichever of the two forms it arrived in.
 *
 * Built-in sets are all [Vector]; a pack on disk may ship either, so everything downstream of a
 * lookup is expressed as a [Painter] rather than an `ImageVector` — that is the one type both forms
 * reach.
 */
@Immutable
sealed interface IconArt {
    @Immutable
    data class Vector(val image: ImageVector) : IconArt

    @Immutable
    data class Raster(val bitmap: ImageBitmap) : IconArt
}

/** This art as something an `Icon` or `Image` can draw. */
@Composable
fun IconArt.painter(): Painter = when (this) {
    is IconArt.Vector -> rememberVectorPainter(image)
    is IconArt.Raster -> remember(bitmap) { BitmapPainter(bitmap) }
}

/**
 * Decodes icon files shipped by an icon pack, memoizing the result.
 *
 * A file icon set is addressed one row at a time while the Explorer scrolls, so decoding is both
 * repeated and latency-sensitive; a bounded LRU keeps the working set warm without pinning every
 * icon a large pack ships (Seti-sized themes run to hundreds) for the life of the process.
 */
object IconArtLoader {

    /** Extensions this loader can decode. Anything else is refused rather than half-read. */
    val SUPPORTED = setOf("svg", "png", "webp", "jpg", "jpeg")

    private const val MAX_CACHED = 256
    private const val MAX_SVG_BYTES = 512 * 1024

    private val cache = object : LinkedHashMap<String, IconArt>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IconArt>): Boolean =
            size > MAX_CACHED
    }

    /**
     * Cache key for [file]. Includes the modification time so an icon edited in place — which is
     * every save while an author is drawing one — is re-read rather than served stale.
     */
    fun keyOf(file: File): String = "${file.path}:${file.lastModified()}"

    /** The already-decoded art for [file], or null. Cheap enough to call during composition. */
    fun peek(file: File): IconArt? = synchronized(cache) { cache[keyOf(file)] }

    /** Decodes [file], off the calling thread. Returns null for a missing or unreadable icon. */
    suspend fun load(file: File, designSize: Dp = 24.dp, autoMirror: Boolean = false): IconArt? {
        val key = keyOf(file)
        synchronized(cache) { cache[key] }?.let { return it }
        val art = withContext(Dispatchers.Default) { decode(file, designSize, autoMirror) } ?: return null
        synchronized(cache) { cache[key] = art }
        return art
    }

    /** Drops every decoded icon. Called when the selected set changes or a pack is uninstalled. */
    fun clear() {
        synchronized(cache) { cache.clear() }
    }

    private fun decode(file: File, designSize: Dp, autoMirror: Boolean): IconArt? {
        if (!file.isFile) return null
        return when (file.extension.lowercase()) {
            "svg" -> {
                // A pack should not be able to stall the UI with a multi-megabyte "icon"; the
                // largest hand-drawn glyph is a few KB and the cap is generous by two orders.
                if (file.length() > MAX_SVG_BYTES) return null
                val text = runCatching { file.readText() }.getOrNull() ?: return null
                SvgImageVector.parse(text, file.nameWithoutExtension, autoMirror, designSize)
                    ?.let { IconArt.Vector(it) }
            }

            in SUPPORTED -> runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
                ?.let { IconArt.Raster(it.asImageBitmap()) }

            else -> null
        }
    }
}
