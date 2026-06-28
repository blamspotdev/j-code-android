package dev.jcode.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/** One action in a [CompactContextMenu]. */
data class ContextAction(
    val icon: JCodeIcon,
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * The app's single compact context menu, used by every long-press menu so they stay consistent.
 * [quickActions] render as a top row of icon-only buttons (common verbs: copy/cut/paste/delete/close);
 * [listActions] render below as compact icon+label rows. Both lists are optional. Picking any action
 * dismisses the menu first. [offset] positions the menu (e.g. at a touch point) when it isn't anchored.
 */
@Composable
fun CompactContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    quickActions: List<ContextAction> = emptyList(),
    listActions: List<ContextAction> = emptyList(),
    offset: DpOffset = DpOffset(0.dp, 0.dp),
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, offset = offset) {
        if (quickActions.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                quickActions.forEach { action ->
                    JcTooltip(action.label) {
                        IconButton(
                            onClick = { onDismissRequest(); action.onClick() },
                            modifier = Modifier.size(38.dp),
                        ) {
                            Icon(
                                imageVector = jcIcon(action.icon),
                                contentDescription = action.label,
                                tint = if (action.destructive) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
            if (listActions.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
            }
        }
        listActions.forEach { action ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDismissRequest(); action.onClick() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = jcIcon(action.icon),
                    contentDescription = null,
                    tint = if (action.destructive) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = action.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (action.destructive) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
