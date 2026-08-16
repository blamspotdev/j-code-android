package dev.jcode.webengine

import android.content.Context
import android.view.View

/**
 * The seam between JCode and its bundled web engine.
 *
 * The engine (GeckoView) lives in the `:webengine` dynamic-feature split, delivered by the Web
 * Engine marketplace extension — never in the base APK. Everything here is engine-agnostic on
 * purpose: the base app compiles against these interfaces only, and the implementation is looked
 * up reflectively once the split is present ([WebEngineHost]). JCode's web surfaces gate on that
 * presence and show [WebEnginePlaceholder] otherwise; there is deliberately no fallback to the
 * device's system WebView, whose version the app can neither choose nor trust.
 */
interface WebEngine {
    /** Engine name + version for display, e.g. "GeckoView 153.0". */
    val label: String

    /**
     * What the page sees for `prefers-color-scheme`, following the workbench theme — the same
     * contract the WebView surfaces implement via `Theme.JCode.Web.*`. Engine-wide, because a
     * runtime serves every tab.
     */
    fun setColorScheme(dark: Boolean)

    /** Create a browser tab. The engine's runtime starts lazily on the first call. */
    fun createTab(context: Context, events: WebEngineEvents): WebEngineTab

    /** Wipe cookies, storage, caches — everything the engine keeps for any site. */
    fun clearBrowsingData()
}

/** One browser tab: an Android [view] plus the verbs the toolbar speaks. */
interface WebEngineTab {
    val view: View
    fun navigate(url: String)
    fun goBack()
    fun goForward()
    fun reload()
    fun reloadBypassingCache()
    fun stopLoading()
    fun setDesktopMode(desktop: Boolean)

    /** Release the tab's session. The engine runtime itself stays up for the process's lifetime. */
    fun dispose()
}

/** Page state flowing back from the engine; all callbacks arrive on the main thread. */
interface WebEngineEvents {
    fun onUrlChange(url: String)
    fun onTitleChange(title: String)
    fun onProgress(progress: Int)
    fun onLoadingChange(loading: Boolean)
    fun onNavigationState(canGoBack: Boolean, canGoForward: Boolean)
    fun onSecurityChange(secure: Boolean)
}

/**
 * Finds the engine if its split is installed.
 *
 * Presence is answered by the class loader, not by package metadata: after a split install the
 * running process doesn't see the new classes until restart, and `splitNames` alone can't say
 * whether the classes are actually loadable. `Class.forName` answers the only question that
 * matters — can we instantiate the engine right now — and the result is cached both ways.
 *
 * Instantiating [WebEngine] is cheap and starts nothing; the Gecko runtime boots on the first
 * [WebEngine.createTab]. That laziness is the point of the seam: a user who never opens a web
 * surface never pays the engine's startup, and the base app's cold start is untouched.
 */
object WebEngineHost {
    const val SPLIT_NAME = "webengine"
    private const val IMPL_CLASS = "dev.jcode.webengine.impl.GeckoWebEngine"

    @Volatile private var cached: WebEngine? = null
    @Volatile private var known: Boolean? = null

    /** Whether the engine split is installed and loadable in this process. */
    fun installed(): Boolean {
        known?.let { return it }
        val ok = runCatching { Class.forName(IMPL_CLASS) }.isSuccess
        known = ok
        return ok
    }

    /** The engine, or null when the split isn't installed. */
    fun get(context: Context): WebEngine? {
        cached?.let { return it }
        if (!installed()) return null
        return runCatching {
            Class.forName(IMPL_CLASS)
                .getDeclaredConstructor(Context::class.java)
                .newInstance(context.applicationContext) as WebEngine
        }.onFailure { known = false }.getOrNull()?.also { cached = it }
    }
}
