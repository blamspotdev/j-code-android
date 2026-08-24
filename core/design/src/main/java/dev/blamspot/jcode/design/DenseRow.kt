package dev.blamspot.jcode.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun DenseRow(
    modifier: Modifier = Modifier,
    height: Dp = when (LocalDensityMode.current) {
        DensityMode.Compact -> 28.dp
        DensityMode.Comfortable -> 40.dp
    },
    leading: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = height)
            .padding(horizontal = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        if (leading != null) {
            Box(contentAlignment = Alignment.Center) { leading() }
        }

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            content()
        }

        if (trailing != null) {
            Box(contentAlignment = Alignment.CenterEnd) { trailing() }
        }
    }
}
