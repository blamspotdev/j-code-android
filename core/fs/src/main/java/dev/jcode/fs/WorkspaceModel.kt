package dev.jcode.fs

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

const val DEFAULT_SHARED_WORKSPACE_ROOT: String = "/storage/emulated/0/JCode/workspaces/default"
const val DEFAULT_SHARED_PROJECTS_ROOT: String = "/storage/emulated/0/JCode/projects"

enum class ProjectKind {
    Local,
    Saf,
}

@Entity(tableName = "workspaces")
data class WorkspaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val rootPath: String,
    val lastOpened: Long,
)

@Entity(
    tableName = "projects",
    foreignKeys = [
        ForeignKey(
            entity = WorkspaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["workspaceId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("workspaceId")],
)
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val workspaceId: Long,
    val kind: ProjectKind,
    val location: String,
    val name: String,
    val distroBindTarget: String,
    val order: Int,
)

@Entity(tableName = "recents")
data class RecentEntity(
    @PrimaryKey val uri: String,
    val kind: ProjectKind,
    val lastOpened: Long,
    val pinned: Boolean,
)

data class WorkspaceWithProjects(
    @Embedded val workspace: WorkspaceEntity,
    @Relation(parentColumn = "id", entityColumn = "workspaceId")
    val projects: List<ProjectEntity>,
)

@Immutable
data class Project(
    val id: Long,
    val kind: ProjectKind,
    val location: String,
    val name: String,
    val distroBindTarget: String,
    val order: Int,
) {
    val fsPath: FsPath
        get() = when (kind) {
            ProjectKind.Local -> FsPath.Local(java.io.File(location))
            ProjectKind.Saf -> FsPath.Saf(Uri.parse(location))
        }
}

@Immutable
data class Workspace(
    val id: Long,
    val name: String,
    val rootPath: String,
    val lastOpened: Long,
    val projects: List<Project>,
)

internal fun WorkspaceWithProjects.toDomain(): Workspace = Workspace(
    id = workspace.id,
    name = workspace.name,
    rootPath = workspace.rootPath,
    lastOpened = workspace.lastOpened,
    projects = projects.sortedBy { it.order }.map { entity ->
        Project(
            id = entity.id,
            kind = entity.kind,
            location = entity.location,
            name = entity.name,
            distroBindTarget = entity.distroBindTarget,
            order = entity.order,
        )
    },
)
