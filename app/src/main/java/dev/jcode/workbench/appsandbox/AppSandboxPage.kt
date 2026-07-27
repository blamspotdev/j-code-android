package dev.jcode.workbench.appsandbox

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.util.Size
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jcode.design.CompactFilledButton
import dev.jcode.design.CompactOutlinedButton
import dev.jcode.design.LocalAndroidDevice
import dev.jcode.design.ManagerNoticeCard
import dev.jcode.design.ManagerSectionCard
import dev.jcode.design.ManagerSummaryRow
import dev.jcode.design.SettingsTextFieldRow
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Editor tab that runs one of the user's own Android apps inside JCode: the app is started on a
 * virtual display owned by the on-device display server (shell uid, over adb), streamed here as
 * H.264 and driven by touches and keys sent back on the same connection.
 *
 * The session itself lives in [AppSandboxSessions], not in this composition — switching tabs detaches
 * the surface and pauses the encoder, and never restarts the guest app.
 */
@Composable
internal fun AppSandboxPage(
    tabId: String,
    screenshotDir: File?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val device = LocalAndroidDevice.current
    val generation by AppSandboxSessions.generation.collectAsStateWithLifecycle()
    val session = remember(generation, tabId) { AppSandboxSessions.get(tabId) }

    // isHardwareAccelerated only answers once the view is attached, so it is read after a frame
    // rather than on first composition.
    var hardwareAccelerated by remember(view) { mutableStateOf(view.isHardwareAccelerated) }
    LaunchedEffect(view) {
        withFrameNanos { }
        hardwareAccelerated = view.isHardwareAccelerated
    }
    val tier = AppSandboxLauncher.tier(hardwareAccelerated)

    var packageName by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) {
        snapshotFlow { AppSandbox.pendingPackage.value }.collect { requested ->
            if (!requested.isNullOrBlank()) packageName = requested
        }
    }

    var starting by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var surfaceView by remember { mutableStateOf<AppSandboxSurfaceView?>(null) }

    LaunchedEffect(session) {
        session?.stopped?.filterNotNull()?.collect { stopped ->
            notice = stopped.reason
            AppSandboxSessions.destroy(tabId, stopped.reason)
        }
    }
    LaunchedEffect(session) {
        session?.appGone?.collect { gone ->
            if (gone) notice = "${session.packageName ?: "The app"} is no longer running on the sandbox display."
        }
    }

    Column(modifier) {
        if (session != null) {
            SandboxToolbar(
                onBack = { session.sendKeyPress(KeyEvent.KEYCODE_BACK) },
                onHome = { session.sendKeyPress(KeyEvent.KEYCODE_HOME) },
                onRecents = { session.sendKeyPress(KeyEvent.KEYCODE_APP_SWITCH) },
                onRotate = session::rotate,
                onRestart = session::restartApp,
                onScreenshot = {
                    surfaceView?.screenshot { bitmap ->
                        scope.launch {
                            notice = saveScreenshot(context, screenshotDir, bitmap)
                        }
                    } ?: run { notice = "The sandbox is not on screen." }
                },
                onKeyboard = { surfaceView?.showKeyboard() },
                onStop = {
                    surfaceView?.hideKeyboard()
                    AppSandboxSessions.destroy(tabId)
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                session != null -> SandboxStream(
                    session = session,
                    onView = { surfaceView = it },
                    modifier = Modifier.fillMaxSize(),
                )
                else -> SandboxSetup(
                    tier = tier,
                    packageName = packageName,
                    onPackageNameChange = { packageName = it },
                    bridgeStatus = device.status,
                    bridgeReady = device.ready,
                    starting = starting,
                    progress = progress,
                    onOpenAndroidDevice = device.onOpenPage,
                    onStart = {
                        starting = true
                        notice = null
                        scope.launch {
                            val display = sandboxDisplay(context)
                            runCatching {
                                AppSandboxLauncher.start(
                                    context = context,
                                    packageName = packageName.trim().ifEmpty { null },
                                    size = display.first,
                                    densityDpi = display.second,
                                    onProgress = { progress = it },
                                )
                            }
                                .onSuccess { AppSandboxSessions.put(tabId, it) }
                                .onFailure { notice = it.message ?: "Could not start the sandbox." }
                            starting = false
                            progress = ""
                        }
                    },
                    onLaunchOnDevice = {
                        scope.launch {
                            notice = AppSandboxLauncher.launchOnDevice(context, packageName.trim())
                                ?: "Launched ${packageName.trim()} on this device's own display."
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Falls back to the server's own last log line, which is all there is to go on when something
        // fails on the device side.
        val serverLog by (session?.log ?: emptyLogFlow).collectAsStateWithLifecycle()
        (notice ?: serverLog.lastOrNull())?.let { message ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Text(
                text = message,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val emptyLogFlow = MutableStateFlow<List<String>>(emptyList())

/** Letterboxed video: the guest display keeps its aspect ratio, the rest of the tab stays black. */
@Composable
private fun SandboxStream(
    session: AppSandboxSession,
    onView: (AppSandboxSurfaceView?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val videoSize by session.videoSize.collectAsStateWithLifecycle()
    BoxWithConstraints(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val scale = minOf(
            constraints.maxWidth.toFloat() / videoSize.width.coerceAtLeast(1),
            constraints.maxHeight.toFloat() / videoSize.height.coerceAtLeast(1),
        )
        AndroidView(
            factory = { AppSandboxSurfaceView(it).also(onView) },
            update = { surface ->
                surface.videoSize = videoSize
                surface.session = session
            },
            modifier = Modifier.size(
                width = with(density) { (videoSize.width * scale).toDp() },
                height = with(density) { (videoSize.height * scale).toDp() },
            ),
        )
        DisposableEffect(session) {
            onDispose { onView(null) }
        }
    }
}

@Composable
private fun SandboxToolbar(
    onBack: () -> Unit,
    onHome: () -> Unit,
    onRecents: () -> Unit,
    onRotate: () -> Unit,
    onRestart: () -> Unit,
    onScreenshot: () -> Unit,
    onKeyboard: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ToolbarAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
        ToolbarAction(Icons.Rounded.Home, "Home", onHome)
        ToolbarAction(Icons.Rounded.Layers, "Recent apps", onRecents)
        ToolbarAction(Icons.Rounded.ScreenRotation, "Rotate", onRotate)
        ToolbarAction(Icons.Rounded.Keyboard, "Keyboard", onKeyboard)
        Box(modifier = Modifier.weight(1f))
        ToolbarAction(Icons.Rounded.PhotoCamera, "Screenshot", onScreenshot)
        ToolbarAction(Icons.Rounded.RestartAlt, "Restart app", onRestart)
        ToolbarAction(Icons.Rounded.Stop, "Stop sandbox", onStop, tint = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ToolbarAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun SandboxSetup(
    tier: AppSandboxTier,
    packageName: String,
    onPackageNameChange: (String) -> Unit,
    bridgeStatus: String,
    bridgeReady: Boolean,
    starting: Boolean,
    progress: String,
    onOpenAndroidDevice: () -> Unit,
    onStart: () -> Unit,
    onLaunchOnDevice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                imageVector = Icons.Rounded.PhoneAndroid,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("App sandbox", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "Run your app on a private display and drive it from this tab — display 0 " +
                        "keeps showing JCode.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        if (tier == AppSandboxTier.DeviceDisplay) {
            ManagerNoticeCard(
                title = "Hardware acceleration is off",
                message = "The sandbox decodes video straight onto a surface, which needs the GPU. Turn " +
                    "Settings → Performance → Rendering → Hardware acceleration back on and restart " +
                    "JCode; until then the app can only be launched on this phone's own display.",
            )
        }

        ManagerSectionCard(
            title = "Device",
            description = "The sandbox drives this phone's own adb, the same connection Build & Run uses.",
        ) {
            ManagerSummaryRow("ADB bridge", bridgeStatus)
            CompactOutlinedButton(
                text = if (bridgeReady) "Open Android device" else "Set up ADB…",
                onClick = onOpenAndroidDevice,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ManagerSectionCard(
            title = "App",
            description = "The package to start on the sandbox display. Leave it empty to open an empty " +
                "display and launch something onto it yourself.",
        ) {
            SettingsTextFieldRow(
                label = "Package name",
                value = packageName,
                onValueChange = onPackageNameChange,
                placeholder = "com.example.app",
                monospace = true,
            )
            if (tier == AppSandboxTier.Embedded) {
                CompactFilledButton(
                    text = if (starting) progress.ifEmpty { "Starting…" } else "Start sandbox",
                    enabled = !starting,
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                CompactFilledButton(
                    text = "Launch on this device",
                    enabled = packageName.isNotBlank(),
                    onClick = onLaunchOnDevice,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Size and density for the guest display: this phone's own, scaled down so the encoder is not asked
 * for more pixels than it can keep up with, with the density scaled to match so the app still lays
 * itself out like a phone.
 */
private fun sandboxDisplay(context: Context): Pair<Size, Int> {
    val metrics = context.resources.displayMetrics
    val width = metrics.widthPixels.coerceAtLeast(MIN_DIMENSION)
    val height = metrics.heightPixels.coerceAtLeast(MIN_DIMENSION)
    val scale = minOf(1f, MAX_SHORT_SIDE.toFloat() / minOf(width, height))
    return Size((width * scale).toInt(), (height * scale).toInt()) to (metrics.densityDpi * scale).toInt()
}

private suspend fun saveScreenshot(context: Context, screenshotDir: File?, bitmap: Bitmap?): String {
    if (bitmap == null) return "Could not copy the sandbox surface."
    val dir = screenshotDir
        ?: File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir, "sandbox")
    return withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            val file = File(dir, "sandbox-${System.currentTimeMillis()}.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            "Saved ${file.absolutePath}"
        }.getOrElse { "Could not save the screenshot: ${it.message}" }
    }
}

private const val MAX_SHORT_SIDE = 1080
private const val MIN_DIMENSION = 320
