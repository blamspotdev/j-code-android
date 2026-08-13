package dev.jcode.vdevice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Keyboard
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
 * and the out-of-band activity creation the container depends on rests on non-SDK members — so a
 * failure is reported on the device's own screen rather than worked around. There is nowhere else to
 * run an app: a guest is the tab, and full screen means full screen *within* it.
 *
 * One-off results — an app that closed itself — go to [onSnackbar] rather than to a band along the
 * bottom: they are over once they have been read, and the device's screen is the scarce thing here. What a running guest could not do is not one-off, so it stays reachable from the
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
    var apkPath by AppSandbox.apkPath
    val activityClass by AppSandbox.activityClass
    var running by AppSandbox.running
    var size by remember { mutableStateOf(IntSize.Zero) }
    var surfaceView by remember { mutableStateOf<AppSandboxSurfaceView?>(null) }
    var installOpen by remember { mutableStateOf(false) }
    val surface by session.surface.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // The device's launcher lives on the surface, not in this composition — see VirtualLauncher. All
    // that is left here is reading what is installed and handing it over, and hosting the one menu
    // that is J Code's rather than the device's.
    val revision = VirtualDeviceApps.revision.intValue
    var home by remember { mutableStateOf<List<LauncherApp>?>(null) }
    var menuFor by remember { mutableStateOf<Pair<VirtualDeviceApp, Offset>?>(null) }
    LaunchedEffect(revision, running) {
        home = if (running) null else withContext(Dispatchers.IO) { VirtualLauncher.load(context) }
    }
    LaunchedEffect(home, size, surfaceView) {
        surfaceView?.showHome(home)
        if (home != null) menuFor = null
    }

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

    /** Puts an app from the device's own launcher on its screen — a tap on an icon. */
    fun open(app: VirtualDeviceApp) {
        installOpen = false
        // The launcher runs an app as the device would: its own MAIN/LAUNCHER activity, whatever
        // the last run happened to name.
        AppSandbox.activityClass.value = null
        apkPath = app.apkPath
        running = true
    }

    // Clearing `running` is the whole teardown: the home effect below reloads what is installed and
    // repaints the surface, so there is one place that decides what an idle device shows.
    fun stop() {
        surfaceView?.hideKeyboard()
        session.close()
        running = false
    }

    fun uninstall(app: VirtualDeviceApp) {
        if (app.apkPath == apkPath) stop()
        scope.launch(Dispatchers.IO) { VirtualDeviceApps.uninstall(context, app.packageName) }
        onSnackbar("Uninstalled ${app.label}.")
    }

    // Re-set rather than captured once: the surface outlives every composition that reads these.
    LaunchedEffect(surfaceView) {
        surfaceView?.onLaunchApp = { open(it) }
        surfaceView?.onAppMenu = { app, x, y -> menuFor = app to Offset(x, y) }
    }

    Box(modifier) {
        DeviceScreen(
            session = session,
            status = status,
            running = running,
            onSurface = { surfaceView = it },
            onSized = { width, height -> size = IntSize(width, height) },
            onRetry = {
                session.restart(apkPath, activityClass, size.width, size.height, surfaceView?.hostToken())
            },
            onDismiss = { stop() },
            onInstall = { installOpen = true },
            modifier = Modifier.fillMaxSize(),
        )

        // Held over the icon that was long-pressed. A menu is J Code's, not the device's, so it is
        // composed here rather than drawn onto the screen a capture reads.
        menuFor?.let { (app, at) ->
            val density = LocalDensity.current
            CompactContextMenu(
                expanded = true,
                onDismissRequest = { menuFor = null },
                offset = with(density) { DpOffset(at.x.toDp(), at.y.toDp()) },
                listActions = buildList {
                    add(ContextAction(JCodeIcon.Run, "Open") { menuFor = null; open(app) })
                    add(ContextAction(JCodeIcon.Clear, "Clear data") {
                        menuFor = null
                        scope.launch(Dispatchers.IO) { VirtualDeviceApps.clearData(context, app.packageName) }
                        onSnackbar("Cleared ${app.label}'s data.")
                    })
                    add(ContextAction(JCodeIcon.Delete, "Uninstall", destructive = true) {
                        menuFor = null
                        uninstall(app)
                    })
                },
            )
        }

        if (installOpen) {
            InstallSheet(
                hardwareAccelerated = hardwareAccelerated,
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
    onSurface: (AppSandboxSurfaceView?) -> Unit,
    onSized: (Int, Int) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
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
            // The home screen itself is on the surface, drawn by VirtualLauncher — only the chrome
            // that does not belong to the device is composed over it.
            !running -> HomeChrome(onInstall, modifier = Modifier.fillMaxSize())

            status is SandboxStatus.Starting || status is SandboxStatus.Idle ->
                ScreenMessage("Starting the app…")

            status is SandboxStatus.Failed -> ScreenFallback(
                message = status.message,
                onRetry = onRetry,
                onDismiss = onDismiss,
            )

            else -> Unit
        }
    }
}

/**
 * The only part of the home screen that is *not* the device: J Code's own affordance for putting an
 * app on it.
 *
 * Everything the device itself shows — wallpaper, its name, the app icons, the "No app installed"
 * placeholder — is drawn onto the surface by [VirtualLauncher], so `adb shell screencap` answers
 * with it. This button is deliberately outside that: it is the IDE reaching onto the device, the
 * same as the control bar, and a capture must not show it as though it were part of the app grid.
 */
@Composable
private fun HomeChrome(onInstall: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        CompactOutlinedButton(
            text = "Install an app",
            onClick = onInstall,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
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
    hardwareAccelerated: Boolean,
    apkPath: String,
    onApkPathChange: (String) -> Unit,
    onInstall: (String) -> Unit,
    onRunHere: () -> Unit,
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

            if (!hardwareAccelerated) {
                ManagerNoticeCard(
                    title = "Hardware acceleration is off",
                    message = "The device is composited onto a surface, which needs the GPU. Turn " +
                        "Settings → Performance → Rendering → Hardware acceleration back on and " +
                        "restart J Code; until then the device cannot draw.",
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
                CompactOutlinedButton(
                    text = "Run once, without installing",
                    enabled = readable,
                    onClick = onRunHere,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ManagerNoticeCard(
                title = "What a guest gives up",
                message = "The app runs without an activity of its own, so it cannot raise the soft " +
                    "keyboard itself — use the keyboard button. Its own Lifecycle is driven directly, " +
                    "but callbacks it registers on the activity with registerActivityLifecycleCallbacks " +
                    "miss the pre/post start, resume and stop steps, because Android 13 puts that list " +
                    "out of reach.",
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
