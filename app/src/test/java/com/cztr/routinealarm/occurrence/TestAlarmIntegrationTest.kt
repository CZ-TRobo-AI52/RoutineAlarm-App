package com.cztr.routinealarm.occurrence

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.cztr.routinealarm.AlarmScheduler
import com.cztr.routinealarm.PersonalSchedule
import com.cztr.routinealarm.execution.*
import com.cztr.routinealarm.support.awaitUi
import com.cztr.routinealarm.support.awaitUiReadersStopped
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
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TestAlarmIntegrationTest {
    private lateinit var context: Context
    private lateinit var repo: SqliteOccurrenceRepository

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("occurrences.db")
        repo = TestAlarmRuntime.repository(context)
    }
    @After fun cleanup() { repo.close(); context.deleteDatabase("occurrences.db") }

    private fun planned() = TestAlarmCoordinator(repo,
        Clock.offset(Clock.systemDefaultZone(), Duration.ofMinutes(-2))).create(1)
    private fun fired() = TestAlarmRuntime.coordinator(repo).trigger(AlarmDelivery.from(planned()))!!
    private fun intent(item: RoutineOccurrence, type: Class<*>) =
        TestAlarmRuntime.putToken(Intent(context, type), AlarmDelivery.from(item))
    private fun notificationToken(): AlarmDelivery? {
        val notification = shadowOf(context.getSystemService(NotificationManager::class.java))
            .getNotification(TestAlarmService.NOTIFICATION) ?: return null
        return notification.contentIntent?.let { TestAlarmRuntime.token(shadowOf(it).savedIntent) }
    }
    private fun buttons(view: View): List<Button> = when (view) {
        is Button -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun transportRoundtripRejectsMissingAndChangedIdentity() {
        val token = AlarmDelivery("test:id with / and ü", 3, 7)
        val value = TestAlarmRuntime.putToken(Intent(), token)
        assertEquals(token, TestAlarmRuntime.token(value))
        assertNull(TestAlarmRuntime.token(Intent()))
        assertNull(TestAlarmRuntime.token(Intent(value).setData(Uri.parse("routinealarm://test-occurrence/wrong"))))
        assertNull(TestAlarmRuntime.token(Intent(value).putExtra("occurrence_generation", 4L)))
        assertNull(TestAlarmRuntime.token(Intent(value).removeExtraAndReturn("occurrence_revision")))
    }
    private fun Intent.removeExtraAndReturn(name: String): Intent = apply { removeExtra(name) }

    @Test fun existingTestButtonSchedulerPersistsBeforePlatformSchedule() {
        AlarmScheduler.scheduleTest(context, 1)
        val item = repo.recentTests().single()
        val alarms = shadowOf(context.getSystemService(android.app.AlarmManager::class.java)).scheduledAlarms
        val scheduled = alarms.single()
        assertEquals(AlarmDelivery.from(item), TestAlarmRuntime.token(shadowOf(scheduled.operation).savedIntent))
        assertEquals(item.currentTriggerAt.toEpochMilli(), scheduled.triggerAtTime)
        assertEquals("SCHEDULE", repo.history(item.id).single().actionType)
    }

    @Test fun testAndroidAdapterDoesNotScheduleRecoverOrDeliverPersonalOccurrences() {
        val clock = Clock.systemDefaultZone()
        val event = PersonalSchedule.allEvents.single { it.id == "evening_messages_2110" }
        fun personal(days: Long) = OccurrencePlanner(clock).plan(
            listOf(event), LocalDate.now(clock).plusDays(days), 1, clock.zone, "adapter-boundary:$days",
        ).single().also { repo.materialize(listOf(it)) }
        val future = personal(1)
        val overdue = personal(-1)
        val before = repo.exportBackup()
        assertThrows(IllegalStateException::class.java) { TestAlarmRuntime.schedule(context, future) }
        TestAlarmRuntime.restoreFuture(context)
        TestAlarmReceiver().onReceive(context, intent(overdue, TestAlarmReceiver::class.java))

        assertTrue(shadowOf(context.getSystemService(android.app.AlarmManager::class.java)).scheduledAlarms.isEmpty())
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        assertArrayEquals(before, repo.exportBackup())
        assertTrue(repo.schedulingAttempts(future.id).isEmpty())
        assertEquals(OccurrenceStatus.SCHEDULED, repo.get(overdue.id)!!.status)
    }

    @Test fun receiverReadsStoredPayloadAndDropsTerminalReplay() {
        val item = planned()
        val delivery = intent(item, TestAlarmReceiver::class.java).putExtra("title", "forged")
        TestAlarmReceiver().onReceive(context, delivery)
        val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertEquals(TestAlarmService::class.java.name, started.component!!.className)
        val current = repo.get(item.id)!!
        assertEquals(AlarmDelivery.from(current), TestAlarmRuntime.token(started))
        assertEquals("RoutineAlarm Test", current.title)
        TestAlarmRuntime.coordinator(repo).act(AlarmDelivery.from(current), ActionType.DONE)
        TestAlarmReceiver().onReceive(context, delivery)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }

    @Test fun serviceRetainsFirstAlarmThenAdvancesQueueAfterItsPersistedDone() {
        val a = fired()
        val b = fired()
        val service = Robolectric.buildService(TestAlarmService::class.java).create()
        try {
            service.get().onStartCommand(intent(a, TestAlarmService::class.java), 0, 1)
            assertEquals(AlarmDelivery.from(a), notificationToken())
            service.get().onStartCommand(intent(b, TestAlarmService::class.java), 0, 2)
            assertEquals(AlarmDelivery.from(a), notificationToken())
            TestAlarmRuntime.coordinator(repo).act(AlarmDelivery.from(a), ActionType.DONE)
            service.get().onStartCommand(Intent().setAction(TestAlarmService.REFRESH), 0, 3)
            assertEquals(AlarmDelivery.from(b), notificationToken())
            service.get().onStartCommand(intent(a, TestAlarmService::class.java), 0, 4)
            assertEquals(AlarmDelivery.from(b), notificationToken())
            assertEquals(OccurrenceStatus.TRIGGERED, repo.get(b.id)!!.status)
        } finally { service.destroy() }
    }

    @Test fun serviceRejectsDeliveryCompletedBetweenReceiverAndService() {
        val item = fired()
        TestAlarmRuntime.coordinator(repo).act(AlarmDelivery.from(item), ActionType.DONE)
        val service = Robolectric.buildService(TestAlarmService::class.java).create()
        try {
            service.get().onStartCommand(intent(item, TestAlarmService::class.java), 0, 1)
            assertNull(notificationToken())
        } finally { service.destroy() }
    }

    @Test fun activityDoneRecordsExplicitUserActionAndSurvivesReopen() {
        val item = fired()
        val activity = Robolectric.buildActivity(TestAlarmActivity::class.java,
            intent(item, TestAlarmActivity::class.java)).setup()
        try {
            awaitUi(activity.get())
            buttons(activity.get().window.decorView).single { it.text == "Erledigt" }.performClick()
            assertTrue(activity.get().isFinishing)
            TestAlarmRuntime.repository(context).use {
                assertEquals(OccurrenceStatus.DONE, it.get(item.id)!!.status)
                assertEquals(Actor.USER, it.history(item.id).last().actor)
                assertEquals("DONE", it.history(item.id).last().actionType)
            }
        } finally { activity.pause().stop().destroy(); awaitUiReadersStopped(activity.get()) }
    }

    @Test fun activitySnoozeSchedulesSameOccurrenceWithNewGeneration() {
        val item = fired()
        val activity = Robolectric.buildActivity(TestAlarmActivity::class.java,
            intent(item, TestAlarmActivity::class.java)).setup()
        try {
            awaitUi(activity.get())
            buttons(activity.get().window.decorView).single { it.text == "5 Minuten später" }.performClick()
            val snoozed = repo.get(item.id)!!
            assertEquals(OccurrenceStatus.SNOOZED, snoozed.status)
            assertEquals(item.plannedAt, snoozed.plannedAt)
            assertEquals(item.generation + 1, snoozed.generation)
            val alarm = shadowOf(context.getSystemService(android.app.AlarmManager::class.java)).scheduledAlarms.single()
            assertEquals(AlarmDelivery.from(snoozed), TestAlarmRuntime.token(shadowOf(alarm.operation).savedIntent))
        } finally { activity.pause().stop().destroy(); awaitUiReadersStopped(activity.get()) }
    }

    @Test fun staleActivityCannotOverwriteSnoozeOrStopOtherAlarm() {
        val a = fired()
        val b = fired()
        val activity = Robolectric.buildActivity(TestAlarmActivity::class.java,
            intent(a, TestAlarmActivity::class.java)).setup()
        try {
            awaitUi(activity.get())
            TestAlarmRuntime.coordinator(repo).act(AlarmDelivery.from(a), ActionType.SNOOZE, 5)
            buttons(activity.get().window.decorView).single { it.text == "Erledigt" }.performClick()
            awaitUi(activity.get())
            assertEquals(OccurrenceStatus.SNOOZED, repo.get(a.id)!!.status)
            assertEquals(b, repo.get(b.id))
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        } finally { activity.pause().stop().destroy(); awaitUiReadersStopped(activity.get()) }
    }
}
