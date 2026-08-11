package dev.jcode.vdevice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jcode.core.distro.WorkspaceHostPaths
import dev.jcode.design.CompactFilledButton
import dev.jcode.design.CompactOutlinedButton
import dev.jcode.design.ContextAction
import dev.jcode.design.CompactContextMenu
import dev.jcode.design.JCodeIcon
import dev.jcode.design.ManagerNoticeCard
import dev.jcode.design.ManagerSectionCard
import dev.jcode.design.SettingsTextFieldRow
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * How long the device's controls stay up once they are left alone. Long enough to reach a second
 * control without the bar going out from under the finger, short enough that the app under test is
 * not covered while it is being watched.
 */
private const val TOOLBAR_IDLE_COLLAPSE_MS = 4_000L

/** The collapsed handle: a fifth of the device's width, sized like a sheet grabber. */
private const val HANDLE_WIDTH_FRACTION = 0.2f
private val HANDLE_THICKNESS = 4.dp
private val HANDLE_TOUCH_HEIGHT = 24.dp

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
 *
 * One-off results — a full-screen launch, an app that closed itself — go to [onSnackbar] rather than
 * to a band along the bottom: they are over once they have been read, and the device's screen is the
 * scarce thing here. What a running guest could not do is not one-off, so it stays reachable from the
 * control bar for as long as that guest is up.
 */
@Composable
internal fun AppSandboxPage(onSnackbar: (String) -> Unit, modifier: Modifier = Modifier) {
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
    var installOpen by remember { mutableStateOf(false) }
    val surface by session.surface.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

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
    // An app that closes itself leaves the device, not the tab: the screen goes back to blank — the
    // same place the Stop button lands on — and why it went is said once, on the way past.
    LaunchedEffect(status) {
        (status as? SandboxStatus.Stopped)?.let {
            onSnackbar(it.reason)
            session.close()
            running = false
        }
    }

    fun runFullScreen(path: String = apkPath, activity: String? = activityClass) {
        installOpen = false
        VirtualDevice.launch(context, path.trim(), activity)
            .onSuccess { onSnackbar("Started ${it.label} full screen.") }
            .onFailure { onSnackbar(it.message ?: "Could not start the app.") }
    }

    /** Puts an app from the device's own launcher on its screen — a tap on an icon. */
    fun open(app: VirtualDeviceApp) {
        installOpen = false
        // The launcher runs an app as the device would: its own MAIN/LAUNCHER activity, whatever
        // the last run happened to name.
        AppSandbox.activityClass.value = null
        apkPath = app.apkPath
        if (tier == AppSandboxTier.Embedded) running = true else runFullScreen(app.apkPath, null)
    }

    fun stop() {
        surfaceView?.hideKeyboard()
        session.close()
        running = false
        // Back to a live, blank device rather than the last frame the guest left on the surface.
        surfaceView?.paintWallpaper()
    }

    Box(modifier) {
        DeviceScreen(
            session = session,
            status = status,
            running = running,
            tier = tier,
            onSurface = { surfaceView = it },
            onSized = { width, height -> size = IntSize(width, height) },
            onRetry = {
                session.restart(apkPath, activityClass, size.width, size.height, surfaceView?.hostToken())
            },
            onFullScreen = { runFullScreen() },
            onDismiss = { stop() },
            onOpen = { open(it) },
            onOpenFullScreen = { runFullScreen(it.apkPath, null) },
            onUninstall = { app ->
                if (app.apkPath == apkPath) stop()
                scope.launch(Dispatchers.IO) {
                    VirtualDeviceApps.uninstall(context, app.packageName)
                }
                onSnackbar("Uninstalled ${app.label}.")
            },
            onClearData = { app ->
                scope.launch(Dispatchers.IO) { VirtualDeviceApps.clearData(context, app.packageName) }
                onSnackbar("Cleared ${app.label}'s data.")
            },
            onInstall = { installOpen = true },
            modifier = Modifier.fillMaxSize(),
        )

        if (installOpen) {
            InstallSheet(
                tier = tier,
                apkPath = apkPath,
                onApkPathChange = {
                    apkPath = it
                    // The activity a launch named belongs to the APK it named, not to this one.
                    AppSandbox.activityClass.value = null
                },
                onInstall = { path ->
                    scope.launch {
                        withContext(Dispatchers.IO) { VirtualDeviceApps.installCopy(context, File(path)) }
                            .onSuccess {
                                installOpen = false
                                onSnackbar("Installed ${it.label} on ${VirtualIdentity.MODEL}.")
                            }
                            .onFailure { onSnackbar(it.message ?: "Could not install that APK.") }
                    }
                },
                onRunHere = {
                    installOpen = false
                    running = true
                },
                onRunFullScreen = { runFullScreen() },
                onClose = { installOpen = false },
                modifier = Modifier.fillMaxSize(),
            )
        } else if (running) {
            // Every control on the bar acts on a running guest, and the home screen already names
            // the device — so with nothing running there is nothing for it to say.
            DeviceControls(
                caveat = (status as? SandboxStatus.Running)?.warning,
                onBack = { session.back() },
                onKeyboard = { surfaceView?.showKeyboard() },
                onRestart = {
                    session.restart(apkPath, activityClass, size.width, size.height, surfaceView?.hostToken())
                },
                onFullScreen = { runFullScreen() },
                onStop = { stop() },
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
    tier: AppSandboxTier,
    onSurface: (AppSandboxSurfaceView?) -> Unit,
    onSized: (Int, Int) -> Unit,
    onRetry: () -> Unit,
    onFullScreen: () -> Unit,
    onDismiss: () -> Unit,
    onOpen: (VirtualDeviceApp) -> Unit,
    onOpenFullScreen: (VirtualDeviceApp) -> Unit,
    onUninstall: (VirtualDeviceApp) -> Unit,
    onClearData: (VirtualDeviceApp) -> Unit,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Matches the wallpaper painted onto the surface, so the two never disagree along its edges
    // while the surface is being created or resized.
    Box(
        modifier = modifier.background(Color(VirtualWallpaper.BACKGROUND)),
        contentAlignment = Alignment.Center,
    ) {
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
            !running -> DeviceHome(
                tier = tier,
                onOpen = onOpen,
                onOpenFullScreen = onOpenFullScreen,
                onUninstall = onUninstall,
                onClearData = onClearData,
                onInstall = onInstall,
                modifier = Modifier.fillMaxSize(),
            )

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

/**
 * The device's home screen: what is installed on it, and nothing else.
 *
 * This is the tab's resting state and what `screencap` answers with when no app is running, so it is
 * deliberately a *device* screen rather than an IDE panel — the wallpaper behind it is the surface's
 * own, and the grid is the same set `adb shell pm list packages` reports, in the same order every
 * time. An app installed over adb appears here without the tab being touched: [VirtualDeviceApps]
 * bumps a revision the list is keyed on.
 */
@Composable
private fun DeviceHome(
    tier: AppSandboxTier,
    onOpen: (VirtualDeviceApp) -> Unit,
    onOpenFullScreen: (VirtualDeviceApp) -> Unit,
    onUninstall: (VirtualDeviceApp) -> Unit,
    onClearData: (VirtualDeviceApp) -> Unit,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val revision = VirtualDeviceApps.revision.intValue
    var apps by remember { mutableStateOf<List<VirtualDeviceApp>?>(null) }
    LaunchedEffect(revision) {
        apps = withContext(Dispatchers.IO) { VirtualDeviceApps.list(context) }
    }

    Column(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = VirtualIdentity.MODEL,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White.copy(alpha = 0.8f),
        )
        // Null is "not read yet", which must not flash the empty state on the way to a full grid.
        val installed = apps
        when {
            installed == null -> Box(modifier = Modifier.weight(1f))
            installed.isEmpty() -> EmptyHome(onInstall, modifier = Modifier.weight(1f))
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(88.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(installed, key = { it.packageName }) { app ->
                    AppTile(
                        app = app,
                        tier = tier,
                        onOpen = { onOpen(app) },
                        onOpenFullScreen = { onOpenFullScreen(app) },
                        onUninstall = { onUninstall(app) },
                        onClearData = { onClearData(app) },
                    )
                }
            }
        }
        // The empty state carries its own copy of this button, next to the sentence explaining it.
        if (!installed.isNullOrEmpty()) {
            CompactOutlinedButton(text = "Install an app", onClick = onInstall)
        }
    }
}

/** A device that has never had anything put on it. */
@Composable
private fun EmptyHome(onInstall: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = Icons.Rounded.PhoneAndroid,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.35f),
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = "The screen is on and nothing is installed. Install over adb, or point this at " +
                "an APK you have already built.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.45f),
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 300.dp),
        )
        CompactOutlinedButton(text = "Install an app", onClick = onInstall)
    }
}

/** One installed app: its own icon and label, tapped to run and held for everything else. */
@Composable
private fun AppTile(
    app: VirtualDeviceApp,
    tier: AppSandboxTier,
    onOpen: () -> Unit,
    onOpenFullScreen: () -> Unit,
    onUninstall: () -> Unit,
    onClearData: () -> Unit,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    // The icon is decoded off an APK archive, which is slow enough to keep out of the first frame.
    val icon by produceState<ImageBitmap?>(initialValue = null, app.apkPath) {
        value = withContext(Dispatchers.IO) {
            VirtualDevice.icon(context, app.apkPath)?.let { runCatching { it.toBitmap() }.getOrNull() }
        }?.asImageBitmap()
    }

    Box {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true })
                .padding(vertical = 8.dp, horizontal = 4.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val iconBitmap = icon
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap,
                    contentDescription = null,
                    modifier = Modifier.size(46.dp).clip(RoundedCornerShape(11.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Color.White.copy(alpha = 0.10f)),
                )
            }
            Text(
                text = app.label,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CompactContextMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            listActions = buildList {
                add(ContextAction(JCodeIcon.Run, "Open", onClick = onOpen))
                if (tier == AppSandboxTier.Embedded) {
                    add(ContextAction(JCodeIcon.Fullscreen, "Open full screen", onClick = onOpenFullScreen))
                }
                add(ContextAction(JCodeIcon.Clear, "Clear data", onClick = onClearData))
                add(ContextAction(JCodeIcon.Delete, "Uninstall", destructive = true, onClick = onUninstall))
            },
        )
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
 *
 * [caveat] is what the container could not give the guest that is up — a button here when there is
 * something to say, and no button at all when there is not.
 */
@Composable
private fun BoxScope.DeviceControls(
    caveat: String?,
    onBack: () -> Unit,
    onKeyboard: () -> Unit,
    onRestart: () -> Unit,
    onFullScreen: () -> Unit,
    onStop: () -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }
    var caveatOpen by remember { mutableStateOf(false) }
    // The dialog is a window of its own, so nothing presses the bar while it is up — and a guest that
    // stops takes its caveat with it, which must also let the timer go again.
    val openCaveat = caveat?.takeIf { caveatOpen }

    AnimatedVisibility(
        visible = expanded,
        modifier = Modifier.align(Alignment.TopCenter),
        enter = expandVertically(animationSpec = tween(200)),
        exit = shrinkVertically(animationSpec = tween(200)),
    ) {
        // Remembered with the bar rather than with the page, so a press that the collapse animation
        // cut short cannot strand the timer once the pill brings the bar back.
        var pressed by remember { mutableStateOf(false) }
        LaunchedEffect(pressed, openCaveat) {
            if (!pressed && openCaveat == null) {
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
                DeviceToolbar(
                    caveat = caveat,
                    onBack = onBack,
                    onKeyboard = onKeyboard,
                    onCaveat = { caveatOpen = true },
                    onRestart = onRestart,
                    onFullScreen = onFullScreen,
                    onStop = onStop,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }

    if (!expanded) {
        DeviceControlsHandle(
            onClick = { expanded = true },
            // Top-centre: the workbench's own chrome pill owns TopEnd in the same Box, and in
            // distraction-free mode the two would sit on top of each other.
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }

    // Outside the collapsing bar: the message takes longer to read than the bar stays up.
    openCaveat?.let { message ->
        GuestCaveatDialog(message = message, onDismiss = { caveatOpen = false })
    }
}

/**
 * What the container could not give the running guest, word for word. It is a paragraph about one
 * app's fidelity rather than something to act on, so it waits behind a tap instead of standing in a
 * band of the device's screen — and nothing is trimmed to fit once it is opened.
 */
@Composable
private fun GuestCaveatDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What this guest could not do") },
        text = {
            Text(
                text = message,
                modifier = Modifier.verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * The collapsed controls: a grabber line, not a button. It sits over whatever the guest is drawing,
 * so it stays deliberately small — the touch target is taller than the line it shows, which is why
 * the clickable box and the bar have separate sizes.
 */
@Composable
private fun DeviceControlsHandle(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth(HANDLE_WIDTH_FRACTION)
            .height(HANDLE_TOUCH_HEIGHT)
            .clickable(onClickLabel = "Show the device controls", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(HANDLE_THICKNESS)
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)),
        )
    }
}

@Composable
private fun DeviceToolbar(
    caveat: String?,
    onBack: () -> Unit,
    onKeyboard: () -> Unit,
    onCaveat: () -> Unit,
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
        // Only lit when this guest actually lost something: a warning that is always on is a warning
        // nobody reads. Carries the same colour ManagerNoticeCard gives the launcher's version of it.
        if (caveat != null) {
            ToolbarAction(
                icon = Icons.Rounded.Warning,
                label = "What this guest could not do",
                onClick = onCaveat,
                tint = MaterialTheme.colorScheme.error,
            )
        }
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

/**
 * Puts an APK on the device from a path — the tab's half of `adb install`, plus the one-off run that
 * skips installing entirely. Over the screen, not instead of it: the device stays up.
 */
@Composable
private fun InstallSheet(
    tier: AppSandboxTier,
    apkPath: String,
    onApkPathChange: (String) -> Unit,
    onInstall: (String) -> Unit,
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
                    Text("Install an app", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "Put a freshly built APK on ${VirtualIdentity.MODEL} — no install on " +
                            "this phone, no ADB. It runs in J Code's own process under a virtual " +
                            "device identity, and everything it stores is cleared when J Code starts.",
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
                description = "The APK to put on the device. Installing it leaves an icon on the home " +
                    "screen and lists it under `adb shell pm list packages`; running it straight from " +
                    "here does neither. A virtual-device run config fills this in with whatever it just " +
                    "built, and `adb install` reaches the same device.",
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
                CompactFilledButton(
                    text = "Install on ${VirtualIdentity.MODEL}",
                    enabled = readable,
                    onClick = { onInstall(apkPath.trim()) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (tier == AppSandboxTier.Embedded) {
                    CompactOutlinedButton(
                        text = "Run once, without installing",
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
