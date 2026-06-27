package dev.jcode.feature.editor.pane

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.jcode.core.editor.EditorView

/**
 * Editor pane composable that hosts a tab strip and the active EditorView.
 */
@Composable
fun EditorPane(
    group: EditorGroup,
    modifier: Modifier = Modifier,
    onTabSelected: (String) -> Unit = {},
    onTabClosed: (String) -> Unit = {},
    onOpenFile: () -> Unit = {},
) {
    Column(modifier = modifier.clipToBounds()) {
        // Tab strip — explicit fixed height so it's never compressed
        TabStrip(
            group = group,
            onTabSelected = onTabSelected,
            onTabClosed = onTabClosed,
            onOpenFile = onOpenFile,
        )

        // Active editor view
        val activeTab = group.activeTab
        if (activeTab != null) {
            Box(
                modifier = Modifier
                    .weight(1f, fill = true)
                    .clipToBounds(),
            ) {
                EditorViewHost(
                    editorState = activeTab.editorState,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            // Empty state
            Box(
                modifier = Modifier.weight(1f, fill = true),
                contentAlignment = Alignment.Center,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "No file open",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Open a file to start editing",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Horizontal tab strip for editor tabs.
 */
@Composable
private fun TabStrip(
    group: EditorGroup,
    onTabSelected: (String) -> Unit,
    onTabClosed: (String) -> Unit,
    onOpenFile: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .height(36.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            group.tabs.forEach { tab ->
                TabItem(
                    tab = tab,
                    isActive = tab.id == group.activeTabId,
                    onSelected = { onTabSelected(tab.id) },
                    onClosed = { onTabClosed(tab.id) },
                )
            }

            // Open file button
            IconButton(
                onClick = onOpenFile,
                modifier = Modifier
                    .width(36.dp)
                    .height(36.dp),
            ) {
                Text("+", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * Individual tab item.
 */
@Composable
private fun TabItem(
    tab: EditorTab,
    isActive: Boolean,
    onSelected: () -> Unit,
    onClosed: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onSelected)
            .background(
                if (isActive) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface
            )
            .height(36.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Dirty indicator
        if (tab.isDirty) {
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .height(8.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }

        Text(
            text = tab.title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )

        IconButton(
            onClick = onClosed,
            modifier = Modifier
                .width(20.dp)
                .height(20.dp),
        ) {
            Text(
                text = "×",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Hosts the custom EditorView inside Compose via AndroidView.
 */
@Composable
fun EditorViewHost(
    editorState: dev.jcode.core.editor.EditorState,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current

    AndroidView(
        factory = { context ->
            EditorView(context).apply {
                attach(editorState)
            }
        },
        modifier = modifier.clipToBounds(),
        update = { view ->
            // Ensure the view is attached to the current state
            view.attach(editorState)
        },
        onRelease = { view ->
            view.detach()
        },
    )

    DisposableEffect(editorState) {
        onDispose {
            // EditorState lifecycle managed by EditorTab
        }
    }
}
