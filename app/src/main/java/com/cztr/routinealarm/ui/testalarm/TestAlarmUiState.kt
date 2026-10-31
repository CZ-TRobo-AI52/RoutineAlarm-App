package com.cztr.routinealarm.ui.testalarm

import com.cztr.routinealarm.ui.notification.MenuChoice
import com.cztr.routinealarm.ui.notification.MenuStage

enum class TestHistoryRowAction { ACTIVATE, OPEN, REPLAN }

data class TestHistoryUiRow(
    val id: String,
    val headline: String,
    val detail: String,
    val scheduling: String,
    val pausedNotice: String? = null,
    val action: TestHistoryRowAction? = null,
)

sealed interface TestAlarmUiAction {
    data object CLOSE : TestAlarmUiAction
    data object RETRY : TestAlarmUiAction
    data object OPEN_HISTORY : TestAlarmUiAction
    data object DONE : TestAlarmUiAction
    data object SKIP : TestAlarmUiAction
    data object NEWER : TestAlarmUiAction
    data object OLDER : TestAlarmUiAction
    data class Snooze(val minutes: Int) : TestAlarmUiAction
    data class HistoryRow(val id: String, val action: TestHistoryRowAction) : TestAlarmUiAction
    data class NotificationChoice(val choice: MenuChoice) : TestAlarmUiAction
}

sealed interface TestAlarmUiState {
    data class Loading(val title: String) : TestAlarmUiState
    data class Invalid(val title: String, val detail: String) : TestAlarmUiState
    data class Error(val message: String, val title: String = "Verlauf nicht verfügbar") : TestAlarmUiState
    data class NotificationActions(val title: String, val stage: MenuStage) : TestAlarmUiState
    data class Active(
        val title: String,
        val spokenText: String?,
        val timing: String?,
        val active: Boolean,
        val snoozeMinutes: List<Int>,
        val evidenceBoundary: String,
    ) : TestAlarmUiState
    data class History(
        val intro: String,
        val pausedNotice: String?,
        val generalNotice: String,
        val permissionNotice: String?,
        val emptyNotice: String?,
        val rows: List<TestHistoryUiRow>,
        val hasNewer: Boolean,
        val hasOlder: Boolean,
    ) : TestAlarmUiState
}
