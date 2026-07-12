package dev.jcode.workbench

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jcode.design.JcTooltip
import dev.jcode.design.jcIcon

/**
 * Leaf chrome buttons for the workbench (activity rail, top-bar quick actions, sidebar tool row).
 * Stateless and param-driven — peeled out of JCodeShell so the shell composable stays small.
 */

@Composable
internal fun WorkbenchIconActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    active: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    shimmer: Boolean = false,
    badge: Boolean = false,
) {
    val containerColor = if (active) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
    }
    val contentColor = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    // Pulse the icon while a background terminal is busy (a foreground process is running). The
    // transition must exist ONLY while [shimmer] is true: an infinite transition resumes a
    // coroutine + writes snapshot state on EVERY vsync for as long as it is composed, even if
    // nobody reads its value — an unconditional one here (× every visible button) kept the main
    // thread permanently awake (~12% of a core measured idle). The value is read in the draw
    // phase (graphicsLayer), so the pulsing button repaints without recomposing either.
    val shimmerModifier = if (shimmer) {
        val shimmerAlpha by rememberInfiniteTransition(label = "shimmer").animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "shimmerAlpha",
        )
        Modifier.graphicsLayer { alpha = shimmerAlpha }
    } else {
        Modifier
    }

    JcTooltip(contentDescription) {
        Box {
        Surface(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .then(
                    if (onLongClick != null) {
                        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    } else {
                        Modifier.clickable(onClick = onClick)
                    }
                ),
            shape = RoundedCornerShape(10.dp),
            color = containerColor,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(16.dp).then(shimmerModifier),
                    tint = contentColor,
                )
            }
        }
            if (badge) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

@Composable
internal fun WorkbenchActionButton(
    text: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    val containerColor = if (active) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
    }
    val contentColor = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
    ) {
        Box(
            modifier = Modifier
                .height(32.dp)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            )
        }
    }
}

@Composable
internal fun SidebarToolButton(
    tool: WorkbenchTool,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = jcIcon(tool.icon),
                contentDescription = tool.label,
                modifier = Modifier.size(14.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = tool.compactLabel,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
