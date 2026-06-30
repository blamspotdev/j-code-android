package dev.jcode.workbench.marketplace

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jcode.feature.marketplace.ExtensionType
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val remoteIconCache = ConcurrentHashMap<String, ImageBitmap>()

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
    val bitmap by produceState<ImageBitmap?>(initialValue = null, iconFile, iconUrl) {
        value = loadIcon(iconFile, iconUrl)
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

private suspend fun loadIcon(iconFile: File?, iconUrl: String?): ImageBitmap? = withContext(Dispatchers.IO) {
    if (iconFile != null && iconFile.isFile) {
        runCatching { BitmapFactory.decodeFile(iconFile.absolutePath)?.asImageBitmap() }
            .getOrNull()?.let { return@withContext it }
    }
    if (iconUrl.isNullOrBlank()) return@withContext null
    remoteIconCache[iconUrl]?.let { return@withContext it }
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
            ?.also { remoteIconCache[iconUrl] = it }
    }.getOrNull()
}
