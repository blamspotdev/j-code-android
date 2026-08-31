package dev.blamspot.jcode.design

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

/**
 * A settings row that performs an action rather than holding a value, so it has nothing to reset.
 *
 * Shared rather than private to the settings screen: an extension may declare one of these too — a
 * `type: action` setting is a button on that extension's own settings card — and two rows that look
 * almost the same in two places is how a settings screen stops looking like one screen.
 */
@Composable
fun SettingsActionRow(
    label: String,
    supporting: String?,
    buttonLabel: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Space.ms),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            supporting?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        CompactFilledButton(
            text = buttonLabel,
            onClick = onClick,
            enabled = enabled,
            busy = busy,
        )
    }
}
