package dev.jcode.design

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.MenuOpen
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.BuildCircle
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DatasetLinked
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.FormatAlignLeft
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.IntegrationInstructions
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsBackupRestore
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
    Sdk, Lsp, Scm, Settings, Search, Extensions, Destinations, Code, Database, Vm,
    Add, Close, Refresh, Paste, Collapse, MoreVert, Save, Undo, Redo, Discard,
    Continue, Rerun, StepInto, StepOver, StepOut,
    Output, Logs, Problems, Radar, Debug, Tasks, Chat, Cursor,
    DropDown, ChevronDown, ChevronRight, ArrowUp, MenuToggle, Help,
    Copy, Cut, Delete, Open, Rename, SelectAll, Clear, Definition, References, Format,
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
        JCodeIcon.Lsp to Icons.Rounded.IntegrationInstructions,
        JCodeIcon.Debug to Icons.Rounded.BugReport,
        JCodeIcon.Scm to Icons.Rounded.Source,
        JCodeIcon.Settings to Icons.Rounded.Settings,
        JCodeIcon.Search to Icons.Rounded.Search,
        JCodeIcon.Extensions to Icons.Rounded.Extension,
        JCodeIcon.Destinations to Icons.Rounded.DatasetLinked,
        JCodeIcon.Database to Icons.Rounded.DatasetLinked,
        JCodeIcon.Vm to Icons.Rounded.Dns,
        JCodeIcon.Code to Icons.Rounded.Code,
        JCodeIcon.Add to Icons.Rounded.Add,
        JCodeIcon.Close to Icons.Rounded.Close,
        JCodeIcon.Refresh to Icons.Rounded.Refresh,
        JCodeIcon.Paste to Icons.Rounded.ContentPaste,
        JCodeIcon.Save to Icons.Rounded.Save,
        JCodeIcon.Undo to Icons.AutoMirrored.Rounded.Undo,
        JCodeIcon.Redo to Icons.AutoMirrored.Rounded.Redo,
        JCodeIcon.Discard to Icons.Rounded.SettingsBackupRestore,
        JCodeIcon.Continue to Icons.Rounded.PlayArrow,
        JCodeIcon.Rerun to Icons.Rounded.RestartAlt,
        JCodeIcon.StepInto to Icons.Rounded.ArrowDownward,
        JCodeIcon.StepOver to Icons.AutoMirrored.Rounded.Redo,
        JCodeIcon.StepOut to Icons.Rounded.ArrowUpward,
        JCodeIcon.Collapse to Icons.Rounded.UnfoldLess,
        JCodeIcon.MoreVert to Icons.Rounded.MoreVert,
        JCodeIcon.Output to Icons.Rounded.Description,
        JCodeIcon.Logs to Icons.AutoMirrored.Rounded.Article,
        JCodeIcon.Problems to Icons.Rounded.SyncProblem,
        JCodeIcon.Radar to Icons.Rounded.Radar,
        JCodeIcon.Tasks to Icons.Rounded.Memory,
        JCodeIcon.Chat to Icons.Rounded.Forum,
        JCodeIcon.Cursor to Icons.Rounded.TextFields,
        JCodeIcon.DropDown to Icons.Rounded.ArrowDropDown,
        JCodeIcon.ChevronDown to Icons.Rounded.KeyboardArrowDown,
        JCodeIcon.ChevronRight to Icons.AutoMirrored.Rounded.KeyboardArrowRight,
        JCodeIcon.ArrowUp to Icons.Rounded.ArrowUpward,
        JCodeIcon.MenuToggle to Icons.AutoMirrored.Rounded.MenuOpen,
        JCodeIcon.Help to Icons.AutoMirrored.Rounded.HelpOutline,
        JCodeIcon.Copy to Icons.Rounded.ContentCopy,
        JCodeIcon.Cut to Icons.Rounded.ContentCut,
        JCodeIcon.Delete to Icons.Rounded.DeleteOutline,
        JCodeIcon.Open to Icons.Rounded.FileOpen,
        JCodeIcon.Rename to Icons.Rounded.DriveFileRenameOutline,
        JCodeIcon.SelectAll to Icons.Rounded.SelectAll,
        JCodeIcon.Clear to Icons.Rounded.ClearAll,
        JCodeIcon.Definition to Icons.Rounded.MyLocation,
        JCodeIcon.References to Icons.Rounded.ManageSearch,
        JCodeIcon.Format to Icons.Rounded.FormatAlignLeft,
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
