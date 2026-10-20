@file:Suppress("DEPRECATION")

package com.cztr.routinealarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import org.robolectric.shadows.ShadowAlarmManager
import java.time.ZonedDateTime

/**
 * Exercises the production scheduler payload and receiver/service handoff.
 * Direct receiver invocation represents delivery; it does not prove OS delivery or spoken output.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@LooperMode(LooperMode.Mode.PAUSED)
class LegacyWeeklyDeliveryTest {
    private lateinit var context: Context
    private lateinit var manager: AlarmManager

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        manager = context.getSystemService(AlarmManager::class.java)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ScheduleState.setEnabled(context, true)
    }

    @After fun cleanup() {
        shadowOf(manager).scheduledAlarms.toList().forEach {
            val pending = requireNotNull(it.operation)
            manager.cancel(pending)
            pending.cancel()
        }
        ScheduleState.setEnabled(context, false)
    }

    /** Remove the consumed alarm registration, preserving its PendingIntent identity. */
    private fun scheduledDelivery(event: RoutineEvent): Pair<PendingIntent, Intent> {
        assertTrue(AlarmScheduler.scheduleEvent(context, event, ZonedDateTime.now().minusWeeks(1)))
        val alarm = shadowOf(manager).scheduledAlarms.single()
        val pending = requireNotNull(alarm.operation)
        val intent = Intent(shadowOf(pending).savedIntent)
        assertEquals(AlarmReceiver::class.java.name, intent.component!!.className)
        assertEquals(Uri.parse("routinealarm://event/${Uri.encode(event.id)}"), intent.data)
        assertEquals(event.id, intent.getStringExtra(AlarmScheduler.EXTRA_EVENT_ID))
        assertTrue(intent.getBooleanExtra(AlarmScheduler.EXTRA_RECURRING, false))
        manager.cancel(pending)
        assertTrue(shadowOf(manager).scheduledAlarms.isEmpty())
        return pending to intent
    }

    private fun deliverAndAssertRearmed(
        event: RoutineEvent,
        previous: PendingIntent,
        intent: Intent
    ) {
        // Receiver uses the real clock. A boundary crossing may legitimately select either deadline.
        val before = ZonedDateTime.now()
        AlarmReceiver().onReceive(context, intent)
        val after = ZonedDateTime.now()
        val alarm = shadowOf(manager).scheduledAlarms.single()
        assertEquals(previous, alarm.operation)
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        val earliest = AlarmScheduler.nextOccurrence(event, before).toInstant().toEpochMilli()
        val latest = AlarmScheduler.nextOccurrence(event, after).toInstant().toEpochMilli()
        assertTrue("Next deadline precedes delivery", alarm.triggerAtTime >= earliest)
        assertTrue("Next deadline skips an eligible occurrence", alarm.triggerAtTime <= latest)
    }

    private fun assertNoEffects() {
        assertTrue(shadowOf(manager).scheduledAlarms.isEmpty())
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }

    @Test fun everyTrainingStartRearmsItsIdentityAndStartsOnlyTheCorrectTrainingDay() {
        assertEquals(6, TrainingSessionPlan.startEvents.size)
        TrainingSessionPlan.startEvents.forEach { event ->
            val (pending, intent) = scheduledDelivery(event)

            deliverAndAssertRearmed(event, pending, intent)

            val service = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
            assertNotNull("Missing training dispatch for ${event.id}", service)
            assertEquals(TrainingSessionService::class.java.name, service!!.component!!.className)
            assertEquals(event.daysOfWeek.single().name, service.getStringExtra("training_day"))
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
            manager.cancel(pending)
            pending.cancel()
        }
    }

    @Test fun personalRoutineUsesRegistryContentInsteadOfStaleTransportExtrasAndRearmsIdentity() {
        val event = PersonalSchedule.allEvents.first { it.enabled && it.mode == EventMode.ROUTINE }
        val (pending, intent) = scheduledDelivery(event)
        intent.putExtra(AlarmScheduler.EXTRA_TITLE, "stale transport title")
        intent.putExtra(AlarmScheduler.EXTRA_SPOKEN_TEXT, "stale transport speech")
        intent.putExtra(AlarmScheduler.EXTRA_MODE, EventMode.ANNOUNCEMENT.name)

        deliverAndAssertRearmed(event, pending, intent)

        val service = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertNotNull(service)
        assertEquals(AlarmService::class.java.name, service!!.component!!.className)
        assertEquals(event.id, service.getStringExtra(AlarmScheduler.EXTRA_EVENT_ID))
        assertEquals(event.title, service.getStringExtra(AlarmScheduler.EXTRA_TITLE))
        assertEquals(event.spokenText, service.getStringExtra(AlarmScheduler.EXTRA_SPOKEN_TEXT))
        assertEquals(event.mode.name, service.getStringExtra(AlarmScheduler.EXTRA_MODE))
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }

    @Test fun pausedWeeklyPlanIgnoresPreviouslyScheduledTrainingAndPersonalDeliveries() {
        val events = listOf(
            TrainingSessionPlan.startEvents.first(),
            PersonalSchedule.allEvents.first { it.enabled }
        )
        events.forEach { event ->
            val (pending, intent) = scheduledDelivery(event)
            ScheduleState.setEnabled(context, false)

            AlarmReceiver().onReceive(context, intent)

            assertNoEffects()
            pending.cancel()
            ScheduleState.setEnabled(context, true)
        }
    }

    @Test fun unknownBlankAndMissingRecurringEventIdsHaveNoEffects() {
        listOf("unknown-weekly-event", "", "   ", null).forEach { eventId ->
            val intent = Intent(context, AlarmReceiver::class.java)
                .putExtra(AlarmScheduler.EXTRA_RECURRING, true)
                .putExtra(AlarmScheduler.EXTRA_TITLE, "must not start")
            if (eventId != null) intent.putExtra(AlarmScheduler.EXTRA_EVENT_ID, eventId)

            AlarmReceiver().onReceive(context, intent)

            assertNoEffects()
        }
    }

    @Test fun disabledRegistryEventCannotBeScheduledOrDispatchFromAnOldRecurringIntent() {
        val event = PersonalSchedule.allEvents.first { !it.enabled }
        assertFalse(AlarmScheduler.scheduleEvent(context, event))
        val intent = Intent(context, AlarmReceiver::class.java)
            .putExtra(AlarmScheduler.EXTRA_RECURRING, true)
            .putExtra(AlarmScheduler.EXTRA_EVENT_ID, event.id)
            .putExtra(AlarmScheduler.EXTRA_TITLE, "must not start")

        AlarmReceiver().onReceive(context, intent)

        assertNoEffects()
    }
}
