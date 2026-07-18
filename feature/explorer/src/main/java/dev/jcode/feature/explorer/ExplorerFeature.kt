package dev.jcode.feature.explorer

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.jcode.fs.Fs
import dev.jcode.fs.FsPath
import dev.jcode.fs.Project
import dev.jcode.fs.Workspace

/**
 * Explorer feature entry point.
 *
 * Provides the file/folder explorer with tree and list views, file operations,
 * and drag-and-drop scaffolding.
 */
object ExplorerFeature {

    /**
     * Main explorer composable for a given project.
     *
     * @param workspace current workspace
     * @param project the project to explore
     * @param fs filesystem implementation
     * @param context android context for SAF operations
     * @param onFileSelected callback when a file is tapped
     * @param onSnackbar callback for showing user messages
     */
    @Composable
    fun Content(
        workspace: Workspace?,
        project: Project,
        fs: Fs,
        context: Context,
        modifier: Modifier = Modifier,
        viewMode: ExplorerViewMode = ExplorerViewMode.Tree,
        hiddenPatterns: List<String> = emptyList(),
        greyOutExcluded: Boolean = true,
        onFileSelected: ((dev.jcode.fs.FsNode) -> Unit)? = null,
        onSnackbar: ((String) -> Unit)? = null,
    ) {
        ExplorerView(
            workspace = workspace,
            project = project,
            fs = fs,
            context = context,
            modifier = modifier,
            viewMode = viewMode,
            hiddenPatterns = hiddenPatterns,
            greyOutExcluded = greyOutExcluded,
            onFileSelected = onFileSelected,
            onSnackbar = onSnackbar,
        )
    }
}
