package com.cztr.routinealarm.occurrence

import com.cztr.routinealarm.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class PersonalPlanReplacementTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val cutoff = Instant.parse("2026-10-20T12:00:00Z")
    private val clock = Clock.fixed(cutoff, zone)
    private val rule = RoutineEvent("personal", DayOfWeek.entries.toSet(), 21, 10,
        "Messages", category = EventCategory.PERSONAL, mode = EventMode.ANNOUNCEMENT)
    private val scope = OccurrenceScope.PersonalEvent(rule.id)
    private fun item(revision: String = "A", date: String = "2026-10-20", event: RoutineEvent = rule) =
        OccurrencePlanner(clock).plan(listOf(event), LocalDate.parse(date), 1, zone, revision).single()
    private fun decide(authority: PersonalPlanAuthority = PersonalPlanAuthority(),
        existing: List<RoutineOccurrence> = emptyList(), candidates: List<RoutineOccurrence> = listOf(item()),
        revision: String = "A", expected: Long = authority.version, at: Instant = cutoff) =
        PersonalPlanReplacement.decide(scope, authority, expected, existing, candidates, revision, zone, at)
    private fun ready(value: PersonalPlanDecision): PersonalPlanDecision.Ready {
        assertTrue("Expected an applicable replacement", value is PersonalPlanDecision.Ready)
        return value as PersonalPlanDecision.Ready
    }

    @Test fun firstPlanAndReplacementReturnOneAtomicDecisionWithoutChangingInputs() {
        val a = item()
        val first = ready(decide())
        assertEquals(1L, first.next.version)
        assertEquals(listOf(a), first.insertions)
        val b = item("B")
        val replaced = ready(decide(first.next, listOf(a), listOf(b), "B"))
        assertEquals(2L, replaced.next.version)
        assertEquals(setOf(a.id), replaced.retireIds)
        assertEquals(setOf(a.id), replaced.next.retiredIds)
        assertEquals(listOf(b), replaced.insertions)
        assertEquals(OccurrenceStatus.SCHEDULED, a.status)
        assertTrue(first.next.retiredIds.isEmpty())
    }

    @Test fun staleExpectedVersionReturnsConflictWithoutAReplacement() {
        val first = ready(decide())
        assertEquals(PersonalPlanDecision.Conflict(1), decide(first.next, listOf(item()), expected = 0))
    }

    @Test fun retryIsIdempotentAndIgnoresObservationTimestampAndInputOrder() {
        val a = item()
        val tomorrow = item(date = "2026-10-21")
        val first = ready(decide(candidates = listOf(a, tomorrow)))
        val retry = ready(decide(first.next, listOf(a, tomorrow),
            listOf(tomorrow.copy(lastUpdatedAt = cutoff.plusSeconds(1)), a)))
        assertEquals(first.next, retry.next)
        assertTrue(retry.insertions.isEmpty())
        assertTrue(retry.retireIds.isEmpty())
    }

    @Test fun previouslyUsedRevisionCannotReturnEvenAfterAnEmptyPlan() {
        val a = item()
        val first = ready(decide())
        val disabled = ready(decide(first.next, listOf(a), emptyList(), "OFF"))
        assertEquals(setOf(a.id), disabled.retireIds)
        assertThrows(IllegalArgumentException::class.java) {
            decide(disabled.next, listOf(a), listOf(a))
        }
    }

    @Test fun sameRevisionCannotChangeExecutablePayloadOrCalendarWindow() {
        val a = item()
        val first = ready(decide())
        listOf(a.copy(title = "Other"), a.copy(spokenText = "Other"),
            a.copy(mode = EventMode.ROUTINE), a.copy(category = EventCategory.WORK)).forEach {
            assertThrows(IllegalArgumentException::class.java) { decide(first.next, listOf(a), listOf(it)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            decide(first.next, listOf(a), listOf(a, item(date = "2026-10-21")))
        }
    }

    @Test fun rejectsForeignTestMixedRevisionZoneAndDuplicateCandidates() {
        val a = item()
        listOf(listOf(a.copy(eventId = "other")), listOf(a.copy(isTest = true)),
            listOf(a, item("B", "2026-10-21")), listOf(a.copy(zoneId = "UTC")),
            listOf(a, a)).forEach {
            assertThrows(IllegalArgumentException::class.java) { decide(candidates = it) }
        }
        assertThrows(IllegalArgumentException::class.java) { decide(existing = listOf(a, a)) }
        assertThrows(IllegalArgumentException::class.java) { decide(existing = listOf(a.copy(isTest = true))) }
    }

    @Test fun dueBoundaryAndStartedOrTerminalHistoryRemainUntouched() {
        val due = item(date = "2026-10-19")
        val future = item()
        val triggered = item(date = "2026-10-21").copy(status = OccurrenceStatus.TRIGGERED, revision = 1)
        val snoozed = item(date = "2026-10-22").copy(status = OccurrenceStatus.SNOOZED, revision = 2, generation = 1)
        val done = item(date = "2026-10-23").copy(status = OccurrenceStatus.DONE, revision = 2, generation = 1)
        val all = listOf(due, future, triggered, snoozed, done)
        val result = ready(decide(existing = all, candidates = emptyList(), revision = "OFF"))
        assertEquals(setOf(future.id), result.retireIds)
        assertEquals(listOf(due, triggered, snoozed, done).map { it.id }.toSet(), result.preservedIds)
        assertTrue(ready(decide(existing = listOf(future), candidates = emptyList(),
            revision = "OFF", at = future.plannedAt)).retireIds.isEmpty())
    }

    @Test fun freshDueOccurrencesCannotBeStartedByAReplacement() {
        val due = item(date = "2026-10-19")
        assertThrows(IllegalArgumentException::class.java) { decide(candidates = listOf(due)) }
        val first = ready(decide())
        assertTrue(ready(decide(first.next, listOf(item()), at = item().plannedAt)).insertions.isEmpty())
    }

    @Test fun cannotAliasRetainedInFlightExecutionOrRetiredIdentity() {
        val a = item()
        val active = a.copy(status = OccurrenceStatus.TRIGGERED, revision = 1)
        assertThrows(IllegalArgumentException::class.java) {
            decide(existing = listOf(active), candidates = listOf(item("B")), revision = "B")
        }
        assertThrows(IllegalArgumentException::class.java) {
            decide(authority = PersonalPlanAuthority(retiredIds = setOf(a.id)))
        }
    }

    @Test fun malformedInitialCandidateAndAuthorityOverflowCannotProduceAPlan() {
        assertThrows(IllegalArgumentException::class.java) {
            decide(candidates = listOf(item().copy(generation = 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            decide(candidates = listOf(item().copy(currentTriggerAt = cutoff)))
        }
        assertThrows(ArithmeticException::class.java) {
            decide(authority = PersonalPlanAuthority(version = Long.MAX_VALUE))
        }
    }

    @Test fun resultOwnsItsCollectionsAndDoesNotAllowRetirementRemoval() {
        val a = item()
        val first = ready(decide())
        val candidates = mutableListOf(item("B"))
        val result = ready(decide(first.next, listOf(a), candidates, "B"))
        candidates.clear()
        assertEquals(1, result.insertions.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (result.next.retiredIds as MutableSet<String>).clear()
        }
    }

    @Test fun authorityBelongsToExactlyOnePersonalEvent() {
        val foreign = PersonalPlanAuthority(eventId = "other")
        assertThrows(IllegalArgumentException::class.java) { decide(authority = foreign) }
        assertEquals(rule.id, ready(decide()).next.eventId)
    }

    @Test fun successiveReplacementAndRetryNeverPreservePreviouslyRetiredIds() {
        val a = item()
        val b = item("B")
        val c = item("C")
        val first = ready(decide())
        val second = ready(decide(first.next, listOf(a), listOf(b), "B"))
        val third = ready(decide(second.next, listOf(a, b), listOf(c), "C"))
        assertEquals(setOf(a.id, b.id), third.next.retiredIds)
        assertTrue(third.preservedIds.intersect(third.next.retiredIds).isEmpty())
        val retry = ready(decide(third.next, listOf(a, b, c), listOf(c), "C"))
        assertEquals(setOf(c.id), retry.preservedIds)
        assertEquals(third.next, retry.next)
    }

    @Test fun usedAuthorityCannotLoseItsEventBinding() {
        val used = ready(decide()).next.copy(eventId = null)
        assertThrows(IllegalArgumentException::class.java) { decide(used, listOf(item())) }
    }
}
