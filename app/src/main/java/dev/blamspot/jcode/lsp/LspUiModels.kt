package dev.blamspot.jcode.lsp

/** One row in the reference/definition picker. */
data class LspLocationEntry(
    val path: String,
    /** 0-based, as LSP reports them. */
    val line: Int,
    val character: Int,
    /** The source line the match sits on, trimmed for display. */
    val preview: String,
) {
    val fileName: String get() = path.substringAfterLast('/')
    /** 1-based, as people read line numbers. */
    val displayLine: Int get() = line + 1
}

/** The picker shown when a symbol resolves to more than one place. */
data class LspLocationPicker(
    val title: String,
    val entries: List<LspLocationEntry>,
)

/** An in-flight rename: the position to rename at, awaiting a new name from the dialog. */
data class LspRenameRequest(
    val path: String,
    val line: Int,
    val character: Int,
    val symbol: String,
)
