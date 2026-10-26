package com.cztr.routinealarm.occurrence

import com.cztr.routinealarm.*
import java.time.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Exercise initial materialization directly, without a caller-side restore guard. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RestoredMaterializationIsolationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val source = "materialize-source.db"
    private val restored = "materialize-restored.db"
    private val zone = ZoneId.of("Europe/Berlin")
    private val now = Instant.parse("2026-10-26T12:00:00Z")
    private val clock = Clock.fixed(now, zone)
    private val rule = RoutineEvent("personal", DayOfWeek.entries.toSet(), 21, 10,
        "Synthetic routine", category = EventCategory.PERSONAL, mode = EventMode.ANNOUNCEMENT)
    private val scope = OccurrenceScope.PersonalEvent(rule.id)
    private fun item(revision: String, event: RoutineEvent = rule) =
        OccurrencePlanner(clock).plan(listOf(event), LocalDate.parse("2026-10-26"), 1, zone, revision).single()
    private fun repo(name: String) = SqliteOccurrenceRepository(context, clock, name)
    @After fun cleanup() { context.deleteDatabase(source); context.deleteDatabase(restored) }

    private fun seed(format: Int, status: OccurrenceStatus = OccurrenceStatus.SCHEDULED,
        test: Boolean = false): RoutineOccurrence {
        cleanup()
        val initial = item("A").copy(isTest = test)
        val bytes = repo(source).use { r ->
            r.materialize(listOf(initial))
            r.beginSchedulingAttempt(initial, true, if (test) OccurrenceScope.Tests else scope)
            fun apply(type: ActionType) {
                val current = r.get(initial.id)!!
                r.apply(ExecutionAction("seed-" + type.name, current.id, type,
                    if (type == ActionType.TRIGGER) Actor.SYSTEM else Actor.USER,
                    current.currentTriggerAt.plusSeconds(1), current.revision, current.generation,
                    if (type == ActionType.SNOOZE) 3 else null, "synthetic-source"))
            }
            if (status != OccurrenceStatus.SCHEDULED) apply(ActionType.TRIGGER)
            if (status == OccurrenceStatus.SNOOZED) apply(ActionType.SNOOZE)
            if (status == OccurrenceStatus.DONE) apply(ActionType.DONE)
            OccurrenceBackup.encode(OccurrenceBackup.decode(r.exportBackup()).copy(formatVersion = format))
        }
        repo(restored).use { assertEquals(1, it.restoreBackup(bytes)) }
        return initial
    }

    private fun assertBlocked(format: Int) {
        for (status in listOf(OccurrenceStatus.SCHEDULED, OccurrenceStatus.TRIGGERED, OccurrenceStatus.SNOOZED)) {
            val original = seed(format, status)
            repo(restored).use { r ->
                val before = r.exportBackup()
                val epoch = r.deliveryEpoch()
                val authority = r.personalPlanAuthority(scope)
                assertEquals(0L, authority.version)
                assertEquals(status, r.get(original.id)!!.status)
                val fresh = item("B")
                assertThrows(IllegalStateException::class.java) { r.materialize(listOf(fresh)) }
                assertNull(r.get(fresh.id))
                assertTrue(r.history(fresh.id).isEmpty())
                assertTrue(r.schedulingAttempts(fresh.id).isEmpty())
                assertFalse(r.isDeliveryAllowed(fresh.id))
                assertArrayEquals(before, r.exportBackup())
                assertEquals(epoch, r.deliveryEpoch())
                assertEquals(authority, r.personalPlanAuthority(scope))
                assertEquals(1, r.restorePausedCount())
                assertNull(r.schedulingAttempts(original.id).single().receipt)
            }
        }
    }
    @Test fun legacyFormatCannotMaterializeAnUnpausedReplacement() = assertBlocked(1)
    @Test fun currentFormatWithoutAuthorityCannotMaterializeAnUnpausedReplacement() = assertBlocked(2)

    private fun assertBatchRollback(format: Int) {
        seed(format)
        val test = item("NEW-TEST-REVISION", rule.copy(id = "test")).copy(isTest = true)
        val other = item("NEW-OTHER-REVISION", rule.copy(id = "other"))
        val blocked = item("NEW-BLOCKED-REVISION")
        val before = repo(restored).use { it.exportBackup() }
        val epoch = repo(restored).use { it.deliveryEpoch() }
        repo(restored).use { r ->
            assertThrows(IllegalStateException::class.java) { r.materialize(listOf(test, other, blocked)) }
        }
        repo(restored).use { r ->
            // Snapshot equality also covers newly frozen schedule revisions and schedule journals.
            assertArrayEquals(before, r.exportBackup())
            for (row in listOf(test, other, blocked)) {
                assertNull(r.get(row.id)); assertTrue(r.history(row.id).isEmpty())
            }
            assertEquals(epoch, r.deliveryEpoch()); assertEquals(1, r.restorePausedCount())
        }
    }
    @Test fun legacyFormatMixedBatchRollsBackAllEarlierWrites() = assertBatchRollback(1)
    @Test fun currentFormatMixedBatchRollsBackAllEarlierWrites() = assertBatchRollback(2)

    private fun assertRetry(format: Int) {
        val original = seed(format, OccurrenceStatus.SNOOZED)
        repo(restored).use { r ->
            val before = r.exportBackup()
            assertEquals(0, r.materialize(listOf(original)))
            assertEquals(OccurrenceStatus.SNOOZED, r.get(original.id)!!.status)
            assertFalse(r.isDeliveryAllowed(original.id))
            assertEquals(1, r.restorePausedCount())
            assertArrayEquals(before, r.exportBackup())
        }
    }
    @Test fun legacyRetryPreservesImportedExecutionAndQuarantine() = assertRetry(1)
    @Test fun currentRetryPreservesImportedExecutionAndQuarantine() = assertRetry(2)

    private fun assertUnrelated(format: Int) {
        val original = seed(format)
        val test = item("TEST", rule.copy(id = "test")).copy(isTest = true)
        val other = item("OTHER", rule.copy(id = "other"))
        repo(restored).use { r ->
            assertEquals(2, r.materialize(listOf(test, other)))
            assertTrue(r.isDeliveryAllowed(test.id)); assertTrue(r.isDeliveryAllowed(other.id))
            assertNotNull(r.beginSchedulingAttempt(test, true, OccurrenceScope.Tests))
            assertNotNull(r.beginSchedulingAttempt(other, true, OccurrenceScope.PersonalEvent(other.eventId)))
            assertFalse(r.isDeliveryAllowed(original.id)); assertEquals(1, r.restorePausedCount())
            assertEquals(0L, r.personalPlanAuthority(scope).version)
        }
    }
    @Test fun legacyQuarantineDoesNotBlockOtherEventsOrTests() = assertUnrelated(1)
    @Test fun currentQuarantineDoesNotBlockOtherEventsOrTests() = assertUnrelated(2)

    @Test fun terminalImportedHistoryDoesNotCreateAnEventWidePause() {
        for (format in 1..2) {
            val original = seed(format, OccurrenceStatus.DONE)
            repo(restored).use { r ->
                assertEquals(0, r.restorePausedCount())
                assertEquals(1, r.materialize(listOf(item("B"))))
                assertEquals(OccurrenceStatus.DONE, r.get(original.id)!!.status)
                assertTrue(r.isDeliveryAllowed(item("B").id))
            }
        }
    }

    @Test fun importedTestSharingAnEventIdDoesNotPausePersonalMaterialization() {
        for (format in 1..2) {
            val original = seed(format, test = true)
            repo(restored).use { r ->
                assertEquals(1, r.materialize(listOf(item("B"))))
                assertFalse(r.isDeliveryAllowed(original.id))
                assertTrue(r.isDeliveryAllowed(item("B").id))
                assertEquals(1, r.restorePausedCount())
            }
        }
    }
}
