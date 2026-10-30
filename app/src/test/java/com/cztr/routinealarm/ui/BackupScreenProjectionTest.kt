package com.cztr.routinealarm.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BackupScreenProjectionTest {
    private val root = File(System.getProperty("user.dir")).let { working ->
        if (File(working, "app/src/main").isDirectory) working
        else requireNotNull(working.parentFile).also {
            require(File(it, "app/src/main").isDirectory) { "Project source root unavailable" }
        }
    }
    private fun source(path: String) = File(root, path).readText()

    @Test fun backupActivityUsesProjectedTypedSurface() {
        val activity = source("app/src/main/java/com/cztr/routinealarm/backup/BackupActivity.kt")
        assertTrue(activity.contains("BackupUiMapper.map"))
        assertTrue(activity.contains("BackupScreenView"))
        assertTrue(activity.contains("handleBackupAction"))
    }

    @Test fun viewKeepsExistingNavigationLabels() {
        val view = source("app/src/main/java/com/cztr/routinealarm/ui/backup/BackupScreenView.kt")
        listOf("Datei speichern", "Datei laden", "Testverlauf öffnen", "Schließen").forEach {
            assertTrue("Missing $it", view.contains(it))
        }
    }

    @Test fun viewOwnsNoRepositoryOrDocumentIo() {
        val view = source("app/src/main/java/com/cztr/routinealarm/ui/backup/BackupScreenView.kt")
        assertTrue(view.contains("onAction: (BackupUiAction) -> Unit"))
        assertFalse(view.contains("TestAlarmRuntime"))
        assertFalse(view.contains("BackupDocumentIO"))
        assertFalse(view.contains("contentResolver"))
    }

    @Test fun platformIoRemainsOffTheUiThread() {
        val activity = source("app/src/main/java/com/cztr/routinealarm/backup/BackupActivity.kt")
        assertTrue(activity.contains("worker.execute"))
        assertTrue(activity.contains("runOnUiThread"))
    }

    @Test fun backupActionsRemainReachableAtLargeText() {
        val view = source("app/src/main/java/com/cztr/routinealarm/ui/backup/BackupScreenView.kt")
        assertTrue(view.contains("R52Dimensions.TOUCH_TARGET"))
        assertTrue(view.contains("maxLines"))
        assertTrue(view.contains("ellipsize"))
        assertTrue(view.contains("ScrollView"))
    }
}
