package dev.blamspot.jcode.feature.editor.pane

import dev.blamspot.jcode.core.buffer.Buffer
import dev.blamspot.jcode.core.editor.EditorState
import dev.blamspot.jcode.core.editor.LanguageDescriptor
import java.io.File

/**
 * Identifies a non-file "page" tab (e.g. the in-editor Settings page). [None] is an ordinary
 * file-backed tab.
 */
enum class EditorPageKind { None, Settings, Environment, SdkDetail, LspDetail, DebugEngineDetail, ExtensionDetail, ExtensionPermissions, ExtensionSources, ExtensionApp, VsixPanel, RunConfig, BuildConfig, Browser, ImageViewer, AndroidDevice, AppSandbox, VirtualHardware, Trash }

/**
 * Represents a single open tab in the editor. A file tab carries an [editorState]; a page tab
 * (see [pageKind]) has a null [editorState] and renders host-provided Compose content instead.
 */
data class EditorTab(
    val id: String,
    val title: String,
    val filePath: File,
    val editorState: EditorState?,
    val isDirty: Boolean = false,
    val languageDescriptor: LanguageDescriptor? = null,
    val pageKind: EditorPageKind = EditorPageKind.None,
    /** File tab showing its rendered preview (e.g. Markdown) instead of the source editor. */
    val previewMode: Boolean = false,
    /** Pinned tabs sort to the front, hide their close "×" (close via the long-press menu), and are
     *  skipped by "Close others" / "Close to the right". */
    val pinned: Boolean = false,
) {
    val isPage: Boolean get() = pageKind != EditorPageKind.None

    companion object {
        fun page(id: String, title: String, kind: EditorPageKind): EditorTab = EditorTab(
            id = id,
            title = title,
            filePath = File(""),
            editorState = null,
            pageKind = kind,
        )

        fun create(file: File, id: String = file.path): EditorTab {
            val buffer = Buffer.fromText(
                if (file.exists()) file.readText() else ""
            )
            val state = EditorState(buffer)
            val languageDescriptor = guessLanguageDescriptor(file.name)
            state.setLanguage(languageDescriptor)
            return EditorTab(
                id = id,
                title = file.name,
                filePath = file,
                editorState = state,
                languageDescriptor = languageDescriptor,
            )
        }

        fun createFromText(text: String, title: String, id: String = title): EditorTab {
            val buffer = Buffer.fromText(text)
            val state = EditorState(buffer)
            val languageDescriptor = guessLanguageDescriptor(title)
            state.setLanguage(languageDescriptor)
            return EditorTab(
                id = id,
                title = title,
                filePath = File(""),
                editorState = state,
                languageDescriptor = languageDescriptor,
            )
        }

        private fun guessLanguageDescriptor(fileName: String): LanguageDescriptor {
            val normalized = fileName.substringAfterLast('.', "").lowercase()
            val descriptor = when (normalized) {
                "json", "jsonc" -> LanguageDescriptor("json", "JSON", listOf("json", "jsonc"))
                "yaml", "yml" -> LanguageDescriptor("yaml", "YAML", listOf("yaml", "yml"))
                "kt", "kts" -> LanguageDescriptor("kotlin", "Kotlin", listOf("kt", "kts"))
                "java" -> LanguageDescriptor("java", "Java", listOf("java"))
                "ts", "tsx" -> LanguageDescriptor("typescript", "TypeScript", listOf("ts", "tsx"))
                "js", "jsx", "mjs", "cjs" -> LanguageDescriptor("javascript", "JavaScript", listOf("js", "jsx", "mjs", "cjs"))
                "md", "markdown" -> LanguageDescriptor("markdown", "Markdown", listOf("md", "markdown"))
                "html", "htm" -> LanguageDescriptor("html", "HTML", listOf("html", "htm"))
                "css" -> LanguageDescriptor("css", "CSS", listOf("css"))
                "py" -> LanguageDescriptor("python", "Python", listOf("py"))
                "rs" -> LanguageDescriptor("rust", "Rust", listOf("rs"))
                "c", "h" -> LanguageDescriptor("c", "C", listOf("c", "h"))
                "cpp", "cc", "cxx", "hpp", "hxx" -> LanguageDescriptor("cpp", "C++", listOf("cpp", "cc", "cxx", "hpp", "hxx"))
                else -> LanguageDescriptor("text", "Plain Text")
            }
            return descriptor
        }
    }
}

/**
 * A group of editor tabs that share a split pane.
 */
data class EditorGroup(
    val id: String,
    val tabs: List<EditorTab> = emptyList(),
    val activeTabId: String? = null,
) {
    val activeTab: EditorTab?
        get() = tabs.find { it.id == activeTabId } ?: tabs.firstOrNull()

    fun withTabAdded(tab: EditorTab): EditorGroup {
        return copy(
            tabs = tabs + tab,
            activeTabId = tab.id,
        )
    }

    fun withTabRemoved(tabId: String): EditorGroup {
        val newTabs = tabs.filter { it.id != tabId }
        val newActive = if (activeTabId == tabId) {
            newTabs.lastOrNull()?.id
        } else {
            activeTabId
        }
        return copy(tabs = newTabs, activeTabId = newActive)
    }

    fun withActiveTabChanged(tabId: String): EditorGroup {
        return copy(activeTabId = tabId)
    }

    fun withTabUpdated(updated: EditorTab): EditorGroup {
        return copy(tabs = tabs.map { if (it.id == updated.id) updated else it })
    }

    companion object {
        fun create(id: String = "default"): EditorGroup = EditorGroup(id)
    }
}
