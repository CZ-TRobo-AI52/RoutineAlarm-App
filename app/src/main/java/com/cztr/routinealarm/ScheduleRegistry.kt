package com.cztr.routinealarm

object ScheduleRegistry {

    /*
     * Vollständige Registry für Lookup und stabile IDs.
     * Enthält auch alle einzelnen Trainings-Cues.
     */
    val allEvents: List<RoutineEvent> =
        PersonalSchedule.allEvents +
            TrainingSchedule.allEvents

    /*
     * Tatsächlich über AlarmManager geplante Ereignisse:
     * - alle persönlichen Routinen
     * - nur sechs Trainingsstarts
     *
     * Die einzelnen Trainings-Cues übernimmt TrainingSessionService.
     */
    val scheduledEvents: List<RoutineEvent> =
        PersonalSchedule.allEvents +
            TrainingSessionPlan.startEvents

    init {
        val allIds =
            allEvents.map { it.id }

        require(allIds.size == allIds.toSet().size) {
            "RoutineEvent IDs müssen global eindeutig sein"
        }

        val scheduledIds =
            scheduledEvents.map { it.id }

        require(
            scheduledIds.size ==
                scheduledIds.toSet().size
        ) {
            "Geplante RoutineEvent IDs müssen eindeutig sein"
        }
    }

    val enabledEvents: List<RoutineEvent>
        get() =
            scheduledEvents.filter { it.enabled }

    fun byId(id: String): RoutineEvent? =
        allEvents.firstOrNull { it.id == id }
}
