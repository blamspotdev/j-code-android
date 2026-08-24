package dev.blamspot.jcode

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Result of a GitHub-release update check. */
data class UpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val releaseUrl: String,
    /**
     * Direct download URL of the release's `.apk` asset **for this build's channel**, or null when
     * the release publishes none it can install — then the in-app updater opens [releaseUrl]
     * instead. Null is also the honest answer when a Beta build is told about a *release* build:
     * see [UpdateChecker].
     */
    val apkUrl: String?,
    val updateAvailable: Boolean,
)

/**
 * Checks the public GitHub releases of the JCode repo for a newer version than this build.
 *
 * ### Two channels, because there are two apps
 *
 * A Beta build is not the release app with a label on it — it is `dev.blamspot.jcode.beta`, its own
 * `applicationId`, installed alongside `dev.blamspot.jcode` with its own data. So "is there an update?" is
 * a different question per channel, and answering it with the other channel's APK would install a
 * *different application* rather than update this one.
 *
 * - **stable** asks `releases/latest`, which by definition is the newest release that is neither a
 *   draft nor a pre-release — so a stable user is never offered a beta.
 * - **beta** asks for the release *list* and takes the highest version in it, pre-releases
 *   included, because `releases/latest` would never mention one.
 *
 * A Beta build does still hear about the release it was previewing: when the highest version is a
 * final, it is reported with a null [UpdateInfo.apkUrl], so the app offers the release page rather
 * than an install it cannot perform. That is the case the old comparator got silently wrong — it
 * ignored pre-release labels, so `1.4.10-beta` compared *equal* to `1.4.10` and a tester was never
 * told the version they were testing had shipped.
 *
 * Network and parsing failures return null (offline / rate-limited / no releases) — never throws.
 */
object UpdateChecker {
    private const val REPO = "https://api.github.com/repos/blamspotdev/j-code-android"
    private const val LATEST_RELEASE_API = "$REPO/releases/latest"

    /** Enough to reach back past a run of previews to the last final. */
    private const val RELEASE_LIST_API = "$REPO/releases?per_page=30"
    private const val RELEASES_PAGE =
        "https://github.com/blamspotdev/j-code-android/releases/latest"

    const val CHANNEL_BETA = "beta"

    suspend fun check(
        currentVersion: String = BuildConfig.VERSION_NAME,
        channel: String = BuildConfig.UPDATE_CHANNEL,
    ): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val release = if (channel == CHANNEL_BETA) newestOfList() else newestStable()
            if (release == null) return@runCatching null
            val tag = release.optString("tag_name").ifBlank { release.optString("name") }
            val latest = tag.trim().trimStart('v', 'V').trim()
            if (latest.isBlank()) return@runCatching null
            UpdateInfo(
                currentVersion = currentVersion,
                latestVersion = latest,
                releaseUrl = release.optString("html_url").ifBlank { RELEASES_PAGE },
                apkUrl = pickApkAsset(release.optJSONArray("assets"), channel),
                updateAvailable = isNewer(latest, currentVersion),
            )
        }.getOrNull()
    }

    private fun newestStable(): JSONObject? = get(LATEST_RELEASE_API)?.let { JSONObject(it) }

    /**
     * The highest-versioned release on the list, pre-releases included.
     *
     * Highest by version rather than first in the list: GitHub orders these by creation date, and a
     * patch to an older line published after a preview would otherwise read as the newest thing
     * there is.
     */
    private fun newestOfList(): JSONObject? {
        val body = get(RELEASE_LIST_API) ?: return null
        val all = JSONArray(body)
        var best: JSONObject? = null
        for (i in 0 until all.length()) {
            val release = all.optJSONObject(i) ?: continue
            if (release.optBoolean("draft")) continue
            val tag = release.optString("tag_name").ifBlank { release.optString("name") }
            if (tag.isBlank()) continue
            val chosen = best?.let { it.optString("tag_name").ifBlank { it.optString("name") } }
            if (chosen == null || isNewer(tag, chosen)) best = release
        }
        return best
    }

    private fun get(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub rejects requests without a User-Agent.
            setRequestProperty("User-Agent", "JCode-Android")
        }
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** True if [latest] is a strictly higher semantic version than [current]. */
    internal fun isNewer(latest: String, current: String): Boolean = compare(latest, current) > 0

    /**
     * Semantic Versioning 2.0.0 precedence — negative, zero or positive, the way [Comparator] reads.
     *
     * The two rules the old numeric-split comparator could not express, and both matter here:
     * **a version carrying a pre-release ranks below the same version without one**
     * (`1.5.0-rc.1` < `1.5.0`), and **identifiers are compared field by field**, numerically where
     * both are numeric and by ASCII otherwise — which is what puts `beta.2` below `rc.1` rather
     * than above it, since the old parse collapsed every label to 0 and compared the numbers
     * beside them.
     *
     * Build metadata (`+sha`) is dropped without comparing, as the specification requires.
     */
    internal fun compare(a: String, b: String): Int {
        val (aCore, aPre) = parse(a)
        val (bCore, bPre) = parse(b)
        for (i in 0 until maxOf(aCore.size, bCore.size)) {
            val result = aCore.getOrElse(i) { 0 }.compareTo(bCore.getOrElse(i) { 0 })
            if (result != 0) return result
        }
        if (aPre.isEmpty() && bPre.isEmpty()) return 0
        if (aPre.isEmpty()) return 1
        if (bPre.isEmpty()) return -1

        for (i in 0 until maxOf(aPre.size, bPre.size)) {
            // A shorter run of identifiers ranks lower when everything before it is equal, so
            // `1.5.0-beta` < `1.5.0-beta.1`.
            val x = aPre.getOrNull(i) ?: return -1
            val y = bPre.getOrNull(i) ?: return 1
            val xn = x.toIntOrNull()
            val yn = y.toIntOrNull()
            val result = when {
                xn != null && yn != null -> xn.compareTo(yn)
                // "Numeric identifiers always have lower precedence than alphanumeric ones."
                xn != null -> -1
                yn != null -> 1
                else -> x.compareTo(y)
            }
            if (result != 0) return result
        }
        return 0
    }

    /** A version split into its numeric core and its pre-release identifiers. */
    private fun parse(version: String): Pair<List<Int>, List<String>> {
        val clean = version.trim().trimStart('v', 'V').trim().substringBefore('+')
        val core = clean.substringBefore('-')
            .split('.')
            .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val pre = clean.substringAfter('-', "")
        return core.ifEmpty { listOf(0) } to
            if (pre.isBlank()) emptyList() else pre.split('.')
    }

    /**
     * The release's `.apk` asset **for [channel]**, or null when it publishes none.
     *
     * Matched on the name the release scripts give it — `jcode-v<version>-<code>-<variant>.apk` —
     * so a beta build takes the `-beta` one and a stable build takes the one that is neither that
     * nor a debug or unsigned build. This used to prefer *any* non-beta APK regardless of what was
     * asking, which meant a Beta build offered a preview would have downloaded the release APK:
     * a different `applicationId`, so not an update to itself at all.
     */
    private fun pickApkAsset(assets: JSONArray?, channel: String): String? {
        if (assets == null) return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name").lowercase()
            if (!name.endsWith(".apk")) continue
            if (name.contains("debug") || name.contains("unsigned")) continue
            val isBetaAsset = name.contains("beta")
            if (isBetaAsset != (channel == CHANNEL_BETA)) continue
            asset.optString("browser_download_url").ifBlank { null }?.let { return it }
        }
        return null
    }
}
