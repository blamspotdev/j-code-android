package dev.blamspot.jcode.design

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BuildCircle
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.KeyboardCommandKey
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DatasetLinked
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.FormatAlignLeft
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.IntegrationInstructions
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
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
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Semantic icon slots used across the app. Call sites reference these instead of `Icons.*` directly,
 * so the whole icon set is swappable by providing a different [UiIconSet]. New slots are added here
 * as the UI grows.
 */
enum class JCodeIcon {
    Run, Stop, Terminal,
    Files, Folder, OpenFolder, NewFolder, NewFile,
    Sdk, Lsp, Scm, Settings, Search, Extensions, Sources, Destinations, Code, Database, Vm,
    Add, Minus, Close, Refresh, Paste, Collapse, MoreVert, Save, Undo, Redo, Discard,
    Continue, Pause, Rerun, StepInto, StepOver, StepOut,
    Output, Logs, Problems, Radar, Debug, Tasks, Chat, Cursor,
    Browser, DevTools, Image,
    DropDown, ChevronDown, ChevronUp, ChevronRight, ArrowUp, ArrowBack, ArrowForward, MenuToggle, Help,
    Copy, Cut, Delete, Open, Rename, SelectAll, Clear, Definition, References, Format,
    Preview, Pin, Palette, CommandPalette, ScreenRotation, Fullscreen, KeepAwake,
    Lock, LockOpen,
    TextIncrease, TextDecrease, GoToLine,
    Trash, Restore,
}

/**
 * A swappable set of the app's own chrome icons — the toolbars, menus, tabs and panel headers.
 *
 * [overrides] need only cover the slots a set wants to restyle; anything missing resolves through
 * [fallback] (ultimately the built-in default), so a pack can ship just its hero icons and inherit
 * the rest. Art is an [IconArt] rather than an `ImageVector` because a pack installed from disk may
 * ship either SVG (parsed to a vector) or PNG.
 *
 * The companion set for files and folders is [FileIconSet]; the two are chosen independently in
 * Settings, since wanting a different toolbar look and wanting a different `.ts` badge are different
 * wants.
 */
@Immutable
class UiIconSet(
    val id: String,
    val name: String,
    val description: String,
    val author: String = "JCode",
    /** Id of the extension this set came from, or null for a built-in. */
    val providerId: String? = null,
    private val overrides: Map<JCodeIcon, IconArt>,
    private val fallback: UiIconSet? = null,
) {
    fun art(icon: JCodeIcon): IconArt =
        overrides[icon] ?: fallback?.art(icon) ?: FALLBACK_ART

    /** Slots this set fills itself, not counting anything inherited through [fallback]. */
    val filledSlots: Int get() = overrides.size

    companion object {
        private val FALLBACK_ART = IconArt.Vector(Icons.Rounded.Circle)

        /** A set built from Compose vectors — the shape every built-in takes. */
        fun ofVectors(
            id: String,
            name: String,
            description: String,
            author: String = "JCode",
            overrides: Map<JCodeIcon, ImageVector>,
            fallback: UiIconSet? = null,
        ): UiIconSet = UiIconSet(
            id = id,
            name = name,
            description = description,
            author = author,
            overrides = overrides.mapValues { (_, vector) -> IconArt.Vector(vector) },
            fallback = fallback,
        )
    }
}

/** Built-in Material icon set, unified to the Rounded family for consistency. Always complete. */
val defaultUiIconSet = UiIconSet.ofVectors(
    id = "material",
    name = "Material Rounded",
    description = "The built-in Material rounded icon set.",
    overrides = mapOf(
        JCodeIcon.Run to Icons.Rounded.PlayArrow,
        JCodeIcon.Stop to Icons.Rounded.Stop,
        JCodeIcon.Terminal to Icons.Rounded.Terminal,
        JCodeIcon.Files to Icons.Rounded.FolderOpen,
        JCodeIcon.Folder to Icons.Rounded.Folder,
        JCodeIcon.OpenFolder to Icons.Rounded.FolderOpen,
        JCodeIcon.NewFolder to Icons.Rounded.CreateNewFolder,
        JCodeIcon.NewFile to Icons.Rounded.NoteAdd,
        JCodeIcon.Sdk to Icons.Rounded.BuildCircle,
        JCodeIcon.Palette to Icons.Rounded.Palette,
        JCodeIcon.Lock to Icons.Rounded.Lock,
        JCodeIcon.LockOpen to Icons.Rounded.LockOpen,
        JCodeIcon.CommandPalette to Icons.Rounded.KeyboardCommandKey,
        JCodeIcon.ScreenRotation to Icons.Rounded.ScreenRotation,
        JCodeIcon.Fullscreen to Icons.Rounded.Fullscreen,
        JCodeIcon.KeepAwake to Icons.Rounded.Bolt,
        JCodeIcon.TextIncrease to Icons.Rounded.TextIncrease,
        JCodeIcon.TextDecrease to Icons.Rounded.TextDecrease,
        JCodeIcon.Lsp to Icons.Rounded.IntegrationInstructions,
        JCodeIcon.Debug to Icons.Rounded.BugReport,
        JCodeIcon.Scm to Icons.Rounded.Source,
        JCodeIcon.Settings to Icons.Rounded.Settings,
        JCodeIcon.Search to Icons.Rounded.Search,
        JCodeIcon.Extensions to Icons.Rounded.Extension,
        JCodeIcon.Sources to Icons.Rounded.DatasetLinked,
        JCodeIcon.Destinations to Icons.Rounded.DatasetLinked,
        JCodeIcon.Database to Icons.Rounded.DatasetLinked,
        JCodeIcon.Vm to Icons.Rounded.Dns,
        JCodeIcon.Code to Icons.Rounded.Code,
        JCodeIcon.Browser to Icons.Rounded.Public,
        JCodeIcon.DevTools to Icons.Rounded.DeveloperMode,
        JCodeIcon.Image to Icons.Rounded.Image,
        JCodeIcon.Add to Icons.Rounded.Add,
        JCodeIcon.Minus to Icons.Rounded.Remove,
        JCodeIcon.Close to Icons.Rounded.Close,
        JCodeIcon.Refresh to Icons.Rounded.Refresh,
        JCodeIcon.Paste to Icons.Rounded.ContentPaste,
        JCodeIcon.Save to Icons.Rounded.Save,
        JCodeIcon.Undo to Icons.AutoMirrored.Rounded.Undo,
        JCodeIcon.Redo to Icons.AutoMirrored.Rounded.Redo,
        JCodeIcon.Discard to Icons.Rounded.SettingsBackupRestore,
        JCodeIcon.Continue to Icons.Rounded.PlayArrow,
        JCodeIcon.Pause to Icons.Rounded.Pause,
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
        JCodeIcon.ChevronUp to Icons.Rounded.KeyboardArrowUp,
        JCodeIcon.ChevronRight to Icons.AutoMirrored.Rounded.KeyboardArrowRight,
        JCodeIcon.ArrowUp to Icons.Rounded.ArrowUpward,
        // Auto-mirrored: back is a direction, and it is the other one in a right-to-left locale.
        JCodeIcon.ArrowBack to Icons.AutoMirrored.Rounded.ArrowBack,
        JCodeIcon.ArrowForward to Icons.AutoMirrored.Rounded.ArrowForward,
        // A plain hamburger, not `MenuOpen`: the same button both shows and hides the sidebar, and
        // which it will do is carried by the tint, not the glyph. MenuOpen's chevron pointed one way
        // in both states, so it described the control's direction wrongly half the time.
        JCodeIcon.MenuToggle to Icons.Rounded.Menu,
        JCodeIcon.Help to Icons.AutoMirrored.Rounded.HelpOutline,
        JCodeIcon.Copy to Icons.Rounded.ContentCopy,
        JCodeIcon.Cut to Icons.Rounded.ContentCut,
        JCodeIcon.Delete to Icons.Rounded.DeleteOutline,
        // Filled, where the row action is outlined: the bin itself and "delete this" sit in the same
        // toolbars, and one silhouette for both makes the destructive one look like the safe one.
        JCodeIcon.Trash to Icons.Rounded.Delete,
        JCodeIcon.Restore to Icons.Rounded.RestoreFromTrash,
        JCodeIcon.Open to Icons.Rounded.FileOpen,
        JCodeIcon.Rename to Icons.Rounded.DriveFileRenameOutline,
        JCodeIcon.SelectAll to Icons.Rounded.SelectAll,
        JCodeIcon.Clear to Icons.Rounded.ClearAll,
        JCodeIcon.Definition to Icons.Rounded.MyLocation,
        JCodeIcon.References to Icons.Rounded.ManageSearch,
        JCodeIcon.Format to Icons.Rounded.FormatAlignLeft,
        JCodeIcon.Preview to Icons.Rounded.Visibility,
        JCodeIcon.Pin to Icons.Rounded.PushPin,
        JCodeIcon.GoToLine to Icons.Rounded.FormatListNumbered,
    ),
)

object UiIconSetRegistry {
    // Hand-drawn vector packs are appended here (see JCodeLineIconSet.kt).
    val builtIns: List<UiIconSet> = listOf(defaultUiIconSet) + customUiIconSets

    val default: UiIconSet get() = defaultUiIconSet

    /** [id] resolved against the built-ins plus whatever [installed] sets an extension provides. */
    fun byId(id: String?, installed: List<UiIconSet> = emptyList()): UiIconSet =
        builtIns.firstOrNull { it.id == id } ?: installed.firstOrNull { it.id == id } ?: default
}

val LocalUiIconSet = staticCompositionLocalOf { defaultUiIconSet }

/** Resolve a semantic icon through the active [UiIconSet]. */
@Composable
fun jcIcon(icon: JCodeIcon): Painter = LocalUiIconSet.current.art(icon).painter()
