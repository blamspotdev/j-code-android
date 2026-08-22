package dev.blamspot.jcode.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** Compact filter pill for manager panels — matches the tab-pill styling, denser than M3 FilterChip. */
@Composable
fun ManagerFilterChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(Radius.lg),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
        },
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.lg))
            .clickable(onClick = onClick)
            .handCursor(),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Space.ms, vertical = Space.s),
        )
    }
}
