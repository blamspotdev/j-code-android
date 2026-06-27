package dev.jcode.fs

import android.net.Uri
import androidx.compose.runtime.Immutable
import java.io.File
import kotlinx.coroutines.flow.Flow

sealed interface FsPath {
    val displayName: String

    /** Globally-unique, stable identity for this path — safe as a map/set key or list key.
     *  Unlike [displayName] (a bare label), two distinct paths never share a [stableId]. */
    val stableId: String

    data class Local(val file: File) : FsPath {
        override val displayName: String = file.name.ifBlank { file.path }
        override val stableId: String = file.absolutePath
    }

    data class Saf(val uri: Uri) : FsPath {
        override val displayName: String = uri.lastPathSegment ?: uri.toString()
        override val stableId: String = uri.toString()
    }
}

enum class FsKind {
    File,
    Directory,
}

@Immutable
data class FsNode(
    val path: FsPath,
    val name: String,
    val kind: FsKind,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
)

enum class FsWatchEventType {
    Created,
    Modified,
    Deleted,
    FullRescan,
}

data class FsWatchEvent(
    val root: FsPath,
    val affectedPath: String?,
    val type: FsWatchEventType,
)

interface Fs {
    suspend fun list(path: FsPath): List<FsNode>
    suspend fun read(path: FsPath): ByteArray
    suspend fun write(path: FsPath, bytes: ByteArray)
    fun watch(path: FsPath): Flow<FsWatchEvent>
}
