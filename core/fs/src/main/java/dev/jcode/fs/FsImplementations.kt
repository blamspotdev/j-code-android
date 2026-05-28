package dev.jcode.fs

import android.content.Context
import android.net.Uri
import android.os.FileObserver
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Singleton
class PosixFs @Inject constructor() : Fs {
    override suspend fun list(path: FsPath): List<FsNode> = withContext(Dispatchers.IO) {
        val file = path.requireLocal()
        file.listFiles().orEmpty()
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .map { child ->
                FsNode(
                    path = FsPath.Local(child),
                    name = child.name,
                    kind = if (child.isDirectory) FsKind.Directory else FsKind.File,
                    sizeBytes = if (child.isFile) child.length() else 0L,
                    modifiedAtMillis = child.lastModified(),
                )
            }
    }

    override suspend fun read(path: FsPath): ByteArray = withContext(Dispatchers.IO) {
        path.requireLocal().readBytes()
    }

    override suspend fun write(path: FsPath, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val file = path.requireLocal()
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    override fun watch(path: FsPath): Flow<FsWatchEvent> = callbackFlow {
        val root = path.requireLocal()
        val observed = if (root.isDirectory) root else root.parentFile ?: root
        val observer = object : FileObserver(observed.absolutePath, ALL_EVENTS) {
            override fun onEvent(event: Int, relativePath: String?) {
                val type = when {
                    event and (CREATE or MOVED_TO) != 0 -> FsWatchEventType.Created
                    event and (DELETE or DELETE_SELF or MOVED_FROM) != 0 -> FsWatchEventType.Deleted
                    event and (MODIFY or CLOSE_WRITE or ATTRIB) != 0 -> FsWatchEventType.Modified
                    else -> FsWatchEventType.FullRescan
                }
                trySend(FsWatchEvent(FsPath.Local(observed), relativePath, type))
            }
        }

        observer.startWatching()
        awaitClose { observer.stopWatching() }
    }.flowOn(Dispatchers.IO)
}

@Singleton
class SafFs @Inject constructor(
    @ApplicationContext private val context: Context,
) : Fs {
    override suspend fun list(path: FsPath): List<FsNode> = withContext(Dispatchers.IO) {
        val root = path.requireSafDocument(context)
        root.listFiles()
            .sortedWith(compareBy<DocumentFile>({ !it.isDirectory }, { it.name?.lowercase().orEmpty() }))
            .map { child ->
                FsNode(
                    path = FsPath.Saf(child.uri),
                    name = child.name ?: child.uri.toString(),
                    kind = if (child.isDirectory) FsKind.Directory else FsKind.File,
                    sizeBytes = child.length(),
                    modifiedAtMillis = child.lastModified(),
                )
            }
    }

    override suspend fun read(path: FsPath): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(path.requireSafUri())?.use { it.readBytes() }
            ?: error("Unable to read $path")
    }

    override suspend fun write(path: FsPath, bytes: ByteArray) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(path.requireSafUri(), "wt")?.use { stream ->
            stream.write(bytes)
        } ?: error("Unable to write $path")
    }

    override fun watch(path: FsPath): Flow<FsWatchEvent> = flow {
        val rootUri = path.requireSafUri()
        var previous = snapshotDirectory(rootUri)
        emit(FsWatchEvent(FsPath.Saf(rootUri), null, FsWatchEventType.FullRescan))

        while (currentCoroutineContext().isActive) {
            delay(2_000)
            val current = snapshotDirectory(rootUri)
            if (current != previous) {
                emit(FsWatchEvent(FsPath.Saf(rootUri), null, FsWatchEventType.FullRescan))
                previous = current
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun snapshotDirectory(uri: Uri): List<String> {
        val root = uri.requireSafDocument(context)
        return root.listFiles().map { file ->
            buildString {
                append(file.name ?: file.uri.toString())
                append('#')
                append(file.lastModified())
                append('#')
                append(file.length())
            }
        }.sorted()
    }
}

private fun FsPath.requireLocal(): File = when (this) {
    is FsPath.Local -> file
    is FsPath.Saf -> error("Expected local path but was SAF: $uri")
}

private fun FsPath.requireSafUri(): Uri = when (this) {
    is FsPath.Local -> error("Expected SAF path but was local: ${file.path}")
    is FsPath.Saf -> uri
}

private fun FsPath.requireSafDocument(context: Context): DocumentFile = requireSafUri().requireSafDocument(context)

private fun Uri.requireSafDocument(context: Context): DocumentFile {
    return DocumentFile.fromTreeUri(context, this)
        ?: DocumentFile.fromSingleUri(context, this)
        ?: error("Unable to resolve document for $this")
}
