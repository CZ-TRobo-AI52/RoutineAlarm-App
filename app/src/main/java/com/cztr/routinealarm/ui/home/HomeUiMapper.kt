package com.cztr.routinealarm.ui.home

import com.cztr.routinealarm.AlarmScheduler
import com.cztr.routinealarm.EventCategory
import com.cztr.routinealarm.RoutineEvent
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object HomeUiMapper {
    /** The caller supplies scheduledEvents, never the registry's internal training cues. */
    fun map(now: ZonedDateTime, planEnabled: Boolean, exactAllowed: Boolean,
        scheduledEvents: List<RoutineEvent>): HomeUiState {
        val greeting=when(now.hour) {
            in 5..10 -> "Guten Morgen, Christian"
            in 11..17 -> "Guten Tag, Christian"
            else -> "Guten Abend, Christian"
        }
        if (!planEnabled) return HomeUiState(greeting,HomePlanState.PAUSED,
            "Wochenplan pausiert","Im Plan kannst du die vorgesehenen Routinen ansehen und den Wochenplan aktivieren.",emptyList())
        val formatter=DateTimeFormatter.ofPattern("EEE, dd.MM. · HH:mm",Locale.GERMAN)
        val upcoming=scheduledEvents.asSequence().filter { it.enabled }
            .map { it to AlarmScheduler.nextOccurrence(it,now) }
            .sortedWith(compareBy({ it.second.toInstant() }, { it.first.id })).take(5)
            .map { (event,at) -> HomeUpcomingItem(event.id,event.title,formatter.format(at),category(event.category),at) }.toList()
        if (!exactAllowed) return HomeUiState(greeting,HomePlanState.PERMISSION_REQUIRED,
            "Exakte Wecker fehlen","Android hat exakte Wecker nicht freigegeben. Die Termine unten sind nur eine Wochenplan-Vorschau.",upcoming)
        val next=upcoming.firstOrNull() ?: return HomeUiState(greeting,HomePlanState.EMPTY,
            "Keine nächste Routine gefunden","Im Wochenplan ist aktuell keine aktivierte Routine enthalten.",emptyList())
        val minutes=Duration.between(now.toInstant(),next.projectedAt.toInstant()).toMinutes()
        val countdown=when {
            minutes<1 -> "in weniger als 1 Minute"
            minutes<60 -> "in $minutes Minuten"
            else -> "in ${minutes/60} Std. ${minutes%60} Min."
        }
        return HomeUiState(greeting,HomePlanState.PREVIEW,next.title,
            "${next.timeLabel} Uhr · $countdown\n${next.categoryLabel}",upcoming)
    }
    private fun category(c: EventCategory)=when(c) {
        EventCategory.MORNING->"Morgen"; EventCategory.WORK->"Arbeit"; EventCategory.MEAL->"Mahlzeit"
        EventCategory.TRAINING->"Training"; EventCategory.PERSONAL->"Persönlich"; EventCategory.PROJECT->"Projekt"
        EventCategory.EVENING->"Abend"; EventCategory.SLEEP->"Schlaf"
    }
}
