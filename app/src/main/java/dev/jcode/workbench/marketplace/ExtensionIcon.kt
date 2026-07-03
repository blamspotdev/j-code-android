package dev.jcode.workbench.marketplace

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jcode.core.resource.LruManagedCache
import dev.jcode.core.resource.ResourceManagerLocator
import dev.jcode.feature.marketplace.ExtensionType
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bounded, memory-pressure-aware cache for decoded extension icons (local files and remote URLs).
 * Registered with [ResourceManagerLocator] on first use so it trims under OS memory pressure —
 * replaces the former unbounded map that pinned every icon ever decoded for the app's lifetime.
 */
private object ExtensionIconCache {
    @Volatile
    private var cache: LruManagedCache<String, ImageBitmap>? = null

    fun of(context: Context): LruManagedCache<String, ImageBitmap> =
        cache ?: synchronized(this) {
            cache ?: LruManagedCache<String, ImageBitmap>("ExtensionIconCache", maxSize = 64).also {
                runCatching { ResourceManagerLocator.resourceManager(context).registerCache(it) }
                cache = it
            }
        }
}

/**
 * An extension's icon: the shipped PNG (local file for installed, remote URL for marketplace)
 * decoded off the main thread, or a type-tinted monogram fallback when none is available.
 */
@Composable
internal fun ExtensionIcon(
    type: ExtensionType,
    name: String,
    modifier: Modifier = Modifier,
    iconFile: File? = null,
    iconUrl: String? = null,
    size: Dp = 36.dp,
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, iconFile, iconUrl) {
        value = loadIcon(context, iconFile, iconUrl)
    }
    val shape = RoundedCornerShape(size / 4)
    val loaded = bitmap
    if (loaded != null) {
        Image(
            bitmap = loaded,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape),
        )
    } else {
        Box(
            modifier = modifier.size(size).clip(shape).background(fallbackColor(type)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = monogram(name),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.42f).sp,
            )
        }
    }
}

private fun monogram(name: String): String {
    val letters = name.trim().split(Regex("\\s+")).mapNotNull { it.firstOrNull()?.uppercaseChar() }
    return when {
        letters.isEmpty() -> "?"
        letters.size == 1 -> letters.first().toString()
        else -> "${letters[0]}${letters[1]}"
    }
}

private fun fallbackColor(type: ExtensionType): Color = when (type) {
    ExtensionType.Language -> Color(0xFF3178C6)
    ExtensionType.Templates -> Color(0xFF14B8A6)
    ExtensionType.Formatter -> Color(0xFFE8912D)
    ExtensionType.App -> Color(0xFF8B5CF6)
    ExtensionType.DbManager -> Color(0xFF0EA5E9)
    ExtensionType.Unknown -> Color(0xFF6B7280)
}

private suspend fun loadIcon(context: Context, iconFile: File?, iconUrl: String?): ImageBitmap? = withContext(Dispatchers.IO) {
    val cache = ExtensionIconCache.of(context)
    if (iconFile != null && iconFile.isFile) {
        val key = "file:${iconFile.absolutePath}:${iconFile.lastModified()}"
        cache.get(key)?.let { return@withContext it }
        runCatching { BitmapFactory.decodeFile(iconFile.absolutePath)?.asImageBitmap() }
            .getOrNull()?.let {
                cache.put(key, it)
                return@withContext it
            }
    }
    if (iconUrl.isNullOrBlank()) return@withContext null
    val urlKey = "url:$iconUrl"
    cache.get(urlKey)?.let { return@withContext it }
    runCatching {
        val conn = (URL(iconUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "JCode")
        }
        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            return@runCatching null
        }
        val bytes = conn.inputStream.use { it.readBytes() }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            ?.also { cache.put(urlKey, it) }
    }.getOrNull()
}
