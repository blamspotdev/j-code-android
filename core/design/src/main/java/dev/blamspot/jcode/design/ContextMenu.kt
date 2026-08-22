package dev.blamspot.jcode.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
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
    val enabled: Boolean = true,
    /**
     * A tick at the end of the row, for a row that is a setting rather than a verb.
     *
     * Null for the ordinary case — a verb has no state to show, and an empty checkbox beside "Copy"
     * would invite the reading that copying is switched off. A two-way *choice* is still better said
     * by naming the action ("Request desktop site"); this is for a mode that is simply on or off,
     * where the name has to stay put so you can find it again.
     */
    val checked: Boolean? = null,
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
    /**
     * An optional block above the actions — e.g. the debugger's variable peek. It renders inside the
     * same menu surface so a long-press yields one consistent card rather than a floating popover
     * stacked over the context menu. A divider separates it from the actions below.
     */
    header: (@Composable () -> Unit)? = null,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, offset = offset) {
        if (header != null) {
            header()
            if (quickActions.isNotEmpty() || listActions.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = Space.sm, vertical = Space.xxs),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
            }
        }
        if (quickActions.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(horizontal = Space.xs),
                horizontalArrangement = Arrangement.spacedBy(Space.xxs),
            ) {
                quickActions.forEach { action ->
                    JcTooltip(action.label) {
                        IconButton(
                            onClick = { onDismissRequest(); action.onClick() },
                            enabled = action.enabled,
                            modifier = Modifier.size(38.dp),
                        ) {
                            Icon(
                                imageVector = jcIcon(action.icon),
                                contentDescription = action.label,
                                tint = when {
                                    !action.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    action.destructive -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
            if (listActions.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = Space.sm, vertical = Space.xxs),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
            }
        }
        listActions.forEach { action ->
            val disabledTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (action.enabled) {
                            Modifier.clickable { onDismissRequest(); action.onClick() }.handCursor()
                        }
                        else Modifier
                    )
                    .padding(horizontal = Space.md, vertical = Space.sm),
                horizontalArrangement = Arrangement.spacedBy(Space.ms),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = jcIcon(action.icon),
                    contentDescription = null,
                    tint = when {
                        !action.enabled -> disabledTint
                        action.destructive -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = action.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        !action.enabled -> disabledTint
                        action.destructive -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    modifier = if (action.checked != null) Modifier.weight(1f) else Modifier,
                )
                if (action.checked == true) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(IconSize.sm),
                    )
                }
            }
        }
    }
}
