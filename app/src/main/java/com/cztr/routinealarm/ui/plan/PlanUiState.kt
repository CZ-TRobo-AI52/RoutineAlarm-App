package com.cztr.routinealarm.ui.plan

import java.time.DayOfWeek

enum class PlanAvailability { PAUSED, PERMISSION_REQUIRED, ACTIVE }

data class PlanDayChoice(val day: DayOfWeek, val label: String, val selected: Boolean)

data class PlanUiItem(
    val id: String,
    val title: String,
    val timeLabel: String,
    val categoryLabel: String,
)

data class PlanUiState(
    val availability: PlanAvailability,
    val title: String,
    val detail: String,
    val primaryActionLabel: String,
    val selectedDay: DayOfWeek,
    val dayTitle: String,
    val dayChoices: List<PlanDayChoice>,
    val events: List<PlanUiItem>,
    val training: List<PlanUiItem>,
    val showTrainingDetails: Boolean,
)
