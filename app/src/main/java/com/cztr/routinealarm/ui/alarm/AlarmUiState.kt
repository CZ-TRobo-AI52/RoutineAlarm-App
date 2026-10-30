package com.cztr.routinealarm.ui.alarm

sealed interface AlarmUiAction {
    data object DONE : AlarmUiAction
    data object SKIP : AlarmUiAction
    data object CLOSE_OUTPUT : AlarmUiAction
    data object CLOSE_SCREEN : AlarmUiAction
    data class Snooze(val minutes: Int) : AlarmUiAction
}

data class AlarmUiState(
    val clockText: String,
    val dateText: String,
    val title: String,
    val spokenText: String?,
    val snoozeMinutes: List<Int>,
    val pendingSnoozeMinutes: Int?,
    val showCloseOutput: Boolean,
    val warning: String?,
    val snoozeBoundary: String,
    val evidenceBoundary: String,
)
