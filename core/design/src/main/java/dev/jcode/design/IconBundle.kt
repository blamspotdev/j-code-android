package dev.jcode.design

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.MenuOpen
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.BuildCircle
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DatasetLinked
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Source
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Semantic icon slots used across the app. Call sites reference these instead of `Icons.*` directly,
 * so the whole icon set is swappable by providing a different [IconBundle]. New slots are added here
 * as the UI grows.
 */
enum class JCodeIcon {
    Run, Stop, Terminal,
    Files, Folder, NewFolder, NewFile,
    Sdk, Scm, Settings, Search, Extensions, Destinations, Code,
    Add, Close, Refresh, Paste, Collapse, MoreVert,
    Output, Logs, Problems, Radar,
    DropDown, ChevronDown, ChevronRight, ArrowUp, MenuToggle, Help,
}

/**
 * A swappable icon set. [overrides] need only cover the slots a bundle wants to restyle; anything
 * missing resolves through [fallback] (ultimately the built-in default), so a custom vector pack can
 * ship just its hero icons and inherit the rest. This is also the shape a disk/asset icon-bundle
 * extension would map onto, so external packs can be added later without touching call sites.
 */
@Immutable
class IconBundle(
    val id: String,
    val name: String,
    val description: String,
    val author: String = "J Code",
    private val overrides: Map<JCodeIcon, ImageVector>,
    private val fallback: IconBundle? = null,
) {
    operator fun get(icon: JCodeIcon): ImageVector =
        overrides[icon] ?: fallback?.get(icon) ?: Icons.Rounded.Circle
}

/** Built-in Material icon set, unified to the Rounded family for consistency. Always complete. */
val defaultIconBundle = IconBundle(
    id = "material",
    name = "Material Rounded",
    description = "The built-in Material rounded icon set.",
    overrides = mapOf(
        JCodeIcon.Run to Icons.Rounded.PlayArrow,
        JCodeIcon.Stop to Icons.Rounded.Stop,
        JCodeIcon.Terminal to Icons.Rounded.Terminal,
        JCodeIcon.Files to Icons.Rounded.FolderOpen,
        JCodeIcon.Folder to Icons.Rounded.Folder,
        JCodeIcon.NewFolder to Icons.Rounded.CreateNewFolder,
        JCodeIcon.NewFile to Icons.Rounded.NoteAdd,
        JCodeIcon.Sdk to Icons.Rounded.BuildCircle,
        JCodeIcon.Scm to Icons.Rounded.Source,
        JCodeIcon.Settings to Icons.Rounded.Settings,
        JCodeIcon.Search to Icons.Rounded.Search,
        JCodeIcon.Extensions to Icons.Rounded.Extension,
        JCodeIcon.Destinations to Icons.Rounded.DatasetLinked,
        JCodeIcon.Code to Icons.Rounded.Code,
        JCodeIcon.Add to Icons.Rounded.Add,
        JCodeIcon.Close to Icons.Rounded.Close,
        JCodeIcon.Refresh to Icons.Rounded.Refresh,
        JCodeIcon.Paste to Icons.Rounded.ContentPaste,
        JCodeIcon.Collapse to Icons.Rounded.UnfoldLess,
        JCodeIcon.MoreVert to Icons.Rounded.MoreVert,
        JCodeIcon.Output to Icons.Rounded.Description,
        JCodeIcon.Logs to Icons.AutoMirrored.Rounded.Article,
        JCodeIcon.Problems to Icons.Rounded.SyncProblem,
        JCodeIcon.Radar to Icons.Rounded.Radar,
        JCodeIcon.DropDown to Icons.Rounded.ArrowDropDown,
        JCodeIcon.ChevronDown to Icons.Rounded.KeyboardArrowDown,
        JCodeIcon.ChevronRight to Icons.AutoMirrored.Rounded.KeyboardArrowRight,
        JCodeIcon.ArrowUp to Icons.Rounded.ArrowUpward,
        JCodeIcon.MenuToggle to Icons.AutoMirrored.Rounded.MenuOpen,
        JCodeIcon.Help to Icons.AutoMirrored.Rounded.HelpOutline,
    ),
)

object IconBundleRegistry {
    // Custom vector packs are appended here (see CustomIconBundle.kt).
    val builtIns: List<IconBundle> = listOf(defaultIconBundle) + customIconBundles

    val default: IconBundle get() = defaultIconBundle

    fun byId(id: String?): IconBundle = builtIns.firstOrNull { it.id == id } ?: default
}

val LocalIconBundle = staticCompositionLocalOf { defaultIconBundle }

/** Resolve a semantic icon through the active [IconBundle]. */
@Composable
fun jcIcon(icon: JCodeIcon): ImageVector = LocalIconBundle.current[icon]
