package com.cztr.routinealarm.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.cztr.routinealarm.ui.design.R52Colors
import com.cztr.routinealarm.ui.shell.ThemeChoice
import com.cztr.routinealarm.ui.system.SystemScreenView
import com.cztr.routinealarm.ui.system.SystemUiInput
import com.cztr.routinealarm.ui.system.SystemUiMapper
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SystemScreenProjectionTest {
    private val root = File(System.getProperty("user.dir")).let { working ->
        if (File(working, "app/src/main").isDirectory) working
        else requireNotNull(working.parentFile).also {
            require(File(it, "app/src/main").isDirectory) { "Project source root unavailable" }
        }
    }
    private fun source(path: String) = File(root, path).readText()

    @Test fun moreDestinationUsesProjectedSystemSurface() {
        val main = source("app/src/main/java/com/cztr/routinealarm/MainActivity.kt")
        assertTrue(main.contains("SystemProjectionSource.from"))
        assertTrue(main.contains("SystemUiMapper.map"))
        assertTrue(main.contains("SystemScreenView"))
    }

    @Test fun systemSurfaceOnlyEmitsTypedUiActions() {
        val view = source("app/src/main/java/com/cztr/routinealarm/ui/system/SystemScreenView.kt")
        assertTrue(view.contains("onAction: (SystemAction) -> Unit"))
        assertFalse(view.contains("AlarmScheduler"))
        assertFalse(view.contains("ScheduleState"))
        assertFalse(view.contains("TestAlarmRuntime"))
    }

    @Test fun systemSurfaceKeepsBackupAndRecoveryTruthBoundariesVisible() {
        val state = SystemUiMapper.map(SystemUiInput(
            notificationPermissionRequired = true, notificationGranted = false,
            exactAlarmGranted = false, weeklyPlanEnabled = false, recoveryRequested = true,
            versionName = "test", packageName = "test.package", theme = ThemeChoice.DARK,
        ))
        val screen = SystemScreenView(RuntimeEnvironment.getApplication(), state, R52Colors.Dark) {}
        fun labels(node: View): List<String> =
            (if (node is TextView) listOf(node.text.toString()) else emptyList()) +
                (if (node is ViewGroup) (0 until node.childCount).flatMap { labels(node.getChildAt(it)) } else emptyList())
        val visibleText = labels(screen).joinToString("\n")
        assertTrue(visibleText.contains("Sicherung & Wiederherstellung"))
        assertTrue(visibleText.contains("Recovery"))
        assertTrue(visibleText.contains("nicht bestätigt"))
        assertTrue(visibleText.contains("unverschlüsselt"))
    }

    @Test fun primarySystemActionsRemainReachableAtLargeFont() {
        val view = source("app/src/main/java/com/cztr/routinealarm/ui/system/SystemScreenView.kt")
        assertTrue(view.contains("R52Dimensions.TOUCH_TARGET"))
        assertTrue(view.contains("maxLines"))
        assertTrue(view.contains("ellipsize"))
    }

    @Test fun mainRoutesSystemActionsThroughExistingCapabilities() {
        val main = source("app/src/main/java/com/cztr/routinealarm/MainActivity.kt")
        assertTrue(main.contains("SystemAction.REQUEST_NOTIFICATIONS"))
        assertTrue(main.contains("SystemAction.REQUEST_EXACT_ALARMS"))
        assertTrue(main.contains("SystemAction.OPEN_BACKUP"))
        assertTrue(main.contains("SystemAction.SCHEDULE_TEST_ALARM"))
        assertTrue(main.contains("SystemAction.SET_THEME_DARK"))
    }
}
