package com.cztr.routinealarm.ui.alarm

import java.time.LocalDateTime

data class AlarmUiInput(
    val title: String,
    val spokenText: String,
    val now: LocalDateTime,
    val deliveryAllowed: Boolean,
    val pendingSnoozeMinutes: Int?,
    val outputInstanceAvailable: Boolean,
)
