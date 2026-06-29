package dev.jcode.fs

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Launches Android's SAF folder picker and hands the granted folder to [onFolderPicked]. The caller
 * decides what to do next (e.g. detect a missing `.jcode` type and prompt Project vs Workspace).
 */
@Composable
fun rememberOpenFolderLauncher(
    onFolderPicked: (FsPath) -> Unit,
): ActivityResultLauncher<Uri?> {
    val context = LocalContext.current

    return rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            onFolderPicked(FsPath.Saf(uri))
        }
    }
}
