package dev.jcode

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.jcode.core.debug.DebugState
import dev.jcode.core.term.TerminalSessionManager
import dev.jcode.design.JCodeIcon
import dev.jcode.design.JcTooltip
import dev.jcode.design.jcIcon
import dev.jcode.workbench.LocalDebugSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/** One row of the app-owned Linux process list (proot trees, adapters, the app itself). */
private data class LinuxProc(val pid: Int, val name: String, val rssKb: Long)

/** Host device RAM (the Android device, not the proot guest), read from /proc/meminfo. */
private data class HostMemory(val availKb: Long, val totalKb: Long)

/**
 * Task Manager access to background extensions (persistent WebView hosts + their service.start
 * servers), provided by [JCodeApp] via CompositionLocal so the Tasks panel can list and stop them
 * without threading the ViewModel through the shell. [snapshot] is polled on the panel's refresh tick.
 */
internal data class TaskManagerBackgroundActions(
    val snapshot: () -> List<MainViewModel.BackgroundExtensionInfo> = { emptyList() },
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
    var processes by remember { mutableStateOf<List<LinuxProc>>(emptyList()) }
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
                val (procs, mem) = withContext(Dispatchers.IO) { listOwnProcesses() to readHostMemory() }
                processes = procs
                hostMemory = mem
                backgroundExtensions = backgroundActions.snapshot()
                now = System.currentTimeMillis()
                delay(2_000L)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
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
                modifier = Modifier.padding(horizontal = 4.dp),
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
                            runCatching { Os.kill(proc.pid, OsConstants.SIGTERM) }
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
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
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
                .padding(start = 10.dp, top = 2.dp, bottom = 2.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                        imageVector = jcIcon(JCodeIcon.Stop),
                        contentDescription = actionDescription,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
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
        }.joinToString(" · ").ifEmpty { "running" }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.26f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, top = 2.dp, bottom = 2.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                            imageVector = jcIcon(JCodeIcon.Run),
                            contentDescription = "Start extension",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            } else {
                JcTooltip("Stop extension") {
                    IconButton(onClick = onStop) {
                        Icon(
                            imageVector = jcIcon(JCodeIcon.Stop),
                            contentDescription = "Stop extension",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
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
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
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
private fun ProcessRow(proc: LinuxProc, isSelf: Boolean, onKill: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, top = 1.dp, bottom = 1.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                    imageVector = jcIcon(JCodeIcon.Stop),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            JcTooltip("Kill process") {
                IconButton(onClick = onKill) {
                    Icon(
                        imageVector = jcIcon(JCodeIcon.Stop),
                        contentDescription = "Kill process",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * The app-uid processes visible in /proc — Android mounts /proc with hidepid, so this is exactly
 * the IDE's own tree: the app process plus every proot/guest process it spawned. Sorted by memory.
 */
private fun listOwnProcesses(): List<LinuxProc> {
    val myUid = android.os.Process.myUid()
    val pageKb = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) / 1024 }.getOrDefault(4L)
    return File("/proc").listFiles().orEmpty().mapNotNull { dir ->
        val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
        runCatching {
            if (Os.stat(dir.path).st_uid != myUid) return@mapNotNull null
            // cmdline is NUL-separated argv; the first token is the executable path.
            val cmdline = File(dir, "cmdline").readBytes()
                .toString(Charsets.UTF_8)
                .split('\u0000')
                .firstOrNull { it.isNotBlank() }
            val comm = File(dir, "comm").readText().trim()
            val name = (cmdline?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: comm)
                .ifBlank { "pid $pid" }
            val rssPages = File(dir, "statm").readText().split(' ').getOrNull(1)?.toLongOrNull() ?: 0L
            LinuxProc(pid = pid, name = name, rssKb = rssPages * pageKb)
        }.getOrNull()
    }.sortedByDescending { it.rssKb }
}

/**
 * Host device RAM from /proc/meminfo — MemTotal and MemAvailable (kB). This is the Android device's
 * memory, not the proot guest's. /proc/meminfo is a global, always-readable file (unaffected by the
 * per-pid hidepid mount that limits [listOwnProcesses]). Returns null if it can't be parsed.
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
