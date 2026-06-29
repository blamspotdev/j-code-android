package dev.jcode.core.editor.completion

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * Completion window composable that shows completion items in a popup.
 * Anchored to the caret position via CursorAnchorInfo coordinates.
 */
@Composable
fun CompletionWindow(
    context: CompletionContext?,
    anchorX: Float,
    anchorY: Float,
    onDismiss: () -> Unit,
    onSelect: (CompletionItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (context == null || context.items.isEmpty()) return

    var selectedIndex by remember { mutableIntStateOf(0) }
    var filterText by remember { mutableStateOf("") }

    val filteredItems = remember(context.items, filterText) {
        if (filterText.isEmpty()) {
            context.items.sortedBy { it.sortText }
        } else {
            context.items
                .filter { it.filterText.contains(filterText, ignoreCase = true) }
                .sortedByDescending { it.filterText.startsWith(filterText, ignoreCase = true) }
                .sortedBy { it.sortText }
        }
    }

    LaunchedEffect(filteredItems) {
        if (selectedIndex >= filteredItems.size) {
            selectedIndex = 0
        }
    }

    // Anchor the popup just below the caret/word (anchorX, anchorY are pixels relative to the editor
    // view, which the popup's parent overlays), flipping above when there isn't room below.
    val positionProvider = remember(anchorX, anchorY) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val x = (anchorBounds.left + anchorX.toInt())
                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val below = anchorBounds.top + anchorY.toInt()
                val y = if (below + popupContentSize.height <= windowSize.height) {
                    below
                } else {
                    (below - popupContentSize.height).coerceAtLeast(0)
                }
                return IntOffset(x, y)
            }
        }
    }

    Popup(
        popupPositionProvider = positionProvider,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
        onDismissRequest = onDismiss,
    ) {
        Surface(
            modifier = modifier
                .widthIn(min = 250.dp, max = 450.dp)
                .heightIn(max = 300.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
        ) {
            val listState = rememberLazyListState()

            LaunchedEffect(selectedIndex) {
                listState.animateScrollToItem(selectedIndex)
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(
                    items = filteredItems,
                    key = { "${it.source}:${it.label}:${it.kind}" },
                ) { item ->
                    val index = filteredItems.indexOf(item)
                    CompletionItemRow(
                        item = item,
                        isSelected = index == selectedIndex,
                        onClick = { onSelect(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CompletionItemRow(
    item: CompletionItem,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Kind icon/badge
        Text(
            text = item.kind.displayName.take(2),
            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.primary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .width(24.dp)
                .padding(end = 4.dp),
        )

        // Label
        Text(
            text = item.label,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        // Detail
        if (item.detail != null) {
            Text(
                text = item.detail,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
