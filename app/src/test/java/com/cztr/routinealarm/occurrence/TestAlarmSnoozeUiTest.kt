package com.cztr.routinealarm.occurrence

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.cztr.routinealarm.execution.TestAlarmActivity
import com.cztr.routinealarm.execution.TestAlarmRuntime
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
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TestAlarmSnoozeUiTest {
    private lateinit var context: Context
    private lateinit var repo: SqliteOccurrenceRepository
    private val manager get() = context.getSystemService(AlarmManager::class.java)
    private val alarms get() = shadowOf(manager).scheduledAlarms.toList()

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("occurrences.db")
        repo = TestAlarmRuntime.repository(context)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After fun cleanup() { repo.close(); context.deleteDatabase("occurrences.db") }

    private fun fired(): RoutineOccurrence {
        val item = TestAlarmCoordinator(repo,
            Clock.offset(Clock.systemDefaultZone(), Duration.ofMinutes(-2))).create(1)
        return requireNotNull(TestAlarmRuntime.coordinator(repo).trigger(AlarmDelivery.from(item)))
    }

    private fun buttons(view: View): List<Button> = when (view) {
        is Button -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun choice(activity: TestAlarmActivity, minutes: Int): Button {
        val label = if (minutes == 1) "1 Minute später" else "$minutes Minuten später"
        val button = buttons(activity.window.decorView).singleOrNull { it.text == label }
        assertNotNull("Missing usable snooze choice: $label", button)
        return requireNotNull(button)
    }

    private fun withAlarm(item: RoutineOccurrence, check: (TestAlarmActivity) -> Unit) {
        val intent = TestAlarmRuntime.putToken(Intent(context, TestAlarmActivity::class.java),
            AlarmDelivery.from(item))
        val controller = Robolectric.buildActivity(TestAlarmActivity::class.java, intent).setup()
        try {
            awaitUi(controller.get())
            check(controller.get())
            if (!controller.get().isFinishing) awaitUi(controller.get())
        }
        finally { controller.pause().stop().destroy(); awaitUiReadersStopped(controller.get()) }
    }

    private fun assertDelay(minutes: Int) {
        val original = fired()
        withAlarm(original) { activity ->
            choice(activity, minutes).performClick()
            assertTrue(activity.isFinishing)
            TestAlarmRuntime.repository(context).use { reopened ->
                val saved = requireNotNull(reopened.get(original.id))
                val history = reopened.history(original.id)
                val action = history.single { it.actionType == "SNOOZE" && it.outcome == Outcome.APPLIED }
                assertEquals(OccurrenceStatus.SNOOZED, saved.status)
                assertEquals(original.plannedAt, saved.plannedAt)
                assertEquals(original.generation + 1, saved.generation)
                assertEquals(original.revision + 1, saved.revision)
                assertEquals(minutes, action.snoozeMinutes)
                assertEquals(Actor.USER, action.actor)
                // SQLite projection and Android deadlines use epoch milliseconds.
                assertEquals(action.occurredAt.toEpochMilli() + minutes * 60_000L,
                    saved.currentTriggerAt.toEpochMilli())
                assertEquals(original.revision, action.expectedRevision)
                assertEquals(original.generation, action.expectedGeneration)
                assertEquals(3, history.size)
                assertEquals(1, reopened.recentTests().size)
                val alarm = alarms.single()
                assertEquals(saved.currentTriggerAt.toEpochMilli(), alarm.triggerAtTime)
                assertEquals(AlarmDelivery.from(saved), TestAlarmRuntime.token(shadowOf(alarm.operation).savedIntent))
            }
        }
    }

    @Test fun snooze1SchedulesPlus1() = assertDelay(1)
    @Test fun snooze2SchedulesPlus2() = assertDelay(2)
    @Test fun snooze3SchedulesPlus3() = assertDelay(3)
    @Test fun snooze4SchedulesPlus4() = assertDelay(4)
    @Test fun snooze5SchedulesPlus5() = assertDelay(5)

    @Test fun snoozeDoesNotDuplicateOccurrenceOrReplaceOtherAlarm() {
        val other = TestAlarmRuntime.create(context, 30)
        val original = fired()
        withAlarm(original) { activity ->
            val button = choice(activity, 2)
            button.performClick()
            val saved = requireNotNull(repo.get(original.id))
            button.performClick() // A queued second tap still carries the old revision.
            assertEquals(saved, repo.get(original.id))
            assertEquals(other, repo.get(other.id))
            assertEquals(2, repo.recentTests().size)
            assertEquals(2, alarms.size)
            assertEquals(setOf(AlarmDelivery.from(other), AlarmDelivery.from(saved)),
                alarms.map { TestAlarmRuntime.token(shadowOf(it.operation).savedIntent) }.toSet())
            assertEquals(other.currentTriggerAt.toEpochMilli(), alarms.single {
                TestAlarmRuntime.token(shadowOf(it.operation).savedIntent)?.occurrenceId == other.id
            }.triggerAtTime)
            assertEquals(1, repo.history(original.id).count { it.actionType == "SNOOZE" && it.outcome == Outcome.APPLIED })
        }
    }

    @Test fun snoozeSurvivesRepositoryReopenAndPlatformRecovery() {
        val original = fired()
        withAlarm(original) { choice(it, 3).performClick() }
        val saved = requireNotNull(repo.get(original.id))
        val history = repo.history(original.id)
        alarms.forEach { manager.cancel(requireNotNull(it.operation)) }
        repo.close()
        repo = TestAlarmRuntime.repository(context)
        TestAlarmRuntime.restoreFuture(context)
        TestAlarmRuntime.restoreFuture(context)
        assertEquals(saved, repo.get(original.id))
        assertEquals(history, repo.history(original.id))
        assertEquals(saved.currentTriggerAt.toEpochMilli(), alarms.single().triggerAtTime)
        assertEquals(AlarmDelivery.from(saved), TestAlarmRuntime.token(shadowOf(alarms.single().operation).savedIntent))
    }

    @Test fun staleSnoozeCannotExecuteAfterCompletion() {
        val original = fired()
        withAlarm(original) { activity ->
            val button = choice(activity, 1)
            TestAlarmRuntime.coordinator(repo).act(AlarmDelivery.from(original), ActionType.DONE)
            val completed = requireNotNull(repo.get(original.id))
            button.performClick()
            assertEquals(completed, repo.get(original.id))
            assertTrue(alarms.isEmpty())
            assertFalse(repo.history(original.id).any { it.actionType == "SNOOZE" && it.outcome == Outcome.APPLIED })
        }
    }

    @Test @Config(sdk = [28])
    fun snoozeDurationCrossesBothBerlinClockChangesWithoutWallTimeReinterpretation() {
        val zone = ZoneId.of("Europe/Berlin")
        for ((at, expected) in listOf(
            "2026-03-29T00:59:30Z" to "2026-03-29T01:01:30Z",
            "2026-10-25T00:59:30Z" to "2026-10-25T01:01:30Z",
        )) {
            val now = Instant.parse(at)
            val original = TestAlarmCoordinator(repo, Clock.fixed(now.minusSeconds(60), zone)).create(1)
            val coordinator = TestAlarmCoordinator(repo, Clock.fixed(now, zone))
            val triggered = requireNotNull(coordinator.trigger(AlarmDelivery.from(original)))
            val result = requireNotNull(coordinator.act(AlarmDelivery.from(triggered), ActionType.SNOOZE, 2))
            assertEquals(Outcome.APPLIED, result.outcome)
            assertEquals(Instant.parse(expected), result.occurrence.currentTriggerAt)
            assertEquals(original.plannedAt, result.occurrence.plannedAt)
        }
    }
}
