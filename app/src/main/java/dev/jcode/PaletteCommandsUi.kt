package dev.jcode

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.gestures.detectTapGestures
import dev.jcode.design.JCodeIcon
import dev.jcode.design.jcIcon
import kotlin.math.roundToInt

/**
 * Session-scoped window modes driven by Command Palette toggles. A process-wide holder (not shell
 * state) because [StatusBarKeyboardController] in the outer shell must coordinate with fullscreen —
 * it re-shows the status bar on IME transitions and would otherwise fight the fullscreen command.
 */
internal object WindowModeState {
    val fullscreen = MutableStateFlow(false)
    val keepAwake = MutableStateFlow(false)
    val orientationLocked = MutableStateFlow(false)
}

/** Applies [WindowModeState] to the hosting Activity window; composed once by the shell. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WindowModeController() {
    val activity = LocalContext.current.findActivity() ?: return
    val fullscreen by WindowModeState.fullscreen.collectAsState()
    val keepAwake by WindowModeState.keepAwake.collectAsState()
    val orientationLocked by WindowModeState.orientationLocked.collectAsState()
    // The IME's own insets animation can re-surface system bars; re-assert fullscreen after it.
    val imeVisible = WindowInsets.isImeVisible

    LaunchedEffect(fullscreen, imeVisible) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    LaunchedEffect(keepAwake) {
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (keepAwake) activity.window.addFlags(flag) else activity.window.clearFlags(flag)
    }
    LaunchedEffect(orientationLocked) {
        // SCREEN_ORIENTATION_LOCKED pins whatever the user actually sees, resolved by the window
        // manager at enforcement time — unlike deriving an axis from Configuration.orientation, which
        // captures the split-screen PANE shape and would flip the app the wrong way on exit.
        activity.requestedOrientation = if (orientationLocked) {
            ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

/** "Go to Line" palette command: accepts `line` or `line:column` (1-based) and reveals it. */
@Composable
internal fun GoToLineDialog(
    lineCount: Int,
    onDismiss: () -> Unit,
    onGo: (line: Int, column: Int) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val parsed = remember(input) { parseLineColumn(input) }

    fun confirm() {
        parsed?.let { (line, col) ->
            onGo(line, col)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to Line") },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("line or line:column (1–$lineCount)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { confirm() }),
                singleLine = true,
                isError = input.isNotBlank() && parsed == null,
            )
        },
        confirmButton = {
            TextButton(onClick = ::confirm, enabled = parsed != null) { Text("Go") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun parseLineColumn(input: String): Pair<Int, Int>? {
    val m = Regex("""^\s*(\d+)\s*(?:[:,]\s*(\d+))?\s*$""").find(input) ?: return null
    val line = m.groupValues[1].toIntOrNull()?.takeIf { it >= 1 } ?: return null
    val col = m.groupValues[2].ifEmpty { "1" }.toIntOrNull()?.takeIf { it >= 1 } ?: return null
    return line to col
}

/**
 * "Color Search" pick surface: a transparent full-screen layer that captures ONE tap, samples the
 * window pixel under it via [PixelCopy] (so WebViews/canvases are included), and reports the color.
 * A hint chip at the top doubles as the cancel affordance.
 */
@Composable
internal fun ColorPickOverlay(
    onPicked: (argb: Int) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    // Back cancels the armed picker instead of falling through to the workbench underneath.
    BackHandler(enabled = true) { onCancel() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(24f)
            .onGloballyPositioned { coords = it }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val layout = coords ?: return@detectTapGestures
                    val inWindow = layout.localToWindow(pos)
                    sampleWindowPixel(context, inWindow) { color ->
                        if (color != null) onPicked(color) else onCancel()
                    }
                }
            },
    ) {
        Surface(
            // Its own tap handler consumes the gesture so tapping the instruction chip doesn't
            // sample the chip's own pixels; the whole chip cancels, matching its Cancel affordance.
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 14.dp)
                .pointerInput(Unit) { detectTapGestures { onCancel() } },
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Tap anywhere to sample a color",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
                Text(
                    text = "Cancel",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.inversePrimary,
                )
            }
        }
    }
}

/** Copy the 1×1 window pixel at [inWindow] off the composited surface (main-thread callback). */
private fun sampleWindowPixel(context: android.content.Context, inWindow: Offset, onResult: (Int?) -> Unit) {
    val window = context.findActivity()?.window ?: return onResult(null)
    val decor = window.decorView
    val x = inWindow.x.roundToInt().coerceIn(0, (decor.width - 1).coerceAtLeast(0))
    val y = inWindow.y.roundToInt().coerceIn(0, (decor.height - 1).coerceAtLeast(0))
    val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    runCatching {
        PixelCopy.request(
            window,
            Rect(x, y, x + 1, y + 1),
            bitmap,
            { result ->
                if (result == PixelCopy.SUCCESS) onResult(bitmap.getPixel(0, 0)) else onResult(null)
            },
            Handler(Looper.getMainLooper()),
        )
    }.onFailure { onResult(null) }
}

/** Sampled-color result: swatch plus copyable HEX / RGB / RGBA rows, with a re-pick shortcut. */
@Composable
internal fun ColorSampleDialog(
    argb: Int,
    onPickAgain: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val a = (argb ushr 24) and 0xFF
    val r = (argb ushr 16) and 0xFF
    val g = (argb ushr 8) and 0xFF
    val b = argb and 0xFF
    val alpha = String.format(java.util.Locale.US, "%.2f", a / 255f).trimEnd('0').trimEnd('.')
    val rows = listOf(
        "HEX" to String.format(java.util.Locale.US, "#%02X%02X%02X", r, g, b),
        "RGB" to "rgb($r, $g, $b)",
        "RGBA" to "rgba($r, $g, $b, ${alpha.ifEmpty { "0" }})",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sampled Color") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(argb)),
                )
                rows.forEach { (label, value) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(text = value, fontFamily = FontFamily.Monospace)
                        }
                        IconButton(onClick = { clipboard.setText(AnnotatedString(value)) }) {
                            Icon(
                                imageVector = jcIcon(JCodeIcon.Copy),
                                contentDescription = "Copy $label",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
        dismissButton = {
            TextButton(onClick = onPickAgain) { Text("Pick again") }
        },
    )
}
