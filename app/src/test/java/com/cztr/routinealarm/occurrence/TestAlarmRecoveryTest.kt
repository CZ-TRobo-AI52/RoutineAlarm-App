package com.cztr.routinealarm.occurrence

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import com.cztr.routinealarm.BootReceiver
import com.cztr.routinealarm.MainActivity
import com.cztr.routinealarm.ScheduleState
import com.cztr.routinealarm.execution.TestAlarmActivity
import com.cztr.routinealarm.execution.TestAlarmReceiver
import com.cztr.routinealarm.execution.TestAlarmRuntime
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Clock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TestAlarmRecoveryTest {
    private lateinit var context: Context
    private lateinit var repo: SqliteOccurrenceRepository
    private lateinit var manager: AlarmManager

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("occurrences.db")
        repo = TestAlarmRuntime.repository(context)
        manager = context.getSystemService(AlarmManager::class.java)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ScheduleState.setEnabled(context, false)
    }

    @After fun cleanup() { repo.close(); context.deleteDatabase("occurrences.db") }

    private fun create(offsetMinutes: Long = 0) = TestAlarmCoordinator(repo,
        Clock.offset(Clock.systemDefaultZone(), Duration.ofMinutes(offsetMinutes))).create(1)

    private fun cancelPlatformAlarms() {
        shadowOf(manager).scheduledAlarms.toList().forEach { manager.cancel(it.operation!!) }
    }

    private fun tokens() = shadowOf(manager).scheduledAlarms.map {
        TestAlarmRuntime.token(shadowOf(it.operation).savedIntent)
    }

    @Test fun restoresPersistedFutureAfterRepositoryReopenWithoutNewJournalEntries() {
        val item = create()
        repo.close()
        TestAlarmRuntime.restoreFuture(context)
        TestAlarmRuntime.restoreFuture(context)
        assertEquals(listOf(AlarmDelivery.from(item)), tokens())
        assertEquals(item.currentTriggerAt.toEpochMilli(), shadowOf(manager).scheduledAlarms.single().triggerAtTime)
        TestAlarmRuntime.repository(context).use {
            assertEquals(item, it.get(item.id))
            assertEquals(listOf("SCHEDULE"), it.history(item.id).map { entry -> entry.actionType })
        }
    }

    @Test fun allRecoveryBroadcastsRestoreTestsWhileWeeklyPlanRemainsPaused() {
        val item = create()
        listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED).forEach { action ->
            cancelPlatformAlarms()
            BootReceiver().onReceive(context, Intent(action))
            assertEquals(action, listOf(AlarmDelivery.from(item)), tokens())
            assertFalse(ScheduleState.isEnabled(context))
        }
    }

    @Test fun permissionDenialRetainsProjectionAndGrantRestoresIt() {
        val item = create()
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        TestAlarmRuntime.restoreFuture(context)
        assertTrue(tokens().isEmpty())
        assertEquals(item, repo.get(item.id))
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        BootReceiver().onReceive(context, Intent(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED))
        assertEquals(listOf(AlarmDelivery.from(item)), tokens())
        assertEquals(1, repo.history(item.id).size)
    }

    @Test fun mainResumeAndHistoryResumeRestoreAfterPlatformClearsAlarms() {
        val item = create()
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        try { assertEquals(listOf(AlarmDelivery.from(item)), tokens()) }
        finally { main.pause().stop().destroy() }
        cancelPlatformAlarms()
        val history = Robolectric.buildActivity(TestAlarmActivity::class.java).setup()
        try { assertEquals(listOf(AlarmDelivery.from(item)), tokens()) }
        finally { history.pause().stop().destroy() }
    }

    @Test fun recoveryKeepsOverdueAndTriggeredTestsForExplicitUserRecovery() {
        val overdue = create(-3)
        val triggered = TestAlarmRuntime.coordinator(repo).trigger(AlarmDelivery.from(create(-2)))!!
        val journals = listOf(overdue, triggered).associate { it.id to repo.history(it.id) }
        TestAlarmRuntime.restoreFuture(context)
        assertTrue(tokens().isEmpty())
        journals.forEach { (id, history) -> assertEquals(history, repo.history(id)) }
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        // The existing user-initiated retry remains available for an overdue test.
        TestAlarmRuntime.schedule(context, overdue)
        assertEquals(listOf(AlarmDelivery.from(overdue)), tokens())
    }

    @Test fun recoveryProjectionExcludesTheExactDeadline() {
        val item = create()
        assertEquals(listOf(item), repo.futureTests(item.currentTriggerAt.minusMillis(1)))
        assertTrue(repo.futureTests(item.currentTriggerAt).isEmpty())
        assertTrue(repo.futureTests(item.currentTriggerAt.plusMillis(1)).isEmpty())
    }

    @Test fun recoveryOnlyRestoresCurrentSnoozeAndOldDeliveryCannotReviveIt() {
        val original = create(-2)
        val coordinator = TestAlarmRuntime.coordinator(repo)
        val triggered = coordinator.trigger(AlarmDelivery.from(original))!!
        val snoozed = coordinator.act(AlarmDelivery.from(triggered), ActionType.SNOOZE, 5)!!.occurrence
        TestAlarmRuntime.restoreFuture(context)
        assertEquals(listOf(AlarmDelivery.from(snoozed)), tokens())
        val journal = repo.history(original.id)
        TestAlarmReceiver().onReceive(context, TestAlarmRuntime.putToken(
            Intent(context, TestAlarmReceiver::class.java), AlarmDelivery.from(original)))
        assertEquals(snoozed, repo.get(original.id))
        assertEquals(journal, repo.history(original.id))
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }

    @Test fun recoveryDoesNotScheduleTerminalOrPersonalOccurrences() {
        val coordinator = TestAlarmRuntime.coordinator(repo)
        listOf(ActionType.DONE, ActionType.SKIP).forEach { action ->
            val triggered = coordinator.trigger(AlarmDelivery.from(create(-2)))!!
            coordinator.act(AlarmDelivery.from(triggered), action)
        }
        val future = create()
        val personal = future.copy(id = "personal", eventId = "personal", isTest = false)
        repo.materialize(listOf(personal))
        TestAlarmRuntime.restoreFuture(context)
        assertEquals(listOf(AlarmDelivery.from(future)), tokens())
        assertEquals(personal, repo.get(personal.id))
    }

    @Test fun futureRecoveryIsNotLimitedToTwentyVisibleHistoryEntries() {
        val futures = (1..25).map { create(it.toLong()) }
        TestAlarmRuntime.restoreFuture(context)
        assertEquals(futures.map { AlarmDelivery.from(it) }.toSet(), tokens().toSet())
        assertEquals(25, tokens().size)
        cancelPlatformAlarms()
        BootReceiver().onReceive(context, Intent("unrelated.action"))
        assertTrue(tokens().isEmpty())
    }
}
