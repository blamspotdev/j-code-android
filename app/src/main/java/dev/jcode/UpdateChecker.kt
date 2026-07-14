package dev.jcode

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Result of a GitHub-release update check. */
data class UpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val releaseUrl: String,
    val updateAvailable: Boolean,
)

/**
 * Checks the public GitHub releases of the JCode repo for a newer version than this build.
 * Compares [BuildConfig.VERSION_NAME] against the latest release's `tag_name` (semver, "v" stripped).
 * Network + parsing failures return null (offline / rate-limited / no releases) — never throws.
 */
object UpdateChecker {
    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/blamspotdev/j-code-android/releases/latest"
    private const val RELEASES_PAGE =
        "https://github.com/blamspotdev/j-code-android/releases/latest"

    suspend fun check(currentVersion: String = BuildConfig.VERSION_NAME): UpdateInfo? =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    // GitHub rejects requests without a User-Agent.
                    setRequestProperty("User-Agent", "JCode-Android")
                }
                try {
                    if (conn.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val obj = JSONObject(body)
                    val tag = obj.optString("tag_name").ifBlank { obj.optString("name") }
                    val latest = tag.trim().trimStart('v', 'V').trim()
                    if (latest.isBlank()) return@runCatching null
                    val url = obj.optString("html_url").ifBlank { RELEASES_PAGE }
                    UpdateInfo(
                        currentVersion = currentVersion,
                        latestVersion = latest,
                        releaseUrl = url,
                        updateAvailable = isNewer(latest, currentVersion),
                    )
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        }

    /** True if [latest] is a strictly higher semantic version than [current]. */
    internal fun isNewer(latest: String, current: String): Boolean {
        val l = parse(latest)
        val c = parse(current)
        for (i in 0 until maxOf(l.size, c.size)) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv != cv) return lv > cv
        }
        return false
    }

    /** Split a version into numeric components, ignoring any pre-release/build suffix. */
    private fun parse(version: String): List<Int> =
        version.split('.', '-', '+')
            .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            .let { nums -> if (nums.isEmpty()) listOf(0) else nums }
}
