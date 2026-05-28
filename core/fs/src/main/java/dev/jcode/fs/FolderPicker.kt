package dev.jcode.fs

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

@Composable
fun rememberOpenFolderLauncher(
    workspaceManager: WorkspaceManager,
    onSafWarning: (String) -> Unit = {},
): ActivityResultLauncher<Uri?> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    return rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            scope.launch {
                workspaceManager.addProject(FsPath.Saf(uri))
                onSafWarning("SAF projects can't be bind-mounted into the distro; use manual sync for toolchain access.")
            }
        }
    }
}
