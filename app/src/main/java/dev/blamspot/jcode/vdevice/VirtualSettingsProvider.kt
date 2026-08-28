package dev.blamspot.jcode.vdevice

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import dev.blamspot.jcode.ext.api.JCodeVirtualDevice
import dev.blamspot.jcode.ext.api.VirtualDeviceComponents

/**
 * How the device's own Settings app reads and changes the device's settings.
 *
 * A **stub**, for the same reason [VirtualStorageProvider] is: the authority is `${applicationId}`
 * -scoped and comes from the manifest, while what answers on it ships in the Android Dev Pack.
 * Unexported — the only caller is a guest, which reaches it because a provider never
 * permission-checks its own uid.
 *
 * Resolved lazily rather than in [onCreate], which runs before `Application.onCreate`.
 */
class VirtualSettingsProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private val delegate: ContentProvider? by lazy {
        val context = context ?: return@lazy null
        VirtualDeviceBridge.init(context)
        VirtualDeviceBridge.provider(JCodeVirtualDevice.Roles.SETTINGS)?.also { attach(it, context) }
    }

    private fun attach(provider: ContentProvider, context: Context) {
        val info = runCatching {
            context.packageManager.resolveContentProvider(
                context.packageName + VirtualDeviceComponents.SETTINGS_AUTHORITY,
                0,
            )
        }.getOrNull()
        runCatching { provider.attachInfo(context, info) }
            .onFailure { Log.w(TAG, "the device's settings provider would not attach", it) }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? =
        delegate?.call(method, arg, extras)

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = delegate?.query(uri, projection, selection, selectionArgs, sortOrder)

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
