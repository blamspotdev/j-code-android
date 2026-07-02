package dev.jcode

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jcode.core.debug.DebugState
import dev.jcode.design.JCodeIcon
import dev.jcode.design.JcTooltip
import dev.jcode.design.jcIcon
import dev.jcode.workbench.DebugSessionUi

/**
 * The live debug-session section of the Run/Debug panel: a launch button when idle, and a
 * transport toolbar + call-stack + variables + console once a session is running. Reads its state
 * from [dev.jcode.workbench.LocalDebugSession]; the caller passes the resolved [ui].
 */
@Composable
internal fun DebugSessionPanel(ui: DebugSessionUi, modifier: Modifier = Modifier) {
    val active = ui.state != DebugState.DISCONNECTED && ui.state != DebugState.TERMINATED
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.padding(start = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(jcIcon(JCodeIcon.Debug), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("Debug", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (active) DebugStateChip(ui.state)
        }

        if (!active) {
            DebugLaunchRow(ui)
        } else {
            DebugToolbar(ui)
            if (ui.callStack.isNotEmpty()) CallStackList(ui)
            if (ui.variables.isNotEmpty()) VariablesList(ui)
            // Console output lives in the right-drawer "Debug" tab (alongside Terminal/Output), not here.
        }
    }
}

@Composable
private fun DebugLaunchRow(ui: DebugSessionUi) {
    val target = ui.debugTargetName
    when {
        target != null && ui.canDebug -> {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f),
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Debug $target",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    JcTooltip("Start debugging") {
                        IconButton(onClick = ui.onDebug) {
                            Icon(
                                imageVector = jcIcon(JCodeIcon.Debug),
                                contentDescription = "Start debugging",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }
        }
        target != null -> Text(
            "No debug engine installed for $target. Install one in Debug Engines (DBG).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Text(
            "Open a source file to debug it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DebugToolbar(ui: DebugSessionUi) {
    val stopped = ui.state == DebugState.STOPPED
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        DebugAction(JCodeIcon.Continue, "Continue", enabled = stopped, tint = MaterialTheme.colorScheme.primary, onClick = ui.onContinue)
        DebugAction(JCodeIcon.StepOver, "Step over", enabled = stopped, onClick = ui.onStepOver)
        DebugAction(JCodeIcon.StepInto, "Step into", enabled = stopped, onClick = ui.onStepInto)
        DebugAction(JCodeIcon.StepOut, "Step out", enabled = stopped, onClick = ui.onStepOut)
        Spacer(Modifier.weight(1f))
        DebugAction(JCodeIcon.Stop, "Stop", enabled = true, tint = MaterialTheme.colorScheme.error, onClick = ui.onStop)
    }
}

@Composable
private fun DebugAction(icon: JCodeIcon, label: String, enabled: Boolean, tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant, onClick: () -> Unit) {
    JcTooltip(label) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(
                imageVector = jcIcon(icon),
                contentDescription = label,
                tint = if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
    )
}

@Composable
private fun CallStackList(ui: DebugSessionUi) {
    SectionLabel("Call stack")
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp).verticalScroll(rememberScrollState()),
    ) {
        ui.callStack.forEach { frame ->
            val where = frame.sourcePath?.substringAfterLast('/')?.let { "$it:${frame.line}" } ?: ""
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = frame.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (where.isNotEmpty()) {
                    Text(
                        text = where,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun VariablesList(ui: DebugSessionUi) {
    SectionLabel("Variables")
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
    ) {
        ui.variables.forEach { row ->
            if (row.depth == 0) {
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 1.dp),
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 1.dp, bottom = 1.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(0.4f, fill = false),
                    )
                    Text(
                        text = row.value,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Scrolling monospace console lines — shared by the Debug side-panel and the right-drawer tab. */
@Composable
internal fun DebugConsoleLines(
    lines: List<String>,
    maxHeight: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Unspecified,
    modifier: Modifier = Modifier,
) {
    val heightModifier = if (maxHeight.isSpecified) Modifier.heightIn(max = maxHeight) else Modifier
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(heightModifier)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        lines.takeLast(200).forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
    }
}

/**
 * The right-drawer "Debug" tab: live session state + transport controls + the full-height console.
 * Reads [dev.jcode.workbench.LocalDebugSession] so it mirrors the Run/Debug side panel's session.
 */
@Composable
internal fun DebugConsoleSidebarContent(modifier: Modifier = Modifier) {
    val ui = dev.jcode.workbench.LocalDebugSession.current
    val active = ui.state != DebugState.DISCONNECTED && ui.state != DebugState.TERMINATED
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
        if (!active && ui.output.isEmpty()) {
            Text(
                "Start a debug session to see its console output here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(6.dp),
            )
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = ui.debugTargetName?.let { "Debug: $it" } ?: "Debug",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            DebugStateChip(ui.state)
        }
        if (active) DebugToolbar(ui)
        DebugConsoleLines(ui.output, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DebugStateChip(state: DebugState) {
    val (label, active) = when (state) {
        DebugState.STARTING, DebugState.INITIALIZING -> "Starting…" to false
        DebugState.RUNNING -> "Running" to true
        DebugState.STOPPED -> "Paused" to true
        DebugState.ERROR -> "Error" to false
        else -> state.name.lowercase() to false
    }
    Surface(
        color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
