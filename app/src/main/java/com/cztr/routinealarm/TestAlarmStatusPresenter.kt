package com.cztr.routinealarm

import com.cztr.routinealarm.occurrence.OccurrenceStatus
import com.cztr.routinealarm.occurrence.RoutineOccurrence
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A database summary, not an assertion that Android delivered sound or a notification. */
data class TestAlarmStatus(
    val title: String,
    val openCount: Int?,
    val triggeredCount: Int?,
    val overdueCount: Int?,
    val nextTest: RoutineOccurrence?,
    val nextText: String?,
    val needsExactPermission: Boolean,
)

object TestAlarmStatusPresenter {
    /** Null means the database could not be read; it must not become an empty history. */
    fun present(occurrences: List<RoutineOccurrence>?, now: Instant, zone: ZoneId,
        exactAllowed: Boolean): TestAlarmStatus {
        if (occurrences == null) return TestAlarmStatus(
            "Teststatus nicht verfügbar", null, null, null, null, null, !exactAllowed)

        val open = occurrences.filter { it.isTest && it.status in setOf(
            OccurrenceStatus.SCHEDULED, OccurrenceStatus.SNOOZED, OccurrenceStatus.TRIGGERED) }
        val triggered = open.count { it.status == OccurrenceStatus.TRIGGERED }
        val waiting = open.filter { it.status != OccurrenceStatus.TRIGGERED }
        val overdue = waiting.count { !it.currentTriggerAt.isAfter(now) }
        val next = waiting.filter { it.currentTriggerAt.isAfter(now) }
            .minWithOrNull(compareBy<RoutineOccurrence> { it.currentTriggerAt }.thenBy { it.id })
        val title = when {
            triggered > 0 -> "$triggered ausgelöst – noch offen"
            overdue > 0 -> "$overdue überfällig – Verlauf prüfen"
            open.isEmpty() -> "Keine offenen Testwecker"
            open.size == 1 -> "1 offener Testwecker"
            else -> "${open.size} offene Testwecker"
        }
        val nextText = next?.let {
            val time = it.currentTriggerAt.atZone(zone)
            val day = if (time.toLocalDate() == now.atZone(zone).toLocalDate()) "heute"
                else time.format(DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMAN))
            "Nächster gespeicherter Test: $day, ${time.format(DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN))} Uhr"
        }
        return TestAlarmStatus(title, open.size, triggered, overdue, next, nextText, !exactAllowed)
    }
}
