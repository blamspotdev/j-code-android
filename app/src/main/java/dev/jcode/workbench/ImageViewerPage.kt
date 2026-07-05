package dev.jcode.workbench

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Result of loading an image: the HTML shell to render, plus metadata for the toolbar. */
private data class ImageLoad(
    val html: String?,
    val info: String,
    val error: String? = null,
)

/**
 * Built-in image viewer shown as a full-screen editor page for png/jpg/jpeg/gif/webp/bmp/svg (and
 * animated gif/webp). [source] is a local absolute path or a `content://` URI; the bytes are embedded
 * as a data URI inside a tiny WebView (uniform across formats incl. SVG, pinch-to-zoom, no file-access
 * config). A native toolbar shows the name, pixel dimensions and file size.
 */
@Composable
fun ImageViewerPage(source: String, name: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val load by produceState(initialValue = ImageLoad(null, "Loading…"), source) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = if (source.startsWith("content://")) {
                    context.contentResolver.openInputStream(Uri.parse(source))?.use { it.readBytes() }
                        ?: throw IllegalStateException("cannot open file")
                } else {
                    File(source).readBytes()
                }
                if (bytes.size > MAX_BYTES) {
                    return@runCatching ImageLoad(null, "", "Image is too large to preview (${humanSize(bytes.size.toLong())}).")
                }
                val ext = name.substringAfterLast('.', "").lowercase()
                val mime = mimeFor(ext)
                val dims = if (ext != "svg") decodeDimensions(bytes) else null
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val info = buildString {
                    if (dims != null) append("${dims.first} × ${dims.second}")
                    if (dims != null) append("  ·  ")
                    append(humanSize(bytes.size.toLong()))
                }
                // A data: URI loaded directly as the document (see the WebView factory below).
                ImageLoad("data:$mime;base64,$b64", info)
            }.getOrElse { e -> ImageLoad(null, "", e.message ?: "Failed to load image") }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = load.info,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val html = load.html
            when {
                load.error != null -> Text(
                    load.error!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(24.dp),
                )
                html == null -> Text(
                    load.info,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            setBackgroundColor(0xFF2B2B2B.toInt())
                            // Load the image as the document itself (loadUrl, like the browser page).
                            // loadDataWithBaseURL did not paint reliably in this AndroidView context.
                            loadUrl(html)
                        }
                    },
                )
            }
        }
    }
}

private fun mimeFor(ext: String): String = when (ext) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    "svg" -> "image/svg+xml"
    "ico" -> "image/x-icon"
    else -> "image/*"
}

private fun decodeDimensions(bytes: ByteArray): Pair<Int, Int>? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    return if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
}

private fun humanSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private const val MAX_BYTES = 40 * 1024 * 1024
