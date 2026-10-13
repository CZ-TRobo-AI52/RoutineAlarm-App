package com.cztr.routinealarm.occurrence

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TestAlarmCoordinatorTest {
    private lateinit var context: Context
    private lateinit var repo: SqliteOccurrenceRepository
    private val now = Instant.parse("2026-10-13T10:00:00Z")
    private val zone = ZoneId.of("Europe/Berlin")
    private fun coordinator(at: Instant = now) = TestAlarmCoordinator(repo, Clock.fixed(at, zone))

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        repo = SqliteOccurrenceRepository(context, Clock.fixed(now, zone), "test.db")
    }
    @After fun cleanup() { repo.close(); context.deleteDatabase("test.db") }

    @Test fun testsAtSameInstantHaveDistinctIdsAndDoNotEnterPersonalHistory() {
        val a = coordinator().create(1)
        val b = coordinator().create(1)
        assertNotEquals(a.id, b.id)
        assertTrue(a.isTest)
        assertEquals(now.plusSeconds(60), a.plannedAt)
        assertTrue(repo.listForDate(a.nominalDate, zone).isEmpty())
        assertEquals(2, repo.listForDate(a.nominalDate, zone, true).size)
    }

    @Test fun repeatedTriggerAfterReopenDoesNotCreateSecondJournalAction() {
        val planned = coordinator().create(1)
        val delivery = AlarmDelivery.from(planned)
        val fired = coordinator(now.plusSeconds(61)).trigger(delivery)!!
        repo.close()
        repo = SqliteOccurrenceRepository(context, Clock.fixed(now, zone), "test.db")
        assertEquals(fired, coordinator(now.plusSeconds(80)).trigger(delivery))
        assertEquals(listOf("SCHEDULE", "TRIGGER"), repo.history(planned.id).map { it.actionType })
    }

    @Test fun replayAfterDoneCannotReviveTerminalOccurrence() {
        val planned = coordinator().create(1)
        val delivery = AlarmDelivery.from(planned)
        val fired = coordinator(now.plusSeconds(61)).trigger(delivery)!!
        val result = coordinator(now.plusSeconds(62)).act(AlarmDelivery.from(fired), ActionType.DONE)
        assertEquals(Outcome.APPLIED, result!!.outcome)
        assertNull(coordinator(now.plusSeconds(70)).trigger(delivery))
        assertNull(coordinator().active(AlarmDelivery.from(fired)))
        assertEquals(OccurrenceStatus.DONE, repo.get(planned.id)!!.status)
    }

    @Test fun snoozePreservesIdentityAndOldDeliveryCannotTriggerNewGeneration() {
        val planned = coordinator().create(1)
        val fired = coordinator(now.plusSeconds(60)).trigger(AlarmDelivery.from(planned))!!
        val token = AlarmDelivery.from(fired)
        val snoozed = coordinator(now.plusSeconds(70)).act(token, ActionType.SNOOZE, 5)!!.occurrence
        assertEquals(planned.id, snoozed.id)
        assertEquals(planned.plannedAt, snoozed.plannedAt)
        assertEquals(now.plusSeconds(370), snoozed.currentTriggerAt)
        assertNull(coordinator(now.plusSeconds(400)).trigger(AlarmDelivery.from(planned)))
        assertEquals(Outcome.CONFLICT, coordinator(now.plusSeconds(71)).act(token, ActionType.DONE)!!.outcome)
        assertNull(coordinator(now.plusSeconds(369)).trigger(AlarmDelivery.from(snoozed)))
        assertNotNull(coordinator(now.plusSeconds(370)).trigger(AlarmDelivery.from(snoozed)))
    }

    @Test fun staleUserActionCannotChangeAnotherOccurrence() {
        val a = coordinator().create(1)
        val b = coordinator().create(1)
        val firedA = coordinator(now.plusSeconds(60)).trigger(AlarmDelivery.from(a))!!
        val firedB = coordinator(now.plusSeconds(60)).trigger(AlarmDelivery.from(b))!!
        coordinator(now.plusSeconds(61)).act(AlarmDelivery.from(firedA), ActionType.SKIP)
        assertEquals(firedB, coordinator().active(AlarmDelivery.from(firedB)))
        assertNull(coordinator(now.plusSeconds(65)).trigger(AlarmDelivery.from(a)))
        assertEquals(listOf(b.id), repo.openTests().map { it.id })
    }

    @Test fun unknownOrInvalidTokensAndNonTestOccurrencesFailClosed() {
        val planned = coordinator().create(1)
        assertNull(coordinator().trigger(AlarmDelivery("missing", 0, 0)))
        assertNull(coordinator(now.plusSeconds(70)).trigger(AlarmDelivery(planned.id, -1, 0)))
        assertNull(coordinator(now.plusSeconds(70)).trigger(AlarmDelivery(planned.id, 0, 100)))
        repo.materialize(listOf(planned.copy(id = "personal", isTest = false)))
        assertNull(coordinator(now.plusSeconds(70)).trigger(AlarmDelivery("personal", 0, 0)))
        assertThrows(IllegalArgumentException::class.java) { coordinator().create(0) }
    }
}
