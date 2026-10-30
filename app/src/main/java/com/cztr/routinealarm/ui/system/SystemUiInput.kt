package com.cztr.routinealarm.ui.system

import com.cztr.routinealarm.ui.shell.ThemeChoice

/** Immutable facts projected by the Android host. This type performs no platform reads. */
data class SystemUiInput(
    val notificationPermissionRequired: Boolean,
    val notificationGranted: Boolean,
    val exactAlarmGranted: Boolean,
    val weeklyPlanEnabled: Boolean,
    val recoveryRequested: Boolean,
    val versionName: String,
    val packageName: String,
    val theme: ThemeChoice,
)

object SystemProjectionSource {
    fun from(
        notificationPermissionRequired: Boolean,
        notificationGranted: Boolean,
        exactAlarmGranted: Boolean,
        weeklyPlanEnabled: Boolean,
        recoveryRequested: Boolean,
        versionName: String,
        packageName: String,
        theme: ThemeChoice,
    ) = SystemUiInput(
        notificationPermissionRequired = notificationPermissionRequired,
        notificationGranted = notificationGranted,
        exactAlarmGranted = exactAlarmGranted,
        weeklyPlanEnabled = weeklyPlanEnabled,
        recoveryRequested = recoveryRequested,
        versionName = versionName,
        packageName = packageName,
        theme = theme,
    )
}
