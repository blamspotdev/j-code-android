package dev.jcode.fs

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

@Dao
interface WorkspaceDao {
    @Transaction
    @Query("SELECT * FROM workspaces WHERE id = :id LIMIT 1")
    fun observeWorkspace(id: Long): Flow<WorkspaceWithProjects?>

    @Transaction
    @Query("SELECT * FROM workspaces ORDER BY lastOpened DESC")
    fun observeWorkspaces(): Flow<List<WorkspaceWithProjects>>

    @Query("SELECT * FROM workspaces ORDER BY lastOpened DESC LIMIT 1")
    suspend fun getMostRecentWorkspace(): WorkspaceEntity?

    @Query("SELECT * FROM workspaces WHERE rootPath = :rootPath LIMIT 1")
    suspend fun getWorkspaceByRootPath(rootPath: String): WorkspaceEntity?

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun getProject(id: Long): ProjectEntity?

    // --- One-time ext4 migration helpers (re-anchor absolute host paths; see WorkspaceManager). ---
    @Query("SELECT * FROM workspaces")
    suspend fun getAllWorkspaces(): List<WorkspaceEntity>

    @Query("SELECT * FROM projects")
    suspend fun getAllProjects(): List<ProjectEntity>

    @Query("SELECT * FROM recents")
    suspend fun getAllRecents(): List<RecentEntity>

    // In-place UPDATE (NOT an @Insert REPLACE): REPLACE on workspaces would DELETE+INSERT the row and
    // CASCADE-delete its projects (ForeignKey.CASCADE), wiping them during migration.
    @Query("UPDATE workspaces SET rootPath = :rootPath WHERE id = :id")
    suspend fun updateWorkspaceRootPath(id: Long, rootPath: String)

    @Query("DELETE FROM recents WHERE uri = :uri")
    suspend fun deleteRecent(uri: String)

    @Query("SELECT location FROM projects WHERE workspaceId = :workspaceId")
    suspend fun getProjectLocations(workspaceId: Long): List<String>

    @Query("UPDATE projects SET name = :name, location = :location WHERE id = :id")
    suspend fun updateProject(id: Long, name: String, location: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorkspace(workspace: WorkspaceEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProject(project: ProjectEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecent(recent: RecentEntity)

    @Query("SELECT * FROM recents ORDER BY pinned DESC, lastOpened DESC LIMIT :limit")
    fun observeRecents(limit: Int): Flow<List<RecentEntity>>

    @Query("SELECT COUNT(*) FROM projects WHERE workspaceId = :workspaceId")
    suspend fun projectCount(workspaceId: Long): Int

    @Query("DELETE FROM projects WHERE id = :projectId")
    suspend fun deleteProject(projectId: Long)

    @Query("UPDATE workspaces SET lastOpened = :lastOpened WHERE id = :workspaceId")
    suspend fun updateWorkspaceLastOpened(workspaceId: Long, lastOpened: Long)
}

@Database(
    entities = [WorkspaceEntity::class, ProjectEntity::class, RecentEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class WorkspaceDatabase : RoomDatabase() {
    abstract fun workspaceDao(): WorkspaceDao
}

@Singleton
class SafPermissionStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val rememberedTreesKey = stringSetPreferencesKey("remembered_saf_tree_uris")

    val rememberedTrees: Flow<Set<Uri>> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            preferences[rememberedTreesKey].orEmpty().map(Uri::parse).toSet()
        }

    suspend fun remember(uri: Uri) {
        dataStore.edit { preferences ->
            preferences[rememberedTreesKey] = preferences[rememberedTreesKey].orEmpty() + uri.toString()
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object FsModule {
    @Provides
    @Singleton
    fun provideWorkspaceDatabase(
        @ApplicationContext context: Context,
    ): WorkspaceDatabase = Room.databaseBuilder(
        context,
        WorkspaceDatabase::class.java,
        "jcode-workspaces.db",
    ).fallbackToDestructiveMigration().build()

    @Provides
    fun provideWorkspaceDao(database: WorkspaceDatabase): WorkspaceDao = database.workspaceDao()

    @Provides
    @Singleton
    fun provideFsPreferencesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
        context.preferencesDataStoreFile("fs-preferences.preferences_pb")
    }
}
