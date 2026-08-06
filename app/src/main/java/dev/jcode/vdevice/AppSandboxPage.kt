package dev.jcode.vdevice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jcode.core.distro.WorkspaceHostPaths
import dev.jcode.design.CompactFilledButton
import dev.jcode.design.CompactOutlinedButton
import dev.jcode.design.ManagerNoticeCard
import dev.jcode.design.ManagerSectionCard
import dev.jcode.design.SettingsTextFieldRow
import java.io.File

/**
 * Editor tab that runs one of the user's own Android apps inside J Code.
 *
 * The guest is built and composited by the `:guest` process ([EmbeddedGuest]) and shown here through
 * a `SurfaceControlViewHost` surface package. Embedding can fail for reasons this tab cannot fix —
 * the window may not be hardware accelerated, and the out-of-band activity creation the container
 * depends on rests on non-SDK members — so every failure lands on the same visible fallback: run the
 * app full screen, the way the container has always been able to.
 */
@Composable
internal fun AppSandboxPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val session = remember { AppSandbox.session(context) }
    val status by session.status.collectAsStateWithLifecycle()

    // isHardwareAccelerated only answers once the view is attached, so it is read after a frame
    // rather than on first composition.
    var hardwareAccelerated by remember(view) { mutableStateOf(view.isHardwareAccelerated) }
    LaunchedEffect(view) {
        withFrameNanos { }
        hardwareAccelerated = view.isHardwareAccelerated
    }
    val tier = if (hardwareAccelerated) AppSandboxTier.Embedded else AppSandboxTier.FullScreen

    var apkPath by AppSandbox.apkPath
    val activityClass by AppSandbox.activityClass
    var showing by AppSandbox.showing
    var size by remember { mutableStateOf(IntSize.Zero) }
    var surfaceView by remember { mutableStateOf<AppSandboxSurfaceView?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val surface by session.surface.collectAsStateWithLifecycle()

    // The guest display is the tab, so the surface's own pixel size is what the container is asked
    // for — which keeps forwarded touches in the guest's coordinates with no mapping at all.
    LaunchedEffect(size, showing, apkPath, surfaceView) {
        if (showing) {
            session.ensureStarted(apkPath, activityClass, size.width, size.height, surfaceView?.hostToken())
        }
    }
    LaunchedEffect(surface, surfaceView) {
        surface?.let { surfaceView?.adopt(it) }
    }

    fun runFullScreen() {
        VirtualDevice.launch(context, apkPath.trim(), activityClass)
            .onSuccess { notice = "Started ${it.label} full screen." }
            .onFailure { notice = it.message ?: "Could not start the app." }
    }

    fun restart() =
        session.restart(apkPath, activityClass, size.width, size.height, surfaceView?.hostToken())

    Column(modifier) {
        if (showing) {
            SandboxToolbar(
                onBack = { session.back() },
                onKeyboard = { surfaceView?.showKeyboard() },
                onRestart = { restart() },
                onFullScreen = { runFullScreen() },
                onStop = {
                    surfaceView?.hideKeyboard()
                    session.close()
                    showing = false
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (showing) {
                SandboxStage(
                    session = session,
                    status = status,
                    onSurface = { surfaceView = it },
                    onSized = { width, height -> size = IntSize(width, height) },
                    onRetry = { restart() },
                    onFullScreen = { runFullScreen() },
                    onDismiss = {
                        session.close()
                        showing = false
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                SandboxSetup(
                    tier = tier,
                    apkPath = apkPath,
                    onApkPathChange = {
                        apkPath = it
                        // The activity a launch named belongs to the APK it named, not to this one.
                        AppSandbox.activityClass.value = null
                    },
                    onRunHere = { showing = true },
                    onRunFullScreen = { runFullScreen() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        (notice ?: (status as? SandboxStatus.Running)?.warning)?.let { message ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Text(
                text = message,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SandboxStage(
    session: AppSandboxSession,
    status: SandboxStatus,
    onSurface: (AppSandboxSurfaceView?) -> Unit,
    onSized: (Int, Int) -> Unit,
    onRetry: () -> Unit,
    onFullScreen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context ->
                AppSandboxSurfaceView(context, session, onSized).also(onSurface)
            },
            modifier = Modifier.fillMaxSize(),
        )
        DisposableEffect(Unit) { onDispose { onSurface(null) } }

        when (status) {
            SandboxStatus.Idle, SandboxStatus.Starting -> StageMessage("Starting the app…")
            is SandboxStatus.Running -> Unit
            is SandboxStatus.Stopped -> StageFallback(
                title = "The app stopped",
                message = status.reason,
                onRetry = onRetry,
                onFullScreen = onFullScreen,
                onDismiss = onDismiss,
            )
            is SandboxStatus.Failed -> StageFallback(
                title = "Could not run the app in this tab",
                message = status.message,
                onRetry = onRetry,
                onFullScreen = onFullScreen,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun StageMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.7f),
        textAlign = TextAlign.Center,
    )
}

/** Never a black tab: whatever went wrong, the app can still be started the old way from here. */
@Composable
private fun StageFallback(
    title: String,
    message: String,
    onRetry: () -> Unit,
    onFullScreen: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.padding(24.dp).widthIn(max = 420.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CompactFilledButton(
                text = "Run full screen instead",
                onClick = onFullScreen,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactOutlinedButton(text = "Try again", onClick = onRetry, modifier = Modifier.weight(1f))
                CompactOutlinedButton(text = "Close", onClick = onDismiss, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SandboxToolbar(
    onBack: () -> Unit,
    onKeyboard: () -> Unit,
    onRestart: () -> Unit,
    onFullScreen: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ToolbarAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
        ToolbarAction(Icons.Rounded.Keyboard, "Keyboard", onKeyboard)
        Box(modifier = Modifier.weight(1f))
        ToolbarAction(Icons.Rounded.Fullscreen, "Run full screen", onFullScreen)
        ToolbarAction(Icons.Rounded.RestartAlt, "Restart app", onRestart)
        ToolbarAction(Icons.Rounded.Stop, "Stop", onStop, tint = MaterialTheme.colorScheme.error)
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
    apkPath: String,
    onApkPathChange: (String) -> Unit,
    onRunHere: () -> Unit,
    onRunFullScreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val problem = remember(apkPath) { apkProblem(apkPath) }
    val readable = apkPath.isNotBlank() && problem == null
    // Projects live on app-private ext4, not the shared /storage tree an older build used, so the
    // example path is resolved rather than written out.
    val projectsRoot = remember { WorkspaceHostPaths.projectsRoot }
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
                    text = "Run a freshly built APK in this tab — no install, no ADB. The app runs in " +
                        "J Code's own process under a virtual device identity.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        if (tier == AppSandboxTier.FullScreen) {
            ManagerNoticeCard(
                title = "Hardware acceleration is off",
                message = "The guest is composited onto a surface, which needs the GPU. Turn " +
                    "Settings → Performance → Rendering → Hardware acceleration back on and restart " +
                    "J Code; until then the app can only take over the whole screen.",
            )
        }

        ManagerSectionCard(
            title = "App",
            description = "The APK to run. A virtual-device run config fills this in with whatever it " +
                "just built, and `adb shell am start` opens it here too.",
        ) {
            SettingsTextFieldRow(
                label = "APK path",
                value = apkPath,
                onValueChange = onApkPathChange,
                placeholder = "$projectsRoot/…/app-debug.apk",
                monospace = true,
            )
            problem?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (tier == AppSandboxTier.Embedded) {
                CompactFilledButton(
                    text = "Run in this tab",
                    enabled = readable,
                    onClick = onRunHere,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            CompactOutlinedButton(
                text = "Run full screen",
                enabled = readable,
                onClick = onRunFullScreen,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ManagerNoticeCard(
            title = "What an embedded guest gives up",
            message = "The app runs without an activity of its own, so it cannot raise the soft " +
                "keyboard itself — use the keyboard button — and it lays itself out against this " +
                "phone's screen rather than the tab, which can leave content past the edges. Run it " +
                "full screen, or install it, before trusting what you see.",
        )
    }
}

/** Why this path cannot be run, in the user's terms — null when it can. */
private fun apkProblem(path: String): String? {
    val trimmed = path.trim()
    if (trimmed.isEmpty()) return null
    val file = File(trimmed)
    return when {
        file.isDirectory -> "That is a folder — point this at the .apk file inside it."
        !file.exists() -> "Nothing is at that path. A debug build leaves its APK under " +
            "app/build/outputs/apk/debug/ inside the project."
        !file.canRead() -> "J Code cannot read that file."
        else -> null
    }
}
