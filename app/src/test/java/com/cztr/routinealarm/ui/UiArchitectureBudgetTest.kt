package com.cztr.routinealarm.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UiArchitectureBudgetTest {
    private val root = File(System.getProperty("user.dir")).let { working ->
        if (File(working, "app/src/main").isDirectory) working
        else requireNotNull(working.parentFile).also {
            require(File(it, "app/src/main").isDirectory) { "Project source root unavailable" }
        }
    }
    private fun source(path: String) = File(root, path).readText()

    private val screens = listOf(
        "home/HomeScreenView.kt", "plan/PlanScreenView.kt", "studio/StudioScreenView.kt",
        "history/HistoryScreenView.kt", "system/SystemScreenView.kt", "alarm/AlarmScreenView.kt",
        "backup/BackupScreenView.kt", "testalarm/TestAlarmScreenView.kt",
    ).map { "app/src/main/java/com/cztr/routinealarm/ui/$it" }

    @Test fun headingSemanticsAreSizeQualifiedInsteadOfEveryBoldLabel() {
        val helper = source("app/src/main/java/com/cztr/routinealarm/ui/accessibility/R52Accessibility.kt")
        assertTrue(helper.contains("fun isHeading"))
        assertTrue(helper.contains("size >= 18f"))
        screens.forEach { path ->
            val text = source(path)
            assertTrue("Qualified heading missing in $path", text.contains("R52Accessibility.isHeading"))
        }
    }

    @Test fun pureMappersRemainAndroidFree() {
        val mappers = File(root, "app/src/main/java/com/cztr/routinealarm/ui")
            .walkTopDown().filter { it.isFile && it.name.endsWith("UiMapper.kt") }.toList()
        assertTrue(mappers.isNotEmpty())
        mappers.forEach { file ->
            val text = file.readText()
            assertFalse("Android import in pure mapper ${file.path}", text.contains("import android."))
            assertFalse("Context in pure mapper ${file.path}", text.contains("android.content.Context"))
        }
    }

    @Test fun screenViewsDoNotOwnPersistenceOrSchedulingEffects() {
        val forbidden = listOf("SQLite", "Sqlite", "AlarmManager", ".repository(", "scheduleAll(", "restoreBackup(")
        screens.forEach { path ->
            val text = source(path)
            forbidden.forEach { token ->
                assertFalse("$token leaked into $path", text.contains(token))
            }
        }
    }

    @Test fun screenFilesRemainBoundedAndScrollableWhereNeeded() {
        screens.forEach { path ->
            val lines = source(path).lineSequence().count()
            assertTrue("Screen grew beyond reviewable budget: $path ($lines)", lines <= 180)
        }
        listOf("alarm/AlarmScreenView.kt", "backup/BackupScreenView.kt", "testalarm/TestAlarmScreenView.kt")
            .forEach { assertTrue(source("app/src/main/java/com/cztr/routinealarm/ui/$it").contains("ScrollView")) }
    }

    @Test fun asynchronousReadersRetainLifecycleInvalidation() {
        val main = source("app/src/main/java/com/cztr/routinealarm/MainActivity.kt")
        val history = source("app/src/main/java/com/cztr/routinealarm/execution/TestAlarmActivity.kt")
        assertTrue(main.contains("LatestUiLoader"))
        assertTrue(main.contains("testStatusLoader.invalidate()"))
        assertTrue(main.contains("testStatusLoader.close()"))
        assertTrue(history.contains("reader.invalidate()"))
        assertTrue(history.contains("reader.close()"))
    }
}
