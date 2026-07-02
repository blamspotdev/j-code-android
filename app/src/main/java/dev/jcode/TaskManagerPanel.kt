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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    var processes by remember { mutableStateOf<List<LinuxProc>>(emptyList()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Live refresh while the tab is visible: process list + the idle clocks, every 2 seconds.
    LaunchedEffect(Unit) {
        while (isActive) {
            processes = withContext(Dispatchers.IO) { listOwnProcesses() }
            now = System.currentTimeMillis()
            delay(2_000L)
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
