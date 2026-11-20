package dev.blamspot.jcode.fs

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecentFolderPruneTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun existingFolderIsKept() {
        val project = temp.newFolder("project")
        assertFalse(isFolderGone(project))
    }

    @Test
    fun deletedFolderIsGone() {
        val project = temp.newFolder("project")
        project.deleteRecursively()
        assertTrue(isFolderGone(project))
    }

    @Test
    fun projectOfADeletedWorkspaceIsGone() {
        val workspace = temp.newFolder("workspace")
        val project = File(workspace, "app").apply { mkdirs() }
        workspace.deleteRecursively()
        assertTrue(isFolderGone(project))
    }

    @Test
    fun folderUnderAnUnlistableAncestorIsKept() {
        // A regular file can't be listed, which is how an unreadable storage root looks here.
        val unlistable = temp.newFile("not-a-folder")
        assertFalse(isFolderGone(File(unlistable, "project")))
    }
}
