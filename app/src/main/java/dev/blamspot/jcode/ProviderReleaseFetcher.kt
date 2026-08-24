package dev.blamspot.jcode

import dev.blamspot.jcode.feature.marketplace.VsixPackage
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** The newest `.vsix`-bearing release resolved from a custom provider repo, plus what the repo says
 *  about the extension it publishes (see [ProviderReleaseFetcher]). */
data class ProviderRelease(
    /** Release version, normalized (leading `v` stripped) so it compares against an installed copy. */
    val version: String,
    /** `browser_download_url` of the release's `.vsix` asset. */
    val vsixAssetUrl: String,
    val assetName: String,
    /** `html_url` of the release, for "view release" links. */
    val releaseUrl: String,
    /** The `publisher.name` this extension installs under, read from the manifest in the repo. Null
     *  when the repo commits none — the source still lists, it just cannot be matched to an already
     *  installed copy until it has been installed from here once. */
    val extensionId: String? = null,
    val displayName: String? = null,
    val publisher: String? = null,
    val description: String? = null,
    /** Absolute URL of the extension's icon, for the list row and detail page before install. */
    val iconUrl: String? = null,
)

/**
 * Resolves a user-added extension source — a GitHub repo whose releases publish `.vsix` files — to
 * its newest installable release, and to enough about the extension to list it before it is
 * installed. This is the custom-provider counterpart to [UpdateChecker]: it reads
 * `api.github.com/repos/{owner}/{repo}/releases`, picks the highest-versioned non-draft release that
 * carries a `.vsix` asset, and reports the asset URL + version.
 *
 * Same transport as [UpdateChecker] (plain [HttpURLConnection] + `org.json`, GitHub `Accept`/
 * `User-Agent` headers) and it reuses that object's SemVer comparator. Network or parse failures
 * return null — it never throws — so a bad or unreachable URL simply yields "no release found".
 */
object ProviderReleaseFetcher {
    private const val API = "https://api.github.com"
    private const val RAW = "https://raw.githubusercontent.com"

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
            val body = get("$API/repos/$repo/releases?per_page=30")
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
            val found = best ?: return@runCatching null
            val about = describe(repo)
            found.copy(
                extensionId = about.id,
                displayName = about.displayName,
                publisher = about.publisher,
                description = about.description,
                iconUrl = about.iconUrl,
            )
        }.getOrNull()
    }

    /** What a repo says about the extension it publishes. Every field is best-effort. */
    private data class RepoExtension(
        val id: String? = null,
        val displayName: String? = null,
        val publisher: String? = null,
        val description: String? = null,
        val iconUrl: String? = null,
    )

    /**
     * What to show for a source's extension **before** anything is installed: its id, name, summary
     * and icon.
     *
     * The release asset cannot answer that cheaply — a `.vsix` is one archive, often tens of MB — so
     * the repo is read instead. Its file tree is scanned for the VS Code manifest (the `package.json`
     * declaring `engines.vscode`, which in a monorepo is not the root one), and that manifest carries
     * the install id (`publisher.name`), display name, description and icon path. Repos that commit
     * none — generated at build time, or built from a private path — fall back to the GitHub repo's
     * own description and its owner's avatar, so a source is never listed nameless.
     *
     * Read at `HEAD` rather than at the release tag: it is always a valid ref and needs no escaping,
     * and this metadata (unlike the version, which comes from the release) rarely moves between them.
     */
    private fun describe(repo: String): RepoExtension {
        val fromManifest = manifestPaths(repo).firstNotNullOfOrNull { readManifest(repo, it) }
        if (fromManifest?.description != null && fromManifest.iconUrl != null) return fromManifest
        val fromRepo = repoInfo(repo) ?: return fromManifest ?: RepoExtension()
        return RepoExtension(
            id = fromManifest?.id,
            displayName = fromManifest?.displayName ?: fromRepo.displayName,
            publisher = fromManifest?.publisher ?: fromRepo.publisher,
            description = fromManifest?.description ?: fromRepo.description,
            iconUrl = fromManifest?.iconUrl ?: fromRepo.iconUrl,
        )
    }

    /** The `package.json` paths worth reading, likeliest first. One request covers the whole tree. */
    private fun manifestPaths(repo: String): List<String> {
        val body = get("$API/repos/$repo/git/trees/HEAD?recursive=1") ?: return emptyList()
        val tree = runCatching { JSONObject(body).optJSONArray("tree") }.getOrNull() ?: return emptyList()
        val paths = buildList {
            for (i in 0 until tree.length()) {
                val path = tree.optJSONObject(i)?.optString("path").orEmpty()
                if (path.endsWith("package.json") && !path.contains("node_modules/")) add(path)
            }
        }
        // An extension manifest usually sits somewhere that says so; failing that the shallowest one
        // is likeliest. Only the first few are fetched, so this ordering decides what is read at all.
        return paths
            .sortedWith(
                compareByDescending<String> { it.contains("vscode", true) || it.contains("vsix", true) }
                    .thenByDescending { it.contains("extension", true) }
                    .thenBy { path -> path.count { it == '/' } },
            )
            .take(4)
    }

    /** Read one candidate manifest, or null when it is not a VS Code extension's. */
    private fun readManifest(repo: String, path: String): RepoExtension? {
        val dir = path.substringBeforeLast('/', "")
        val json = get(rawUrl(repo, path)) ?: return null
        // `parse` requires a publisher, which no ordinary npm package declares, and `engines.vscode`
        // is what makes the manifest an extension's — together they reject the repo's other packages.
        val manifest = runCatching { VsixPackage.parse(json) }.getOrNull() ?: return null
        if (manifest.engineRange == null) return null
        // `parse` resolves `%placeholders%` as it goes and hands back the bare key when it has no
        // bundle, so whether there was one has to be read off the untouched manifest.
        val raw = runCatching { JSONObject(json) }.getOrNull()
        val rawDisplayName = raw?.optString("displayName").orEmpty()
        val rawDescription = raw?.optString("description").orEmpty()
        // Only a localised manifest needs its string bundle, so it costs a request only when it must.
        val localized = if (rawDisplayName.startsWith("%") || rawDescription.startsWith("%")) {
            runCatching { VsixPackage.parse(json, get(rawUrl(repo, joinPath(dir, "package.nls.json")))) }
                .getOrNull() ?: manifest
        } else {
            manifest
        }
        return RepoExtension(
            id = localized.id,
            displayName = resolved(rawDisplayName, localized.displayName) ?: manifest.name,
            publisher = localized.publisher,
            description = resolved(rawDescription, localized.description),
            iconUrl = localized.icon?.let { rawUrl(repo, joinPath(dir, it)) },
        )
    }

    /**
     * A localised manifest value, or null when it never resolved to words. A `%key%` whose string is
     * missing comes out of `localize` as the bare key, which would put `extension.description` on
     * screen — better to have nothing and let the repo's own description stand in.
     */
    private fun resolved(raw: String, localized: String): String? = localized.takeIf {
        it.isNotBlank() && !it.startsWith("%") && !(raw.startsWith("%") && it == raw.trim('%'))
    }

    /** The repo's own name, description, owner and avatar — the fallback when no manifest is committed. */
    private fun repoInfo(repo: String): RepoExtension? {
        val body = get("$API/repos/$repo") ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val owner = json.optJSONObject("owner")
        return RepoExtension(
            displayName = json.optString("name").takeIf { it.isNotBlank() },
            publisher = owner?.optString("login")?.takeIf { it.isNotBlank() },
            description = json.optString("description").takeIf { it.isNotBlank() },
            iconUrl = owner?.optString("avatar_url")?.takeIf { it.isNotBlank() },
        )
    }

    private fun rawUrl(repo: String, path: String): String = "$RAW/$repo/HEAD/$path"

    private fun joinPath(dir: String, name: String): String = if (dir.isEmpty()) name else "$dir/$name"

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

    /**
     * Ceiling on a response held in memory. A recursive file tree is unbounded in principle — GitHub
     * itself only stops at 100k entries — and this runs on a phone, so an outsized one is dropped
     * rather than parsed. Comfortably above a real releases page (~600 KB for a repo publishing two
     * dozen assets per release) or any extension repo's tree.
     */
    private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024

    private fun get(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub rejects requests without a User-Agent.
            setRequestProperty("User-Agent", "JCode-Android")
        }
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = StringBuilder()
            val buffer = CharArray(8 * 1024)
            conn.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    if (body.length + read > MAX_RESPONSE_BYTES) return null
                    body.appendRange(buffer, 0, read)
                }
            }
            body.toString()
        } finally {
            conn.disconnect()
        }
    }
}
