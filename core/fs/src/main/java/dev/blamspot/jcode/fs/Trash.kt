package dev.blamspot.jcode.fs

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.documentfile.provider.DocumentFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** What put a thing in the bin, so the list can say where it came from. */
enum class TrashSource { Explorer, SourceControl }

/**
 * One thing in the bin, and everything needed to put it back.
 *
 * [parentPath] is kept as well as [originalPath] because a restore needs the *container*, and after
 * a delete the entry's own path no longer resolves to anything — for a SAF document it is not even
 * addressable, since the document id died with the document.
 */
@Immutable
data class TrashEntry(
    val id: String,
    val name: String,
    /** Where it was, for the user to read. */
    val originalPath: String,
    /** Where it goes back to: a directory path, or a SAF tree/document uri. */
    val parentPath: String,
    /** True when [parentPath] is a SAF uri rather than a local path. */
    val saf: Boolean,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val deletedAtMillis: Long,
    /** Project name plus the folder inside it, as a label — "scmtest/src". */
    val location: String,
    val source: TrashSource,
)

/**
 * The app's bin: deleted files kept aside for a while instead of destroyed.
 *
 * **App-private, and one bin for every project.** Not a folder inside the project, which is the
 * other obvious place to put it: a bin under the project root is untracked content in the middle of
 * a git repository, so `git clean` — which is half of what "Discard all changes" runs — would delete
 * the very copies it had just made, and a trashed build directory would arrive in `git status` as
 * thousands of new files. Out of the tree, none of that can happen.
 *
 * Each entry is a directory of its own so that nothing has to be rewritten when one is added or
 * removed, and a half-finished delete can only ever damage its own entry:
 *
 * ```
 * <filesDir>/trash/<id>/entry.json   what it was, and where it came from
 * <filesDir>/trash/<id>/data/<name>  the thing itself
 * ```
 */
class Trash(private val root: File) {

    /** Everything in the bin, newest first. Entries that lost their payload are dropped as they surface. */
    suspend fun list(): List<TrashEntry> = withContext(Dispatchers.IO) {
        root.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { dir -> read(dir) }
            .sortedByDescending { it.deletedAtMillis }
    }

    /** Whether there is anything in the bin, without reading what. */
    suspend fun isEmpty(): Boolean = withContext(Dispatchers.IO) {
        root.listFiles().orEmpty().none { it.isDirectory }
    }

    /** What the bin is costing in storage, so the user can decide whether to care. */
    suspend fun totalBytes(): Long = withContext(Dispatchers.IO) {
        root.listFiles().orEmpty().sumOf { sizeOf(it) }
    }

    /**
     * Move something into the bin.
     *
     * [projectRoot] only shapes the label: it turns an absolute path nobody reads into the project
     * and folder the user recognises.
     */
    suspend fun put(
        context: Context,
        path: FsPath,
        projectName: String,
        projectRoot: String?,
        source: TrashSource,
    ): TrashEntry = withContext(Dispatchers.IO) {
        val id = newId()
        val dir = File(root, id)
        val data = File(dir, DATA).apply { mkdirs() }
        val entry = when (path) {
            is FsPath.Local -> takeLocal(path.file, data)
            is FsPath.Saf -> takeSaf(context, path.uri, data)
        }.let { taken ->
            TrashEntry(
                id = id,
                name = taken.name,
                originalPath = taken.original,
                parentPath = taken.parent,
                saf = path is FsPath.Saf,
                isDirectory = taken.isDirectory,
                sizeBytes = sizeOf(data),
                deletedAtMillis = System.currentTimeMillis(),
                location = label(projectName, projectRoot, taken.parent),
                source = source,
            )
        }
        write(dir, entry)
        entry
    }

    /**
     * Put one back where it came from, and return where that turned out to be.
     *
     * A name already taken is not a reason to refuse or to overwrite — the point of a bin is that
     * nothing in it is destroyed — so the restore lands beside the occupant under a numbered name
     * and the caller says so.
     */
    suspend fun restore(context: Context, entry: TrashEntry): String = withContext(Dispatchers.IO) {
        val payload = payloadOf(File(root, entry.id)) ?: error("'${entry.name}' is no longer in the Trash")
        val landed =
            if (entry.saf) restoreSaf(context, entry, payload) else restoreLocal(entry, payload)
        File(root, entry.id).deleteRecursively()
        landed
    }

    /** Destroy one entry for good. */
    suspend fun purge(id: String) = withContext(Dispatchers.IO) {
        File(root, id).deleteRecursively()
        Unit
    }

    suspend fun empty() = withContext(Dispatchers.IO) {
        root.listFiles().orEmpty().forEach { it.deleteRecursively() }
        Unit
    }

    /**
     * Drop whatever has been in the bin longer than the user chose to keep it.
     *
     * [retentionDays] of 0 means keep everything: a bin that quietly destroys things is worse than
     * no bin, so "forever" has to be sayable.
     */
    suspend fun sweep(retentionDays: Int): Int = withContext(Dispatchers.IO) {
        if (retentionDays <= 0) return@withContext 0
        val cutoff = System.currentTimeMillis() - retentionDays * DAY_MS
        var dropped = 0
        root.listFiles().orEmpty().filter { it.isDirectory }.forEach { dir ->
            val entry = read(dir)
            // An entry too damaged to read is swept too — it can never be restored, and leaving it
            // means the bin only ever grows.
            if (entry == null || entry.deletedAtMillis < cutoff) {
                dir.deleteRecursively()
                dropped++
            }
        }
        dropped
    }

    // --- taking things in --------------------------------------------------------------------

    private class Taken(
        val name: String,
        val original: String,
        val parent: String,
        val isDirectory: Boolean,
    )

    private fun takeLocal(file: File, data: File): Taken {
        require(file.exists()) { "'${file.name}' no longer exists" }
        val taken = Taken(
            name = file.name,
            original = file.absolutePath,
            parent = file.parentFile?.absolutePath.orEmpty(),
            isDirectory = file.isDirectory,
        )
        val target = File(data, file.name)
        // A rename when the bin and the project share a volume, which is the normal case — projects
        // live under filesDir and so does this. It costs nothing whatever the folder weighs; the
        // copy below is the fallback for a project still on the legacy shared storage.
        if (file.renameTo(target)) return taken
        if (file.isDirectory) {
            file.copyRecursively(target, overwrite = true)
        } else {
            file.copyTo(target, overwrite = true)
        }
        check(file.deleteRecursively()) { "Could not remove '${file.name}' after copying it to the Trash" }
        return taken
    }

    private fun takeSaf(context: Context, uri: android.net.Uri, data: File): Taken {
        val doc = DocumentFile.fromTreeUri(context, uri)
            ?: DocumentFile.fromSingleUri(context, uri)
            ?: error("Unable to resolve $uri")
        val name = doc.name ?: uri.lastPathSegment ?: "item"
        val parent = doc.parentFile?.uri?.toString().orEmpty()
        require(parent.isNotEmpty()) { "'$name' has no parent folder to restore into" }
        copySafInto(context, doc, File(data, name))
        check(doc.delete()) { "Could not remove '$name' after copying it to the Trash" }
        return Taken(name = name, original = uri.toString(), parent = parent, isDirectory = doc.isDirectory)
    }

    private fun copySafInto(context: Context, doc: DocumentFile, target: File) {
        if (doc.isDirectory) {
            target.mkdirs()
            doc.listFiles().forEach { child ->
                copySafInto(context, child, File(target, child.name ?: child.uri.lastPathSegment ?: "item"))
            }
            return
        }
        target.parentFile?.mkdirs()
        context.contentResolver.openInputStream(doc.uri)?.use { input ->
            target.outputStream().use { input.copyTo(it) }
        } ?: error("Unable to read ${doc.name}")
    }

    // --- putting things back -----------------------------------------------------------------

    private fun restoreLocal(entry: TrashEntry, payload: File): String {
        val parent = File(entry.parentPath)
        parent.mkdirs()
        val target = freeName(parent, payload.name)
        if (!payload.renameTo(target)) {
            if (payload.isDirectory) payload.copyRecursively(target, overwrite = true)
            else payload.copyTo(target, overwrite = true)
        }
        return target.absolutePath
    }

    private fun restoreSaf(context: Context, entry: TrashEntry, payload: File): String {
        val parentUri = android.net.Uri.parse(entry.parentPath)
        val parent = DocumentFile.fromTreeUri(context, parentUri)
            ?: DocumentFile.fromSingleUri(context, parentUri)
            ?: error("The folder '${entry.name}' came from is no longer available")
        val taken = parent.listFiles().mapNotNull { it.name }.toSet()
        val name = generateSequence(0) { it + 1 }
            .map { if (it == 0) payload.name else suffixed(payload.name, it) }
            .first { it !in taken }
        writeSaf(context, parent, payload, name)
        return parent.uri.toString() + "/" + name
    }

    private fun writeSaf(context: Context, parent: DocumentFile, payload: File, name: String) {
        if (payload.isDirectory) {
            val dir = parent.createDirectory(name) ?: error("Could not recreate the folder '$name'")
            payload.listFiles().orEmpty().forEach { child -> writeSaf(context, dir, child, child.name) }
            return
        }
        val file = parent.createFile("application/octet-stream", name)
            ?: error("Could not recreate the file '$name'")
        context.contentResolver.openOutputStream(file.uri)?.use { out ->
            payload.inputStream().use { it.copyTo(out) }
        } ?: error("Could not write '$name'")
    }

    /** [name] in [parent], or the first numbered variant of it that nothing else answers to. */
    private fun freeName(parent: File, name: String): File =
        generateSequence(0) { it + 1 }
            .map { File(parent, if (it == 0) name else suffixed(name, it)) }
            .first { !it.exists() }

    /** "notes.txt" at 2 is "notes (2).txt" — the number goes before the extension, not after it. */
    private fun suffixed(name: String, n: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "$name ($n)" else name.substring(0, dot) + " ($n)" + name.substring(dot)
    }

    // --- the entry file ------------------------------------------------------------------------

    private fun read(dir: File): TrashEntry? {
        val json = runCatching { JSONObject(File(dir, ENTRY).readText()) }.getOrNull() ?: return null
        val name = json.optString("name").ifBlank { return null }
        if (payloadOf(dir) == null) return null
        return TrashEntry(
            id = dir.name,
            name = name,
            originalPath = json.optString("originalPath"),
            parentPath = json.optString("parentPath"),
            saf = json.optBoolean("saf"),
            isDirectory = json.optBoolean("isDirectory"),
            sizeBytes = json.optLong("sizeBytes"),
            deletedAtMillis = json.optLong("deletedAtMillis"),
            location = json.optString("location"),
            source = runCatching { TrashSource.valueOf(json.optString("source")) }
                .getOrDefault(TrashSource.Explorer),
        )
    }

    private fun write(dir: File, entry: TrashEntry) {
        File(dir, ENTRY).writeText(
            JSONObject()
                .put("name", entry.name)
                .put("originalPath", entry.originalPath)
                .put("parentPath", entry.parentPath)
                .put("saf", entry.saf)
                .put("isDirectory", entry.isDirectory)
                .put("sizeBytes", entry.sizeBytes)
                .put("deletedAtMillis", entry.deletedAtMillis)
                .put("location", entry.location)
                .put("source", entry.source.name)
                .toString(),
        )
    }

    /** The single child of an entry's `data/` — the thing that was deleted. */
    private fun payloadOf(dir: File): File? = File(dir, DATA).listFiles()?.firstOrNull()

    private fun sizeOf(file: File): Long =
        if (file.isDirectory) file.walkTopDown().filter { it.isFile }.sumOf { it.length() } else file.length()

    private fun label(projectName: String, projectRoot: String?, parent: String): String {
        val root = projectRoot?.trimEnd('/').orEmpty()
        val inside = when {
            root.isEmpty() -> ""
            parent == root -> ""
            parent.startsWith("$root/") -> parent.removePrefix("$root/")
            // A device-storage folder addresses itself with a content uri, which is not something to
            // show anyone; the project name alone is the honest label.
            parent.contains("://") -> ""
            else -> return parent
        }
        return if (inside.isEmpty()) projectName else "$projectName/$inside"
    }

    /** Sortable and unique: the moment it was binned, plus enough randomness for the same millisecond. */
    private fun newId(): String =
        System.currentTimeMillis().toString() + "-" + java.util.UUID.randomUUID().toString().take(8)

    private companion object {
        const val ENTRY = "entry.json"
        const val DATA = "data"
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
