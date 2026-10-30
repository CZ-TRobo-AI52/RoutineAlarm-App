package com.cztr.routinealarm.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AlarmScreenProjectionTest {
    private val root = File(System.getProperty("user.dir")).let { working ->
        if (File(working, "app/src/main").isDirectory) working
        else requireNotNull(working.parentFile).also {
            require(File(it, "app/src/main").isDirectory) { "Project source root unavailable" }
        }
    }
    private fun source(path: String) = File(root, path).readText()

    @Test fun alarmActivityUsesProjectedTypedAlarmSurface() {
        val activity = source("app/src/main/java/com/cztr/routinealarm/AlarmActivity.kt")
        assertTrue(activity.contains("AlarmUiMapper.map"))
        assertTrue(activity.contains("AlarmScreenView"))
        assertTrue(activity.contains("handleAlarmUiAction"))
    }

    @Test fun viewDoesNotOwnSchedulerOrServiceAuthority() {
        val view = source("app/src/main/java/com/cztr/routinealarm/ui/alarm/AlarmScreenView.kt")
        assertTrue(view.contains("onAction: (AlarmUiAction) -> Unit"))
        assertFalse(view.contains("AlarmScheduler"))
        assertFalse(view.contains("AlarmService"))
        assertFalse(view.contains("ScheduleState"))
    }

    @Test fun screenCloseAndOutputCloseRemainDifferentActions() {
        val activity = source("app/src/main/java/com/cztr/routinealarm/AlarmActivity.kt")
        assertTrue(activity.contains("AlarmUiAction.CLOSE_SCREEN -> finish()"))
        assertTrue(activity.contains("AlarmUiAction.CLOSE_OUTPUT ->"))
        assertTrue(activity.contains("stopAlarm()"))
    }

    @Test fun snoozeMenuSupportsOneThroughFiveMinutes() {
        val view = source("app/src/main/java/com/cztr/routinealarm/ui/alarm/AlarmScreenView.kt")
        val mapper = source("app/src/main/java/com/cztr/routinealarm/ui/alarm/AlarmUiMapper.kt")
        assertTrue(mapper.contains("(1..5).toList()"))
        assertTrue(view.contains("Minute später"))
        assertTrue(view.contains("Minuten später"))
    }

    @Test fun alarmActionsStayReachableWithLargeText() {
        val view = source("app/src/main/java/com/cztr/routinealarm/ui/alarm/AlarmScreenView.kt")
        assertTrue(view.contains("R52Dimensions.TOUCH_TARGET"))
        assertTrue(view.contains("maxLines"))
        assertTrue(view.contains("ellipsize"))
        assertTrue(view.contains("ScrollView"))
    }
}
