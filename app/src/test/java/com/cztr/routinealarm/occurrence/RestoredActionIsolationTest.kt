package com.cztr.routinealarm.occurrence

import com.cztr.routinealarm.EventCategory
import com.cztr.routinealarm.EventMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Exercise the actual SQLite boundary without the coordinator's earlier permission check. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RestoredActionIsolationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val now = Instant.parse("2026-10-26T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val scope = OccurrenceScope.PersonalEvent("personal")
    private fun repo(name: String) = SqliteOccurrenceRepository(context, clock, name)
    @After fun cleanup() { listOf("action-source.db", "action-restored.db").forEach(context::deleteDatabase) }

    private fun initial(test: Boolean) = RoutineOccurrence(
        "item", if (test) "test" else scope.eventId, "plan-v1", LocalDate.parse("2026-10-26"),
        LocalTime.of(12, 5), "Z", 0, now.plusSeconds(300), now.plusSeconds(300),
        lastUpdatedAt = now, traceId = "trace", title = "Routine", spokenText = "Routine",
        category = EventCategory.PERSONAL, mode = EventMode.ROUTINE, isTest = test,
    )

    private fun action(row: RoutineOccurrence, type: ActionType, id: String = "new-${type.name}") =
        ExecutionAction(id, row.id, type,
            if (type in setOf(ActionType.TRIGGER, ActionType.MISS)) Actor.SYSTEM else Actor.USER,
            row.currentTriggerAt.plusSeconds(1), row.revision, row.generation,
            if (type == ActionType.SNOOZE) 3 else null, "restore-action-test")

    private fun seed(test: Boolean, status: OccurrenceStatus): ByteArray = repo("action-source.db").use {
        val row = initial(test)
        if (test) it.materialize(listOf(row)) else
            it.replacePersonalPlan(scope, 0, listOf(row), row.scheduleRevision, ZoneOffset.UTC, now)
        val attempt = it.beginSchedulingAttempt(row, true, if (test) OccurrenceScope.Tests else scope)!!
        it.recordSchedulingReceipt(attempt.id, SchedulingOutcome.CALL_RETURNED, SchedulingReason.RETURNED)
        it.beginSchedulingAttempt(row, true, if (test) OccurrenceScope.Tests else scope) // Unknown effect retained.
        if (status != OccurrenceStatus.SCHEDULED) it.apply(action(row, ActionType.TRIGGER, "original-trigger"))
        if (status == OccurrenceStatus.SNOOZED) {
            it.apply(action(it.get(row.id)!!, ActionType.SNOOZE, "original-snooze"))
        }
        it.exportBackup()
    }

    private fun assertQuarantinedActions(test: Boolean, status: OccurrenceStatus) {
        val bytes = seed(test, status)
        repo("action-restored.db").use { it.restoreBackup(bytes) }
        // Reopen to prove the block follows persisted restore state, not an in-memory caller flag.
        repo("action-restored.db").use {
            val row = it.get("item")!!
            val epoch = it.deliveryEpoch()
            val authority = it.personalPlanAuthority(scope)
            assertEquals(status, row.status)
            assertFalse(it.isDeliveryAllowed(row.id))
            for (type in ActionType.entries) {
                assertThrows("$status/$type must not bypass restore quarantine", IllegalStateException::class.java) {
                    it.apply(action(row, type))
                }
                assertArrayEquals(bytes, it.exportBackup())
                assertEquals(row, it.get(row.id))
                assertEquals(epoch, it.deliveryEpoch())
                assertEquals(authority, it.personalPlanAuthority(scope))
                assertEquals(1, it.restorePausedCount())
                assertNull(it.schedulingAttempts(row.id).last().receipt)
            }
        }
    }

    @Test fun scheduledTestActionsCannotBypassQuarantine() = assertQuarantinedActions(true, OccurrenceStatus.SCHEDULED)
    @Test fun triggeredTestActionsCannotBypassQuarantine() = assertQuarantinedActions(true, OccurrenceStatus.TRIGGERED)
    @Test fun snoozedTestActionsCannotBypassQuarantine() = assertQuarantinedActions(true, OccurrenceStatus.SNOOZED)
    @Test fun scheduledPersonalActionsCannotBypassQuarantine() = assertQuarantinedActions(false, OccurrenceStatus.SCHEDULED)
    @Test fun triggeredPersonalActionsCannotBypassQuarantine() = assertQuarantinedActions(false, OccurrenceStatus.TRIGGERED)
    @Test fun snoozedPersonalActionsCannotBypassQuarantine() = assertQuarantinedActions(false, OccurrenceStatus.SNOOZED)

    @Test fun historicalReplayStillReturnsOriginalDecisionWithoutUnpausingOrRewriting() {
        val bytes = seed(false, OccurrenceStatus.SNOOZED)
        repo("action-restored.db").use {
            it.restoreBackup(bytes)
            val original = action(initial(false), ActionType.TRIGGER, "original-trigger")
            val replay = it.apply(original)
            assertTrue(replay.duplicate)
            assertEquals(Outcome.APPLIED, replay.outcome)
            assertEquals(OccurrenceStatus.TRIGGERED, replay.occurrence.status)
            assertEquals(OccurrenceStatus.SNOOZED, it.get("item")!!.status)
            assertThrows(ActionIdConflict::class.java) { it.apply(original.copy(source = "different")) }
            assertArrayEquals(bytes, it.exportBackup())
            assertEquals(1, it.restorePausedCount())
        }
    }

    @Test fun historicalRejectedReplayRemainsReadableWhileNewRejectedActionsCannotReserveIds() {
        seed(true, OccurrenceStatus.SCHEDULED)
        val invalid = action(initial(true), ActionType.DONE, "old-rejected")
        val bytes = repo("action-source.db").use {
            assertEquals(Outcome.REJECTED, it.apply(invalid).outcome)
            it.exportBackup()
        }
        repo("action-restored.db").use {
            it.restoreBackup(bytes)
            val replay = it.apply(invalid)
            assertTrue(replay.duplicate)
            assertEquals(Outcome.REJECTED, replay.outcome)
            assertThrows(IllegalStateException::class.java) { it.apply(invalid.copy(id = "new-rejected")) }
            assertArrayEquals(bytes, it.exportBackup())
        }
    }

    @Test fun explicitlyActivatedTestCanReuseBlockedActionIdAfterReopen() {
        val bytes = seed(true, OccurrenceStatus.SCHEDULED)
        val trigger = action(initial(true), ActionType.TRIGGER)
        repo("action-restored.db").use {
            it.restoreBackup(bytes)
            assertThrows(IllegalStateException::class.java) { it.apply(trigger) }
            assertTrue(it.activateRestored("item"))
        }
        repo("action-restored.db").use {
            val applied = it.apply(trigger)
            assertEquals(Outcome.APPLIED, applied.outcome)
            assertFalse(applied.duplicate)
            assertEquals(OccurrenceStatus.TRIGGERED, it.get("item")!!.status)
            assertEquals(2, it.history("item").size)
            assertEquals(0, it.restorePausedCount())
            assertNull(it.schedulingAttempts("item").last().receipt)
        }
    }

    @Test fun genericActivationCannotEnableRestoredPersonalActions() {
        val bytes = seed(false, OccurrenceStatus.SCHEDULED)
        repo("action-restored.db").use {
            it.restoreBackup(bytes)
            assertFalse(it.activateRestored("item"))
            assertThrows(IllegalStateException::class.java) { it.apply(action(initial(false), ActionType.TRIGGER)) }
            assertArrayEquals(bytes, it.exportBackup())
            assertEquals(1L, it.personalPlanAuthority(scope).version)
        }
    }
}
