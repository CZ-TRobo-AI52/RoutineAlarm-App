package com.cztr.routinealarm

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.cztr.routinealarm.execution.TestAlarmActivity
import com.cztr.routinealarm.execution.TestAlarmRuntime
import com.cztr.routinealarm.occurrence.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Actual screen -> existing stable facade -> native SQLite journal and Android effects. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31], shadows = [UiReadDatabaseGate::class])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class StableTestAlarmScreenConsumerTest {
    private lateinit var context: Application
    private val screens = mutableListOf<ActivityController<TestAlarmActivity>>()
    private val executors = mutableListOf<ExecutorService>()

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        UiReadDatabaseGate.reset()
        context.deleteDatabase("occurrences.db")
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.toList()
            .forEach { context.getSystemService(AlarmManager::class.java).cancel(requireNotNull(it.operation)) }
    }

    @After fun cleanup() {
        UiReadDatabaseGate.releaseAll()
        screens.forEach { it.destroy() }
        executors.forEach { assertTrue(it.awaitTermination(10, TimeUnit.SECONDS)) }
        shadowOf(Looper.getMainLooper()).idle()
        UiReadDatabaseGate.reset()
        context.deleteDatabase("occurrences.db")
    }

    @Test fun actualDoneButtonRecordsAnActionRecoverableByExistingStableFacade() {
        val fired = fixture()
        val screen = open(fired)
        click(screen, "Erledigt")
        drain(screen)
        TestAlarmRuntime.repository(context).use { repo ->
            assertEquals(OccurrenceStatus.DONE, repo.get(fired.id)!!.status)
            val entry = repo.history(fired.id).single { it.actionType == "DONE" }
            val action = StableTestAlarmActions(repo, Clock.systemUTC()).recover(entry.actionId)
            assertNotNull("The actual screen still bypasses the existing stable consumer", action)
            assertEquals(ActionType.DONE, action!!.type)
            assertEquals(fired.id, action.occurrenceId)
            assertEquals(repo.deliveryEpoch(), StableTestAlarmActions.epoch(action))
        }
    }

    @Test fun slowActionStoreKeepsMainEventsResponsiveAndDoesNotCommitEarly() {
        val fired = fixture()
        val screen = open(fired)
        val gate = UiReadDatabaseGate.holdNext()
        click(screen, "Erledigt")
        assertEquals("Action database work still ran on UI", 0, UiReadDatabaseGate.mainAccesses.get())
        gate.awaitEntered()
        assertTrue(labels(screen).any { it.contains("Speicherung") })
        var mainEvent = false
        Handler(Looper.getMainLooper()).post { mainEvent = true }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(mainEvent)
        inspect { repo -> assertEquals(OccurrenceStatus.TRIGGERED, repo.get(fired.id)!!.status) }
        gate.release.countDown()
        drain(screen)
        inspect { repo -> assertEquals(OccurrenceStatus.DONE, repo.get(fired.id)!!.status) }
    }

    @Test fun failedRequestSurvivesRecreationAndRetriesTheOriginalFullPayload() {
        val fired = fixture()
        val screen = open(fired)
        UiReadDatabaseGate.failNext()
        click(screen, "3 Minuten später")
        drain(screen)
        assertFalse(screen.get().isFinishing)
        val state = Bundle().also { screen.saveInstanceState(it) }
        val original = savedAction(state)
        assertEquals(ActionType.SNOOZE, original.type)
        assertEquals(3, original.snoozeMinutes)
        inspect { repo -> assertEquals(fired, repo.get(fired.id)); assertNull(StableTestAlarmActions(repo, Clock.systemUTC()).recover(original.id)) }
        val replacement = recreate(screen, fired, state)
        click(replacement, "Erneut versuchen")
        drain(replacement)
        inspect { repo ->
            assertEquals(original, StableTestAlarmActions(repo, Clock.systemUTC()).recover(original.id))
            assertEquals(1, repo.get(fired.id)!!.snoozeCount)
            assertEquals(Instant.ofEpochMilli(original.occurredAt.toEpochMilli()).plusSeconds(180), repo.get(fired.id)!!.currentTriggerAt)
        }
    }

    @Test fun lostReplyRecoveryReadsHistoricalDuplicateWithoutRepeatingAndroidEffects() {
        val fired = fixture()
        val screen = open(fired)
        click(screen, "2 Minuten später")
        executor(screen).submit {}.get(10, TimeUnit.SECONDS) // Persisted, but paused main looper has no reply yet.
        val state = Bundle().also { screen.saveInstanceState(it) }
        val original = savedAction(state)
        val history = inspect { it.history(fired.id) }
        val manager = context.getSystemService(AlarmManager::class.java)
        shadowOf(manager).scheduledAlarms.toList().forEach { manager.cancel(requireNotNull(it.operation)) }
        while (shadowOf(context).nextStartedService != null) { }
        val replacement = recreate(screen, fired, state)
        click(replacement, "Erneut versuchen")
        drain(replacement)
        inspect { repo ->
            assertEquals(history, repo.history(fired.id))
            assertEquals(original, StableTestAlarmActions(repo, Clock.systemUTC()).recover(original.id))
        }
        assertTrue("Historical acknowledgement scheduled a fresh Android effect", shadowOf(manager).scheduledAlarms.isEmpty())
        assertNull("Historical acknowledgement restarted output", shadowOf(context).nextStartedService)
    }

    @Test fun acceptedRequestStillCommitsAfterScreenIsClosed() {
        val fired = fixture()
        val screen = open(fired)
        val gate = UiReadDatabaseGate.holdNext()
        click(screen, "Überspringen")
        assertEquals(0, UiReadDatabaseGate.mainAccesses.get())
        gate.awaitEntered()
        screen.destroy(); screens.remove(screen)
        gate.release.countDown()
        assertTrue(executor(screen).awaitTermination(10, TimeUnit.SECONDS))
        inspect { repo -> assertEquals(OccurrenceStatus.SKIPPED, repo.get(fired.id)!!.status) }
    }

    @Test fun earlierActionReplyCannotFinishAReplacementDeliveryScreen() {
        val first = fixture()
        val second = fixture()
        val screen = open(first)
        val gate = UiReadDatabaseGate.holdNext()
        click(screen, "Erledigt")
        assertEquals(0, UiReadDatabaseGate.mainAccesses.get())
        gate.awaitEntered()
        screen.newIntent(intent(second))
        gate.release.countDown()
        drain(screen)
        assertFalse(screen.get().isFinishing)
        inspect { repo ->
            assertEquals(OccurrenceStatus.DONE, repo.get(first.id)!!.status)
            assertEquals(OccurrenceStatus.TRIGGERED, repo.get(second.id)!!.status)
        }
        assertTrue(labels(screen).any { it == second.title })
    }

    @Test fun sameDeliveryRedeliveryKeepsTheOriginalUnconfirmedRequest() {
        val fired = fixture()
        val screen = open(fired)
        UiReadDatabaseGate.failNext()
        click(screen, "4 Minuten später")
        drain(screen)
        val original = savedAction(Bundle().also { screen.saveInstanceState(it) })
        screen.newIntent(Intent(screen.get().intent))
        drain(screen)
        assertEquals(original, savedAction(Bundle().also { screen.saveInstanceState(it) }))
        click(screen, "Erneut versuchen")
        drain(screen)
        inspect { repo -> assertEquals(original, StableTestAlarmActions(repo, Clock.systemUTC()).recover(original.id)) }
    }

    @Test fun queuedHistoryNavigationCannotBeFinishedByAnEarlierActionReply() {
        val fired = fixture()
        val screen = open(fired)
        val history = views(screen.get().window.decorView).filterIsInstance<Button>()
            .single { it.text == "Testverlauf öffnen" }
        val gate = UiReadDatabaseGate.holdNext()
        click(screen, "Erledigt")
        gate.awaitEntered()
        history.performClick() // Already queued user navigation from the earlier view.
        gate.release.countDown()
        drain(screen)
        assertFalse("Earlier command reply closed the history the user just opened", screen.get().isFinishing)
        assertNull(screen.get().intent.data)
        assertTrue(labels(screen).contains("Testverlauf"))
        inspect { repo -> assertEquals(OccurrenceStatus.DONE, repo.get(fired.id)!!.status) }
    }

    @Test fun oldViewActionCannotCompleteTheReplacementDelivery() {
        val first = fixture()
        val second = fixture()
        val screen = open(first)
        val oldDone = views(screen.get().window.decorView).filterIsInstance<Button>().single { it.text == "Erledigt" }
        screen.newIntent(intent(second))
        drain(screen)
        oldDone.performClick()
        drain(screen)
        inspect { repo ->
            assertEquals(OccurrenceStatus.TRIGGERED, repo.get(first.id)!!.status)
            assertEquals("Old view completed the newer delivery", OccurrenceStatus.TRIGGERED, repo.get(second.id)!!.status)
            assertFalse(repo.history(second.id).any { it.actionType == "DONE" })
        }
    }

    @Test fun acknowledgementCannotFinishTheStoppedScreenWhileTheCommandStillCommits() {
        val fired = fixture()
        val screen = open(fired).start().resume()
        drain(screen)
        val gate = UiReadDatabaseGate.holdNext()
        click(screen, "Erledigt")
        gate.awaitEntered()
        screen.pause().stop()
        val stoppedUi = labels(screen)
        gate.release.countDown()
        executor(screen).submit {}.get(10, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(stoppedUi, labels(screen))
        assertFalse("Late acknowledgement finished a stopped screen", screen.get().isFinishing)
        inspect { repo -> assertEquals(OccurrenceStatus.DONE, repo.get(fired.id)!!.status) }
    }

    @Test fun restoredStoreRejectsOldSavedRequestAndKeepsQuarantine() {
        val fired = fixture()
        val screen = open(fired)
        UiReadDatabaseGate.failNext()
        click(screen, "1 Minute später")
        drain(screen)
        val state = Bundle().also { screen.saveInstanceState(it) }
        val original = savedAction(state)
        val originalIntent = Intent(screen.get().intent)
        screen.destroy(); screens.remove(screen)
        assertTrue(executor(screen).awaitTermination(10, TimeUnit.SECONDS))
        val backup = inspect { it.exportBackup() }
        context.deleteDatabase("occurrences.db")
        inspect { it.restoreBackup(backup) }
        val restoredHistory = inspect { it.history(fired.id) }
        val replacement = openIntent(originalIntent, state)
        click(replacement, "Erneut versuchen")
        drain(replacement)
        inspect { repo ->
            assertEquals(fired, repo.get(fired.id))
            assertFalse(repo.isDeliveryAllowed(fired.id))
            assertNotEquals(StableTestAlarmActions.epoch(original), repo.deliveryEpoch())
            assertEquals(restoredHistory, repo.history(fired.id))
            assertNull(StableTestAlarmActions(repo, Clock.systemUTC()).recover(original.id))
        }
        assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test fun malformedSavedRequestCannotEnableAnyAction() {
        val fired = fixture()
        val screen = open(fired)
        UiReadDatabaseGate.failNext()
        click(screen, "Erledigt")
        drain(screen)
        val state = Bundle().also { screen.saveInstanceState(it) }
        savedAction(state)
        val key = state.keySet().single { (state.get(it) as? String)?.contains("test-user:") == true }
        state.putString(key, "{broken request")
        val replacement = recreate(screen, fired, state)
        val buttons = views(replacement.get().window.decorView).filterIsInstance<Button>().map { it.text.toString() }
        assertFalse(buttons.contains("Erledigt"))
        assertFalse(buttons.contains("Überspringen"))
        assertFalse(buttons.any { it.endsWith("später") })
        inspect { repo -> assertEquals(fired, repo.get(fired.id)) }
        assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
    }

    private fun fixture(): RoutineOccurrence = TestAlarmRuntime.repository(context).use { repo ->
        val now = Instant.ofEpochMilli(System.currentTimeMillis())
        val planned = TestAlarmCoordinator(repo, Clock.fixed(now.minusSeconds(61), ZoneOffset.UTC)).create(1)
        TestAlarmCoordinator(repo, Clock.fixed(now, ZoneOffset.UTC)).trigger(AlarmDelivery.from(planned, repo.deliveryEpoch()))!!
    }
    private fun intent(item: RoutineOccurrence) = inspect {
        TestAlarmRuntime.putToken(Intent(context, TestAlarmActivity::class.java), AlarmDelivery.from(item, it.deliveryEpoch()))
    }
    private fun open(item: RoutineOccurrence, state: Bundle? = null): ActivityController<TestAlarmActivity> {
        return openIntent(intent(item), state)
    }
    private fun openIntent(value: Intent, state: Bundle? = null): ActivityController<TestAlarmActivity> {
        val c = Robolectric.buildActivity(TestAlarmActivity::class.java, value)
        screens.add(c); c.create(state)
        executors.add(executor(c)); drain(c)
        return c
    }
    private fun recreate(old: ActivityController<TestAlarmActivity>, item: RoutineOccurrence, state: Bundle): ActivityController<TestAlarmActivity> {
        old.destroy(); screens.remove(old)
        return openIntent(Intent(old.get().intent), state)
    }
    private fun savedAction(state: Bundle): ExecutionAction {
        val encoded = state.keySet().mapNotNull { state.get(it) as? String }
            .firstOrNull { it.contains("test-user:") }
        assertNotNull("Pending immutable request was not saved across recreation", encoded)
        return OccurrenceCodec.decodeAction(encoded!!)
    }
    private fun executor(c: ActivityController<TestAlarmActivity>): ExecutorService {
        val f = TestAlarmActivity::class.java.getDeclaredField("reader").apply { isAccessible = true }
        val loader = f.get(c.get())
        return loader.javaClass.declaredFields.single { ExecutorService::class.java.isAssignableFrom(it.type) }
            .apply { isAccessible = true }.get(loader) as ExecutorService
    }
    private fun drain(c: ActivityController<TestAlarmActivity>) {
        repeat(3) {
            executor(c).submit {}.get(10, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
    private fun <T> inspect(read: (SqliteOccurrenceRepository) -> T): T {
        val armed = UiReadDatabaseGate.armed
        UiReadDatabaseGate.armed = false
        return try { TestAlarmRuntime.repository(context).use(read) } finally { UiReadDatabaseGate.armed = armed }
    }
    private fun click(c: ActivityController<TestAlarmActivity>, label: String) = views(c.get().window.decorView)
        .filterIsInstance<Button>().single { it.text == label }.performClick()
    private fun labels(c: ActivityController<TestAlarmActivity>) = views(c.get().window.decorView)
        .filterIsInstance<TextView>().map { it.text.toString() }
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
}
