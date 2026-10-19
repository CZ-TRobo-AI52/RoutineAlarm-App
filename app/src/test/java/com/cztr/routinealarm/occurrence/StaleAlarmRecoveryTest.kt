@file:Suppress("DEPRECATION")

package com.cztr.routinealarm.occurrence

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.cztr.routinealarm.AlarmScheduler
import com.cztr.routinealarm.ScheduleRegistry
import com.cztr.routinealarm.ScheduleState
import com.cztr.routinealarm.execution.TestAlarmReceiver
import com.cztr.routinealarm.execution.TestAlarmRuntime
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** The shadow's platform inventory is test evidence, never a production inventory API. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class StaleAlarmRecoveryTest {
    private lateinit var context: Context
    private lateinit var repo: SqliteOccurrenceRepository
    private lateinit var manager: AlarmManager
    private lateinit var clock: MutableClock
    private class Crash : Error("process exit after platform effect")
    private class MutableClock(var now: Instant) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    }

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("occurrences.db")
        context.deleteDatabase("stale-source.db")
        clock = MutableClock(Instant.ofEpochMilli(System.currentTimeMillis()))
        repo = SqliteOccurrenceRepository(context, clock)
        manager = context.getSystemService(AlarmManager::class.java)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ScheduleState.setEnabled(context, false)
    }

    @After fun cleanup() {
        repo.close()
        context.deleteDatabase("occurrences.db")
        context.deleteDatabase("stale-source.db")
    }

    private fun create() = TestAlarmCoordinator(repo, clock).create(10)
    private fun register(item: RoutineOccurrence, epoch: String = repo.deliveryEpoch()) {
        val intent = TestAlarmRuntime.putToken(Intent(context, TestAlarmReceiver::class.java),
            AlarmDelivery.from(item, epoch))
        val pending = PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.currentTriggerAt.toEpochMilli(), pending)
    }
    private fun tokens(): List<AlarmDelivery> = shadowOf(manager).scheduledAlarms.mapNotNull {
        TestAlarmRuntime.token(shadowOf(it.operation).savedIntent)
    }

    /** Mutate after the scheduler's last check, then let its old external call take effect. */
    private fun staleAfterCheck(item: RoutineOccurrence, action: ActionType,
        crashAfterReturn: Boolean = false, failAfterEffect: Boolean = false): RoutineOccurrence {
        clock.now = item.currentTriggerAt
        lateinit var changed: RoutineOccurrence
        val scheduler = TestAlarmScheduling(repo, clock, { true }, { requested ->
            val coordinator = TestAlarmCoordinator(repo, clock)
            val epoch = repo.deliveryEpoch()
            val triggered = coordinator.trigger(AlarmDelivery.from(requested, epoch))!!
            changed = coordinator.act(AlarmDelivery.from(triggered, epoch), action,
                if (action == ActionType.SNOOZE) 5 else null)!!.occurrence
            register(requested, epoch)
            if (failAfterEffect) throw IllegalStateException("platform threw after taking effect")
        }, afterPlatformReturn = { if (crashAfterReturn) throw Crash() })
        when {
            crashAfterReturn -> assertThrows(Crash::class.java) { scheduler.schedule(item, false) }
            failAfterEffect -> assertThrows(IllegalStateException::class.java) { scheduler.schedule(item, false) }
            else -> assertTrue(scheduler.schedule(item, false))
        }
        assertTrue(tokens().contains(AlarmDelivery.from(item, repo.deliveryEpoch())))
        return changed
    }

    @Test fun staleSnoozeRegistrationAfterCrashIsRemovedAcrossReopenWithoutRewritingUnknownReceipt() {
        val original = create()
        val snoozed = staleAfterCheck(original, ActionType.SNOOZE, crashAfterReturn = true)
        val history = repo.history(original.id)
        val unknown = repo.schedulingAttempts(original.id).single()
        assertNull(unknown.receipt)
        repo.close()
        repo = SqliteOccurrenceRepository(context, clock)

        repeat(2) { TestAlarmRuntime.restoreFuture(context) }

        assertEquals(listOf(AlarmDelivery.from(snoozed)), tokens())
        assertEquals(snoozed, repo.get(original.id))
        assertEquals(history, repo.history(original.id))
        assertEquals(unknown, repo.schedulingAttempts(original.id).first())
        assertNull(repo.schedulingAttempts(original.id).first().receipt)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }

    @Test fun terminalRegistrationWithReturnedReceiptIsRemovedAndTerminalHistoryStaysUntouched() {
        val original = create()
        val completed = staleAfterCheck(original, ActionType.DONE)
        val history = repo.history(original.id)
        val attempts = repo.schedulingAttempts(original.id)
        assertEquals(SchedulingOutcome.CALL_RETURNED, attempts.single().receipt!!.outcome)

        repeat(2) { TestAlarmRuntime.restoreFuture(context) }

        assertTrue(tokens().isEmpty())
        assertEquals(completed, repo.get(original.id))
        assertEquals(history, repo.history(original.id))
        assertEquals(attempts, repo.schedulingAttempts(original.id))
    }

    @Test fun failedCallThatRegisteredOldIdentityStillGetsReconciled() {
        val original = create()
        val completed = staleAfterCheck(original, ActionType.SKIP, failAfterEffect = true)
        val before = repo.schedulingAttempts(original.id).single()
        assertEquals(SchedulingOutcome.CALL_FAILED, before.receipt!!.outcome)
        assertTrue(before.effectUnknown)

        TestAlarmRuntime.restoreFuture(context)

        assertTrue(tokens().isEmpty())
        assertEquals(completed, repo.get(original.id))
        assertEquals(listOf(before), repo.schedulingAttempts(original.id))
    }

    @Test fun identicalOverdueManualRetryRemainsRegisteredWithoutAutomaticReassert() {
        clock.now = clock.now.minusSeconds(20 * 60)
        val overdue = create()
        TestAlarmRuntime.schedule(context, overdue)
        val history = repo.history(overdue.id)
        val attempts = repo.schedulingAttempts(overdue.id)

        repeat(2) { TestAlarmRuntime.restoreFuture(context) }

        assertEquals(listOf(AlarmDelivery.from(overdue)), tokens())
        assertEquals(history, repo.history(overdue.id))
        assertEquals(attempts, repo.schedulingAttempts(overdue.id))
    }

    @Test fun originalDeliveryTokenForCurrentTriggeredStateRemainsValidForRedelivery() {
        val original = create()
        clock.now = original.currentTriggerAt
        val coordinator = TestAlarmCoordinator(repo, clock)
        lateinit var triggered: RoutineOccurrence
        assertTrue(TestAlarmScheduling(repo, clock, { true }, { requested ->
            triggered = coordinator.trigger(AlarmDelivery.from(requested))!!
            register(requested)
        }).schedule(original, false))
        val history = repo.history(original.id)

        TestAlarmRuntime.restoreFuture(context)

        assertEquals(listOf(AlarmDelivery.from(original)), tokens())
        assertEquals(triggered, coordinator.trigger(AlarmDelivery.from(original)))
        assertEquals(history, repo.history(original.id))
    }

    @Test fun staleTestCleanupLeavesRealWeeklyAlarmIdentityUntouched() {
        assertTrue(AlarmScheduler.scheduleEvent(context, ScheduleRegistry.enabledEvents.first()))
        val weekly = shadowOf(manager).scheduledAlarms.single().operation
        staleAfterCheck(create(), ActionType.DONE)
        assertEquals(2, shadowOf(manager).scheduledAlarms.size)

        TestAlarmRuntime.restoreFuture(context)

        assertEquals(1, shadowOf(manager).scheduledAlarms.size)
        assertEquals(weekly, shadowOf(manager).scheduledAlarms.single().operation)
        assertTrue(tokens().isEmpty())
    }

    @Test fun restoreEpochIsolationSurvivesReconciliationAndPreservesActivatedOverdueRetry() {
        val sourceClock = Clock.offset(Clock.systemUTC(), java.time.Duration.ofMinutes(-2))
        lateinit var original: RoutineOccurrence
        SqliteOccurrenceRepository(context, sourceClock, "stale-source.db").use { source ->
            original = TestAlarmCoordinator(source, sourceClock).create(1)
            assertTrue(TestAlarmScheduling(source, sourceClock, { true }, { register(it, "") })
                .schedule(original, true))
            assertEquals(1, repo.restoreBackup(source.exportBackup()))
        }
        val old = AlarmDelivery.from(original)
        val epoch = repo.deliveryEpoch()
        assertTrue(epoch.isNotEmpty())
        TestAlarmRuntime.restoreFuture(context)
        assertFalse(repo.isDeliveryAllowed(original.id))
        assertTrue(repo.activateRestored(original.id))
        TestAlarmRuntime.schedule(context, original)
        val current = AlarmDelivery.from(original, epoch)
        val history = repo.history(original.id)

        repeat(2) { TestAlarmRuntime.restoreFuture(context) }

        assertTrue(tokens().contains(current))
        TestAlarmReceiver().onReceive(context, TestAlarmRuntime.putToken(
            Intent(context, TestAlarmReceiver::class.java), old))
        assertEquals(original, repo.get(original.id))
        assertEquals(history, repo.history(original.id))
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }
}
