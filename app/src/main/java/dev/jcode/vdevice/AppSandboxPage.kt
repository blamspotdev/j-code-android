package dev.jcode.vdevice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
import dev.jcode.design.FloatingRestorePill
import dev.jcode.design.JCodeIcon
import dev.jcode.design.ManagerNoticeCard
import dev.jcode.design.ManagerSectionCard
import dev.jcode.design.SettingsTextFieldRow
import dev.jcode.design.jcIcon
import java.io.File
import kotlinx.coroutines.delay

/**
 * How long the device's controls stay up once they are left alone. Long enough to reach a second
 * control without the bar going out from under the finger, short enough that the app under test is
 * not covered while it is being watched.
 */
private const val TOOLBAR_IDLE_COLLAPSE_MS = 4_000L

/**
 * Editor tab holding J Code's virtual device — a screen the IDE owns, that an app can be put on and
 * taken off again.
 *
 * The device is the tab, not the app: with nothing running it is a live blank screen that `adb` can
 * install to, launch onto and `screencap`, and stopping an app returns it to that rather than
 * closing anything. Whatever is running is built and composited by the `:guest` process
 * ([EmbeddedGuest]) and shown here through a `SurfaceControlViewHost` surface package.
 *
 * Embedding can fail for reasons this tab cannot fix — the window may not be hardware accelerated,
 * and the out-of-band activity creation the container depends on rests on non-SDK members — so every
 * failure lands on the same visible fallback: run the app full screen, the way the container has
 * always been able to.
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
    var running by AppSandbox.running
    var size by remember { mutableStateOf(IntSize.Zero) }
    var surfaceView by remember { mutableStateOf<AppSandboxSurfaceView?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var launcherOpen by remember { mutableStateOf(false) }
    val surface by session.surface.collectAsStateWithLifecycle()

    // The guest display is the tab, so the surface's own pixel size is what the container is asked
    // for — which keeps forwarded touches in the guest's coordinates with no mapping at all.
    LaunchedEffect(size, running, apkPath, surfaceView) {
        if (running) {
            session.ensureStarted(apkPath, activityClass, size.width, size.height, surfaceView?.hostToken())
        }
    }
    LaunchedEffect(surface, surfaceView) {
        surface?.let { surfaceView?.adopt(it) }
    }
    // An app that closes itself leaves the device, not the tab: the screen goes back to blank and
    // says what happened, which is the same place the Stop button lands on.
    LaunchedEffect(status) {
        (status as? SandboxStatus.Stopped)?.let {
            notice = it.reason
            session.close()
            running = false
        }
    }

    fun runFullScreen() {
        launcherOpen = false
        VirtualDevice.launch(context, apkPath.trim(), activityClass)
            .onSuccess { notice = "Started ${it.label} full screen." }
            .onFailure { notice = it.message ?: "Could not start the app." }
    }

    fun stop() {
        surfaceView?.hideKeyboard()
        session.close()
        running = false
        notice = null
    }

    Column(modifier) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            DeviceScreen(
                session = session,
                status = status,
                running = running,
                onSurface = { surfaceView = it },
                onSized = { width, height -> size = IntSize(width, height) },
                onRetry = {
                    session.restart(apkPath, activityClass, size.width, size.height, surfaceView?.hostToken())
                },
                onFullScreen = { runFullScreen() },
                onDismiss = { stop() },
                onLaunch = { launcherOpen = true },
                modifier = Modifier.fillMaxSize(),
            )

            if (launcherOpen) {
                DeviceLauncher(
                    tier = tier,
                    apkPath = apkPath,
                    onApkPathChange = {
                        apkPath = it
                        // The activity a launch named belongs to the APK it named, not to this one.
                        AppSandbox.activityClass.value = null
                    },
                    onRunHere = {
                        launcherOpen = false
                        notice = null
                        running = true
                    },
                    onRunFullScreen = { runFullScreen() },
                    onClose = { launcherOpen = false },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                DeviceControls(
                    running = running,
                    onBack = { session.back() },
                    onKeyboard = { surfaceView?.showKeyboard() },
                    onLaunch = { launcherOpen = true },
                    onRestart = {
                        session.restart(apkPath, activityClass, size.width, size.height, surfaceView?.hostToken())
                    },
                    onFullScreen = { runFullScreen() },
                    onStop = { stop() },
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

/**
 * The device's screen. The `SurfaceView` is here whether or not an app is: it is what gives the
 * device its resolution and the host token a guest is embedded under, so it is created with the tab
 * and outlives every app put on it.
 */
@Composable
private fun DeviceScreen(
    session: AppSandboxSession,
    status: SandboxStatus,
    running: Boolean,
    onSurface: (AppSandboxSurfaceView?) -> Unit,
    onSized: (Int, Int) -> Unit,
    onRetry: () -> Unit,
    onFullScreen: () -> Unit,
    onDismiss: () -> Unit,
    onLaunch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context ->
                AppSandboxSurfaceView(context, session) { width, height ->
                    onSized(width, height)
                    VirtualScreen.sized(width, height)
                }.also(onSurface)
            },
            modifier = Modifier.fillMaxSize(),
        )
        DisposableEffect(Unit) { onDispose { onSurface(null) } }

        when {
            !running -> IdleScreen(onLaunch)
            status is SandboxStatus.Starting || status is SandboxStatus.Idle ->
                ScreenMessage("Starting the app…")

            status is SandboxStatus.Failed -> ScreenFallback(
                message = status.message,
                onRetry = onRetry,
                onFullScreen = onFullScreen,
                onDismiss = onDismiss,
            )

            else -> Unit
        }
    }
}

/** A device with nothing on it — the tab's resting state, and what `screencap` answers black for. */
@Composable
private fun IdleScreen(onLaunch: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(24.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.PhoneAndroid,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.35f),
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = VirtualIdentity.MODEL,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White.copy(alpha = 0.75f),
        )
        Text(
            text = "The screen is on and nothing is running. Install and launch over adb, or pick " +
                "an APK here.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.45f),
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 300.dp),
        )
        CompactOutlinedButton(text = "Run an app", onClick = onLaunch)
    }
}

@Composable
private fun ScreenMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.7f),
        textAlign = TextAlign.Center,
    )
}

/** Never a black tab with no explanation: whatever went wrong, the app can still be started the old
 *  way from here. */
@Composable
private fun ScreenFallback(
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
            Text(
                text = "Could not run the app on this device",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
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
                CompactOutlinedButton(text = "Clear", onClick = onDismiss, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * The device's controls, floating over its screen instead of taking a band out of it.
 *
 * An embedded guest lays itself out against the phone's screen rather than the tab, so tall content
 * already runs past the bottom edge; every row of tab handed back to it is a row of the app that can
 * actually be seen. The bar therefore collapses itself once it has been left alone, and comes back
 * through the same pill the workbench's hidden chrome uses.
 */
@Composable
private fun BoxScope.DeviceControls(
    running: Boolean,
    onBack: () -> Unit,
    onKeyboard: () -> Unit,
    onLaunch: () -> Unit,
    onRestart: () -> Unit,
    onFullScreen: () -> Unit,
    onStop: () -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }

    AnimatedVisibility(
        visible = expanded,
        modifier = Modifier.align(Alignment.TopCenter),
        enter = expandVertically(animationSpec = tween(200)),
        exit = shrinkVertically(animationSpec = tween(200)),
    ) {
        // Remembered with the bar rather than with the page, so a press that the collapse animation
        // cut short cannot strand the timer once the pill brings the bar back.
        var pressed by remember { mutableStateOf(false) }
        LaunchedEffect(pressed) {
            if (!pressed) {
                delay(TOOLBAR_IDLE_COLLAPSE_MS)
                expanded = false
            }
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                // Watched on the initial pass, never consumed: the buttons still get their taps, a
                // finger still down holds the bar open, and the guest below is spared both.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            pressed = awaitPointerEvent(PointerEventPass.Initial)
                                .changes.any { it.pressed }
                        }
                    }
                },
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            Column {
                DeviceToolbar(running, onBack, onKeyboard, onLaunch, onRestart, onFullScreen, onStop)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }

    if (!expanded) {
        FloatingRestorePill(
            icon = jcIcon(JCodeIcon.ChevronDown),
            contentDescription = "Show the device controls",
            onClick = { expanded = true },
            // Top-centre: the workbench's own chrome pill owns TopEnd in the same Box, and in
            // distraction-free mode the two would sit on top of each other.
            modifier = Modifier.align(Alignment.TopCenter).padding(4.dp),
            // The workbench's own pill is translucent over J Code's background; this one floats over
            // whatever the guest happens to be drawing, so it keeps its own contrast.
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
private fun DeviceToolbar(
    running: Boolean,
    onBack: () -> Unit,
    onKeyboard: () -> Unit,
    onLaunch: () -> Unit,
    onRestart: () -> Unit,
    onFullScreen: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (running) {
            ToolbarAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
            ToolbarAction(Icons.Rounded.Keyboard, "Keyboard", onKeyboard)
        } else {
            Text(
                text = VirtualIdentity.MODEL,
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(modifier = Modifier.weight(1f))
        ToolbarAction(Icons.Rounded.PlayArrow, "Run an app", onLaunch)
        if (running) {
            ToolbarAction(Icons.Rounded.Fullscreen, "Run full screen", onFullScreen)
            ToolbarAction(Icons.Rounded.RestartAlt, "Restart app", onRestart)
            ToolbarAction(Icons.Rounded.Stop, "Stop", onStop, tint = MaterialTheme.colorScheme.error)
        }
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

/** Picks what to put on the device. Over the screen, not instead of it — the device stays up. */
@Composable
private fun DeviceLauncher(
    tier: AppSandboxTier,
    apkPath: String,
    onApkPathChange: (String) -> Unit,
    onRunHere: () -> Unit,
    onRunFullScreen: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val problem = remember(apkPath) { apkProblem(apkPath) }
    val readable = apkPath.isNotBlank() && problem == null
    // Projects live on app-private ext4, not the shared /storage tree an older build used, so the
    // example path is resolved rather than written out.
    val projectsRoot = remember { WorkspaceHostPaths.projectsRoot }
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Run an app", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "Put a freshly built APK on ${VirtualIdentity.MODEL} — no install, no " +
                            "ADB. It runs in J Code's own process under a virtual device identity.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }

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
                    "just built, and `adb shell am start` launches it here too.",
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
                        text = "Run on this device",
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
                    "phone's screen rather than the tab, which can leave content past the edges. Its " +
                    "own Lifecycle is driven directly, but callbacks it registers on the activity " +
                    "with registerActivityLifecycleCallbacks miss the pre/post start, resume and stop " +
                    "steps. Run it full screen, or install it, before trusting what you see.",
            )
        }
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
