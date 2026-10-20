package com.cztr.routinealarm.occurrence

import android.content.Context
import com.cztr.routinealarm.PersonalSchedule
import com.cztr.routinealarm.RoutineEvent
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
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Native SQLite fixtures exercise a personal occurrence without enabling weekly delivery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class OccurrenceDeliveryGateTest {
    private lateinit var context: Context
    private lateinit var repo: SqliteOccurrenceRepository
    private val zone = ZoneId.of("Europe/Berlin")
    private val date = LocalDate.parse("2026-10-19")
    private val clock = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), zone)
    private val event = PersonalSchedule.allEvents.single { it.id == "evening_messages_2110" }

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("gate-source.db")
        context.deleteDatabase("gate-restored.db")
        repo = SqliteOccurrenceRepository(context, clock, "gate-source.db")
    }

    @After fun cleanup() {
        repo.close()
        context.deleteDatabase("gate-source.db")
        context.deleteDatabase("gate-restored.db")
    }

    private fun personal(rule: RoutineEvent = event, day: LocalDate = date): RoutineOccurrence {
        val item = OccurrencePlanner(clock).plan(listOf(rule), day, 1, zone,
            "gate-fixture-v1:${zone.id}").single()
        assertFalse(item.isTest)
        repo.materialize(listOf(item))
        return requireNotNull(repo.get(item.id))
    }

    private fun personalGate(store: OccurrenceRepository = repo) =
        OccurrenceDeliveryGate(store) { !it.isTest && it.eventId == event.id }

    private fun apply(item: RoutineOccurrence, type: ActionType, at: Instant,
        minutes: Int? = null): RoutineOccurrence {
        val result = repo.apply(ExecutionAction(
            "gate-test:${UUID.randomUUID()}", item.id, type,
            if (type == ActionType.TRIGGER) Actor.SYSTEM else Actor.USER,
            at, item.revision, item.generation, minutes, "gate-contract-test",
        ))
        assertEquals(Outcome.APPLIED, result.outcome)
        return result.occurrence
    }

    @Test fun personalWaitingDeliveryRequiresDueTimeAndDoesNotMutateItsStoredState() {
        val item = personal()
        assertEquals(Instant.parse("2026-10-19T19:10:00Z"), item.plannedAt)
        val token = AlarmDelivery.from(item, repo.deliveryEpoch())
        val gate = personalGate()
        val before = repo.exportBackup()
        assertNull(gate.triggerCandidate(token, item.plannedAt.minusMillis(1)))
        assertEquals(item, gate.triggerCandidate(token, item.plannedAt))
        assertEquals(item, gate.actionTarget(token))
        assertNull(gate.active(token))
        assertNull(gate.triggeredResult(token))
        assertArrayEquals(before, repo.exportBackup())
    }

    @Test fun explicitScopesKeepTestOtherPersonalAndSelectedPersonalDeliveriesSeparate() {
        val selected = personal()
        val other = personal(PersonalSchedule.allEvents.single { it.id == "evening_finances_2100" })
        val test = TestAlarmCoordinator(repo, clock).create(1)
        val testGate = OccurrenceDeliveryGate(repo) { it.isTest }
        val personalGate = personalGate()
        assertEquals(selected, personalGate.actionTarget(AlarmDelivery.from(selected)))
        assertNull(personalGate.actionTarget(AlarmDelivery.from(other)))
        assertNull(personalGate.actionTarget(AlarmDelivery.from(test)))
        assertEquals(test, testGate.actionTarget(AlarmDelivery.from(test)))
        assertNull(testGate.actionTarget(AlarmDelivery.from(selected)))
        assertNull(TestAlarmCoordinator(repo, Clock.fixed(selected.plannedAt, zone))
            .trigger(AlarmDelivery.from(selected)))
        assertEquals(listOf("SCHEDULE"), repo.history(selected.id).map { it.actionType })
    }

    @Test fun triggeredRedeliveryUsesOriginalRevisionWhileActiveViewUsesCurrentRevision() {
        val planned = personal()
        val original = AlarmDelivery.from(planned)
        val fired = apply(planned, ActionType.TRIGGER, planned.plannedAt)
        val current = AlarmDelivery.from(fired)
        val before = repo.exportBackup()
        val gate = personalGate()
        assertEquals(fired, gate.triggerCandidate(original, planned.plannedAt.plusSeconds(5)))
        assertEquals(fired, gate.triggeredResult(original))
        assertNull(gate.active(original))
        assertEquals(fired, gate.active(current))
        assertNull(gate.triggerCandidate(current, planned.plannedAt.plusSeconds(5)))
        assertNull(gate.triggeredResult(current))
        assertArrayEquals(before, repo.exportBackup())
        assertEquals(listOf("SCHEDULE", "TRIGGER"), repo.history(planned.id).map { it.actionType })
    }

    @Test fun snoozeInvalidatesOldGenerationAndAdmitsOnlyItsNewDueToken() {
        val planned = personal()
        val fired = apply(planned, ActionType.TRIGGER, planned.plannedAt)
        val snoozed = apply(fired, ActionType.SNOOZE, planned.plannedAt.plusSeconds(1), 5)
        val gate = personalGate()
        assertNull(gate.triggerCandidate(AlarmDelivery.from(planned), snoozed.currentTriggerAt))
        assertNull(gate.active(AlarmDelivery.from(fired)))
        assertNull(gate.triggeredResult(AlarmDelivery.from(planned)))
        val current = AlarmDelivery.from(snoozed)
        assertNull(gate.triggerCandidate(current, snoozed.currentTriggerAt.minusMillis(1)))
        assertEquals(snoozed, gate.triggerCandidate(current, snoozed.currentTriggerAt))
    }

    @Test fun terminalStateCannotPlayButActionTargetPreservesRepositoryDecisionAuthority() {
        for ((offset, type) in listOf(ActionType.DONE, ActionType.SKIP).withIndex()) {
            val planned = personal(day = date.plusDays(offset.toLong()))
            val fired = apply(planned, ActionType.TRIGGER, planned.plannedAt)
            val terminal = apply(fired, type, planned.plannedAt.plusSeconds(1))
            val old = AlarmDelivery.from(fired)
            val gate = personalGate()
            assertNull(gate.triggerCandidate(AlarmDelivery.from(planned), planned.plannedAt.plusSeconds(2)))
            assertNull(gate.active(old))
            assertNull(gate.triggeredResult(AlarmDelivery.from(planned)))
            assertEquals(terminal, gate.actionTarget(old))
        }
    }

    @Test fun invalidUnknownStaleAndOverflowTokensCannotBecomeActiveOrTrigger() {
        val item = personal()
        val gate = personalGate()
        for (token in listOf(
            AlarmDelivery("missing", 0, 0),
            AlarmDelivery(item.id, -1, 0),
            AlarmDelivery(item.id, 0, -1),
            AlarmDelivery(item.id, 0, 0, "bad/epoch"),
            AlarmDelivery(item.id, 0, 0, "different"),
        )) {
            assertNull(gate.actionTarget(token))
            assertNull(gate.triggerCandidate(token, item.plannedAt))
            assertNull(gate.active(token))
            assertNull(gate.triggeredResult(token))
        }
        for (token in listOf(AlarmDelivery(item.id, 1, 0),
            AlarmDelivery(item.id, 0, 5), AlarmDelivery(item.id, 0, Long.MAX_VALUE))) {
            assertEquals(item, gate.actionTarget(token))
            assertNull(gate.triggerCandidate(token, item.plannedAt))
            assertNull(gate.active(token))
            assertNull(gate.triggeredResult(token))
        }
    }

    @Test fun actualRestoreBlocksPersonalDeliveryUntilActivationAndNeverRevivesOldEpoch() {
        val item = personal()
        val oldToken = AlarmDelivery.from(item, repo.deliveryEpoch())
        val backup = repo.exportBackup()
        SqliteOccurrenceRepository(context, clock, "gate-restored.db").use { restored ->
            assertEquals(1, restored.restoreBackup(backup))
            val gate = personalGate(restored)
            val imported = AlarmDelivery.from(item, restored.deliveryEpoch())
            assertNotEquals(oldToken.epoch, imported.epoch)
            val before = restored.exportBackup()
            assertNull(gate.actionTarget(imported))
            assertNull(gate.triggerCandidate(imported, item.plannedAt))
            assertNull(gate.active(imported))
            assertNull(gate.triggeredResult(imported))
            assertArrayEquals(before, restored.exportBackup())
            assertTrue(restored.activateRestored(item.id))
            assertNull(gate.actionTarget(oldToken))
            assertNull(gate.triggerCandidate(oldToken, item.plannedAt))
            assertEquals(item, gate.triggerCandidate(imported, item.plannedAt))
        }
        SqliteOccurrenceRepository(context, clock, "gate-restored.db").use { restored ->
            assertNull(personalGate(restored).actionTarget(oldToken))
            assertEquals(item, personalGate(restored).triggerCandidate(
                AlarmDelivery.from(item, restored.deliveryEpoch()), item.plannedAt))
        }
    }

    @Test fun importedTriggeredPersonalReceiptCannotBecomeActiveBeforeExplicitActivation() {
        val planned = personal()
        val fired = apply(planned, ActionType.TRIGGER, planned.plannedAt)
        SqliteOccurrenceRepository(context, clock, "gate-restored.db").use { restored ->
            restored.restoreBackup(repo.exportBackup())
            val gate = personalGate(restored)
            val active = AlarmDelivery.from(fired, restored.deliveryEpoch())
            val delivery = AlarmDelivery.from(planned, restored.deliveryEpoch())
            assertNull(gate.active(active))
            assertNull(gate.triggeredResult(delivery))
            assertNull(gate.triggerCandidate(delivery, planned.plannedAt.plusSeconds(1)))
            assertTrue(restored.activateRestored(fired.id))
            assertEquals(fired, gate.active(active))
            assertEquals(fired, gate.triggeredResult(delivery))
        }
    }

    @Test fun coordinatorStillJournalsStaleUserActionsAsConflictsAfterSnooze() {
        val created = TestAlarmCoordinator(repo, clock).create(1)
        val due = created.currentTriggerAt
        val fired = TestAlarmCoordinator(repo, Clock.fixed(due, zone)).trigger(AlarmDelivery.from(created))!!
        val stale = AlarmDelivery.from(fired)
        val coordinator = TestAlarmCoordinator(repo, Clock.fixed(due.plusSeconds(1), zone))
        assertEquals(Outcome.APPLIED, coordinator.act(stale, ActionType.SNOOZE, 5)!!.outcome)
        assertEquals(Outcome.CONFLICT, coordinator.act(stale, ActionType.DONE)!!.outcome)
        assertEquals(OccurrenceStatus.SNOOZED, repo.get(created.id)!!.status)
        assertEquals(Outcome.CONFLICT, repo.history(created.id).last().outcome)
        assertEquals("DONE", repo.history(created.id).last().actionType)
    }
}
