package dev.jcode.ext.api

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
}

/**
 * The version of this contract that JCode implements.
 *
 * Bumped whenever anything above changes in a way an already-built extension would notice —
 * a signature change, a removal, a meaning change. Purely additive changes (a new [Params] key, a
 * new interface an old plugin never implements) do not need a bump.
 */
const val JCODE_EXT_ABI: Int = 2
