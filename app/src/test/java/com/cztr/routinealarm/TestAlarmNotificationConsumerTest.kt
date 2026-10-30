package com.cztr.routinealarm

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.cztr.routinealarm.execution.*
import com.cztr.routinealarm.occurrence.*
import com.cztr.routinealarm.ui.notification.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowSystemClock
import java.time.Clock
import java.time.Instant
import java.time.Duration
import java.time.ZoneOffset
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Real service notification -> immutable Android transport -> actual Activity -> native journal. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31], shadows = [UiReadDatabaseGate::class])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TestAlarmNotificationConsumerTest {
    private lateinit var context: Application
    private val screens = mutableListOf<ActivityController<TestAlarmActivity>>()
    private val services = mutableListOf<ServiceController<TestAlarmService>>()
    private val executors = mutableListOf<ExecutorService>()

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        UiReadDatabaseGate.reset()
        context.deleteDatabase("occurrences.db")
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After fun cleanup() {
        UiReadDatabaseGate.releaseAll()
        screens.forEach { it.destroy() }
        executors.forEach { assertTrue(it.awaitTermination(10, TimeUnit.SECONDS)) }
        services.forEach { it.destroy() }
        shadowOf(Looper.getMainLooper()).idle()
        UiReadDatabaseGate.reset()
        context.deleteDatabase("occurrences.db")
    }

    @Test fun actualNotificationExposesExistingMenuWithDistinctImmutableActivityTargets() {
        val item = fixture()
        val note = notification(item)
        assertEquals(listOf("Erledigt", "Später", "Optionen"), note.actions?.map { it.title.toString() })
        val intents = note.actions.map { action ->
            assertTrue(shadowOf(action.actionIntent).isActivityIntent)
            assertTrue(shadowOf(action.actionIntent).isImmutable)
            shadowOf(action.actionIntent).savedIntent
        }
        assertEquals(3, intents.map { it.action }.toSet().size)
        intents.forEach { value ->
            assertEquals(TestAlarmActivity::class.java.name, value.component!!.className)
            assertEquals(token(item), TestAlarmRuntime.token(value))
            val encoded = value.getStringExtra(ROUTE)!!
            val route = NotificationActionRoutes.decode(encoded)!!
            assertEquals(value.dataString, route.deliveryDataUri)
            assertEquals("TEST", route.scopeKey)
            assertEquals(item.revision.toString(), route.planRevision)
        }
    }

    @Test fun notificationDoneCommitsOnlyTheIdentifiedTestAndUsesExistingStableJournal() {
        val first = fixture(); val second = fixture()
        val screen = open(action(notification(first), "Erledigt"))
        drain(screen)
        inspect { repo ->
            assertEquals(OccurrenceStatus.DONE, repo.get(first.id)!!.status)
            assertEquals(second, repo.get(second.id))
            val entry = repo.history(first.id).single { it.actionType == "DONE" }
            assertNotNull(StableTestAlarmActions(repo, Clock.systemUTC()).recover(entry.actionId))
        }
        assertTrue(screen.get().isFinishing)
    }

    @Test fun notificationNavigationDoesNotWriteAnActionAndSnoozeMenuReachesAllDurations() {
        val first = fixture()
        val screen = open(action(notification(first), "Später"))
        drain(screen)
        inspect { assertEquals(first, it.get(first.id)); assertEquals(2, it.history(first.id).size) }
        assertTrue(labels(screen).containsAll(listOf("1 Minute später", "2 Minuten später", "3–5 Minuten")))
        click(screen, "3–5 Minuten")
        drain(screen)
        assertTrue(labels(screen).containsAll(listOf("3 Minuten später", "4 Minuten später", "5 Minuten später")))
        click(screen, "5 Minuten später")
        drain(screen)
        inspect { repo ->
            val updated = repo.get(first.id)!!
            assertEquals(OccurrenceStatus.SNOOZED, updated.status)
            assertEquals(5, updated.totalSnoozeMinutes)
            val recorded = repo.history(first.id).single { it.actionType == "SNOOZE" }
            val original = StableTestAlarmActions(repo, Clock.systemUTC()).recover(recorded.actionId)!!
            assertEquals(original.occurredAt.plusSeconds(300).toEpochMilli(), updated.currentTriggerAt.toEpochMilli())
        }
    }

    @Test fun optionsBackAndScreenCloseHaveNoExecutionEffect() {
        val first = fixture()
        val screen = open(action(notification(first), "Optionen"))
        drain(screen)
        assertTrue(labels(screen).contains("Überspringen"))
        click(screen, "Zurück")
        drain(screen)
        assertTrue(labels(screen).containsAll(listOf("Erledigt", "Später", "Optionen")))
        click(screen, "Schließen")
        inspect { assertEquals(first, it.get(first.id)); assertEquals(2, it.history(first.id).size) }
        assertTrue(screen.get().isFinishing)
    }

    @Test fun optionsSkipCommitsThroughExistingScreenCommand() {
        val first = fixture()
        val screen = open(action(notification(first), "Optionen"))
        drain(screen); click(screen, "Überspringen"); drain(screen)
        inspect { assertEquals(OccurrenceStatus.SKIPPED, it.get(first.id)!!.status) }
    }

    @Test fun repeatedNotificationDoneReadsJournalWithoutRepeatingEffects() {
        val first = fixture()
        val value = action(notification(first), "Erledigt")
        val screen = open(value); drain(screen)
        val history = inspect { it.history(first.id) }
        while (shadowOf(context).nextStartedService != null) { }
        val replay = open(Intent(value)); drain(replay)
        inspect { assertEquals(history, it.history(first.id)) }
        assertNull("Historical receipt started an output service", shadowOf(context).nextStartedService)
        assertFalse(replay.get().isFinishing)
        assertTrue(labels(replay).contains("Testverlauf"))
    }

    @Test fun failedNotificationActionRetriesItsOriginalPayloadAfterRecreation() {
        val first = fixture()
        val value = action(notification(first), "Erledigt")
        UiReadDatabaseGate.failNext()
        val screen = open(value); drain(screen)
        assertTrue(labels(screen).contains("Aktion nicht bestätigt"))
        val saved = Bundle().also { screen.saveInstanceState(it) }
        val original = savedAction(saved)
        screen.destroy(); screens.remove(screen)
        val replacement = open(Intent(value), saved)
        drain(replacement); click(replacement, "Erneut versuchen"); drain(replacement)
        inspect { assertEquals(original, StableTestAlarmActions(it, Clock.systemUTC()).recover(original.id)) }
    }

    @Test fun notificationCommandDoesNotAccessSqliteOnMain() {
        val first = fixture()
        val value = action(notification(first), "Erledigt")
        val gate = UiReadDatabaseGate.holdNext()
        val screen = open(value)
        assertEquals(0, UiReadDatabaseGate.mainAccesses.get())
        gate.awaitEntered()
        inspect { assertEquals(first, it.get(first.id)) }
        var responsive = false
        android.os.Handler(Looper.getMainLooper()).post { responsive = true }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(responsive)
        gate.release.countDown(); drain(screen)
        inspect { assertEquals(OccurrenceStatus.DONE, it.get(first.id)!!.status) }
    }

    @Test fun tamperedScopeRevisionRouteAndMarkerFailClosed() {
        val first = fixture()
        val value = action(notification(first), "Erledigt")
        val original = NotificationActionRoutes.decode(value.getStringExtra(ROUTE)!!)!!
        val malformed = listOf(
            Intent(value).putExtra(ROUTE, "not a route"),
            Intent(value).putExtra(ROUTE, NotificationActionRoutes.encode(original.copy(scopeKey = "PERSONAL"))),
            Intent(value).putExtra(ROUTE, NotificationActionRoutes.encode(original.copy(planRevision = "wrong"))),
            Intent(value).setAction("unrecognized"),
            Intent(value).apply { removeExtra(ROUTE) },
        )
        malformed.forEach { attempt ->
            val screen = open(attempt); drain(screen)
            assertTrue("Malformed notification became actionable", labels(screen).contains("Ungültiger Testwecker"))
            assertFalse(labels(screen).contains("Erledigt"))
            inspect { assertEquals(first, it.get(first.id)) }
        }
    }

    @Test fun staleNotificationCannotCompleteARevisedGeneration() {
        val first = fixture()
        val value = action(notification(first), "Erledigt")
        inspect { repo ->
            StableTestAlarmActions(repo, Clock.systemUTC()).submit(
                StableTestAlarmActions.freeze("test-user:other-command", token(first), ActionType.SNOOZE, Instant.now(), 2))
        }
        val after = inspect { it.get(first.id) }
        val screen = open(value); drain(screen)
        inspect { assertEquals(after, it.get(first.id)); assertFalse(it.history(first.id).any { e -> e.actionType == "DONE" && e.outcome == Outcome.APPLIED }) }
    }

    @Test fun oldNotificationCannotCrossRestoreEpochOrUnpauseTheTest() {
        val first = fixture()
        val value = action(notification(first), "Erledigt")
        val backup = inspect { it.exportBackup() }
        context.deleteDatabase("occurrences.db")
        inspect { it.restoreBackup(backup) }
        val paused = inspect { it.get(first.id) }
        val screen = open(value); drain(screen)
        inspect { assertEquals(paused, it.get(first.id)); assertFalse(it.isDeliveryAllowed(first.id)) }
    }

    @Test fun recreatedMenuPreservesChosenStageWithoutRecordingAnAction() {
        val first = fixture()
        val value = action(notification(first), "Später")
        val screen = open(value); drain(screen); click(screen, "3–5 Minuten"); drain(screen)
        val saved = Bundle().also { screen.saveInstanceState(it) }
        screen.destroy(); screens.remove(screen)
        val replacement = open(Intent(value), saved); drain(replacement)
        assertTrue(labels(replacement).containsAll(listOf("3 Minuten später", "4 Minuten später", "5 Minuten später")))
        inspect { assertEquals(first, it.get(first.id)) }
    }

    @Test fun eachSnoozeDurationUsesTheExplicitTapTimeRatherThanNotificationCreation() {
        for (minutes in 1..5) {
            val first = fixture()
            val note = notification(first)
            ShadowSystemClock.advanceBy(Duration.ofMinutes(7))
            val screen = open(action(note, "Später")); drain(screen)
            if (minutes > 2) { click(screen, "3–5 Minuten"); drain(screen) }
            val tappedAt = System.currentTimeMillis()
            click(screen, if (minutes == 1) "1 Minute später" else "$minutes Minuten später")
            drain(screen)
            inspect { repo ->
                val entry = repo.history(first.id).single { it.actionType == "SNOOZE" }
                val original = StableTestAlarmActions(repo, Clock.systemUTC()).recover(entry.actionId)!!
                assertTrue("Snooze was anchored before the explicit tap", original.occurredAt.toEpochMilli() >= tappedAt)
                assertEquals(minutes, original.snoozeMinutes)
                assertEquals(original.occurredAt.plusSeconds(minutes * 60L).toEpochMilli(), repo.get(first.id)!!.currentTriggerAt.toEpochMilli())
            }
        }
    }

    @Test fun competingNotificationScreensRecoverOneCommittedOriginalAndIssueOneFollowUp() {
        val first = fixture()
        val value = action(notification(first), "Erledigt")
        val gate = UiReadDatabaseGate.holdNext()
        val delayed = open(Intent(value)); gate.awaitEntered()
        val other = open(Intent(value)); drain(other)
        val original = inspect { repo ->
            val entry = repo.history(first.id).single { it.actionType == "DONE" }
            StableTestAlarmActions(repo, Clock.systemUTC()).recover(entry.actionId)!!
        }
        gate.release.countDown(); drain(delayed)
        inspect { repo ->
            assertEquals(original, StableTestAlarmActions(repo, Clock.systemUTC()).recover(original.id))
            assertEquals(1, repo.history(first.id).count { it.actionType == "DONE" })
        }
        val followUps = generateSequence { shadowOf(context).nextStartedService }.toList()
        assertEquals("A historical acknowledgement repeated output refresh", 1, followUps.count { it.action == TestAlarmService.REFRESH })
    }

    @Test fun detachedMenuCannotActOnTheReplacementDelivery() {
        val first = fixture(); val second = fixture()
        val screen = open(action(notification(first), "Optionen")); drain(screen)
        val oldSkip = views(screen.get().window.decorView).filterIsInstance<Button>().single { it.text == "Überspringen" }
        // The running output queue correctly keeps the oldest triggered test. Construct
        // a second delivery with the same production transport factory instead of
        // mistaking that queue's unchanged first notification for the second test.
        val replacement = TestAlarmNotification.intent(context, token(second),
            TestAlarmNotification.route(token(second), MenuStage.ROOT, MenuChoice.OPTIONS))
        assertEquals(token(second), TestAlarmRuntime.token(replacement))
        screen.newIntent(replacement); drain(screen)
        oldSkip.performClick(); drain(screen)
        inspect { assertEquals(first, it.get(first.id)); assertEquals(second, it.get(second.id)) }
        click(screen, "Überspringen"); drain(screen)
        inspect { assertEquals(first, it.get(first.id)); assertEquals(OccurrenceStatus.SKIPPED, it.get(second.id)!!.status) }
    }

    @Test fun malformedSavedMenuFailsClosedWithoutAutomaticallyRunningItsOriginalIntent() {
        val first = fixture()
        val saved = Bundle().apply { putString("test-alarm.notification-stage.v1", "UNKNOWN") }
        val screen = open(action(notification(first), "Erledigt"), saved); drain(screen)
        assertTrue(labels(screen).contains("Ungültiger Testwecker"))
        inspect { assertEquals(first, it.get(first.id)) }
    }

    @Test fun notificationDeliveredToStoppedScreenKeepsItsRequestUntilResume() {
        val first = fixture()
        val value = action(notification(first), "Erledigt")
        val screen = open(TestAlarmRuntime.putToken(Intent(context, TestAlarmActivity::class.java), token(first)))
        drain(screen); screen.stop()
        screen.newIntent(value)
        inspect { assertEquals(first, it.get(first.id)) }
        val saved = Bundle().also { screen.saveInstanceState(it) }
        val original = savedAction(saved)
        assertEquals(ActionType.DONE, original.type)
        screen.resume(); drain(screen)
        inspect { repo ->
            assertEquals(OccurrenceStatus.DONE, repo.get(first.id)!!.status)
            assertEquals(original, StableTestAlarmActions(repo, Clock.systemUTC()).recover(original.id))
        }
    }

    private fun fixture(): RoutineOccurrence = inspect { repo ->
        val now = Instant.ofEpochMilli(System.currentTimeMillis())
        val planned = TestAlarmCoordinator(repo, Clock.fixed(now.minusSeconds(61), ZoneOffset.UTC)).create(1)
        TestAlarmCoordinator(repo, Clock.fixed(now, ZoneOffset.UTC)).trigger(AlarmDelivery.from(planned, repo.deliveryEpoch()))!!
    }
    private fun token(item: RoutineOccurrence) = inspect { AlarmDelivery.from(item, it.deliveryEpoch()) }
    private fun notification(item: RoutineOccurrence): Notification {
        val value = TestAlarmRuntime.putToken(Intent(context, TestAlarmService::class.java), token(item))
        val service = Robolectric.buildService(TestAlarmService::class.java).create()
        services.add(service); service.get().onStartCommand(value, 0, 1)
        return shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(TestAlarmService.NOTIFICATION)!!
    }
    private fun action(note: Notification, label: String): Intent {
        val item = note.actions?.singleOrNull { it.title.toString() == label }
        assertNotNull("Actual alarm notification does not expose $label", item)
        return Intent(shadowOf(item!!.actionIntent).savedIntent)
    }
    private fun open(value: Intent, saved: Bundle? = null): ActivityController<TestAlarmActivity> {
        val screen = Robolectric.buildActivity(TestAlarmActivity::class.java, value)
        screens.add(screen); screen.create(saved); executors.add(executor(screen))
        return screen
    }
    private fun executor(screen: ActivityController<TestAlarmActivity>): ExecutorService {
        val loader = TestAlarmActivity::class.java.getDeclaredField("reader").apply { isAccessible = true }.get(screen.get())
        return loader.javaClass.declaredFields.single { ExecutorService::class.java.isAssignableFrom(it.type) }
            .apply { isAccessible = true }.get(loader) as ExecutorService
    }
    private fun drain(screen: ActivityController<TestAlarmActivity>) {
        repeat(4) { executor(screen).submit {}.get(10, TimeUnit.SECONDS); shadowOf(Looper.getMainLooper()).idle() }
    }
    private fun <T> inspect(read: (SqliteOccurrenceRepository) -> T): T {
        val armed = UiReadDatabaseGate.armed; UiReadDatabaseGate.armed = false
        return try { TestAlarmRuntime.repository(context).use(read) } finally { UiReadDatabaseGate.armed = armed }
    }
    private fun savedAction(state: Bundle): ExecutionAction = state.keySet().mapNotNull { state.get(it) as? String }
        .first { it.contains("test-user:") }.let(OccurrenceCodec::decodeAction)
    private fun click(screen: ActivityController<TestAlarmActivity>, label: String) =
        views(screen.get().window.decorView).filterIsInstance<Button>().single { it.text == label }.performClick()
    private fun labels(screen: ActivityController<TestAlarmActivity>) =
        views(screen.get().window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    private companion object { const val ROUTE = "test-alarm.notification-route.v1" }
}
