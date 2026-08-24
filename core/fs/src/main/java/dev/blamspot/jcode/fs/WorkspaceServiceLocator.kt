package dev.blamspot.jcode.fs

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room

object WorkspaceServiceLocator {
    @Volatile
    private var workspaceManager: WorkspaceManager? = null

    @Volatile
    private var trash: Trash? = null

    fun workspaceManager(context: Context): WorkspaceManager {
        return workspaceManager ?: synchronized(this) {
            workspaceManager ?: createWorkspaceManager(context.applicationContext).also {
                workspaceManager = it
            }
        }
    }

    /** The app's Trash. One bin for every project — see [Trash]. */
    fun trash(context: Context): Trash {
        return trash ?: synchronized(this) {
            trash ?: Trash(java.io.File(context.applicationContext.filesDir, "trash")).also {
                trash = it
            }
        }
    }

    private fun createWorkspaceManager(context: Context): WorkspaceManager {
        val database = Room.databaseBuilder(
            context,
            WorkspaceDatabase::class.java,
            "jcode-workspaces.db",
        ).fallbackToDestructiveMigration().build()

        val dataStore = PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile("fs-preferences.preferences_pb")
        }

        return WorkspaceManager(
            context = context,
            workspaceDao = database.workspaceDao(),
            safPermissionStore = SafPermissionStore(dataStore),
            posixFs = PosixFs(),
            safFs = SafFs(context),
        )
    }
}
