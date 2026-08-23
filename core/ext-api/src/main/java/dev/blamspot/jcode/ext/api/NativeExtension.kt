package dev.blamspot.jcode.ext.api

import androidx.compose.runtime.Composable

/**
 * The contract between JCode and an extension that contributes **native** UI.
 *
 * Everything in this file runs inside JCode's own process, in JCode's own Compose composition. That
 * makes it a real ABI: an extension built against version N of this file keeps running only while
 * JCode still honours version N. Two consequences worth carrying around:
 *
 * - **The surface stays small.** Every declaration here is a promise. A convenience added casually
 *   is a convenience that cannot be withdrawn without breaking installed extensions.
 * - **An extension must declare `entry.abi` and JCode must check it.** A mismatch has to be refused
 *   with something readable; the alternative is a `NoSuchMethodError` inside the IDE's own UI,
 *   which looks like JCode crashing rather than like an extension needing a rebuild.
 *
 * **Extensions must depend on this module — and on Compose — as `compileOnly`.** JCode provides both
 * at runtime. An extension that bundles its own copy of Compose ends up with two runtimes in one
 * process, and the composition it returns belongs to the wrong one.
 */
interface JCodeNativeExtension {

    /**
     * Draw one page.
     *
     * Called from JCode's composition, so the usual Compose rules apply: this may recompose at any
     * time, and anything expensive belongs behind `remember` / `LaunchedEffect`.
     *
     * [params] carries what the page was opened for — see [Params]. Keys absent rather than blank
     * when they do not apply, so `params[FILE]` being null means "no file", not "an empty path".
     */
    @Composable
    fun Content(host: NativeHost, params: Map<String, String>)

    /** Well-known [params] keys. Additions here are additive; existing keys keep their meaning. */
    object Params {
        /** Absolute path of the file this page was opened for. */
        const val FILE = "file"

        /** Absolute path of the enclosing project, when the file is inside one. */
        const val PROJECT_DIR = "projectDir"

        /** "dark" or "light" — the workbench theme, so a plugin can match it without guessing. */
        const val THEME = "theme"

        /**
         * Which of the plugin's own surfaces to draw: [SURFACE_PANEL] for the left drawer, or a
         * route the plugin defines for a page of its own.
         *
         * A plugin is not always one screen. The source-control extension is a drawer panel *and* a
         * handful of editor pages — sign-in, repository management, a diff — and they share its
         * state, so they are one plugin drawing different things rather than several plugins.
         * Absent means the file-claim surface: a page opened for [FILE].
         */
        const val VIEW = "view"

        /** The value of [VIEW] for a plugin drawn in the left drawer. */
        const val SURFACE_PANEL = "panel"
    }
}

/**
 * What an extension may ask of the IDE.
 *
 * Narrow on purpose. A plugin has JCode's full permissions by construction — it is JCode's process —
 * so this interface is not a security boundary and does not pretend to be one. What it is is the
 * *supported* way to reach the workbench: a plugin that reaches around it into JCode's internals is
 * a plugin that breaks on the next refactor, and this is the surface that will not.
 */
interface NativeHost {

    /**
     * The file's current text — the **editor's** text, not the disk's.
     *
     * A file open with unsaved edits differs between the two, and a designer that rendered the disk
     * copy would show something the user is not looking at. Falls back to the disk for a file that
     * is not open.
     */
    fun readFile(path: String): String?

    /**
     * Replace the file's text, through the editor buffer.
     *
     * Routed through the buffer rather than written straight to disk so that one edit is one edit:
     * the source view updates, the tab goes dirty, undo works, and Save is still the user's to
     * press. A plugin that wrote the file directly would leave the open buffer stale and silently
     * lose the user's own unsaved changes on their next keystroke.
     */
    fun writeFile(path: String, text: String)

    /** Absolute path of the active project, or null when nothing is open. */
    fun projectDir(): String?

    /** A transient message in the workbench, for things the user should notice but not act on. */
    fun snackbar(message: String)

    /**
     * The same, with one thing to do about it.
     *
     * [action] runs if [actionLabel] is tapped. For a result worth mentioning but not worth a
     * dialog: the message says what happened, and the action is where the rest of it lives.
     */
    fun snackbar(message: String, actionLabel: String, action: () -> Unit) = snackbar(message)

    /**
     * Report a problem to the Issues pane, or clear this plugin's problems when [messages] is empty.
     *
     * For anything the user may need to *act* on — a layout that will not parse, a resource that
     * cannot be resolved. A snackbar is gone in three seconds; the Issues pane keeps it, with room
     * for the detail.
     */
    fun reportIssues(messages: List<String>)

    /**
     * Put this file's tab back to its source view.
     *
     * A native page *replaces* the editor, and the toggle that opened it lives in the editor's own
     * context menu — so without this there is no way back out except closing the tab. A plugin that
     * takes over a file has to be able to hand it back.
     */
    fun showSource()

    // --- the runtime -------------------------------------------------------------------------

    /**
     * Run a command in the Linux runtime and wait for it.
     *
     * The only way a plugin reaches real tooling — git, a compiler, a formatter. [workdir] is a
     * guest path (what a project's `distroBindTarget` resolves to), not a host one.
     */
    suspend fun exec(
        command: String,
        workdir: String? = null,
        timeoutMs: Long = 60_000L,
        env: Map<String, String> = emptyMap(),
    ): NativeExecResult

    // --- the workbench -----------------------------------------------------------------------

    /** The open project: its name, its guest path, and the workspace holding it. */
    suspend fun projectInfo(): NativeProjectInfo?

    /** Every project root in the open workspace, as guest paths. */
    suspend fun workspaceFolders(): List<String>

    /** Open a file in the editor, optionally at a 1-based line. */
    fun openFile(path: String, line: Int? = null)

    /** Open a folder as the active project, or add it to the open workspace. */
    fun openFolder(path: String)
    fun addFolder(path: String)

    /** Hand a URL to the device — a sign-in page, a repository, a docs link. */
    fun openUrl(url: String)

    /**
     * Show one of this extension's own pages by id, optionally naming it.
     *
     * [title] is what the tab and the workbench header call the page. Without it the workbench makes
     * a label out of the id, which works while the id happens to read like a name and fails as soon
     * as it does not: a stash route is `stash:<repo>:stash@{0}`, and "stash@{0}" is a position in a
     * list rather than the name the user gave the stash. Only the extension knows that name, so only
     * the extension can supply it.
     */
    fun openView(id: String, title: String? = null)
    fun closeView(id: String)

    /**
     * Per-file badges in the Explorer, for an extension that declares `explorerDecorations`.
     *
     * Replaces this extension's whole set; an empty list clears them. Paths are relative to [root].
     */
    fun setExplorerDecorations(root: String, decorations: List<NativeDecoration>)

    /** Paths the Explorer should grey out or hide as ignored, relative to the open project. */
    fun setHiddenInjected(paths: List<String>)

    /**
     * The Explorer context-menu tap waiting for this extension, consumed by reading it.
     *
     * A tap can land before the plugin is ready, so the workbench holds the most recent one rather
     * than dropping it.
     */
    suspend fun pendingContextAction(): NativeContextAction?

    // --- this extension's settings -------------------------------------------------------------
    //
    // Anything that answers is suspend; anything that merely tells the workbench something is not.
    // The workbench answers on its own dispatcher, and a plugin that blocked the composition waiting
    // for it would freeze the frame it was drawing.

    /** Every declared setting, resolved: the user's value where set, the manifest default otherwise. */
    suspend fun config(): Map<String, String>
    fun setConfig(key: String, value: String)

    /**
     * Workbench events this plugin cares about — `config` when its settings change, `filesChanged`
     * when the workspace does, `explorerAction` when a contributed context action is tapped.
     *
     * Close the returned handle to stop listening; a plugin that leaks one keeps its composition
     * alive after its tab is gone.
     */
    fun onEvent(listener: (name: String, json: String) -> Unit): AutoCloseable
}

/** What a command left behind. [error] is set when it could not be run at all. */
data class NativeExecResult(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = -1,
    val error: String? = null,
) {
    val ok: Boolean get() = error == null && exitCode == 0

    /** stdout and stderr as one block, trailing whitespace trimmed — what a log line wants. */
    val output: String get() = (stdout + stderr).trimEnd()
}

/** The open project, as the workbench sees it. */
data class NativeProjectInfo(
    val name: String,
    /** Guest path the project is mounted at, or null for a project with no runtime path. */
    val path: String?,
    val workspace: String?,
)

/** One Explorer badge: a repo-relative path and the status letter to draw against it. */
data class NativeDecoration(val path: String, val status: String)

/** An Explorer context-menu tap addressed to this extension. */
data class NativeContextAction(
    val actionId: String,
    val path: String,
    val isDirectory: Boolean,
)

/**
 * The version of this contract that JCode implements.
 *
 * Bumped whenever anything above changes in a way an already-built extension would notice —
 * a signature change, a removal, a meaning change. Purely additive changes (a new [Params] key, a
 * new interface an old plugin never implements) do not need a bump.
 *
 * 3 was the package move: this contract lived at `dev.jcode.ext.api` until 1.6.2. Nothing in it
 * changed shape, but the names did, so an extension built against 2 no longer implements the
 * interface it thinks it does. Without the bump that surfaces as "does not implement
 * JCodeNativeExtension", which reads like the extension is broken rather than merely old.
 *
 * 4 opens [NativeHost] onto the runtime and the workbench. Until now a native plugin could read a
 * file, write it back, and say something — enough for a designer that only transforms the buffer it
 * was given, and nothing like enough for a plugin that has to run git and put badges in the
 * Explorer. The methods added are the ones the WebView bridge has always offered; a plugin should
 * not have to be a web page to reach them.
 *
 * 5 lets [NativeHost.openView] name the page it opens. The workbench was left to invent a label out
 * of the route, which cannot work for a page whose name only the plugin knows. The parameter has a
 * default, so calling code is unchanged — but a default is a second JVM signature, and a plugin
 * compiled against the one-argument method would not find it here, which is what the number is for.
 */
const val JCODE_EXT_ABI: Int = 6
