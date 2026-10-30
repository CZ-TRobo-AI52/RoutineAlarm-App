package com.cztr.routinealarm.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TestAlarmScreenProjectionTest {
    private val root = File(System.getProperty("user.dir")).let { working ->
        if (File(working, "app/src/main").isDirectory) working
        else requireNotNull(working.parentFile).also {
            require(File(it, "app/src/main").isDirectory) { "Project source root unavailable" }
        }
    }
    private fun source(path: String) = File(root, path).readText()

    @Test fun activityUsesProjectedTypedTestAlarmSurface() {
        val activity = source("app/src/main/java/com/cztr/routinealarm/execution/TestAlarmActivity.kt")
        assertTrue(activity.contains("TestAlarmUiMapper"))
        assertTrue(activity.contains("TestAlarmScreenView"))
        assertTrue(activity.contains("handleUiAction"))
        assertTrue(activity.contains("LatestUiLoader"))
    }

    @Test fun viewOwnsNoRepositorySchedulerOrRestoreAuthority() {
        val view = source("app/src/main/java/com/cztr/routinealarm/ui/testalarm/TestAlarmScreenView.kt")
        assertTrue(view.contains("onAction: (TestAlarmUiAction) -> Unit"))
        assertFalse(view.contains("TestAlarmRuntime"))
        assertFalse(view.contains("AlarmScheduler"))
        assertFalse(view.contains("activateRestored"))
    }

    @Test fun existingUserVisibleActionLabelsRemainStable() {
        val view = source("app/src/main/java/com/cztr/routinealarm/ui/testalarm/TestAlarmScreenView.kt")
        listOf("Erledigt", "Überspringen", "Testverlauf öffnen", "Erneut versuchen",
            "Wiederhergestellten Test aktivieren", "Offenen Testwecker öffnen", "Erneut planen",
            "Neuere Tests", "Ältere Tests", "Schließen").forEach {
            assertTrue("Missing $it", view.contains(it))
        }
    }

    @Test fun fiveSnoozeDurationsRemainReachable() {
        val view = source("app/src/main/java/com/cztr/routinealarm/ui/testalarm/TestAlarmScreenView.kt")
        assertTrue(view.contains("1 Minute später"))
        assertTrue(view.contains("\$minutes Minuten später"))
    }

    @Test fun screenSupportsLargeTextAndScrolling() {
        val view = source("app/src/main/java/com/cztr/routinealarm/ui/testalarm/TestAlarmScreenView.kt")
        assertTrue(view.contains("R52Dimensions.TOUCH_TARGET"))
        assertTrue(view.contains("maxLines"))
        assertTrue(view.contains("ellipsize"))
        assertTrue(view.contains("ScrollView"))
    }
}
