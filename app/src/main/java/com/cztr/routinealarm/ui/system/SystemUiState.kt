package com.cztr.routinealarm.ui.system

import com.cztr.routinealarm.ui.shell.ThemeChoice

enum class SystemReadiness { READY, ACTION_REQUIRED, PAUSED, UNKNOWN }

enum class SystemAction {
    REQUEST_NOTIFICATIONS,
    REQUEST_EXACT_ALARMS,
    OPEN_BACKUP,
    OPEN_HISTORY,
    SCHEDULE_TEST_ALARM,
    SET_THEME_DARK,
    SET_THEME_LIGHT,
    SET_THEME_SYSTEM,
}

data class SystemStatus(
    val title: String,
    val detail: String,
    val readiness: SystemReadiness,
    val action: SystemAction? = null,
    val actionLabel: String? = null,
)

data class SystemUiState(
    val notifications: SystemStatus,
    val exactAlarms: SystemStatus,
    val weeklyPlan: SystemStatus,
    val recovery: SystemStatus,
    val backup: SystemStatus,
    val theme: ThemeChoice,
    val versionName: String,
    val packageName: String,
    val buildClaim: String,
)
