package com.cztr.routinealarm.occurrence

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.cztr.routinealarm.execution.TestAlarmReceiver
import com.cztr.routinealarm.execution.TestAlarmRuntime
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
class TestAlarmReconciliationTest {
    private lateinit var context: Context
    private lateinit var repo: SqliteOccurrenceRepository
    private val clock = MutableClock(Instant.parse("2030-01-01T00:00:00Z"))
    private class MutableClock(var now: Instant) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    }
    private class Crash : Error("exit after cancellation")
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("occurrences.db")
        repo = SqliteOccurrenceRepository(context, clock)
    }
    @After fun cleanup() { repo.close(); context.deleteDatabase("occurrences.db") }
    private fun create() = TestAlarmCoordinator(repo, clock).create(1)
    private fun complete(item: RoutineOccurrence) {
        clock.now = item.currentTriggerAt
        val coordinator = TestAlarmCoordinator(repo, clock)
        val triggered = coordinator.trigger(AlarmDelivery.from(item))!!
        assertEquals(Outcome.APPLIED, coordinator.act(AlarmDelivery.from(triggered), ActionType.DONE)!!.outcome)
    }
    private fun possible(item: RoutineOccurrence) = repo.beginSchedulingAttempt(item, false)!!

    @Test fun onlyNotCalledIsExcludedButLaterNotCalledCannotHideUnknownEffect() {
        val untouched = create()
        val denied = possible(untouched)
        repo.recordSchedulingReceipt(denied.id, SchedulingOutcome.NOT_CALLED, SchedulingReason.PERMISSION_DENIED)
        complete(untouched)
        val uncertain = create()
        val unknown = possible(uncertain)
        val later = possible(uncertain)
        repo.recordSchedulingReceipt(later.id, SchedulingOutcome.NOT_CALLED, SchedulingReason.PERMISSION_DENIED)
        complete(uncertain)
        val called = mutableListOf<AlarmDelivery>()
        val result = TestAlarmReconciliation(repo, {
            called.add(it); CancellationObservation.CALL_RETURNED
        }).reconcile()
        assertEquals(listOf(AlarmDelivery.from(uncertain)), called)
        assertEquals(1, result.callReturned)
        assertEquals(unknown, repo.schedulingAttempts(uncertain.id).first())
    }

    @Test fun allPagesAreVisitedAndDuplicateAttemptsStayOneIdentity() {
        val items = (1..130).map {
            val item = create()
            repeat(3) { possible(item) }
            complete(item)
            item
        }
        assertEquals(64, repo.knownTestDeliveries(repo.schedulingWatermark(), 0).size)
        val called = mutableListOf<AlarmDelivery>()
        val start = System.nanoTime()
        val result = TestAlarmReconciliation(repo, {
            called.add(it); CancellationObservation.CALL_RETURNED
        }).reconcile()
        val elapsed = System.nanoTime() - start
        assertEquals(items.map { AlarmDelivery.from(it) }, called)
        assertEquals(ReconciliationResult(130, 0, 0, 130, 0), result)
        println("RECONCILIATION_HOST_MEASUREMENT identities=130 attempts=390 elapsed_ns=$elapsed")
    }

    @Test fun fixedWatermarkDefersAttemptsCreatedDuringExternalCall() {
        val first = create(); possible(first); complete(first)
        lateinit var late: RoutineOccurrence
        val called = mutableListOf<AlarmDelivery>()
        TestAlarmReconciliation(repo, {
            called.add(it)
            late = create(); possible(late); complete(late)
            CancellationObservation.CALL_RETURNED
        }).reconcile()
        assertEquals(listOf(AlarmDelivery.from(first)), called)
        called.clear()
        TestAlarmReconciliation(repo, {
            called.add(it); CancellationObservation.CALL_RETURNED
        }).reconcile()
        assertEquals(listOf(AlarmDelivery.from(first), AlarmDelivery.from(late)), called)
    }

    @Test fun failureKeepsOtherIdentitiesAndRechecksBeforeRetry() {
        val first = create(); possible(first); complete(first)
        val second = create(); possible(second); complete(second)
        val journals = listOf(first, second).associate { it.id to repo.history(it.id) }
        val errors = mutableListOf<Exception>()
        val calls = mutableListOf<AlarmDelivery>()
        val result = TestAlarmReconciliation(repo, {
            calls.add(it)
            if (it.occurrenceId == first.id) throw SecurityException("platform rejected cancellation")
            CancellationObservation.CALL_RETURNED
        }, errors::add).reconcile()
        assertEquals(2, calls.size)
        assertEquals(1, errors.size)
        assertEquals(ReconciliationResult(2, 0, 0, 1, 1), result)
        val retried = TestAlarmReconciliation(repo, { CancellationObservation.TOKEN_NOT_FOUND }).reconcile()
        assertEquals(ReconciliationResult(2, 0, 2, 0, 0), retried)
        journals.forEach { (id, entries) -> assertEquals(entries, repo.history(id)) }
    }

    @Test fun processExitAfterCancelIsSafeToReconcileAfterReopen() {
        val item = create(); possible(item); complete(item)
        val armed = mutableSetOf(AlarmDelivery.from(item))
        val history = repo.history(item.id)
        assertThrows(Crash::class.java) {
            TestAlarmReconciliation(repo, { armed.remove(it); throw Crash() }).reconcile()
        }
        repo.close(); repo = SqliteOccurrenceRepository(context, clock)
        val result = TestAlarmReconciliation(repo, {
            if (armed.remove(it)) CancellationObservation.CALL_RETURNED else CancellationObservation.TOKEN_NOT_FOUND
        }).reconcile()
        assertEquals(1, result.tokenNotFound)
        assertEquals(history, repo.history(item.id))
        assertNull(repo.schedulingAttempts(item.id).single().receipt)
    }

    @Test fun unknownAttemptWithoutPlatformTokenDoesNotCreateOneDuringCleanup() {
        val item = create(); possible(item); complete(item)
        val intent = TestAlarmRuntime.putToken(Intent(context, TestAlarmReceiver::class.java), AlarmDelivery.from(item))
        repeat(2) {
            TestAlarmRuntime.restoreFuture(context)
            assertNull(PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
        }
        assertNull(repo.schedulingAttempts(item.id).single().receipt)
    }

    @Test fun targetedCleanupLeavesOtherOccurrencesForTheirOwnReconciliation() {
        val first = create(); possible(first); complete(first)
        val second = create(); possible(second); complete(second)
        val called = mutableListOf<AlarmDelivery>()
        val result = TestAlarmReconciliation(repo, {
            called.add(it); CancellationObservation.CALL_RETURNED
        }).reconcile(second.id)
        assertEquals(listOf(AlarmDelivery.from(second)), called)
        assertEquals(1, result.examined)
        assertFalse(TestAlarmReconciliation.isObsolete(repo, AlarmDelivery.from(second, "wrong-epoch")))
    }
}
