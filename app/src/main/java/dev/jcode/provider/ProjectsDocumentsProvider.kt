package dev.jcode.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File

/**
 * Exposes the app-private ext4 projects tree (`filesDir/workspace/projects`) to the system Files app
 * and any SAF picker as a browsable, read/write root titled "JCode Projects".
 *
 * Projects deliberately live on app-private internal storage (ext4: symlinks + exec, needed by npm and
 * git; see core:distro WorkspaceHostPaths) rather than shared /storage, which makes them invisible to
 * file managers. Android has no way to surface app-private files as a raw /storage path, so this
 * DocumentsProvider is the sanctioned bridge: DocumentsUI (the only holder of MANAGE_DOCUMENTS, which
 * guards this provider) queries it and hands other apps per-URI grants. Files never leave ext4.
 *
 * The migration marker is checked directly (not via WorkspaceHostPaths) so the root resolves correctly
 * regardless of provider vs. DistroService init ordering; the "workspace/projects" segment must stay in
 * sync with core:fs StorageRoots and core:distro WorkspaceHostPaths.
 */
class ProjectsDocumentsProvider : DocumentsProvider() {

    private val projectsRoot: File
        get() {
            val base = File(requireContext().filesDir, "workspace")
            return if (File(base, ".migrated-ext4").exists()) {
                File(base, "projects")
            } else {
                File(LEGACY_SHARED_PROJECTS_ROOT)
            }
        }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val root = projectsRoot.apply { runCatching { mkdirs() } }
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, docIdFor(root))
            add(Root.COLUMN_TITLE, "JCode Projects")
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY)
            add(Root.COLUMN_ICON, requireContext().applicationInfo.icon)
            add(Root.COLUMN_MIME_TYPES, Document.MIME_TYPE_DIR)
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        includeFile(cursor, fileFor(documentId))
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        fileFor(parentDocumentId).listFiles()
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?.forEach { includeFile(cursor, it) }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = fileFor(parentDocumentId).canonicalFile
        return runCatching { fileFor(documentId).canonicalFile.path.startsWith(parent.path + File.separator) }
            .getOrDefault(false)
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = fileFor(parentDocumentId)
        var target = File(parent, displayName)
        // De-dupe like the system provider does: "name", "name (1)", "name (2)"…
        if (target.exists()) {
            val dot = displayName.lastIndexOf('.')
            val stem = if (dot > 0) displayName.substring(0, dot) else displayName
            val ext = if (dot > 0) displayName.substring(dot) else ""
            var n = 1
            while (target.exists()) target = File(parent, "$stem ($n)$ext").also { n++ }
        }
        val ok = if (mimeType == Document.MIME_TYPE_DIR) target.mkdir() else target.createNewFile()
        check(ok) { "failed to create $displayName" }
        return docIdFor(target)
    }

    override fun deleteDocument(documentId: String) {
        check(fileFor(documentId).deleteRecursively()) { "failed to delete $documentId" }
    }

    override fun removeDocument(documentId: String, parentDocumentId: String) = deleteDocument(documentId)

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = fileFor(documentId)
        val target = File(file.parentFile, displayName)
        check(!target.exists()) { "'$displayName' already exists" }
        check(file.renameTo(target)) { "failed to rename $documentId" }
        return docIdFor(target)
    }

    override fun getDocumentType(documentId: String): String = mimeTypeOf(fileFor(documentId))

    private fun includeFile(cursor: MatrixCursor, file: File) {
        val mime = mimeTypeOf(file)
        var flags = 0
        if (file.canWrite()) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
            if (file.isDirectory) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        }
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, docIdFor(file))
            add(Document.COLUMN_DISPLAY_NAME, file.name)
            add(Document.COLUMN_MIME_TYPE, mime)
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_SIZE, if (file.isFile) file.length() else 0L)
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
        }
    }

    /** Document id = absolute path; the root's own id is the projects dir. */
    private fun docIdFor(file: File): String = file.absolutePath

    /** Resolve a document id back to a file, refusing anything outside the projects root. */
    private fun fileFor(documentId: String): File {
        val file = File(documentId)
        val root = projectsRoot.canonicalFile
        val canonical = runCatching { file.canonicalFile }.getOrDefault(file)
        require(canonical.path == root.path || canonical.path.startsWith(root.path + File.separator)) {
            "document id outside the projects root: $documentId"
        }
        return file
    }

    private fun mimeTypeOf(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    companion object {
        private const val ROOT_ID = "jcode-projects"
        private const val LEGACY_SHARED_PROJECTS_ROOT = "/storage/emulated/0/JCode/projects"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_MIME_TYPES,
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
