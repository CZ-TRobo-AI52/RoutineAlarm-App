package com.cztr.routinealarm.occurrence

import com.cztr.routinealarm.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class OccurrenceDomainTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC)
    private val zone = ZoneId.of("Europe/Berlin")
    private val event = RoutineEvent("stable-rule", DayOfWeek.entries.toSet(), 2, 30,
        "Example", category = EventCategory.PERSONAL, mode = EventMode.ROUTINE)

    private fun plan(date: String = "2026-10-01", rule: RoutineEvent = event,
                     later: Boolean = false, test: Boolean = false) =
        OccurrencePlanner(clock).plan(listOf(rule), LocalDate.parse(date), 1, zone,
            "baseline-v1", laterOverlap = later, isTest = test).single()

    private fun action(o: RoutineOccurrence, type: ActionType, minutes: Int? = null,
                       actor: Actor = Actor.USER, at: Instant = o.currentTriggerAt) =
        ExecutionAction("action-${o.revision}-$type", o.id, type, actor, at,
            o.revision, o.generation, minutes)

    @Test fun identitySurvivesRenameAndClockChange() {
        val original = plan()
        assertEquals(original.id, plan(rule = event.copy(title = "Renamed", spokenText = "New speech")).id)
        val laterClock = Clock.offset(clock, Duration.ofDays(1))
        assertEquals(original.id, OccurrencePlanner(laterClock).plan(listOf(event), original.nominalDate,
            1, zone, "baseline-v1").single().id)
        assertNotEquals(original.id, plan(test = true).id)
    }

    @Test fun calendarUsesEnabledRulesAndWeekdays() {
        val monday = event.copy(daysOfWeek = setOf(DayOfWeek.MONDAY))
        val planned = OccurrencePlanner(clock).plan(listOf(monday, event.copy(id = "off", enabled = false)),
            LocalDate.parse("2026-09-28"), 14, zone, "baseline-v1")
        assertEquals(listOf("2026-09-28", "2026-10-05"), planned.map { it.nominalDate.toString() })
        assertEquals(2, planned.map { it.id }.distinct().size)
    }

    @Test fun daylightSavingGapShiftsForwardButKeepsNominalTime() {
        val o = plan("2026-03-29")
        assertEquals(LocalTime.of(2, 30), o.nominalTime)
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), o.plannedAt)
        assertEquals(7200, o.offsetSeconds)
    }

    @Test fun daylightSavingFoldDefaultsEarlierAndDistinctInstantHasDistinctIdentity() {
        val early = plan("2026-10-25")
        val late = plan("2026-10-25", later = true)
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), early.plannedAt)
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"), late.plannedAt)
        assertNotEquals(early.id, late.id)
    }

    @Test fun snoozeKeepsOriginalPlanAndIncrementsGenerationForAllFiveButtons() {
        for (minutes in 1..5) {
            val original = plan()
            val triggered = OccurrenceTransitions.apply(original, action(original, ActionType.TRIGGER, actor = Actor.SYSTEM)).occurrence
            val result = OccurrenceTransitions.apply(triggered, action(triggered, ActionType.SNOOZE, minutes))
            val snoozed = result.occurrence
            assertEquals(Outcome.APPLIED, result.outcome)
            assertEquals(original.id, snoozed.id)
            assertEquals(original.plannedAt, snoozed.plannedAt)
            assertEquals(original.plannedAt.plusSeconds(minutes * 60L), snoozed.currentTriggerAt)
            assertEquals(1L, snoozed.generation)
            assertEquals(2L, snoozed.revision)
            assertEquals(1, snoozed.snoozeCount)
            assertEquals(minutes, snoozed.totalSnoozeMinutes)
        }
    }

    @Test fun staleActionAndOldDeliveryCannotChangeNewGeneration() {
        val original = plan()
        val triggered = OccurrenceTransitions.apply(original, action(original, ActionType.TRIGGER, actor = Actor.SYSTEM)).occurrence
        val snoozed = OccurrenceTransitions.apply(triggered, action(triggered, ActionType.SNOOZE, 2)).occurrence
        val stale = OccurrenceTransitions.apply(snoozed, action(triggered, ActionType.DONE))
        assertEquals(Outcome.CONFLICT, stale.outcome)
        assertEquals(snoozed, stale.occurrence)
        val oldGeneration = action(snoozed, ActionType.TRIGGER, actor = Actor.SYSTEM).copy(expectedGeneration = 0)
        assertEquals(Outcome.CONFLICT, OccurrenceTransitions.apply(snoozed, oldGeneration).outcome)
    }

    @Test fun terminalOutcomesStayClosed() {
        for (terminal in listOf(ActionType.DONE, ActionType.SKIP)) {
            val original = plan()
            val triggered = OccurrenceTransitions.apply(original, action(original, ActionType.TRIGGER, actor = Actor.SYSTEM)).occurrence
            val closed = OccurrenceTransitions.apply(triggered, action(triggered, terminal)).occurrence
            assertEquals(1L, closed.generation)
            for (type in ActionType.entries) {
                val result = OccurrenceTransitions.apply(closed, action(closed, type, if(type == ActionType.SNOOZE) 1 else null))
                assertEquals(Outcome.REJECTED, result.outcome)
                assertEquals(closed, result.occurrence)
            }
        }
    }

    @Test fun announcementIsNeverAutomaticallyCompleted() {
        val o = plan(rule = event.copy(mode = EventMode.ANNOUNCEMENT))
        val triggered = OccurrenceTransitions.apply(o, action(o, ActionType.TRIGGER, actor = Actor.SYSTEM)).occurrence
        assertEquals(OccurrenceStatus.TRIGGERED, triggered.status)
        assertEquals(Outcome.REJECTED, OccurrenceTransitions.apply(triggered,
            action(triggered, ActionType.DONE, actor = Actor.SYSTEM)).outcome)
        assertEquals(Outcome.APPLIED, OccurrenceTransitions.apply(triggered, action(triggered, ActionType.DONE)).outcome)
    }

    @Test fun earlyTriggerInvalidSnoozeAndUnprovenMissAreRejected() {
        val o = plan()
        assertEquals(Outcome.REJECTED, OccurrenceTransitions.apply(o,
            action(o, ActionType.TRIGGER, actor = Actor.SYSTEM, at = o.plannedAt.minusMillis(1))).outcome)
        assertEquals(Outcome.REJECTED, OccurrenceTransitions.apply(o, action(o, ActionType.MISS, actor = Actor.SYSTEM)).outcome)
        val triggered = OccurrenceTransitions.apply(o, action(o, ActionType.TRIGGER, actor = Actor.SYSTEM)).occurrence
        for (minutes in listOf(null, -1, 0, 6, Int.MAX_VALUE)) {
            assertEquals(Outcome.REJECTED, OccurrenceTransitions.apply(triggered, action(triggered, ActionType.SNOOZE, minutes)).outcome)
        }
    }

    @Test fun submillisecondActionsUseSameTimeSemanticsBeforeAndAfterPersistence() {
        val o = plan()
        val triggered = OccurrenceTransitions.apply(o, action(o, ActionType.TRIGGER, actor = Actor.SYSTEM)).occurrence
        val result = OccurrenceTransitions.apply(triggered, action(triggered, ActionType.SNOOZE, 1,
            at = triggered.currentTriggerAt.plusNanos(500))).occurrence
        assertEquals(o.plannedAt.plusSeconds(60), result.currentTriggerAt)
        assertEquals(o.plannedAt, result.lastUpdatedAt)
    }

    @Test fun backdatedSnoozeRequiresClockReconciliationAndDoesNotScheduleInPast() {
        val o = plan()
        val triggered = OccurrenceTransitions.apply(o, action(o, ActionType.TRIGGER, actor = Actor.SYSTEM)).occurrence
        val result = OccurrenceTransitions.apply(triggered, action(triggered, ActionType.SNOOZE, 1,
            at = triggered.lastUpdatedAt.minusSeconds(3600)))
        assertEquals(Outcome.REJECTED, result.outcome)
        assertEquals(triggered, result.occurrence)
    }

    @Test fun weekMaterializationMatchesExistingScheduleExactly() {
        val start = LocalDate.parse("2026-09-28")
        val week = OccurrencePlanner(clock).plan(ScheduleRegistry.scheduledEvents, start, 7, zone, "baseline-v1")
        assertEquals(ScheduleRegistry.enabledEvents.sumOf { it.daysOfWeek.size }, week.size)
        assertEquals(week.size, week.map { it.id }.distinct().size)
        for (o in week) {
            val rule = ScheduleRegistry.scheduledEvents.single { it.id == o.eventId }
            assertEquals(LocalTime.of(rule.hour, rule.minute), o.nominalTime)
            assertTrue(o.nominalDate.dayOfWeek in rule.daysOfWeek)
        }
    }

    @Test fun originalTrainingAndDailyAnchorsRemainIntact() {
        assertEquals(338, TrainingSchedule.allEvents.size)
        assertEquals(6, TrainingSessionPlan.startEvents.size)
        assertTrue(TrainingSessionPlan.eventsFor(DayOfWeek.SUNDAY).isEmpty())
        assertEquals(2, PersonalSchedule.allEvents.count { !it.enabled })
        for (time in listOf(LocalTime.of(5, 5), LocalTime.of(16, 10), LocalTime.of(22, 55))) {
            assertTrue(PersonalSchedule.allEvents.any { it.enabled && it.daysOfWeek.size == 7 && LocalTime.of(it.hour, it.minute) == time })
        }
        for (day in DayOfWeek.entries) {
            for ((cue, next) in TrainingSessionPlan.eventsFor(day).zipWithNext()) {
                assertEquals(EventMode.ANNOUNCEMENT, cue.mode)
                if ("_pause_" in cue.id) assertEquals(1, next.minuteOfDay - cue.minuteOfDay)
                if ("_transition_" in cue.id) assertEquals(2, next.minuteOfDay - cue.minuteOfDay)
            }
        }
    }
}
