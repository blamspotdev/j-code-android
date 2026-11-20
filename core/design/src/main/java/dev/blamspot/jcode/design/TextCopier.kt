package dev.blamspot.jcode.design

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import kotlinx.coroutines.launch

/**
 * Copies plain text to the system clipboard. Compose's [LocalClipboard] is suspend-only, and every
 * caller here copies a string from a click, so this hands back a plain function bound to the
 * composition's scope.
 */
@Composable
fun rememberTextCopier(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, scope) {
        { text -> scope.launch { clipboard.setClipEntry(ClipData.newPlainText("JCode", text).toClipEntry()) } }
    }
}
