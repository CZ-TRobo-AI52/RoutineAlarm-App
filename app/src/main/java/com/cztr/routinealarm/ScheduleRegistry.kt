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

    /** Explicit snapshot provider; reading definitions never schedules or grants activation. */
    fun personalEvents(definitions: List<RoutineEvent>): List<RoutineEvent> {
        require(definitions.map { it.id }.distinct().size == definitions.size)
        require(definitions.none { candidate -> TrainingSchedule.allEvents.any { it.id == candidate.id } })
        require(definitions.none { it.enabled }) { "Saved definitions require separate explicit activation" }
        val overrides = definitions.associateBy { it.id }
        return java.util.Collections.unmodifiableList(
            PersonalSchedule.allEvents.map { overrides[it.id] ?: it } +
                definitions.filter { saved -> PersonalSchedule.allEvents.none { it.id == saved.id } })
    }

    val enabledEvents: List<RoutineEvent>
        get() =
            scheduledEvents.filter { it.enabled }

    private val scheduledById = scheduledEvents.associateBy { it.id }

    fun scheduledById(id: String): RoutineEvent? = scheduledById[id]

    fun byId(id: String): RoutineEvent? =
        allEvents.firstOrNull { it.id == id }
}
