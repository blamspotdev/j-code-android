package dev.jcode.webengine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.jcode.design.JCodeIcon
import dev.jcode.design.jcIcon

/**
 * What a web surface shows when the Web Engine extension isn't installed.
 *
 * There is intentionally no "use the system WebView instead" escape hatch here: the system
 * engine's version is whatever the device shipped, which on the devices JCode targets can be
 * years stale — pages break silently and the user blames JCode. One engine, ours, or an honest
 * prompt.
 */
@Composable
fun WebEnginePlaceholder(
    surface: String,
    modifier: Modifier = Modifier,
    onOpenExtensions: (() -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(24.dp).widthIn(max = 420.dp),
        ) {
            Icon(
                imageVector = jcIcon(JCodeIcon.Browser),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
            Text(
                text = "Web Engine not installed",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "The $surface runs on JCode's own web engine, delivered by the " +
                    "Web Engine extension so the base app stays small. Install it from " +
                    "Extensions to use the $surface.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (onOpenExtensions != null) {
                Button(onClick = onOpenExtensions) { Text("Open Extensions") }
            }
        }
    }
}
