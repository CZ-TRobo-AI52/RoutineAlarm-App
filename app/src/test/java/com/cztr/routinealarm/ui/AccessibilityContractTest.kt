package com.cztr.routinealarm.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AccessibilityContractTest {
    private val root = File(System.getProperty("user.dir")).let { working ->
        if (File(working, "app/src/main").isDirectory) working
        else requireNotNull(working.parentFile).also {
            require(File(it, "app/src/main").isDirectory) { "Project source root unavailable" }
        }
    }
    private fun source(path: String) = File(root, path).readText()

    @Test fun accessibilityHelperGuardsPlatformApis() {
        val helper = source("app/src/main/java/com/cztr/routinealarm/ui/accessibility/R52Accessibility.kt")
        assertTrue(helper.contains("Build.VERSION.SDK_INT >= 28"))
        assertTrue(helper.contains("isAccessibilityHeading"))
        assertTrue(helper.contains("Build.VERSION.SDK_INT >= 30"))
        assertTrue(helper.contains("stateDescription"))
    }

    @Test fun sharedButtonKeepsContentDescriptionAndTouchTarget() {
        val button = source("app/src/main/java/com/cztr/routinealarm/ui/components/R52Button.kt")
        assertTrue(button.contains("contentDescription = label"))
        assertTrue(button.contains("R52Dimensions.TOUCH_TARGET"))
    }

    @Test fun bottomNavigationExposesSelectionState() {
        val nav = source("app/src/main/java/com/cztr/routinealarm/ui/components/R52BottomNav.kt")
        assertTrue(nav.contains("R52Accessibility.state"))
        assertTrue(nav.contains("Ausgewählt"))
    }

    @Test fun everyPrimarySurfaceMarksAccessibilityHeadings() {
        val surfaces = listOf(
            "app/src/main/java/com/cztr/routinealarm/ui/home/HomeScreenView.kt",
            "app/src/main/java/com/cztr/routinealarm/ui/plan/PlanScreenView.kt",
            "app/src/main/java/com/cztr/routinealarm/ui/studio/StudioScreenView.kt",
            "app/src/main/java/com/cztr/routinealarm/ui/history/HistoryScreenView.kt",
            "app/src/main/java/com/cztr/routinealarm/ui/system/SystemScreenView.kt",
            "app/src/main/java/com/cztr/routinealarm/ui/alarm/AlarmScreenView.kt",
            "app/src/main/java/com/cztr/routinealarm/ui/backup/BackupScreenView.kt",
            "app/src/main/java/com/cztr/routinealarm/ui/testalarm/TestAlarmScreenView.kt",
        )
        surfaces.forEach { path ->
            assertTrue("Heading semantics missing in $path", source(path).contains("R52Accessibility.heading"))
        }
    }

    @Test fun selectedFiltersAndThemesExposeStateNotColorAlone() {
        val plan = source("app/src/main/java/com/cztr/routinealarm/ui/plan/PlanScreenView.kt")
        val studio = source("app/src/main/java/com/cztr/routinealarm/ui/studio/StudioScreenView.kt")
        val system = source("app/src/main/java/com/cztr/routinealarm/ui/system/SystemScreenView.kt")
        assertTrue(plan.contains("R52Accessibility.state"))
        assertTrue(studio.contains("R52Accessibility.state"))
        assertTrue(system.contains("R52Accessibility.state"))
    }
}
