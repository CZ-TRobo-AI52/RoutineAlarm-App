package com.cztr.routinealarm.ui

import com.cztr.routinealarm.ui.shell.ThemeChoice
import com.cztr.routinealarm.ui.system.*
import org.junit.Assert.*
import org.junit.Test

class SystemUiMapperBoundaryTest {
    @Test fun deniedRequiredPermissionsRemainActionRequired() {
        val state = SystemUiMapper.map(SystemUiInput(
            notificationPermissionRequired = true,
            notificationGranted = false,
            exactAlarmGranted = false,
            weeklyPlanEnabled = false,
            recoveryRequested = true,
            versionName = "0.2.0-alpha14-test",
            packageName = "com.cztr.routinealarm.dev",
            theme = ThemeChoice.DARK,
        ))

        assertEquals(SystemReadiness.ACTION_REQUIRED, state.notifications.readiness)
        assertEquals(SystemAction.REQUEST_NOTIFICATIONS, state.notifications.action)
        assertEquals(SystemReadiness.ACTION_REQUIRED, state.exactAlarms.readiness)
        assertEquals(SystemAction.REQUEST_EXACT_ALARMS, state.exactAlarms.action)
    }

    @Test fun notificationPermissionBelowRuntimeGateIsNotReportedMissing() {
        val state = SystemUiMapper.map(SystemUiInput(
            notificationPermissionRequired = false,
            notificationGranted = false,
            exactAlarmGranted = true,
            weeklyPlanEnabled = true,
            recoveryRequested = true,
            versionName = "test",
            packageName = "package",
            theme = ThemeChoice.SYSTEM,
        ))

        assertEquals(SystemReadiness.READY, state.notifications.readiness)
        assertNull(state.notifications.action)
    }

    @Test fun recoveryRequestIsNeverPresentedAsCompletedRecovery() {
        val state = SystemUiMapper.map(SystemUiInput(
            notificationPermissionRequired = true,
            notificationGranted = true,
            exactAlarmGranted = true,
            weeklyPlanEnabled = true,
            recoveryRequested = true,
            versionName = "test",
            packageName = "package",
            theme = ThemeChoice.LIGHT,
        ))

        assertEquals(SystemReadiness.UNKNOWN, state.recovery.readiness)
        assertTrue(state.recovery.detail.contains("angefordert"))
        assertTrue(state.recovery.detail.contains("nicht bestätigt"))
    }

    @Test fun backupAndRestoreLimitsStayExplicit() {
        val state = SystemUiMapper.map(SystemUiInput(
            notificationPermissionRequired = true,
            notificationGranted = true,
            exactAlarmGranted = true,
            weeklyPlanEnabled = false,
            recoveryRequested = false,
            versionName = "test",
            packageName = "package",
            theme = ThemeChoice.SYSTEM,
        ))

        assertTrue(state.backup.detail.contains("unverschlüsselt"))
        assertTrue(state.backup.detail.contains("persönliche Ereignisse"))
        assertEquals(SystemAction.OPEN_BACKUP, state.backup.action)
        assertEquals("Ereignisverlauf sichern / laden", state.backup.actionLabel)
    }

    @Test fun buildIdentityIsProjectedWithoutInventedReleaseStatus() {
        val state = SystemUiMapper.map(SystemUiInput(
            notificationPermissionRequired = true,
            notificationGranted = true,
            exactAlarmGranted = true,
            weeklyPlanEnabled = false,
            recoveryRequested = false,
            versionName = "0.2.0-alpha14-test",
            packageName = "com.cztr.routinealarm.dev",
            theme = ThemeChoice.SYSTEM,
        ))

        assertEquals("0.2.0-alpha14-test", state.versionName)
        assertEquals("com.cztr.routinealarm.dev", state.packageName)
        assertFalse(state.buildClaim.contains("Gerät verifiziert", ignoreCase = true))
    }
}
