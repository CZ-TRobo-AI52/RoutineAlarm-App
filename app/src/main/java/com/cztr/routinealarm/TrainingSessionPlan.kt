package com.cztr.routinealarm

import java.time.DayOfWeek

object TrainingSessionPlan {

    fun eventsFor(day: DayOfWeek): List<RoutineEvent> =
        when (day) {
            DayOfWeek.MONDAY -> TrainingSchedule.monday
            DayOfWeek.TUESDAY -> TrainingSchedule.tuesday
            DayOfWeek.WEDNESDAY -> TrainingSchedule.wednesday
            DayOfWeek.THURSDAY -> TrainingSchedule.thursday
            DayOfWeek.FRIDAY -> TrainingSchedule.friday
            DayOfWeek.SATURDAY -> TrainingSchedule.saturday
            DayOfWeek.SUNDAY -> emptyList()
        }

    val startEvents: List<RoutineEvent> =
        listOfNotNull(
            TrainingSchedule.monday.firstOrNull(),
            TrainingSchedule.tuesday.firstOrNull(),
            TrainingSchedule.wednesday.firstOrNull(),
            TrainingSchedule.thursday.firstOrNull(),
            TrainingSchedule.friday.firstOrNull(),
            TrainingSchedule.saturday.firstOrNull()
        )

    private val startIds: Set<String> =
        startEvents.map { it.id }.toSet()

    fun isStartEvent(id: String): Boolean =
        id in startIds
}
