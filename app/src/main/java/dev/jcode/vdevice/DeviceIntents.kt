package dev.jcode.vdevice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.MediaStore

/**
 * What the virtual device's own apps answer, and how an implicit intent finds them.
 *
 * ### Why this is a table and not intent-filter matching
 *
 * An implicit intent is resolved by matching it against every installed app's `<intent-filter>`s,
 * and that is not something this container can do: `PackageManager` does not report the filters in
 * an APK it has not installed. `getPackageArchiveInfo` gives activities, permissions and features —
 * everything except the one thing resolution needs. Reading them would mean parsing binary
 * `AndroidManifest.xml` in the container, which is a real parser to write and keep correct against
 * a format nobody documents.
 *
 * What is actually needed is narrower. **The apps that have to answer implicit intents are the
 * device's own**, because they are the ones an app expects a device to have: something to take a
 * photo, something to pick a file, something to open a link. A phone's answer to
 * `ACTION_IMAGE_CAPTURE` is its system camera, and this device's is its system camera. Those are
 * shipped in the container's own assets, so what they answer is known here rather than discovered —
 * and a table that says so plainly is more honest than a parser that rediscovers it every start.
 *
 * A guest answering *another guest's* implicit intent is deliberately not supported. It would need
 * the parser, and nothing has yet wanted it: two sideloaded apps that expect to find each other are
 * not what this device is for.
 */
internal object DeviceIntents {

    private lateinit var host: Context

    fun install(context: Context) {
        host = context.applicationContext
    }

    /** The device's own apps, which are also the ones the launcher will not let you uninstall. */
    val SYSTEM_PACKAGES: Set<String> = setOf(
        VirtualDeviceApps.BROWSER_PACKAGE,
        CAMERA_PACKAGE,
        FILES_PACKAGE,
    )

    /**
     * One of the device's apps, and the intents it is the device's answer to.
     *
     * [schemes] narrows a match where the action alone is too broad: `ACTION_VIEW` is how a link is
     * opened *and* how a file is, and only the first belongs to the browser.
     *
     * The package is named, the activity is not — it is whichever one the APK declares as its
     * launcher, which is the one that answers these filters in all three cases. A class name here
     * would be a second place to change when an app is refactored, and one nothing would catch.
     */
    private class Handler(
        val packageName: String,
        val actions: Set<String>,
        val schemes: Set<String> = emptySet(),
    ) {
        fun matches(intent: Intent): Boolean {
            if (intent.action !in actions) return false
            if (schemes.isEmpty()) return true
            return intent.data?.scheme?.lowercase() in schemes
        }
    }

    private val handlers: List<Handler> = listOf(
        Handler(
            packageName = CAMERA_PACKAGE,
            actions = setOf(
                MediaStore.ACTION_IMAGE_CAPTURE,
                MediaStore.ACTION_IMAGE_CAPTURE_SECURE,
                MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA,
            ),
        ),
        Handler(
            packageName = FILES_PACKAGE,
            actions = setOf(
                Intent.ACTION_OPEN_DOCUMENT,
                Intent.ACTION_GET_CONTENT,
                Intent.ACTION_CREATE_DOCUMENT,
                Intent.ACTION_OPEN_DOCUMENT_TREE,
            ),
        ),
        // The browser was already the device's answer to a link; it is here rather than in its own
        // branch of the launch path so that there is one place that says what this device answers.
        Handler(
            packageName = VirtualDeviceApps.BROWSER_PACKAGE,
            actions = setOf(Intent.ACTION_VIEW),
            schemes = setOf("http", "https"),
        ),
    )

    /**
     * The device's app for [intent], or null if it has none.
     *
     * Null is the honest answer for everything the device does not have an app for, and it is what
     * keeps `resolveActivity` meaningful: an app that asks before it reaches is told no, and hides
     * the button, rather than being told yes and then failing.
     */
    fun resolve(intent: Intent): ComponentName? {
        if (!::host.isInitialized || intent.component != null) return null
        val handler = handlers.firstOrNull { it.matches(intent) } ?: return null
        // Asked of the device rather than assumed: an app can be missing from a device somebody has
        // taken it off, and answering with one that is not there is worse than answering with
        // nothing. Loading it is also what names the activity.
        val apk = VirtualDeviceApps.apk(host, handler.packageName) ?: return null
        val guest = runCatching { GuestLoader.load(host, apk.absolutePath) }.getOrNull() ?: return null
        return ComponentName(handler.packageName, guest.launchActivity)
    }

    /** Whether the launcher should refuse to uninstall [packageName]. */
    fun isSystem(packageName: String): Boolean = packageName in SYSTEM_PACKAGES

    const val CAMERA_PACKAGE = "dev.jcode.vdevice.camera"
    const val FILES_PACKAGE = "dev.jcode.vdevice.files"

    /**
     * The extra the device's Files app answers with — a device path, which the container turns into
     * the `content://` URI the requesting app receives.
     *
     * The split is deliberate. The URI belongs to JCode's own documents provider, whose authority
     * and document-id encoding are the container's business; an app that built one itself would be
     * coupled to a format it cannot see change. What the picker knows is which file the person
     * chose, and that is the part it answers.
     */
    const val EXTRA_DEVICE_PATH = "dev.jcode.vdevice.DEVICE_PATH"
}
