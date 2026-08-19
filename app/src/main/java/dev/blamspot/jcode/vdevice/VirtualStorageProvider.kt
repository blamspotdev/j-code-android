package dev.blamspot.jcode.vdevice

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File

/**
 * The virtual device's storage, as `content://` URIs.
 *
 * A picker hands back a URI, not a path, and everything an app does with one — `openFileDescriptor`,
 * `query` for a display name and a size, `DocumentFile` over a tree — goes through a provider. So the
 * device's storage needs one, or [VirtualStorage] would be a filesystem a guest could be told about
 * and not read.
 *
 * **The document id is the device's path**, `/sdcard/Download/piano.sf2`, rather than the host file's.
 * Two reasons, both practical: an app that falls back to `DocumentsContract.getDocumentId(uri)` for a
 * display name gets a sensible one, and JCode's data directory does not travel inside a URI that a
 * guest can read, print, or persist.
 *
 * `DocumentsProvider.attachInfo` insists on `exported` + `grantUriPermissions` +
 * `MANAGE_DOCUMENTS` on both read and write, and refuses to start otherwise — so this cannot be a
 * private provider however much it would like to be. That permission is held by DocumentsUI alone,
 * which makes the practical audience exactly two: the person, through the phone's Files app, and the
 * guest, which reaches it because a provider never permission-checks its **own uid**.
 *
 * The root is offered to the Files app deliberately, and it is the only sanctioned way onto the
 * device that is not `adb push`: a bank, a fixture, a test asset has to come from somewhere. What is
 * on the other side of it is a directory JCode empties on every start, and the root's summary says
 * so, because a root that quietly forgets everything is worse than one that says it will.
 */
class VirtualStorageProvider : DocumentsProvider() {

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val context = requireContext()
        // One root per volume, and the summaries are the difference between them: a root that
        // quietly forgets everything is worse than one that says it will, and a root that quietly
        // writes into somebody's workspace is worse than one that says where it lands.
        VirtualStorage.Volume.entries.forEach { volume ->
            cursor.newRow().apply {
                add(Root.COLUMN_ROOT_ID, "$ROOT_ID-${volume.name.lowercase()}")
                add(Root.COLUMN_DOCUMENT_ID, volume.deviceRoot)
                add(Root.COLUMN_TITLE, TITLE)
                add(Root.COLUMN_SUMMARY, summaryOf(volume))
                add(
                    Root.COLUMN_FLAGS,
                    Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY,
                )
                add(Root.COLUMN_ICON, context.applicationInfo.icon)
                add(Root.COLUMN_MIME_TYPES, Document.MIME_TYPE_DIR)
            }
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        includeFile(cursor, documentId, fileFor(documentId))
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
            ?.forEach { includeFile(cursor, childId(parentDocumentId, it.name), it) }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId.startsWith(parentDocumentId.trimEnd('/') + "/")

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = fileFor(parentDocumentId)
        var name = displayName
        // De-duped the way the system provider does: "name", "name (1)", "name (2)"…
        if (File(parent, name).exists()) {
            val dot = displayName.lastIndexOf('.')
            val stem = if (dot > 0) displayName.substring(0, dot) else displayName
            val extension = if (dot > 0) displayName.substring(dot) else ""
            var attempt = 1
            while (File(parent, name).exists()) name = "$stem ($attempt)$extension".also { attempt++ }
        }
        val target = File(parent, name)
        val created =
            if (mimeType == Document.MIME_TYPE_DIR) target.mkdirs() else target.createNewFile()
        check(created) { "cannot create $displayName on the virtual device" }
        return childId(parentDocumentId, name)
    }

    override fun deleteDocument(documentId: String) {
        check(fileFor(documentId).deleteRecursively()) { "cannot delete $documentId" }
    }

    override fun removeDocument(documentId: String, parentDocumentId: String) = deleteDocument(documentId)

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = fileFor(documentId)
        val target = File(file.parentFile, displayName)
        check(!target.exists()) { "'$displayName' already exists" }
        check(file.renameTo(target)) { "cannot rename $documentId" }
        return childId(documentId.substringBeforeLast('/'), displayName)
    }

    override fun getDocumentType(documentId: String): String = mimeTypeOf(fileFor(documentId))

    private fun includeFile(cursor: MatrixCursor, documentId: String, file: File) {
        var flags = Document.FLAG_SUPPORTS_WRITE or
            Document.FLAG_SUPPORTS_DELETE or
            Document.FLAG_SUPPORTS_RENAME
        if (file.isDirectory) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, if (file.name.isEmpty()) TITLE else file.name)
            add(Document.COLUMN_MIME_TYPE, mimeTypeOf(file))
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_SIZE, if (file.isFile) file.length() else 0L)
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
        }
    }

    /**
     * The host file a document id names, refusing anything outside the device — [VirtualStorage]
     * compares canonical paths, so neither `../` nor a symlink a guest planted reaches JCode's own
     * data directory.
     */
    private fun fileFor(documentId: String): File =
        VirtualStorage.resolve(requireContext(), documentId)
            ?: throw IllegalArgumentException("$documentId is not on the virtual device")

    private fun childId(parentDocumentId: String, name: String): String =
        parentDocumentId.trimEnd('/') + "/" + name

    private fun mimeTypeOf(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"
    }

    companion object {
        private const val ROOT_ID = "jcode-virtual-device"
        private const val TITLE = "JCode virtual device"

        private fun summaryOf(volume: VirtualStorage.Volume): String = when (volume) {
            VirtualStorage.Volume.Internal -> "Internal — emptied every time JCode starts"
            VirtualStorage.Volume.External ->
                "External — kept in your workspace as ${VirtualStorage.EXTERNAL_FOLDER}"
        }

        /** `${applicationId}.vdevice.files`, as declared in the manifest. */
        fun authority(context: Context): String = context.packageName + ".vdevice.files"

        /** The URI a picker hands back for a file on the device. */
        fun documentUri(context: Context, devicePath: String): Uri =
            DocumentsContract.buildDocumentUri(authority(context), devicePath)

        /** The URI `ACTION_OPEN_DOCUMENT_TREE` hands back for a directory on the device. */
        fun treeUri(context: Context, devicePath: String): Uri =
            DocumentsContract.buildTreeDocumentUri(authority(context), devicePath)

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
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
