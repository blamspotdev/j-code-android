package dev.blamspot.jcode.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A compact, clickable manager list row: name + short description + trailing status chip.
 * Actions live on the detail page, so the drawer stays dense.
 */
@Composable
fun ManagerListRow(
    name: String,
    description: String,
    /** Null for a row that is not an installable thing — a manager the list only opens. Every one of
     *  the three states would be a lie about it, and "Not installed" is the one it would tell. */
    status: ManagerItemStatus?,
    onClick: () -> Unit,
    checking: Boolean = false,
    checkingLabel: String = "Checking…",
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    /** Shown just before the status chip, for a row that needs a word about what it is. */
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .handCursor()
            .padding(horizontal = Space.ms, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.ms),
    ) {
        leading?.invoke()
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.hairline)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Two lines, because one loses the sentence. The status chip beside this column is
                // as wide as its longest word ("Update available"), so a single line left roughly
                // three words of a description before the ellipsis — every row read the same.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
        if (status != null) {
            ManagerStatusChip(status = status, checking = checking, checkingLabel = checkingLabel)
        }
    }
}
