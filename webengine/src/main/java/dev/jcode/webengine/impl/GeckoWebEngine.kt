package dev.jcode.webengine.impl

import android.content.Context
import android.view.View
import dev.jcode.webengine.WebEngine
import dev.jcode.webengine.WebEngineEvents
import dev.jcode.webengine.WebEngineTab
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.StorageController

/**
 * The engine behind [WebEngine], on GeckoView.
 *
 * Instantiated reflectively by `WebEngineHost` — the constructor signature `(Context)` is the
 * contract; keep it in sync with `WebEngineHost.get`. Construction is deliberately inert:
 * [runtime] boots on the first tab, so merely having the split installed costs the app nothing
 * at startup.
 *
 * One [GeckoRuntime] per process is a Gecko invariant, not a choice — `create()` throws on a
 * second call and `shutdown()` is unrecoverable — which happens to be exactly the sharing the
 * seam promises anyway.
 */
class GeckoWebEngine(private val appContext: Context) : WebEngine {

    @Volatile private var runtime: GeckoRuntime? = null
    @Volatile private var darkScheme: Boolean = true

    override val label: String
        get() = "GeckoView " + org.mozilla.geckoview.BuildConfig.MOZILLA_VERSION

    private fun runtime(): GeckoRuntime {
        runtime?.let { return it }
        synchronized(this) {
            runtime?.let { return it }
            val settings = GeckoRuntimeSettings.Builder()
                .consoleOutput(false)
                .aboutConfigEnabled(false)
                // Follows the workbench theme, same contract as the WebView surfaces' isLightTheme.
                .preferredColorScheme(
                    if (darkScheme) GeckoRuntimeSettings.COLOR_SCHEME_DARK
                    else GeckoRuntimeSettings.COLOR_SCHEME_LIGHT,
                )
                .build()
            return GeckoRuntime.create(appContext, settings).also { runtime = it }
        }
    }

    override fun setColorScheme(dark: Boolean) {
        darkScheme = dark
        runtime?.settings?.preferredColorScheme =
            if (dark) GeckoRuntimeSettings.COLOR_SCHEME_DARK else GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
    }

    override fun createTab(context: Context, events: WebEngineEvents): WebEngineTab =
        GeckoTab(context, runtime(), events)

    override fun clearBrowsingData() {
        runtime?.storageController?.clearData(StorageController.ClearFlags.ALL)
    }
}

private class GeckoTab(
    context: Context,
    runtime: GeckoRuntime,
    private val events: WebEngineEvents,
) : WebEngineTab {

    private val session = GeckoSession(
        GeckoSessionSettings.Builder()
            .usePrivateMode(false)
            .build(),
    )

    private val geckoView = GeckoView(context)

    init {
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                if (url != null) events.onUrlChange(url)
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                back = canGoBack
                events.onNavigationState(back, forward)
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                forward = canGoForward
                events.onNavigationState(back, forward)
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                events.onLoadingChange(true)
                events.onSecurityChange(url.startsWith("https:"))
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                events.onLoadingChange(false)
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                events.onProgress(progress)
            }

            override fun onSecurityChange(
                session: GeckoSession,
                securityInfo: GeckoSession.ProgressDelegate.SecurityInformation,
            ) {
                events.onSecurityChange(securityInfo.isSecure)
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                events.onTitleChange(title.orEmpty())
            }
        }
        session.open(runtime)
        geckoView.setSession(session)
    }

    private var back = false
    private var forward = false

    override val view: View get() = geckoView

    override fun navigate(url: String) = session.loadUri(url)
    override fun goBack() = session.goBack()
    override fun goForward() = session.goForward()
    override fun reload() = session.reload()
    override fun reloadBypassingCache() = session.reload(GeckoSession.LOAD_FLAGS_BYPASS_CACHE)
    override fun stopLoading() = session.stop()

    override fun setDesktopMode(desktop: Boolean) {
        session.settings.userAgentMode =
            if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
            else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        session.settings.viewportMode =
            if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
            else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        session.reload()
    }

    override fun dispose() {
        // Detach first: releasing a session out from under an attached GeckoView is an error.
        geckoView.releaseSession()
        session.close()
    }
}
