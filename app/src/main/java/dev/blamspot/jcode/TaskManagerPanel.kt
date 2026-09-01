package dev.blamspot.jcode

import android.system.Os
import android.system.OsConstants
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.blamspot.jcode.core.debug.DebugState
import dev.blamspot.jcode.core.distro.AppProcesses
import dev.blamspot.jcode.core.term.TerminalSessionManager
import dev.blamspot.jcode.design.IconSize
import dev.blamspot.jcode.design.JCodeIcon
import dev.blamspot.jcode.design.JcTooltip
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.jcIcon
import dev.blamspot.jcode.vdevice.VirtualDeviceBridge
import dev.blamspot.jcode.workbench.LocalDebugSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/** Host device RAM (the Android device, not the proot guest), read from /proc/meminfo. */
private data class HostMemory(val availKb: Long, val totalKb: Long)

/**
 * Task Manager access to background extensions (persistent WebView hosts + their service.start
 * servers), provided by [JCodeApp] via CompositionLocal so the Tasks panel can list and stop them
 * without threading the ViewModel through the shell. [snapshot] is polled on the panel's refresh tick.
 */
internal data class TaskManagerBackgroundActions(
    val snapshot: (deviceRunning: Boolean) -> List<MainViewModel.BackgroundExtensionInfo> =
        { _ -> emptyList() },
    val onStop: (String) -> Unit = {},
    val onStart: (String) -> Unit = {},
)

internal val LocalTaskManagerBackgroundActions = compositionLocalOf { TaskManagerBackgroundActions() }

/**
 * The right-drawer "Tasks" tab — a task manager for everything the IDE is running:
 * sessions (terminals with their foreground program + idle time, Build & Run, the debug session)
 * with stop controls, and the raw app-uid Linux process list from /proc (name, PID, memory) with
 * per-process kill. proot's --kill-on-exit makes killing a tree's root reap its descendants.
 */
@Composable
internal fun TaskManagerSidebarContent(
    terminalSessionIds: List<String>,
    terminalSessionFor: (String) -> TerminalSessionManager.Session?,
    terminalTitleFor: (String) -> String?,
    onCloseTerminal: (String) -> Unit,
    runningProjectName: String?,
    runInProgress: Boolean,
    onStopRun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val debug = LocalDebugSession.current
    val backgroundActions = LocalTaskManagerBackgroundActions.current
    // The virtual device's process, named the way /proc reports it — see the kill below.
    val guestProcess = "${LocalContext.current.packageName}:guest"
    var processes by remember { mutableStateOf<List<AppProcesses.Process>>(emptyList()) }
    var backgroundExtensions by remember { mutableStateOf<List<MainViewModel.BackgroundExtensionInfo>>(emptyList()) }
    var hostMemory by remember { mutableStateOf<HostMemory?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Live refresh only while the tab is actually watched: leaving the tab (or closing the drawer)
    // cancels this effect with the composition, and repeatOnLifecycle pauses it while the app is
    // backgrounded with the tab still open — no /proc polling unless the panel is on screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val (procs, mem) = withContext(Dispatchers.IO) { AppProcesses.list() to readHostMemory() }
                processes = procs
                hostMemory = mem
                // The device's own `:guest` process is the honest answer to "is a device running":
                // the page that draws it is disposed by switching to this very tab, and a
                // device at its home screen has no process of its own to find.
                backgroundExtensions = backgroundActions.snapshot(
                    procs.any { it.name.endsWith(":guest") },
                )
                now = System.currentTimeMillis()
                delay(2_000L)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.sm, vertical = Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        val debugActive = debug.state != DebugState.DISCONNECTED && debug.state != DebugState.TERMINATED
        val hasSessions = terminalSessionIds.isNotEmpty() || runningProjectName != null || debugActive

        hostMemory?.let { mem ->
            TaskSectionLabel("Device")
            HostMemoryRow(mem)
        }

        TaskSectionLabel("Sessions")
        if (!hasSessions) {
            Text(
                "Nothing running — terminals, Build & Run, and debug sessions appear here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Space.xs),
            )
        }
        terminalSessionIds.forEach { id ->
            val session = terminalSessionFor(id) ?: return@forEach
            val foreground = session.foreground
            val idleMin = ((now - session.lastActivityAt) / 60_000L).coerceAtLeast(0)
            TaskRow(
                title = "Terminal · ${terminalTitleFor(id) ?: session.label}",
                subtitle = foreground?.let { "running $it" }
                    ?: if (idleMin < 1) "idle at prompt" else "idle at prompt · $idleMin min",
                emphasized = foreground != null,
                actionDescription = "Close terminal",
                onStop = { onCloseTerminal(id) },
            )
        }
        if (runningProjectName != null) {
            TaskRow(
                title = "Build & Run · $runningProjectName",
                subtitle = if (runInProgress) "building/starting" else "running",
                emphasized = true,
                actionDescription = "Stop run",
                onStop = onStopRun,
            )
        }
        if (debugActive) {
            TaskRow(
                title = "Debug · ${debug.debugTargetName ?: "session"}",
                subtitle = when (debug.state) {
                    DebugState.STOPPED -> "paused at breakpoint"
                    DebugState.RUNNING -> "running"
                    else -> debug.state.name.lowercase()
                },
                emphasized = true,
                actionDescription = "Stop debugging",
                onStop = debug.onStop,
            )
        }

        if (backgroundExtensions.isNotEmpty()) {
            TaskSectionLabel("Background extensions")
            backgroundExtensions.forEach { info ->
                BackgroundExtensionRow(
                    info = info,
                    onStop = { backgroundActions.onStop(info.id) },
                    onStart = { backgroundActions.onStart(info.id) },
                )
            }
        }

        val totalMb = processes.sumOf { it.rssKb } / 1024
        TaskSectionLabel("Processes · ${processes.size} · $totalMb MB")
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.26f),
        ) {
            Column {
                processes.forEachIndexed { index, proc ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                    ProcessRow(
                        proc = proc,
                        isSelf = proc.pid == android.os.Process.myPid(),
                        onKill = {
                            // Stopping `:guest` from here is turning the virtual device off, and the
                            // device has a door. A bare SIGTERM reaches a bound session as a death:
                            // the sandbox tab reports "Could not run the app on this device" for
                            // something that was done on purpose, and the container writes an exit
                            // reason for a process nobody lost. Measured, on this row.
                            if (proc.name == guestProcess) {
                                VirtualDeviceBridge.shutdown()
                            } else {
                                runCatching { Os.kill(proc.pid, OsConstants.SIGTERM) }
                            }
                            processes = processes.filterNot { it.pid == proc.pid }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Space.xs, top = Space.xs),
    )
}

@Composable
private fun TaskRow(
    title: String,
    subtitle: String,
    emphasized: Boolean,
    actionDescription: String,
    onStop: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.26f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Space.ms, top = Space.xxs, bottom = Space.xxs, end = Space.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            JcTooltip(actionDescription) {
                IconButton(onClick = onStop) {
                    Icon(
                        painter = jcIcon(JCodeIcon.Stop),
                        contentDescription = actionDescription,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(IconSize.lg),
                    )
                }
            }
        }
    }
}

/** A background extension (persistent host and/or service.start servers). Stop reaps its servers and
 *  tears down its host; a stopped SCM host shows Start (it stays down until then). */
@Composable
private fun BackgroundExtensionRow(
    info: MainViewModel.BackgroundExtensionInfo,
    onStop: () -> Unit,
    onStart: () -> Unit,
) {
    val subtitle = if (info.suspended) {
        "stopped"
    } else {
        buildList {
            if (info.hasHost) add("background host")
            if (info.serviceCount > 0) add("${info.serviceCount} server${if (info.serviceCount > 1) "s" else ""}")
            if (info.hasDevice) add("virtual device")
            if (info.hasAdb) add("device adb")
        }.joinToString(" · ").ifEmpty { "running" }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.26f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Space.ms, top = Space.xxs, bottom = Space.xxs, end = Space.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Extension · ${info.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (info.suspended) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (info.suspended) {
                JcTooltip("Start extension") {
                    IconButton(onClick = onStart) {
                        Icon(
                            painter = jcIcon(JCodeIcon.Run),
                            contentDescription = "Start extension",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(IconSize.lg),
                        )
                    }
                }
            } else {
                JcTooltip("Stop extension") {
                    IconButton(onClick = onStop) {
                        Icon(
                            painter = jcIcon(JCodeIcon.Stop),
                            contentDescription = "Stop extension",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(IconSize.lg),
                        )
                    }
                }
            }
        }
    }
}

/** Host device RAM overview: available (free) memory prominent, with a used-memory gauge. */
@Composable
private fun HostMemoryRow(mem: HostMemory) {
    val usedKb = (mem.totalKb - mem.availKb).coerceAtLeast(0)
    val usedFraction = if (mem.totalKb > 0) (usedKb.toFloat() / mem.totalKb).coerceIn(0f, 1f) else 0f
    val availGb = mem.availKb / 1_048_576.0
    val totalGb = mem.totalKb / 1_048_576.0
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.26f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.ms, vertical = Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Device memory",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "%.1f GB free · %.1f GB".format(availGb, totalGb),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                progress = { usedFraction },
                modifier = Modifier.fillMaxWidth(),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

@Composable
private fun ProcessRow(proc: AppProcesses.Process, isSelf: Boolean, onKill: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Space.ms, top = Space.hairline, bottom = Space.hairline, end = Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(
            text = if (isSelf) "${proc.name} (app)" else proc.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "pid ${proc.pid}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "${proc.rssKb / 1024} MB",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (isSelf) {
            // Killing our own process would just crash the IDE; keep the slot for alignment.
            IconButton(onClick = {}, enabled = false) {
                Icon(
                    painter = jcIcon(JCodeIcon.Stop),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                    modifier = Modifier.size(IconSize.md),
                )
            }
        } else {
            JcTooltip("Kill process") {
                IconButton(onClick = onKill) {
                    Icon(
                        painter = jcIcon(JCodeIcon.Stop),
                        contentDescription = "Kill process",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(IconSize.md),
                    )
                }
            }
        }
    }
}

/**
 * Host device RAM from /proc/meminfo — MemTotal and MemAvailable (kB). This is the Android device's
 * memory, not the proot guest's. /proc/meminfo is a global, always-readable file (unaffected by the
 * per-pid hidepid mount that limits [AppProcesses.list]). Returns null if it can't be parsed.
 */
private fun readHostMemory(): HostMemory? = runCatching {
    var total = 0L
    var avail = 0L
    File("/proc/meminfo").forEachLine { line ->
        when {
            line.startsWith("MemTotal:") ->
                total = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: total
            line.startsWith("MemAvailable:") ->
                avail = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: avail
        }
    }
    if (total > 0L) HostMemory(availKb = avail, totalKb = total) else null
}.getOrNull()
