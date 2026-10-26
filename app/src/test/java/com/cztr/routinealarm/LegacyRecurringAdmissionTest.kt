@file:Suppress("DEPRECATION")

package com.cztr.routinealarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.cztr.routinealarm.execution.TestAlarmRecoveryService
import com.cztr.routinealarm.execution.TestAlarmRuntime
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLog
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlarmManager

/** Historical tokens are built directly: current scheduling must refuse their obsolete owners. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31])
@LooperMode(LooperMode.Mode.PAUSED)
class LegacyRecurringAdmissionTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(AlarmManager::class.java)
    private val current get() = ScheduleRegistry.enabledEvents.first()
    private val disabled get() = ScheduleRegistry.scheduledEvents.first { !it.enabled }
    private val cues get() = ScheduleRegistry.allEvents.filter { candidate ->
        ScheduleRegistry.scheduledEvents.none { it.id == candidate.id }
    }
    private val cue get() = cues.first { it.enabled }
    private val alarms get() = shadowOf(manager).scheduledAlarms

    @Before fun setup() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ScheduleState.setEnabled(context, true)
    }

    @After fun cleanup() {
        (Shadow.extract<Any>(manager) as? FailingLegacyAlarmManagerShadow)?.failing = null
        alarms.toList().forEach {
            val pending = requireNotNull(it.operation)
            manager.cancel(pending); pending.cancel()
        }
        context.getSystemService(JobScheduler::class.java).cancelAll()
        ScheduleState.setEnabled(context, false)
        context.deleteDatabase("occurrences.db")
    }

    private fun intent(event: RoutineEvent) = Intent(context, AlarmReceiver::class.java)
        .setData(Uri.parse("routinealarm://event/${Uri.encode(event.id)}"))
        .putExtra(AlarmScheduler.EXTRA_EVENT_ID, event.id)
        .putExtra(AlarmScheduler.EXTRA_RECURRING, true)

    private fun existing(event: RoutineEvent): PendingIntent? = PendingIntent.getBroadcast(
        context, 0, intent(event), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)

    private fun historical(event: RoutineEvent): PendingIntent {
        val pending = PendingIntent.getBroadcast(context, 0, intent(event),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 60_000, pending)
        return pending
    }

    @Test fun lookupOnlyCuesCannotBeScheduledOrAcquireNewTokens() {
        assertTrue(cues.isNotEmpty())
        cues.forEach {
            assertFalse("Cue ${it.id} is owned by the training session", AlarmScheduler.scheduleEvent(context, it))
            assertNull(existing(it))
        }
        assertTrue(alarms.isEmpty())
    }

    @Test fun oldCueDeliveryCannotRearmOrStartEitherService() {
        val old = historical(cue)
        val delivery = Intent(shadowOf(old).savedIntent)
        manager.cancel(old) // The OS consumed the one-shot registration.
        AlarmReceiver().onReceive(context, delivery)
        assertTrue(alarms.isEmpty())
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        old.cancel()
    }

    @Test fun forgedOrStaleCatalogCopiesCannotChangeRecurringPlans() {
        listOf(current.copy(id = "unknown-old-event"), disabled.copy(enabled = true),
            current.copy(title = "Changed outside catalog"),
            current.copy(minute = (current.minute + 1) % 60)).forEach {
            assertFalse(AlarmScheduler.scheduleEvent(context, it))
        }
        assertTrue(alarms.isEmpty())
    }

    @Test fun cancelAllRemovesKnownCuesDisabledEntriesAndCurrentPlansWithoutCreatingTokens() {
        val events = listOf(current, TrainingSessionPlan.startEvents.first(), cue, disabled)
        events.forEach(::historical)
        AlarmScheduler.cancelAll(context)
        assertTrue(alarms.isEmpty())
        events.forEach { assertNull("Old token ${it.id} survived", existing(it)) }
        AlarmScheduler.cancelAll(context)
        ScheduleRegistry.allEvents.forEach { assertNull(existing(it)) }
    }

    @Test fun scheduleAllRetiresKnownInvalidTokensAndKeepsExactlyOneOfEachCurrentIdentity() {
        historical(cue); historical(disabled)
        val valid = historical(current)
        repeat(2) {
            assertEquals(ScheduleRegistry.enabledEvents.size, AlarmScheduler.scheduleAll(context))
            assertNull(existing(cue)); assertNull(existing(disabled))
            assertEquals(valid, existing(current))
            val ids = alarms.map { shadowOf(requireNotNull(it.operation)).savedIntent
                .getStringExtra(AlarmScheduler.EXTRA_EVENT_ID) }
            assertEquals(ScheduleRegistry.enabledEvents.map { it.id }.toSet(), ids.toSet())
            assertEquals(ids.size, ids.toSet().size)
        }
    }

    @Test @Config(sdk = [31]) fun cleanupWithoutExactPermissionPreservesExistingValidAlarm() {
        historical(cue); historical(disabled)
        val valid = historical(current)
        val priorTime = alarms.single { it.operation == valid }.triggerAtTime
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertEquals(0, AlarmScheduler.scheduleAll(context))
        assertNull(existing(cue)); assertNull(existing(disabled))
        assertEquals(valid, alarms.single().operation)
        assertEquals(priorTime, alarms.single().triggerAtTime)
    }

    @Test @Config(sdk = [31]) fun alreadyDeliveredValidAlarmStillDispatchesWhenNextSchedulingIsDenied() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        AlarmReceiver().onReceive(context, intent(current))
        assertTrue(alarms.isEmpty())
        val service = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertEquals(AlarmService::class.java.name, service!!.component!!.className)
        assertEquals(current.id, service.getStringExtra(AlarmScheduler.EXTRA_EVENT_ID))
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }

    @Test fun cancellingWeeklyPlansPreservesLegacySnoozeAndPersistentTestAlarm() {
        AlarmScheduler.scheduleSnooze(context, "Snooze control")
        TestAlarmRuntime.create(context, 3)
        val independent = alarms.map { requireNotNull(it.operation) }.toSet()
        assertEquals(2, independent.size)
        historical(cue); historical(current)
        AlarmScheduler.cancelAll(context)
        assertEquals(independent, alarms.map { it.operation }.toSet())
    }

    @Test fun nonRecurringSnoozeStillDispatchesWhenWeeklyPlanIsPaused() {
        AlarmScheduler.scheduleSnooze(context, "Snooze control", "Speak control")
        val pending = requireNotNull(alarms.single().operation)
        val delivery = Intent(shadowOf(pending).savedIntent)
        manager.cancel(pending)
        ScheduleState.setEnabled(context, false)
        AlarmReceiver().onReceive(context, delivery)
        assertTrue(alarms.isEmpty())
        val service = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService!!
        assertEquals("Speak control", service.getStringExtra(AlarmScheduler.EXTRA_SPOKEN_TEXT))
        assertEquals(AlarmService::class.java.name, service.component!!.className)
        pending.cancel()
    }

    @Test @Config(sdk = [31]) fun packageReplacementCleansObsoleteCuesEvenWhenWeeklyPausedAndPermissionDenied() {
        historical(cue); historical(disabled)
        ScheduleState.setEnabled(context, false)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        BootReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertNull(existing(cue)); assertNull(existing(disabled))
        assertTrue(alarms.isEmpty())
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }

    @Test @Config(shadows = [FailingLegacyAlarmManagerShadow::class])
    fun failedCancellationDoesNotBlockOtherCleanupOrValidScheduling() {
        val failed = historical(cue)
        val otherCue = cues.last()
        historical(otherCue)
        val fault = Shadow.extract<FailingLegacyAlarmManagerShadow>(manager)
        fault.failing = failed
        assertEquals(ScheduleRegistry.enabledEvents.size, AlarmScheduler.scheduleAll(context))
        assertEquals(1, fault.failures)
        assertEquals(failed, existing(cue))
        assertTrue(alarms.any { it.operation == failed })
        assertNull(existing(otherCue))
        assertEquals(ScheduleRegistry.enabledEvents.size + 1, alarms.size)
        assertTrue(ShadowLog.getLogsForTag("RoutineAlarm").any { it.throwable is SecurityException })
    }

    @Test @Config(shadows = [FailingLegacyAlarmManagerShadow::class])
    fun failedLegacyCancellationStillRequestsIndependentRecovery() {
        val failed = historical(cue)
        val fault = Shadow.extract<FailingLegacyAlarmManagerShadow>(manager)
        fault.failing = failed
        ScheduleState.setEnabled(context, false)
        BootReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(1, fault.failures)
        assertNotNull(context.getSystemService(JobScheduler::class.java)
            .getPendingJob(TestAlarmRecoveryService.JOB_ID))
        assertEquals(failed, existing(cue))
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }

    @Test fun unrelatedBroadcastDoesNotCancelLegacyRegistrations() {
        val old = historical(cue)
        BootReceiver().onReceive(context, Intent("unrelated.action"))
        assertEquals(old, alarms.single().operation)
    }
}

/** A platform failure before cancellation; retains the real historical token for assertions. */
@Implements(AlarmManager::class)
class FailingLegacyAlarmManagerShadow : ShadowAlarmManager() {
    var failing: PendingIntent? = null
    var failures = 0
    @Implementation override fun cancel(operation: PendingIntent) {
        if (operation == failing) {
            failures++
            throw SecurityException("Injected legacy cancel failure")
        }
        super.cancel(operation)
    }
}
