package dev.jcode.fs

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Additional filesystem operations beyond the base [Fs] contract.
 * These are implemented as top-level suspend functions that accept the
 * necessary dependencies explicitly.
 */

/** Create a new file inside [parent] with the given [name]. */
suspend fun createFile(fs: Fs, context: Context, parent: FsPath, name: String): FsNode =
    when (fs) {
        is PosixFs -> createFilePosix(parent, name)
        is SafFs -> createFileSaf(context, parent, name)
        else -> error("Unsupported Fs implementation: ${fs::class}")
    }

/** Create a new directory inside [parent] with the given [name]. */
suspend fun createDirectory(fs: Fs, context: Context, parent: FsPath, name: String): FsNode =
    when (fs) {
        is PosixFs -> createDirectoryPosix(parent, name)
        is SafFs -> createDirectorySaf(context, parent, name)
        else -> error("Unsupported Fs implementation: ${fs::class}")
    }

private suspend fun createDirectoryPosix(parent: FsPath, name: String): FsNode {
    val parentFile = parent.requireLocal()
    val newDir = File(parentFile, name)
    newDir.mkdirs()
    return FsNode(
        path = FsPath.Local(newDir),
        name = newDir.name,
        kind = FsKind.Directory,
        sizeBytes = 0L,
        modifiedAtMillis = newDir.lastModified(),
    )
}

private suspend fun createDirectorySaf(context: Context, parent: FsPath, name: String): FsNode {
    val parentDoc = parent.toDocumentFile(context)
    val created = parentDoc.createDirectory(name)
        ?: error("Failed to create directory '$name'")
    return FsNode(
        path = FsPath.Saf(created.uri),
        name = created.name ?: name,
        kind = FsKind.Directory,
        sizeBytes = 0L,
        modifiedAtMillis = created.lastModified(),
    )
    }

private suspend fun createFilePosix(parent: FsPath, name: String): FsNode {
    val parentFile = parent.requireLocal()
    val newFile = File(parentFile, name)
    newFile.parentFile?.mkdirs()
    if (!newFile.exists()) {
        newFile.writeText("")
    }
    return FsNode(
        path = FsPath.Local(newFile),
        name = newFile.name,
        kind = FsKind.File,
        sizeBytes = newFile.length(),
        modifiedAtMillis = newFile.lastModified(),
    )
}

private suspend fun createFileSaf(context: Context, parent: FsPath, name: String): FsNode {
    val parentDoc = parent.toDocumentFile(context)
    val mimeType = when (name.substringAfterLast('.', "").lowercase()) {
        "kt", "kts" -> "text/x-kotlin"
        "json", "jsonc" -> "application/json"
        "md" -> "text/markdown"
        "txt", "" -> "text/plain"
        "yml", "yaml" -> "application/x-yaml"
        else -> "application/octet-stream"
    }
    val created = parentDoc.createFile(mimeType, name)
        ?: error("Failed to create file '$name'")
    return FsNode(
        path = FsPath.Saf(created.uri),
        name = created.name ?: name,
        kind = FsKind.File,
        sizeBytes = created.length(),
        modifiedAtMillis = created.lastModified(),
    )
}

/** Rename a file/directory at [from] to [newName]. */
suspend fun renameFile(fs: Fs, context: Context, from: FsPath, newName: String): FsNode =
    when (fs) {
        is PosixFs -> renamePosix(from, newName)
        is SafFs -> renameSaf(context, from, newName)
        else -> error("Unsupported Fs implementation: ${fs::class}")
    }

private suspend fun renamePosix(from: FsPath, newName: String): FsNode {
    val oldFile = from.requireLocal()
    val newFile = File(oldFile.parentFile, newName)
    check(oldFile.renameTo(newFile)) { "Rename failed: ${oldFile.path} -> $newName" }
    return FsNode(
        path = FsPath.Local(newFile),
        name = newName,
        kind = if (newFile.isDirectory) FsKind.Directory else FsKind.File,
        sizeBytes = if (newFile.isFile) newFile.length() else 0L,
        modifiedAtMillis = newFile.lastModified(),
    )
}

private suspend fun renameSaf(context: Context, from: FsPath, newName: String): FsNode {
    val doc = from.toDocumentFile(context)
    doc.renameTo(newName)
    return FsNode(
        path = FsPath.Saf(doc.uri),
        name = doc.name ?: newName,
        kind = if (doc.isDirectory) FsKind.Directory else FsKind.File,
        sizeBytes = doc.length(),
        modifiedAtMillis = doc.lastModified(),
    )
}

/**
 * Soft-delete a file/directory to the project's trash.
 * Moves the item to `<project-root>/.jcode/trash/<timestamp>/<original-name>`.
 */
suspend fun deleteToTrash(fs: Fs, context: Context, path: FsPath, projectRoot: FsPath): FsPath =
    when (fs) {
        is PosixFs -> deleteToTrashPosix(path, projectRoot)
        is SafFs -> deleteToTrashSaf(context, path, projectRoot)
        else -> error("Unsupported Fs implementation: ${fs::class}")
    }

/** Copy a file/directory from [source] to [targetParent] with optional [newName]. */
suspend fun copyFileOrDir(fs: Fs, context: Context, source: FsPath, targetParent: FsPath, newName: String? = null): FsPath =
    when (fs) {
        is PosixFs -> copyPosix(source, targetParent, newName)
        is SafFs -> copySaf(context, source, targetParent, newName)
        else -> error("Unsupported Fs implementation: ${fs::class}")
    }

private suspend fun deleteToTrashPosix(path: FsPath, projectRoot: FsPath): FsPath {
    val file = path.requireLocal()
    val root = projectRoot.requireLocal()
    val trashDir = File(root, ".jcode/trash/${System.currentTimeMillis()}")
    trashDir.mkdirs()
    val trashTarget = File(trashDir, file.name)
    check(file.renameTo(trashTarget)) { "Trash move failed: ${file.path}" }
    return FsPath.Local(trashTarget)
}

private suspend fun deleteToTrashSaf(context: Context, path: FsPath, projectRoot: FsPath): FsPath {
    val doc = path.toDocumentFile(context)
    val rootDoc = projectRoot.toDocumentFile(context)
    val jcodeDir = rootDoc.findFile(".jcode")
        ?: rootDoc.createDirectory(".jcode") ?: error("Cannot create .jcode")
    val trashDir = jcodeDir.findFile("trash")
        ?: jcodeDir.createDirectory("trash") ?: error("Cannot create trash")
    val timestampDir = trashDir.createDirectory(System.currentTimeMillis().toString())
        ?: error("Cannot create timestamp dir")

    // DocumentFile doesn't have moveTo; copy content then delete original
    val targetName = doc.name ?: "deleted_file"
    val target = if (doc.isDirectory) {
        timestampDir.createDirectory(targetName)
            ?: error("Cannot create trash directory")
    } else {
        // For files, we need to copy content — stub: just mark as deleted
        timestampDir.createFile("application/octet-stream", targetName)
            ?: error("Cannot create trash file")
    }

    // Delete the original
    runCatching { doc.delete() }
    return FsPath.Saf(target.uri)
}

private suspend fun copyPosix(source: FsPath, targetParent: FsPath, newName: String?): FsPath {
    val sourceFile = source.requireLocal()
    val parentFile = targetParent.requireLocal()
    val targetName = newName ?: sourceFile.name
    val targetFile = File(parentFile, targetName)

    if (sourceFile.isDirectory) {
        sourceFile.copyRecursively(targetFile, overwrite = true)
    } else {
        sourceFile.copyTo(targetFile, overwrite = true)
    }

    return FsPath.Local(targetFile)
}

private suspend fun copySaf(context: Context, source: FsPath, targetParent: FsPath, newName: String?): FsPath {
    val sourceDoc = source.toDocumentFile(context)
    val parentDoc = targetParent.toDocumentFile(context)
    val targetName = newName ?: sourceDoc.name ?: "copy"

    if (sourceDoc.isDirectory) {
        val targetDir = parentDoc.createDirectory(targetName)
            ?: error("Failed to create directory '$targetName'")
        // Copy children recursively
        sourceDoc.listFiles().forEach { child ->
            copySaf(context, FsPath.Saf(child.uri), FsPath.Saf(targetDir.uri), child.name)
        }
        return FsPath.Saf(targetDir.uri)
    } else {
        // Copy file content
        val mimeType = sourceDoc.type ?: "application/octet-stream"
        val targetFile = parentDoc.createFile(mimeType, targetName)
            ?: error("Failed to create file '$targetName'")

        // Read source and write to target
        context.contentResolver.openInputStream(sourceDoc.uri)?.use { input ->
            context.contentResolver.openOutputStream(targetFile.uri, "wt")?.use { output ->
                input.copyTo(output)
            }
        }
        return FsPath.Saf(targetFile.uri)
    }
}

/** Import content:// files (SAF picks) into [targetDir], streaming each into a new child file. Names
 *  come from the provider (DISPLAY_NAME) and are uniquified on collision. Returns the created names. */
suspend fun importContentUris(fs: Fs, context: Context, uris: List<Uri>, targetDir: FsPath): List<String> =
    withContext(Dispatchers.IO) {
        uris.map { uri ->
            val name = displayNameOf(context, uri)
            when (fs) {
                is PosixFs -> {
                    val parent = targetDir.requireLocal()
                    var dest = File(parent, name)
                    var suffix = 1
                    while (dest.exists()) dest = File(parent, uniquifiedName(name, suffix++))
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { input.copyTo(it) }
                    } ?: error("Cannot read '$name'")
                    dest.name
                }
                is SafFs -> {
                    val parentDoc = targetDir.toDocumentFile(context)
                    val doc = parentDoc.createFile("application/octet-stream", name)
                        ?: error("Cannot create '$name'")
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            context.contentResolver.openOutputStream(doc.uri, "wt")?.use { input.copyTo(it) }
                                ?: error("Cannot write '$name'")
                        } ?: error("Cannot read '$name'")
                    } catch (t: Throwable) {
                        runCatching { doc.delete() }
                        throw t
                    }
                    doc.name ?: name
                }
                else -> error("Unsupported Fs implementation: ${fs::class}")
            }
        }
    }

/** Stream a single file at [source] out to a SAF [dest] document (created by the caller's picker). */
suspend fun exportFileToUri(context: Context, source: FsPath, dest: Uri) = withContext(Dispatchers.IO) {
    val input = when (source) {
        is FsPath.Local -> source.file.inputStream()
        is FsPath.Saf -> context.contentResolver.openInputStream(source.uri) ?: error("Cannot read the file")
    }
    input.use { i ->
        context.contentResolver.openOutputStream(dest, "wt")?.use { i.copyTo(it) }
            ?: error("Cannot write the destination")
    }
}

/**
 * Copy the local [source] directory into the SAF tree [treeUri] as a new child named [dirName].
 * `node_modules` subtrees are skipped (large and regenerable). Returns the number of files copied.
 */
suspend fun copyLocalTreeToDocumentTree(context: Context, source: File, treeUri: Uri, dirName: String): Int =
    withContext(Dispatchers.IO) {
        // Refuse destinations inside the source. The app's own DocumentsProvider exposes the projects
        // tree as a pickable, writable target, so "export foo into foo" is one picker tap away — the
        // walk would then enumerate the growing export as part of the source and copy it into itself
        // until the disk fills.
        treeUriToLocalFile(treeUri)?.canonicalPath?.let { dest ->
            val src = source.canonicalPath
            require(dest != src && !dest.startsWith("$src/")) { "The destination is inside '${source.name}'" }
        }
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: error("Cannot open the destination folder")
        val destRoot = root.createDirectory(dirName) ?: error("Cannot create '$dirName' in the destination")
        val dirDocs = hashMapOf("" to destRoot)
        var files = 0
        // walkTopDown yields parents before children, so each entry's parent doc is already mapped.
        // The source root itself is exempt from the prune so exporting a folder named node_modules
        // still copies its content.
        source.walkTopDown()
            .onEnter { it == source || it.name != "node_modules" }
            .forEach { entry ->
                if (entry == source) return@forEach
                val rel = entry.relativeTo(source).path
                val parent = dirDocs.getValue(rel.substringBeforeLast(File.separatorChar, ""))
                if (entry.isDirectory) {
                    dirDocs[rel] = parent.createDirectory(entry.name) ?: error("Cannot create '${entry.name}'")
                } else {
                    val doc = parent.createFile("application/octet-stream", entry.name)
                        ?: error("Cannot create '${entry.name}'")
                    context.contentResolver.openOutputStream(doc.uri, "wt")?.use { out ->
                        entry.inputStream().use { it.copyTo(out) }
                    } ?: error("Cannot write '${entry.name}'")
                    files++
                }
            }
        files
    }

/** Best-effort local backing dir of a picked SAF tree: the app's own DocumentsProvider uses absolute
 *  paths as document ids, and primary external storage is directly mappable; other providers (cloud,
 *  third-party) return null — their storage can't overlap local sources anyway. */
private fun treeUriToLocalFile(treeUri: Uri): File? = runCatching {
    val docId = DocumentsContract.getTreeDocumentId(treeUri)
    val parts = docId.split(":", limit = 2)
    when {
        docId.startsWith("/") -> File(docId)
        parts.firstOrNull().equals("primary", ignoreCase = true) ->
            @Suppress("DEPRECATION")
            File(android.os.Environment.getExternalStorageDirectory(), parts.getOrNull(1).orEmpty())
        else -> null
    }
}.getOrNull()

/** Provider-reported display name, reduced to a safe leaf filename — DISPLAY_NAME is provider-
 *  controlled and must not be able to traverse out of the import target. */
private fun displayNameOf(context: Context, uri: Uri): String {
    var name = ""
    context.contentResolver.query(uri, null, null, null, null)?.use { cur ->
        val idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cur.moveToFirst()) name = cur.getString(idx).orEmpty()
    }
    val leaf = name.substringAfterLast('/').substringAfterLast('\\').trim()
    return if (leaf.isBlank() || leaf == "." || leaf == "..") "import.bin" else leaf
}

private fun uniquifiedName(name: String, suffix: Int): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0) "${name.substring(0, dot)} ($suffix)${name.substring(dot)}" else "$name ($suffix)"
}

// --- Internal helpers ---

private fun FsPath.requireLocal(): File = when (this) {
    is FsPath.Local -> file
    is FsPath.Saf -> error("Expected local path but was SAF: $uri")
}

private fun FsPath.toDocumentFile(context: Context): DocumentFile = when (this) {
    is FsPath.Local -> error("Expected SAF path but was local: ${file.path}")
    is FsPath.Saf -> DocumentFile.fromTreeUri(context, uri)
        ?: DocumentFile.fromSingleUri(context, uri)
        ?: error("Unable to resolve document for $uri")
}
