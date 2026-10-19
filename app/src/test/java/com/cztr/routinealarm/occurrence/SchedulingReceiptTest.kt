package com.cztr.routinealarm.occurrence

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SchedulingReceiptTest {
    private lateinit var context: Context
    private lateinit var repo: SqliteOccurrenceRepository
    private val name = "receipts.db"
    private val clock = MutableClock(Instant.parse("2026-10-19T10:00:00Z"))
    private var failCommit = false
    private val armed = mutableMapOf<AlarmDelivery, Instant>()
    private var calls = 0
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
    @After fun cleanup() { repo.close(); context.deleteDatabase(name) }
    private fun create() = TestAlarmCoordinator(repo, clock).create(1)
    private fun platform(item: RoutineOccurrence) { calls++; armed[AlarmDelivery.from(item)] = item.currentTriggerAt }
    private fun scheduler(afterAttempt: (SchedulingAttempt) -> Unit = {}, afterReturn: () -> Unit = {}) =
        TestAlarmScheduling(repo, clock, { true }, ::platform, afterAttempt, afterReturn)

    @Test fun attemptCommitsBeforePlatformAndReceiptSurvivesReopenWithoutClaimingDelivery() {
        val item = create()
        val scheduler = TestAlarmScheduling(repo, clock, { true }, { requested ->
            SqliteOccurrenceRepository(context, clock, name).use {
                val attempt = it.schedulingAttempts(item.id).single()
                assertNull(attempt.receipt); assertTrue(attempt.effectUnknown)
            }
            platform(requested)
        })
        assertTrue(scheduler.schedule(item, true))
        repo.close()
        repo = SqliteOccurrenceRepository(context, clock, name)
        val attempt = repo.schedulingAttempts(item.id).single()
        assertEquals(SchedulingOutcome.CALL_RETURNED, attempt.receipt!!.outcome)
        assertTrue(attempt.effectUnknown)
        assertEquals(item, repo.get(item.id))
        assertEquals(listOf("SCHEDULE"), repo.history(item.id).map { it.actionType })
    }

    @Test fun failedAttemptCommitPreventsAllPlatformEffects() {
        val item = create(); failCommit = true
        assertThrows(IllegalStateException::class.java) { scheduler().schedule(item, true) }
        failCommit = false
        assertEquals(0, calls); assertTrue(repo.schedulingAttempts(item.id).isEmpty())
        assertEquals(item, repo.get(item.id))
    }

    @Test fun crashBeforePlatformLeavesUnknownAttemptAndRetryReassertsSameIdentity() {
        val item = create()
        assertThrows(Crash::class.java) { scheduler(afterAttempt = { throw Crash() }).schedule(item, true) }
        assertEquals(0, calls)
        assertNull(repo.schedulingAttempts(item.id).single().receipt)
        repo.close(); repo = SqliteOccurrenceRepository(context, clock, name)
        scheduler().schedule(item, true)
        val attempts = repo.schedulingAttempts(item.id)
        assertEquals(2, attempts.size); assertNull(attempts.first().receipt)
        assertEquals(SchedulingOutcome.CALL_RETURNED, attempts.last().receipt!!.outcome)
        assertEquals(setOf(AlarmDelivery.from(item)), armed.keys)
    }

    @Test fun crashAfterPlatformAndBeforeReceiptDoesNotInventFailureOrDuplicatePlatformIdentity() {
        val item = create()
        assertThrows(Crash::class.java) { scheduler(afterReturn = { throw Crash() }).schedule(item, true) }
        assertEquals(1, calls); assertNull(repo.schedulingAttempts(item.id).single().receipt)
        repo.close(); repo = SqliteOccurrenceRepository(context, clock, name)
        scheduler().schedule(item, true)
        assertEquals(2, calls); assertEquals(1, armed.size)
        assertEquals(2, repo.schedulingAttempts(item.id).size)
        assertEquals(1, repo.history(item.id).size)
    }

    @Test fun receiptCommitFailurePreservesUnknownEffectAndCanBeReconciled() {
        val item = create()
        val scheduler = TestAlarmScheduling(repo, clock, { true }, { platform(it); failCommit = true })
        assertThrows(IllegalStateException::class.java) { scheduler.schedule(item, true) }
        failCommit = false
        assertNull(repo.schedulingAttempts(item.id).single().receipt)
        assertEquals(1, calls)
        scheduler().schedule(item, true)
        assertEquals(1, armed.size)
        assertEquals(SchedulingOutcome.CALL_RETURNED, repo.schedulingAttempts(item.id).last().receipt!!.outcome)
    }

    @Test fun permissionLossBeforeCallIsNotCalledButThrownPlatformCallRemainsUncertain() {
        val item = create()
        var allowed = true
        val denied = TestAlarmScheduling(repo, clock, { allowed }, ::platform, afterAttempt = { allowed = false })
        assertThrows(SecurityException::class.java) { denied.schedule(item, true) }
        assertEquals(0, calls)
        assertEquals(SchedulingOutcome.NOT_CALLED, repo.schedulingAttempts(item.id).single().receipt!!.outcome)
        assertFalse(repo.schedulingAttempts(item.id).single().effectUnknown)
        val failed = TestAlarmScheduling(repo, clock, { true }, { platform(it); throw SecurityException("secret detail") })
        assertThrows(SecurityException::class.java) { failed.schedule(item, true) }
        val attempt = repo.schedulingAttempts(item.id).last()
        assertEquals(SchedulingOutcome.CALL_FAILED, attempt.receipt!!.outcome)
        assertEquals(SchedulingReason.PERMISSION_DENIED, attempt.receipt!!.reason)
        assertTrue(attempt.effectUnknown)
        assertEquals(item, repo.get(item.id))
    }

    @Test fun staleGenerationAndTerminalStateCannotBeRevivedBetweenCommitAndCall() {
        val item = create()
        clock.now = item.currentTriggerAt
        val changed = scheduler(afterAttempt = {
            val coordinator = TestAlarmCoordinator(repo, clock)
            val active = coordinator.trigger(AlarmDelivery.from(item))!!
            coordinator.act(AlarmDelivery.from(active), ActionType.SNOOZE, 5)
        })
        assertFalse(changed.schedule(item, false))
        assertEquals(SchedulingReason.STALE, repo.schedulingAttempts(item.id).single().receipt!!.reason)
        assertEquals(0, calls)
        assertThrows(IllegalStateException::class.java) { scheduler().schedule(item, false) }
        val snoozed = repo.get(item.id)!!
        clock.now = snoozed.currentTriggerAt
        val done = scheduler(afterAttempt = {
            val coordinator = TestAlarmCoordinator(repo, clock)
            val active = coordinator.trigger(AlarmDelivery.from(snoozed))!!
            coordinator.act(AlarmDelivery.from(active), ActionType.DONE)
        })
        assertFalse(done.schedule(snoozed, false))
        assertEquals(OccurrenceStatus.DONE, repo.get(item.id)!!.status)
        assertEquals(0, calls)
    }

    @Test fun strictFutureDeadlineAndExplicitExpiredRetryHaveDifferentSemantics() {
        val item = create()
        clock.now = item.currentTriggerAt
        assertFalse(scheduler().schedule(item, true))
        assertTrue(repo.schedulingAttempts(item.id).isEmpty())
        assertTrue(scheduler().schedule(item, false))
        assertEquals(1, calls)
    }

    @Test fun subMillisecondFutureCannotBeRoundedIntoAnImmediateAutomaticAlarm() {
        val source = create()
        val at = clock.now.plusNanos(500_000)
        val item = source.copy(id = "sub-millisecond", plannedAt = at, currentTriggerAt = at)
        repo.materialize(listOf(item))
        assertFalse(scheduler().schedule(item, true))
        assertEquals(0, calls); assertTrue(repo.schedulingAttempts(item.id).isEmpty())
        val manual = repo.beginSchedulingAttempt(item, false)!!
        assertEquals(clock.now, manual.triggerAt)
        assertEquals(manual, repo.schedulingAttempts(item.id).single())
    }

    @Test fun deadlinePassedAfterAttemptCommitRecordsNoCall() {
        val item = create()
        assertFalse(scheduler(afterAttempt = { clock.now = item.currentTriggerAt }).schedule(item, true))
        assertEquals(0, calls)
        assertEquals(SchedulingReason.EXPIRED, repo.schedulingAttempts(item.id).single().receipt!!.reason)
    }

    @Test fun deadlineCrossedDuringPermissionCheckMustNotBecomeImmediateAutomaticAlarm() {
        val item = create()
        val scheduler = TestAlarmScheduling(repo, clock,
            { clock.now = item.currentTriggerAt; true }, ::platform)
        assertFalse(scheduler.schedule(item, true))
        assertEquals(0, calls)
        assertEquals(SchedulingReason.EXPIRED, repo.schedulingAttempts(item.id).single().receipt!!.reason)
    }

    @Test fun adapterPreparationCrossingDeadlinePersistsNotCalledInsteadOfReturned() {
        val item = create()
        val scheduler = TestAlarmScheduling(repo, clock, { true }, { current ->
            clock.now = current.currentTriggerAt
            TestAlarmScheduling.requireCurrentBeforeCall(repo, current, clock, true)
            platform(current)
        })
        assertFalse(scheduler.schedule(item, true))
        assertEquals(0, calls)
        val receipt = repo.schedulingAttempts(item.id).single().receipt!!
        assertEquals(SchedulingOutcome.NOT_CALLED, receipt.outcome)
        assertEquals(SchedulingReason.EXPIRED, receipt.reason)
    }

    @Test fun unrepresentableClockCannotWriteAttemptOrInvokePlatform() {
        val item = create(); clock.now = Instant.MAX
        assertThrows(ArithmeticException::class.java) { scheduler().schedule(item, true) }
        assertEquals(0, calls); assertTrue(repo.schedulingAttempts(item.id).isEmpty())
    }

    @Test fun receiptsReplayIdempotentlyButCannotBeRewrittenAndTablesAreAppendOnly() {
        val item = create(); scheduler().schedule(item, true)
        val attempt = repo.schedulingAttempts(item.id).single()
        clock.now = clock.now.plusSeconds(1)
        assertEquals(attempt.receipt, repo.recordSchedulingReceipt(attempt.id, SchedulingOutcome.CALL_RETURNED, SchedulingReason.RETURNED))
        assertThrows(IllegalStateException::class.java) {
            repo.recordSchedulingReceipt(attempt.id, SchedulingOutcome.CALL_FAILED, SchedulingReason.PLATFORM_EXCEPTION)
        }
        assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
            repo.recordSchedulingReceipt("missing", SchedulingOutcome.CALL_RETURNED, SchedulingReason.RETURNED)
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            for (table in listOf("scheduling_attempts", "scheduling_receipts")) {
                assertThrows(android.database.sqlite.SQLiteException::class.java) { db.execSQL("DELETE FROM $table") }
                assertThrows(android.database.sqlite.SQLiteException::class.java) { db.execSQL("UPDATE $table SET attempt_id = 'rewritten'") }
            }
        }
        assertEquals(listOf(attempt), repo.schedulingAttempts(item.id))
    }
}
