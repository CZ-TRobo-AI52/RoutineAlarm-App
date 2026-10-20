package com.cztr.routinealarm.occurrence

import android.content.Context
import com.cztr.routinealarm.EventCategory
import com.cztr.routinealarm.EventMode
import com.cztr.routinealarm.PersonalSchedule
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
import java.time.ZoneOffset

/** Shared receipts are observations of a supplied adapter; no weekly Android path is enabled here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ScopedSchedulingTest {
    private lateinit var context: Context
    private lateinit var repo: SqliteOccurrenceRepository
    private val name = "scoped-receipts.db"
    private val restoredName = "scoped-restored.db"
    private val event = PersonalSchedule.allEvents.single { it.id == "evening_messages_2110" }
    private val scope = OccurrenceScope.PersonalEvent("evening_messages_2110")
    private val clock = MutableClock(Instant.parse("2026-10-19T12:00:00Z"))
    private var failCommit = false
    private var calls = 0
    private val armed = mutableSetOf<AlarmDelivery>()
    private class Crash : Error("simulated process exit")
    private class MutableClock(var now: Instant) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    }

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        repo = SqliteOccurrenceRepository(context, clock, name, beforeCommit = { if (failCommit) error("commit failed") })
    }
    @After fun cleanup() {
        repo.close()
        context.deleteDatabase(name)
        context.deleteDatabase(restoredName)
    }
    private fun reopen() {
        repo.close()
        repo = SqliteOccurrenceRepository(context, clock, name)
    }
    private fun planned(id: String = event.id, test: Boolean = false, days: Int = 1, revision: String = "scoped:Berlin") =
        OccurrencePlanner(clock).plan(
            listOf(event.copy(id = id)), LocalDate.parse("2026-10-19"), days,
            ZoneId.of("Europe/Berlin"), revision, isTest = test,
        ).also { repo.materialize(it) }
    private fun platform(item: RoutineOccurrence) { calls++; armed += AlarmDelivery.from(item, repo.deliveryEpoch()) }
    private fun scheduler(
        selected: OccurrenceScope = scope,
        permission: () -> Boolean = { true },
        afterAttempt: (SchedulingAttempt) -> Unit = {},
        afterReturn: () -> Unit = {},
    ) = OccurrenceScheduling(repo, clock, selected, permission, ::platform, afterAttempt, afterReturn)
    private fun <T> succeeds(block: () -> T): T {
        val result = runCatching(block)
        assertTrue("Scoped operation failed: ${result.exceptionOrNull()}", result.isSuccess)
        return result.getOrThrow()
    }
    private fun attempt(item: RoutineOccurrence, selected: OccurrenceScope = scope) =
        requireNotNull(succeeds { repo.beginSchedulingAttempt(item, true, selected) })
    private fun transition(item: RoutineOccurrence, type: ActionType, minutes: Int? = null): RoutineOccurrence {
        val result = repo.apply(ExecutionAction(
            "${item.id}:${type.name}:${item.revision}", item.id, type,
            if (type == ActionType.TRIGGER) Actor.SYSTEM else Actor.USER,
            clock.instant(), item.revision, item.generation, minutes, "scoped-test",
        ))
        assertEquals(Outcome.APPLIED, result.outcome)
        return result.occurrence
    }

    @Test fun scopesPartitionTestAndPersonalIdentityAndBoundUtf8Ids() {
        val personal = planned().single()
        val test = planned(test = true).single()
        val other = planned("evening_finances_2100").single()
        assertEquals(EventCategory.EVENING, personal.category)
        assertEquals(EventMode.ANNOUNCEMENT, personal.mode)
        assertEquals(Instant.parse("2026-10-19T19:10:00Z"), personal.plannedAt)
        assertTrue(scope.accepts(personal)); assertFalse(scope.accepts(test)); assertFalse(scope.accepts(other))
        assertTrue(OccurrenceScope.Tests.accepts(test)); assertFalse(OccurrenceScope.Tests.accepts(personal))
        assertEquals("tests", OccurrenceScope.Tests.key)
        assertEquals("personal:evening_messages_2110", scope.key)
        assertEquals(scope, OccurrenceScope.PersonalEvent(event.id))
        assertEquals("personal:" + "ü".repeat(128), OccurrenceScope.PersonalEvent("ü".repeat(128)).key)
        for (invalid in listOf("", " \t\n", "x".repeat(257), "ü".repeat(129))) {
            assertThrows(IllegalArgumentException::class.java) { OccurrenceScope.PersonalEvent(invalid) }
        }
    }

    @Test fun personalAttemptCommitsBeforeCallAndHistoricalReceiptSurvivesTerminalBackup() {
        val item = planned().single()
        val scheduler = OccurrenceScheduling(repo, clock, scope, { true }, { requested ->
            SqliteOccurrenceRepository(context, clock, name).use {
                val saved = it.schedulingAttempts(item.id).single()
                assertNull(saved.receipt); assertTrue(saved.effectUnknown)
                assertEquals(item, it.get(item.id))
            }
            platform(requested)
        })
        assertTrue(succeeds { scheduler.schedule(item, true) })
        attempt(item) // A second attempt has no observation; preserve that uncertainty.
        clock.now = item.currentTriggerAt
        val triggered = transition(item, ActionType.TRIGGER)
        val done = transition(triggered, ActionType.DONE)
        val expectedHistory = repo.history(item.id)
        val expectedAttempts = repo.schedulingAttempts(item.id)
        assertEquals(SchedulingOutcome.CALL_RETURNED, expectedAttempts.first().receipt!!.outcome)
        assertNull(expectedAttempts.last().receipt)
        reopen()
        assertEquals(expectedAttempts, repo.schedulingAttempts(item.id))
        val backup = succeeds { repo.exportBackup() }
        SqliteOccurrenceRepository(context, clock, restoredName).use {
            assertEquals(1, it.restoreBackup(backup))
            assertEquals(done, it.get(item.id))
            assertEquals(expectedHistory, it.history(item.id))
            assertEquals(expectedAttempts, it.schedulingAttempts(item.id))
            assertArrayEquals(backup, it.exportBackup())
        }
        assertEquals(1, calls)
    }

    @Test fun wrongScopeAndLegacyTestEntryPointsRejectBeforeWritingOrCalling() {
        val personal = planned().single()
        val test = planned(test = true).single()
        val other = planned("evening_finances_2100").single()
        assertThrows(IllegalStateException::class.java) {
            TestAlarmScheduling(repo, clock, { true }, ::platform).schedule(personal, true)
        }
        assertThrows(IllegalStateException::class.java) { repo.beginSchedulingAttempt(personal, true) }
        for (item in listOf(test, other)) {
            assertThrows(IllegalStateException::class.java) { scheduler().schedule(item, true) }
        }
        for (item in listOf(personal, test, other)) {
            assertTrue(repo.schedulingAttempts(item.id).isEmpty())
            assertEquals(listOf("SCHEDULE"), repo.history(item.id).map { it.actionType })
        }
        assertEquals(0, calls)
    }

    @Test fun scopedFuturePagesUseExactBoundIdAndStableTieBreakers() {
        val selected = planned(days = 3) + planned(revision = "scoped-second:Berlin")
        val tests = planned(test = true, days = 3)
        planned("evening_finances_2100", days = 3)
        val quotedId = "quote' OR 1=1 --"
        val quoted = planned(quotedId).single()
        val expected = selected.sortedWith(compareBy({ it.currentTriggerAt }, { it.id }))
        val first = repo.futureOccurrencesPage(scope, clock.instant(), limit = 1).single()
        assertEquals(expected.first(), first)
        val rest = repo.futureOccurrencesPage(scope, clock.instant(), first.currentTriggerAt.toEpochMilli(), first.id)
        assertEquals(expected.drop(1), rest)
        assertEquals(tests, repo.futureOccurrencesPage(OccurrenceScope.Tests, clock.instant()))
        assertEquals(tests, repo.futureTestsPage(clock.instant()))
        assertEquals(listOf(quoted), repo.futureOccurrencesPage(OccurrenceScope.PersonalEvent(quotedId), clock.instant()))
        assertTrue(repo.futureOccurrencesPage(OccurrenceScope.PersonalEvent("missing"), clock.instant()).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { repo.futureOccurrencesPage(scope, clock.instant(), limit = 0) }
        assertThrows(IllegalArgumentException::class.java) { repo.futureOccurrencesPage(scope, clock.instant(), 1, null) }
    }

    @Test fun knownDeliveriesKeepScopesSeparateUnknownEffectsAndWatermark() {
        val personal = planned().single()
        val test = planned(test = true).single()
        val other = planned("evening_finances_2100").single()
        val omitted = attempt(personal)
        repo.recordSchedulingReceipt(omitted.id, SchedulingOutcome.NOT_CALLED, SchedulingReason.STALE)
        val cutoff = repo.schedulingWatermark()
        assertTrue(repo.knownDeliveries(scope, cutoff, 0).isEmpty())
        val unknown = attempt(personal)
        val testAttempt = attempt(test, OccurrenceScope.Tests)
        repo.recordSchedulingReceipt(testAttempt.id, SchedulingOutcome.CALL_FAILED, SchedulingReason.PLATFORM_EXCEPTION)
        attempt(other, OccurrenceScope.PersonalEvent(other.eventId))
        val through = repo.schedulingWatermark()
        val delivery = repo.knownDeliveries(scope, through, 0).single()
        assertEquals(AlarmDelivery.from(personal, repo.deliveryEpoch()), delivery.token)
        // First-sequence cursor remains tied to the first attempt, even if it was NOT_CALLED.
        assertEquals(cutoff, delivery.firstSequence)
        assertTrue(repo.knownDeliveries(scope, cutoff, 0).isEmpty())
        assertTrue(repo.knownDeliveries(scope, through, delivery.firstSequence).isEmpty())
        assertTrue(repo.knownDeliveries(scope, through, 0, other.id).isEmpty())
        assertEquals(listOf(test.id), repo.knownTestDeliveries(through, 0).map { it.token.occurrenceId })
        assertEquals(repo.knownTestDeliveries(through, 0), repo.knownDeliveries(OccurrenceScope.Tests, through, 0))
        assertTrue(repo.schedulingAttempts(personal.id).single { it.id == unknown.id }.effectUnknown)
    }

    @Test fun failedAttemptCommitPreventsPersonalPlatformEffects() {
        val item = planned().single()
        failCommit = true
        assertThrows(IllegalStateException::class.java) { scheduler().schedule(item, true) }
        failCommit = false
        assertEquals(0, calls); assertTrue(repo.schedulingAttempts(item.id).isEmpty())
        assertTrue(succeeds { scheduler().schedule(item, true) })
        assertEquals(1, calls)
    }

    @Test fun personalCrashWindowsAndReceiptCommitFailureRemainUnknownAcrossReopen() {
        val item = planned().single()
        assertThrows(Crash::class.java) { scheduler(afterAttempt = { throw Crash() }).schedule(item, true) }
        assertEquals(0, calls); assertNull(repo.schedulingAttempts(item.id).single().receipt)
        reopen()
        assertThrows(Crash::class.java) { scheduler(afterReturn = { throw Crash() }).schedule(item, true) }
        assertEquals(1, calls); assertTrue(repo.schedulingAttempts(item.id).all { it.receipt == null })
        reopen()
        // Use a separate connection with a failing commit hook for the receipt transaction.
        SqliteOccurrenceRepository(context, clock, name, beforeCommit = { if (failCommit) error("receipt commit failed") }).use { failing ->
            val scheduler = OccurrenceScheduling(failing, clock, scope, { true }, { platform(it); failCommit = true })
            assertThrows(IllegalStateException::class.java) { scheduler.schedule(item, true) }
        }
        failCommit = false
        assertEquals(2, calls); assertTrue(repo.schedulingAttempts(item.id).all { it.receipt == null })
        assertTrue(succeeds { scheduler().schedule(item, true) })
        assertEquals(1, armed.size)
        assertEquals(4, repo.schedulingAttempts(item.id).size)
        assertEquals(SchedulingOutcome.CALL_RETURNED, repo.schedulingAttempts(item.id).last().receipt!!.outcome)
        assertEquals(listOf("SCHEDULE"), repo.history(item.id).map { it.actionType })
    }

    @Test fun personalDeadlineAndPermissionDistinguishNoCallFromFailedCall() {
        val item = planned().single()
        assertFalse(succeeds { scheduler(permission = { clock.now = item.currentTriggerAt; true }).schedule(item, true) })
        assertEquals(SchedulingReason.EXPIRED, repo.schedulingAttempts(item.id).single().receipt!!.reason)
        assertEquals(0, calls)
        assertFalse(succeeds { scheduler().schedule(item, true) })
        assertEquals(1, repo.schedulingAttempts(item.id).size)
        assertThrows(SecurityException::class.java) { scheduler(permission = { false }).schedule(item, false) }
        assertFalse(repo.schedulingAttempts(item.id).last().effectUnknown)
        val failing = OccurrenceScheduling(repo, clock, scope, { true }, { platform(it); throw SecurityException("detail") })
        assertThrows(SecurityException::class.java) { failing.schedule(item, false) }
        val receipt = repo.schedulingAttempts(item.id).last()
        assertEquals(SchedulingOutcome.CALL_FAILED, receipt.receipt!!.outcome)
        assertTrue(receipt.effectUnknown); assertEquals(1, calls)
    }

    @Test fun personalChangedStateBetweenAttemptAndCallIsNotRevived() {
        val item = planned().single()
        clock.now = item.currentTriggerAt
        val scheduler = scheduler(permission = {
            val triggered = transition(item, ActionType.TRIGGER)
            transition(triggered, ActionType.SNOOZE, 5)
            true
        })
        assertFalse(succeeds { scheduler.schedule(item, false) })
        assertEquals(SchedulingReason.STALE, repo.schedulingAttempts(item.id).single().receipt!!.reason)
        assertEquals(0, calls); assertEquals(OccurrenceStatus.SNOOZED, repo.get(item.id)!!.status)
        assertThrows(IllegalStateException::class.java) { scheduler().schedule(item, true) }
    }

    @Test fun restoredPersonalHistoryStaysQuarantinedAcrossReopenAndTestQueries() {
        val item = planned().single()
        attempt(item)
        val backup = succeeds { repo.exportBackup() }
        var epoch = ""
        SqliteOccurrenceRepository(context, clock, restoredName).use {
            assertEquals(1, it.restoreBackup(backup))
            epoch = it.deliveryEpoch()
            assertTrue(epoch.isNotBlank())
            assertFalse(it.isDeliveryAllowed(item.id))
            assertTrue(it.futureOccurrencesPage(scope, clock.instant()).isEmpty())
            assertThrows(IllegalStateException::class.java) { it.beginSchedulingAttempt(item, true, scope) }
            assertTrue(it.knownTestDeliveries(it.schedulingWatermark(), 0).isEmpty())
            assertEquals(1, it.knownDeliveries(scope, it.schedulingWatermark(), 0).size)
            assertNull(OccurrenceDeliveryGate(it, scope::accepts).triggerCandidate(AlarmDelivery.from(item, epoch), item.currentTriggerAt))
        }
        SqliteOccurrenceRepository(context, clock, restoredName).use {
            assertEquals(epoch, it.deliveryEpoch())
            assertFalse(it.isDeliveryAllowed(item.id))
            assertTrue(it.activateRestored(item.id))
            assertEquals(listOf(item), it.futureOccurrencesPage(scope, clock.instant()))
            assertTrue(it.futureTestsPage(clock.instant()).isEmpty())
            assertNull(OccurrenceDeliveryGate(it, scope::accepts).triggerCandidate(AlarmDelivery.from(item), item.currentTriggerAt))
            assertArrayEquals(backup, it.exportBackup())
        }
    }

    @Test fun personalAttemptBackupStillRejectsForgedHistoricalBindingBeforeRestore() {
        val item = planned().single()
        attempt(item)
        val backup = succeeds { repo.exportBackup() }
        val snapshot = OccurrenceBackup.decode(backup)
        val tableIndex = OccurrenceBackup.tables.indexOfFirst { it.name == "scheduling_attempts" }
        for ((column, replacement) in listOf("generation" to 7L, "revision" to 100L, "trigger_at" to 0L, "occurrence_id" to "missing")) {
            val columnIndex = OccurrenceBackup.tables[tableIndex].columns.indexOfFirst { it.name == column }
            val modified = OccurrenceBackup.Snapshot(snapshot.tables.mapIndexed { ti, rows ->
                if (ti != tableIndex) rows else rows.map { row ->
                    row.mapIndexed { ci, cell -> if (ci == columnIndex) replacement else cell }
                }
            })
            SqliteOccurrenceRepository(context, clock, restoredName).use {
                assertThrows(IllegalArgumentException::class.java) { it.restoreBackup(OccurrenceBackup.encode(modified)) }
                assertNull(it.get(item.id)); assertEquals("", it.deliveryEpoch())
            }
        }
    }
}
