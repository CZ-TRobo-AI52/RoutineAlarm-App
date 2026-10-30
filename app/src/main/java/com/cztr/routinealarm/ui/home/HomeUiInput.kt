package com.cztr.routinealarm.ui.home

import com.cztr.routinealarm.AlarmScheduler
import com.cztr.routinealarm.EventCategory
import com.cztr.routinealarm.RoutineEvent
import java.time.ZonedDateTime

data class HomeProjectedItem(
    val id: String,
    val title: String,
    val categoryLabel: String,
    val projectedAt: ZonedDateTime,
)

data class HomeUiInput(
    val now: ZonedDateTime,
    val planEnabled: Boolean,
    val exactAllowed: Boolean,
    val projected: List<HomeProjectedItem>,
)

enum class HomeQuickAction(val label: String) {
    OPEN_PLAN("Plan ansehen"),
    OPEN_HISTORY("Verlauf öffnen"),
    OPEN_SYSTEM("System prüfen"),
}

/** Domain-to-presentation boundary. The UI mapper receives only bounded immutable projections. */
object HomeProjectionSource {
    fun from(
        now: ZonedDateTime,
        planEnabled: Boolean,
        exactAllowed: Boolean,
        scheduledEvents: List<RoutineEvent>,
    ): HomeUiInput = HomeUiInput(
        now = now,
        planEnabled = planEnabled,
        exactAllowed = exactAllowed,
        projected = scheduledEvents.asSequence()
            .filter { it.enabled }
            .map { event ->
                HomeProjectedItem(
                    id = event.id,
                    title = event.title,
                    categoryLabel = category(event.category),
                    projectedAt = AlarmScheduler.nextOccurrence(event, now),
                )
            }
            .toList(),
    )

    private fun category(category: EventCategory): String = when (category) {
        EventCategory.MORNING -> "Morgen"
        EventCategory.WORK -> "Arbeit"
        EventCategory.MEAL -> "Mahlzeit"
        EventCategory.TRAINING -> "Training"
        EventCategory.PERSONAL -> "Persönlich"
        EventCategory.PROJECT -> "Projekt"
        EventCategory.EVENING -> "Abend"
        EventCategory.SLEEP -> "Schlaf"
    }
}
