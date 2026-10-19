package com.cztr.routinealarm

import com.cztr.routinealarm.occurrence.OccurrenceStatus
import com.cztr.routinealarm.occurrence.RoutineOccurrence
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TestAlarmStatusPresenterTest {
    private val now = Instant.parse("2026-10-17T10:00:00Z")
    private val zone = ZoneId.of("Europe/Berlin")

    @Test fun emptyHistoryShowsNoOpenAlarmsWithoutInventingANextTime() {
        val status = TestAlarmStatusPresenter.present(emptyList(), now, zone, exactAllowed = true)
        assertEquals(0, status.openCount)
        assertEquals(0, status.triggeredCount)
        assertEquals(0, status.overdueCount)
        assertNull(status.nextTest)
        assertEquals("Keine offenen Testwecker", status.title)
        assertFalse(status.needsExactPermission)
    }

    @Test fun countsDistinguishTriggeredFromOverdueWaitingTests() {
        val status = TestAlarmStatusPresenter.present(listOf(
            occurrence("triggered", OccurrenceStatus.TRIGGERED, now.minusSeconds(120)),
            occurrence("past", OccurrenceStatus.SCHEDULED, now.minusSeconds(60)),
            occurrence("due", OccurrenceStatus.SNOOZED, now),
            occurrence("future", OccurrenceStatus.SCHEDULED, now.plusSeconds(60)),
        ), now, zone, exactAllowed = true)
        assertEquals(4, status.openCount)
        assertEquals(1, status.triggeredCount)
        assertEquals(2, status.overdueCount)
        assertEquals("future", status.nextTest?.id)
        assertEquals("1 ausgelöst – noch offen", status.title)
    }

    @Test fun nextTestUsesCurrentSnoozeTimeAndSortsByInstant() {
        val status = TestAlarmStatusPresenter.present(listOf(
            occurrence("later", OccurrenceStatus.SCHEDULED, now.plusSeconds(600)),
            occurrence("snoozed", OccurrenceStatus.SNOOZED, now.plusSeconds(300))
                .copy(plannedAt = now.minusSeconds(600)),
        ), now, zone, exactAllowed = true)
        assertEquals("snoozed", status.nextTest?.id)
        assertEquals("Nächster gespeicherter Test: heute, 12:05 Uhr", status.nextText)
        assertEquals(0, status.overdueCount)
    }

    @Test fun nextTimeUsesCurrentDeviceZoneAndIncludesDateAcrossMidnight() {
        val status = TestAlarmStatusPresenter.present(listOf(
            occurrence("tomorrow", OccurrenceStatus.SCHEDULED, Instant.parse("2026-10-17T23:30:00Z")),
        ), now, zone, exactAllowed = true)
        assertEquals("Nächster gespeicherter Test: 18.10.2026, 01:30 Uhr", status.nextText)
    }

    @Test fun terminalAndPersonalOccurrencesDoNotEnterTestStatus() {
        val status = TestAlarmStatusPresenter.present(listOf(
            occurrence("done", OccurrenceStatus.DONE, now.minusSeconds(60)),
            occurrence("skipped", OccurrenceStatus.SKIPPED, now.plusSeconds(60)),
            occurrence("missed", OccurrenceStatus.MISSED, now.minusSeconds(60)),
            occurrence("personal", OccurrenceStatus.SCHEDULED, now.plusSeconds(60)).copy(isTest = false),
        ), now, zone, exactAllowed = true)
        assertEquals(0, status.openCount)
        assertNull(status.nextTest)
    }

    @Test fun deniedPermissionPreservesSavedStateAndRequestsSettings() {
        val status = TestAlarmStatusPresenter.present(listOf(
            occurrence("future", OccurrenceStatus.SCHEDULED, now.plusSeconds(60)),
        ), now, zone, exactAllowed = false)
        assertTrue(status.needsExactPermission)
        assertEquals(1, status.openCount)
        assertNotNull(status.nextTest)
        assertEquals("1 offener Testwecker", status.title)
    }

    @Test fun databaseFailureIsUnknownAndNeverPresentedAsEmpty() {
        val status = TestAlarmStatusPresenter.present(null, now, zone, exactAllowed = false)
        assertEquals("Teststatus nicht verfügbar", status.title)
        assertNull(status.openCount)
        assertNull(status.triggeredCount)
        assertNull(status.overdueCount)
        assertNull(status.nextTest)
        assertTrue(status.needsExactPermission)
    }

    private fun occurrence(id: String, status: OccurrenceStatus, at: Instant): RoutineOccurrence {
        val local = at.atZone(zone)
        return RoutineOccurrence(
            id = id, eventId = "event-$id", scheduleRevision = "test-v1:${zone.id}",
            nominalDate = local.toLocalDate(), nominalTime = local.toLocalTime(),
            zoneId = zone.id, offsetSeconds = local.offset.totalSeconds,
            plannedAt = at, currentTriggerAt = at, status = status,
            lastUpdatedAt = now, traceId = "trace-$id", title = "Testwecker",
            spokenText = "Test", category = EventCategory.PERSONAL,
            mode = EventMode.ROUTINE, isTest = true,
        )
    }
}
