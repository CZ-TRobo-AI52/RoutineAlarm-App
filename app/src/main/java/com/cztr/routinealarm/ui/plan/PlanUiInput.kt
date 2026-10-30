package com.cztr.routinealarm.ui.plan

import com.cztr.routinealarm.EventCategory
import com.cztr.routinealarm.RoutineEvent
import java.time.DayOfWeek

data class PlanProjectedItem(
    val id: String,
    val title: String,
    val minuteOfDay: Int,
    val days: Set<DayOfWeek>,
    val enabled: Boolean,
    val categoryLabel: String,
)

data class PlanUiInput(
    val planEnabled: Boolean,
    val exactAllowed: Boolean,
    val activeEventCount: Int,
    val selectedDay: DayOfWeek,
    val events: List<PlanProjectedItem>,
    val training: List<PlanProjectedItem>,
    val showTrainingDetails: Boolean,
)

/** Domain adapter only. It projects existing schedules and never mutates or schedules them. */
object PlanProjectionSource {
    fun from(
        planEnabled: Boolean,
        exactAllowed: Boolean,
        activeEventCount: Int,
        selectedDay: DayOfWeek,
        events: List<RoutineEvent>,
        training: List<RoutineEvent>,
        showTrainingDetails: Boolean,
    ) = PlanUiInput(
        planEnabled = planEnabled,
        exactAllowed = exactAllowed,
        activeEventCount = activeEventCount,
        selectedDay = selectedDay,
        events = events.map(::project),
        training = training.map(::project),
        showTrainingDetails = showTrainingDetails,
    )

    private fun project(event: RoutineEvent) = PlanProjectedItem(
        id = event.id,
        title = event.title,
        minuteOfDay = event.minuteOfDay,
        days = event.daysOfWeek,
        enabled = event.enabled,
        categoryLabel = category(event.category),
    )

    private fun category(category: EventCategory) = when (category) {
        EventCategory.MORNING -> "Morgenroutine"
        EventCategory.WORK -> "Arbeit"
        EventCategory.MEAL -> "Mahlzeit"
        EventCategory.TRAINING -> "Training"
        EventCategory.PERSONAL -> "Persönlich"
        EventCategory.PROJECT -> "Projekt"
        EventCategory.EVENING -> "Abendroutine"
        EventCategory.SLEEP -> "Schlaf"
    }
}
