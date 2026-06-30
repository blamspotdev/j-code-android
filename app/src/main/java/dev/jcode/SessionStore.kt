package dev.jcode

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** One persisted editor tab: its stable id, absolute file path, and (for dirty tabs) the buffer file. */
data class SessionTabRecord(
    val id: String,
    val filePath: String,
    val dirty: Boolean,
    val bufferFileName: String?,
)

/** A persisted workbench session: the open workspace/project plus the editor's open tabs. */
data class SessionRecord(
    val workspaceId: Long?,
    val projectId: Long?,
    val activeTabId: String?,
    val tabs: List<SessionTabRecord>,
)

/**
 * On-disk store for the "restore last session" feature. The manifest (which workspace/project/tabs were
 * open) lives in a small JSON file; each dirty tab's unsaved text is written to its own buffer file so a
 * keystroke only rewrites that one buffer, not the whole manifest. Survives process death / swipe-away.
 */
class SessionStore(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "session")
    private val manifestFile = File(dir, "manifest.json")
    private val buffersDir = File(dir, "buffers")

    fun load(): SessionRecord? = runCatching {
        if (!manifestFile.isFile) return null
        val json = JSONObject(manifestFile.readText())
        val tabsJson = json.optJSONArray("tabs") ?: JSONArray()
        val tabs = (0 until tabsJson.length()).map { i ->
            val t = tabsJson.getJSONObject(i)
            SessionTabRecord(
                id = t.getString("id"),
                filePath = t.getString("path"),
                dirty = t.optBoolean("dirty", false),
                bufferFileName = t.optString("buffer", "").ifBlank { null },
            )
        }
        SessionRecord(
            workspaceId = if (json.has("workspaceId")) json.getLong("workspaceId") else null,
            projectId = if (json.has("projectId")) json.getLong("projectId") else null,
            activeTabId = json.optString("activeTabId", "").ifBlank { null },
            tabs = tabs,
        )
    }.getOrNull()

    fun saveManifest(record: SessionRecord) {
        dir.mkdirs()
        val json = JSONObject().apply {
            put("version", 1)
            record.workspaceId?.let { put("workspaceId", it) }
            record.projectId?.let { put("projectId", it) }
            record.activeTabId?.let { put("activeTabId", it) }
            put("tabs", JSONArray().apply {
                record.tabs.forEach { t ->
                    put(JSONObject().apply {
                        put("id", t.id)
                        put("path", t.filePath)
                        put("dirty", t.dirty)
                        t.bufferFileName?.let { put("buffer", it) }
                    })
                }
            })
        }
        // Write-then-rename so a kill mid-write can't leave a truncated manifest.
        val tmp = File(dir, "manifest.json.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(manifestFile)) {
            manifestFile.writeText(json.toString())
            tmp.delete()
        }
    }

    /** Persist a dirty tab's unsaved text and return the buffer file name to record in the manifest. */
    fun writeBuffer(tabId: String, text: String): String {
        buffersDir.mkdirs()
        val name = bufferNameFor(tabId)
        File(buffersDir, name).writeText(text)
        return name
    }

    fun readBuffer(name: String): String? = runCatching {
        File(buffersDir, name).takeIf { it.isFile }?.readText()
    }.getOrNull()

    /** Delete any buffer files no longer referenced by the current manifest. */
    fun pruneBuffers(keep: Set<String>) {
        buffersDir.listFiles()?.forEach { f ->
            if (f.name !in keep) f.delete()
        }
    }

    fun clear() {
        dir.deleteRecursively()
    }

    private fun bufferNameFor(tabId: String): String =
        tabId.hashCode().toUInt().toString(16) + ".txt"
}
