package dev.jcode

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** The newest `.vsix`-bearing release resolved from a custom provider repo. */
data class ProviderRelease(
    /** Release version, normalized (leading `v` stripped) so it compares against an installed copy. */
    val version: String,
    /** `browser_download_url` of the release's `.vsix` asset. */
    val vsixAssetUrl: String,
    val assetName: String,
    /** `html_url` of the release, for "view release" links. */
    val releaseUrl: String,
)

/**
 * Resolves a user-added extension source — a GitHub repo whose releases publish `.vsix` files — to
 * its newest installable release. This is the custom-provider counterpart to [UpdateChecker]: it
 * reads `api.github.com/repos/{owner}/{repo}/releases`, picks the highest-versioned non-draft release
 * that carries a `.vsix` asset, and reports the asset URL + version.
 *
 * Same transport as [UpdateChecker] (plain [HttpURLConnection] + `org.json`, GitHub `Accept`/
 * `User-Agent` headers) and it reuses that object's SemVer comparator. Network or parse failures
 * return null — it never throws — so a bad or unreachable URL simply yields "no release found".
 */
object ProviderReleaseFetcher {
    /**
     * Parse `owner/repo` from a github.com repo URL. Tolerates `https://`/`git@`, a trailing `.git`
     * or `/`, and extra path segments (`/releases`, `/tree/main`, …). Returns null for non-GitHub or
     * unparseable input.
     */
    fun parseRepo(url: String): String? {
        // Case-insensitive: the host is case-insensitive and soft keyboards often autocapitalize
        // "github" to "GitHub" after "://".
        val match = Regex("""github\.com[/:]+([^/]+)/([^/#?]+)""", RegexOption.IGNORE_CASE)
            .find(url.trim()) ?: return null
        val owner = match.groupValues[1].trim()
        val repo = match.groupValues[2].removeSuffix(".git").trim().trimEnd('/')
        if (owner.isBlank() || repo.isBlank()) return null
        return "$owner/$repo"
    }

    /** The newest non-draft release carrying a `.vsix` asset, or null if none / offline / bad URL. */
    suspend fun latest(repoUrl: String): ProviderRelease? = withContext(Dispatchers.IO) {
        runCatching {
            val repo = parseRepo(repoUrl) ?: return@runCatching null
            val body = get("https://api.github.com/repos/$repo/releases?per_page=30")
                ?: return@runCatching null
            val all = JSONArray(body)
            var best: ProviderRelease? = null
            for (i in 0 until all.length()) {
                val release = all.optJSONObject(i) ?: continue
                if (release.optBoolean("draft")) continue
                val asset = pickVsixAsset(release.optJSONArray("assets")) ?: continue
                val tag = release.optString("tag_name").ifBlank { release.optString("name") }
                val version = tag.trim().trimStart('v', 'V').trim()
                if (version.isBlank()) continue
                // Highest by version, not first in the list: GitHub orders by creation date, so a
                // patch to an older line published after a newer release would otherwise win.
                if (best == null || UpdateChecker.isNewer(version, best.version)) {
                    best = ProviderRelease(
                        version = version,
                        vsixAssetUrl = asset.first,
                        assetName = asset.second,
                        releaseUrl = release.optString("html_url"),
                    )
                }
            }
            best
        }.getOrNull()
    }

    /** First `.vsix` asset in a release's `assets` array, as (download-url, name). */
    private fun pickVsixAsset(assets: JSONArray?): Pair<String, String>? {
        if (assets == null) return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (!name.lowercase().endsWith(".vsix")) continue
            val url = asset.optString("browser_download_url").ifBlank { null } ?: continue
            return url to name
        }
        return null
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
}
