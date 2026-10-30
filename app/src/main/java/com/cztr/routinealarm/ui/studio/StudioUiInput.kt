package com.cztr.routinealarm.ui.studio

import com.cztr.routinealarm.EventCategory
import com.cztr.routinealarm.EventMode
import com.cztr.routinealarm.RoutineEvent
import java.time.DayOfWeek

enum class StudioFilter(val label: String) { ALL("Alle"), ACTIVE("Aktiv"), PAUSED("Pausiert") }

data class StudioProjectedRoutine(
    val id: String,
    val title: String,
    val spokenText: String,
    val minuteOfDay: Int,
    val days: Set<DayOfWeek>,
    val enabled: Boolean,
    val categoryLabel: String,
    val modeLabel: String,
)

data class StudioUiInput(
    val routines: List<StudioProjectedRoutine>,
    val filter: StudioFilter,
    val selectedId: String?,
)

/** Read-only domain adapter until a separately authorized mutable schedule contract exists. */
object StudioProjectionSource {
    fun from(events: List<RoutineEvent>, filter: StudioFilter, selectedId: String?) = StudioUiInput(
        routines = events.map { event ->
            StudioProjectedRoutine(
                id = event.id,
                title = event.title,
                spokenText = event.spokenText,
                minuteOfDay = event.minuteOfDay,
                days = event.daysOfWeek,
                enabled = event.enabled,
                categoryLabel = category(event.category),
                modeLabel = if (event.mode == EventMode.ANNOUNCEMENT) "Ansage" else "Routine",
            )
        },
        filter = filter,
        selectedId = selectedId,
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
