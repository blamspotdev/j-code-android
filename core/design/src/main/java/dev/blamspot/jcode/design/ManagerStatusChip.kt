package dev.blamspot.jcode.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Compact status chip used in the manager list rows and detail headers. */
@Composable
fun ManagerStatusChip(
    status: ManagerItemStatus,
    checking: Boolean = false,
    checkingLabel: String = "Checking…",
    modifier: Modifier = Modifier,
    /** Show a small progress ring alongside the label while [checking] (detail header only). */
    spinner: Boolean = false,
) {
    val (text, active) = when {
        checking -> checkingLabel to false
        status == ManagerItemStatus.UpdateAvailable -> "Update available" to true
        status == ManagerItemStatus.Installed -> "Installed" to true
        else -> "Not installed" to false
    }
    Surface(
        modifier = modifier,
        color = if (active) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Space.ms, vertical = Space.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            if (checking && spinner) {
                CircularProgressIndicator(
                    modifier = Modifier.size(11.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
