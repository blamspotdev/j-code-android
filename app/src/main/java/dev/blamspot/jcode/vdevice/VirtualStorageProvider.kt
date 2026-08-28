package dev.blamspot.jcode.vdevice

import android.content.ContentProvider
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.util.Log
import dev.blamspot.jcode.ext.api.JCodeVirtualDevice
import dev.blamspot.jcode.ext.api.VirtualDeviceComponents
import java.io.FileNotFoundException

/**
 * The device's storage, as `content://` URIs — declared here, served by the Android Dev Pack.
 *
 * A **stub**, for the reason the whole of [VirtualDeviceBridge] exists: a provider authority is
 * `${applicationId}`-scoped and resolved by the system out of the manifest, long before an extension
 * could be consulted about it. So the authority stays declared in the app and what answers on it
 * comes from the pack.
 *
 * **Nothing is resolved in [onCreate].** A manifest-declared provider is created during app startup,
 * before `Application.onCreate`, and loading the pack's archive there would put a multi-megabyte
 * `DexClassLoader` in front of every cold start — including the overwhelming majority that never
 * open a device. The delegate is fetched on the first query that actually needs it.
 *
 * With no pack installed every method answers **empty** rather than failing. The audience is the
 * phone's Files app, which queries every `DocumentsProvider` it can see whether or not the user has
 * ever installed a dev pack, and a provider that throws takes DocumentsUI down with it.
 */
class VirtualStorageProvider : DocumentsProvider() {

    override fun onCreate(): Boolean = true

    private val delegate: DocumentsProvider? by lazy { resolve() }

    private fun resolve(): DocumentsProvider? {
        val context = context ?: return null
        VirtualDeviceBridge.init(context)
        val provider = VirtualDeviceBridge.provider(JCodeVirtualDevice.Roles.FILES) as? DocumentsProvider
            ?: return null
        return provider.also { attach(it, context) }
    }

    /**
     * Give the delegate the context and authority it believes it was declared with.
     *
     * `attachInfo` is what a provider's `getContext()` and `onCreate()` hang off; a delegate that
     * never received it would answer every query against a null context. The [ProviderInfo] is this
     * stub's own, looked up by authority, so the delegate builds document URIs that point back at
     * the authority callers actually hold.
     */
    private fun attach(provider: ContentProvider, context: Context) {
        val info = runCatching {
            context.packageManager.resolveContentProvider(
                context.packageName + VirtualDeviceComponents.FILES_AUTHORITY,
                0,
            )
        }.getOrNull()
        runCatching { provider.attachInfo(context, info) }
            .onFailure { Log.w(TAG, "the device's file provider would not attach", it) }
    }

    override fun queryRoots(projection: Array<out String>?): Cursor =
        delegate?.queryRoots(projection) ?: MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        delegate?.queryDocument(documentId, projection) ?: throw noDevice(documentId)

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor = delegate?.queryChildDocuments(parentDocumentId, projection, sortOrder)
        ?: throw noDevice(parentDocumentId)

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        delegate?.isChildDocument(parentDocumentId, documentId) ?: false

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor = delegate?.openDocument(documentId, mode, signal) ?: throw noDevice(documentId)

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String =
        delegate?.createDocument(parentDocumentId, mimeType, displayName) ?: throw noDevice(parentDocumentId)

    override fun deleteDocument(documentId: String) {
        delegate?.deleteDocument(documentId) ?: throw noDevice(documentId)
    }

    override fun removeDocument(documentId: String, parentDocumentId: String) = deleteDocument(documentId)

    override fun renameDocument(documentId: String, displayName: String): String? =
        delegate?.renameDocument(documentId, displayName) ?: throw noDevice(documentId)

    override fun getDocumentType(documentId: String): String =
        delegate?.getDocumentType(documentId) ?: throw noDevice(documentId)

    private fun noDevice(documentId: String) = FileNotFoundException(
        "No virtual device: install the Android Dev Pack to browse it ($documentId).",
    )

    private companion object {

        /** What an empty root cursor has to carry columns for; DocumentsUI reads them unconditionally. */
        val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_ICON,
        )
    }
}
