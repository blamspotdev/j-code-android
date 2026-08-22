package dev.blamspot.jcode.vdevice

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Turns the device Files app's answer into the URI the requesting app is waiting for.
 *
 * ### What this used to be, and why it is smaller now
 *
 * A guest's `ACTION_OPEN_DOCUMENT` used to leave the device: the **phone's** picker opened over
 * JCode, offering a sandboxed app the user's downloads and photos, and then the answer went nowhere
 * because an embedded activity's token is one no `ActivityRecord` responds to. The first fix was for
 * the container to take the launch off the wire and draw a picker itself.
 *
 * That worked and was still the wrong shape. A drawn screen is not something `PackageManager` can
 * find, so an app calling `resolveActivity` before it offers an "attach a file" button found
 * nothing and offered nothing — and the device's storage had no app of its own to look at it with.
 * The picker is now the device's **Files app**: an ordinary guest, started by ordinary intent
 * resolution ([DeviceIntents]), answering through the ordinary result path ([GuestResults]).
 *
 * What is left here is the one part an app is not qualified to do for itself.
 *
 * ### The translation
 *
 * The Files app answers with a **device path**, under [DeviceIntents.EXTRA_DEVICE_PATH]. The
 * requester needs a `content://` URI into [VirtualStorageProvider], whose authority and document-id
 * encoding are the container's business — an app that built one itself would be coupled to a format
 * it cannot see change, and a picker that shipped separately would be coupled to a format it cannot
 * see at all. So the picker says which file was chosen, and this says what that file is called on
 * the wire.
 */
internal object GuestDocuments {

    private const val TAG = "VDEVICE"

    private lateinit var host: Context

    fun install(context: Context) {
        host = context.applicationContext
    }

    /**
     * Rewrites [result] into something [request]'s sender can open, or returns it unchanged.
     *
     * Unchanged is the common case: this is on the path of every result the device delivers, and
     * only the Files app's answers carry a device path. A result from any other app is its own
     * business and is passed through untouched.
     */
    fun addressed(request: Intent?, result: Intent?): Intent? {
        val path = result?.getStringExtra(DeviceIntents.EXTRA_DEVICE_PATH) ?: return result
        if (!::host.isInitialized) return result
        // A folder request answers with a *tree* URI, which is a different thing from a document
        // URI and the only one `DocumentFile.fromTreeUri` accepts.
        val uri = if (request?.action == Intent.ACTION_OPEN_DOCUMENT_TREE) {
            VirtualStorageProvider.treeUri(host, path)
        } else {
            VirtualStorageProvider.documentUri(host, path)
        }
        grant(uri)
        VirtualDeviceLog.append(
            host,
            'I',
            TAG,
            "${GuestRuntime.activePackage()} " +
                "${request?.action?.substringAfterLast('.') ?: "picked"}: $path",
        )
        return Intent(result).setData(uri).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
    }

    /**
     * Makes the URI persistable before it is handed over.
     *
     * The guest could read it without this — a provider never permission-checks its own uid, and
     * `:guest` is JCode's — but `takePersistableUriPermission` is a *different* question, and it
     * throws for a URI that was never granted persistably. Apps call it the moment they get a
     * document, precisely so a recent-files entry survives a restart, and enough of them do it
     * outside a `try` that answering the question properly is cheaper than the crash.
     */
    private fun grant(uri: Uri) {
        runCatching {
            host.grantUriPermission(
                host.packageName,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }.onFailure { Log.w(TAG, "cannot make $uri persistable; the app may not be able to keep it", it) }
    }
}
