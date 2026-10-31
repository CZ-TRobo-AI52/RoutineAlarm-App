package com.cztr.routinealarm

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
import com.cztr.routinealarm.execution.TestAlarmActivity
import com.cztr.routinealarm.execution.TestAlarmRuntime
import com.cztr.routinealarm.execution.TestAlarmService
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
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Independent request-boundary tests. Production implementation remains owned by C6.
 * SQLite and Activity lifecycle are real Robolectric paths. Payload loss is injected
 * into actual saved state; no physical process-kill or audible-output claim is made.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31], shadows = [UiReadDatabaseGate::class])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TestAlarmOriginalRequestBoundaryTest {
    private lateinit var app: Application
    private val screens = mutableListOf<ActivityController<TestAlarmActivity>>()
    private val services = mutableListOf<ServiceController<TestAlarmService>>()
    private val executors = mutableListOf<ExecutorService>()
    private val pendingKey = "test-alarm.pending-action.v1"

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        UiReadDatabaseGate.reset()
        app.deleteDatabase("occurrences.db")
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After fun cleanup() {
        UiReadDatabaseGate.releaseAll()
        screens.forEach { it.destroy() }
        executors.forEach { assertTrue(it.awaitTermination(10, TimeUnit.SECONDS)) }
        services.forEach { it.destroy() }
        shadowOf(Looper.getMainLooper()).idle()
        UiReadDatabaseGate.reset()
        app.deleteDatabase("occurrences.db")
    }

    @Test fun missingOriginalRemainsUnavailableOnRecreation() {
        val row = fixture()
        val value = doneIntent(row)
        val saved = unconfirmedState(value)
        saved.remove(pendingKey)
        val restored = open(value, saved)
        drain(restored)
        inspect { repo ->
            assertEquals("Retained transport invented a replacement request", row, repo.get(row.id))
            assertEquals(2, repo.history(row.id).size)
        }
        assertFalse(restored.get().isFinishing)
    }

    @Test fun committedOriginalRestoresAsHistoricalReceiptWithoutNewEffects() {
        val row = fixture()
        val value = doneIntent(row)
        val original = commitDone(row, value)
        val history = inspect { it.history(row.id) }
        clearServiceStarts()
        val restored = open(value, savedOriginal(original))
        drain(restored)
        inspect { assertEquals(history, it.history(row.id)) }
        click(restored, "Erneut versuchen")
        drain(restored)
        inspect { repo ->
            assertEquals(original, api(repo).recover(original.id))
            assertEquals(history, repo.history(row.id))
        }
        assertTrue(serviceStarts().isEmpty())
        assertTrue(labels(restored).contains("Testverlauf"))
    }

    @Test fun freshExplicitNotificationTapCommitsOneOriginal() {
        val row = fixture()
        val value = doneIntent(row)
        val screen = open(value)
        drain(screen)
        inspect { repo ->
            val entry = repo.history(row.id).single { it.actionType == "DONE" }
            val original = api(repo).recover(entry.actionId)!!
            assertEquals(ActionType.DONE, original.type)
            assertEquals(row.id, original.occurrenceId)
            assertEquals(OccurrenceStatus.DONE, repo.get(row.id)!!.status)
            assertEquals(3, repo.history(row.id).size)
        }
        assertTrue(screen.get().isFinishing)
    }

    @Test fun duplicateNotificationDeliveryCannotRepeatJournalOrFollowUp() {
        val row = fixture()
        val value = doneIntent(row)
        val original = commitDone(row, value)
        val after = inspect { it.get(row.id) }
        clearServiceStarts()
        val repeated = open(Intent(value))
        drain(repeated)
        inspect { repo ->
            assertEquals(after, repo.get(row.id))
            assertEquals(original, api(repo).recover(original.id))
            assertEquals(1, repo.history(row.id).count { it.actionType == "DONE" })
        }
        assertTrue(serviceStarts().isEmpty())
    }

    @Test fun savedOriginalRetryKeepsItsExactPayload() {
        val row = fixture()
        val value = doneIntent(row)
        val saved = unconfirmedState(value)
        val original = OccurrenceCodec.decodeAction(saved.getString(pendingKey)!!)
        val restored = open(value, saved)
        drain(restored)
        inspect { assertNull(api(it).recover(original.id)) }
        click(restored, "Erneut versuchen")
        drain(restored)
        inspect { repo ->
            assertEquals(original, api(repo).recover(original.id))
            assertEquals(OccurrenceStatus.DONE, repo.get(row.id)!!.status)
        }
    }

    @Test fun restoreEpochRevokesAnUncommittedSavedOriginal() {
        val row = fixture()
        val value = doneIntent(row)
        val saved = unconfirmedState(value)
        val original = OccurrenceCodec.decodeAction(saved.getString(pendingKey)!!)
        restoreDatabase()
        val afterRestore = inspect { it.get(row.id) }
        val restored = open(value, saved)
        drain(restored)
        click(restored, "Erneut versuchen")
        drain(restored)
        inspect { repo ->
            assertNotEquals(StableTestAlarmActions.epoch(original), repo.deliveryEpoch())
            assertEquals(afterRestore, repo.get(row.id))
            assertNull(api(repo).recover(original.id))
            assertFalse(repo.isDeliveryAllowed(row.id))
        }
        assertFalse(restored.get().isFinishing)
    }

    @Test fun historicalOriginalAfterBackupRestoreCannotAuthorizeAnotherEffect() {
        val row = fixture()
        val value = doneIntent(row)
        val original = commitDone(row, value)
        restoreDatabase()
        val afterRestore = inspect { it.get(row.id) }
        val history = inspect { it.history(row.id) }
        clearServiceStarts()
        val restored = open(value, savedOriginal(original))
        drain(restored)
        click(restored, "Erneut versuchen")
        drain(restored)
        inspect { repo ->
            assertEquals(original, api(repo).recover(original.id))
            assertEquals(afterRestore, repo.get(row.id))
            assertEquals(history, repo.history(row.id))
            assertNotEquals(StableTestAlarmActions.epoch(original), repo.deliveryEpoch())
        }
        assertTrue(serviceStarts().isEmpty())
    }

    @Test fun generationAdvanceCannotRetargetTheSavedOriginal() {
        val row = fixture()
        val value = doneIntent(row)
        val saved = unconfirmedState(value)
        inspect { repo ->
            val changed = api(repo).prepare("test-user:boundary-generation", delivery(row, repo),
                ActionType.SNOOZE, 2)
            assertEquals(Outcome.APPLIED, api(repo).submit(changed).outcome)
        }
        val advanced = inspect { it.get(row.id) }
        clearServiceStarts()
        val restored = open(value, saved)
        drain(restored)
        click(restored, "Erneut versuchen")
        drain(restored)
        inspect { repo ->
            assertEquals(advanced, repo.get(row.id))
            assertFalse(repo.history(row.id).any { it.actionType == "DONE" && it.outcome == Outcome.APPLIED })
        }
        assertTrue(serviceStarts().isEmpty())
    }

    @Test fun newExplicitTapAfterRejectedRecreationIsNotTheRetainedIntent() {
        val row = fixture()
        val value = doneIntent(row)
        val saved = unconfirmedState(value)
        saved.remove(pendingKey)
        val restored = open(value, saved)
        drain(restored)
        inspect { assertEquals("Recreation itself applied an action", row, it.get(row.id)) }
        // A distinct framework delivery represents a NEW explicit PendingIntent tap.
        restored.newIntent(Intent(value))
        drain(restored)
        inspect { repo ->
            assertEquals("A fresh explicit tap remained poisoned by rejected recovery",
                OccurrenceStatus.DONE, repo.get(row.id)!!.status)
            assertEquals(1, repo.history(row.id).count { it.actionType == "DONE" })
        }
    }

    private fun api(repo: SqliteOccurrenceRepository) = StableTestAlarmActions(repo, Clock.systemUTC())
    private fun delivery(row: RoutineOccurrence, repo: SqliteOccurrenceRepository) =
        AlarmDelivery.from(row, repo.deliveryEpoch())

    private fun fixture(): RoutineOccurrence = inspect { repo ->
        val now = Instant.ofEpochMilli(System.currentTimeMillis())
        val planned = TestAlarmCoordinator(repo, Clock.fixed(now.minusSeconds(61), ZoneOffset.UTC)).create(1)
        TestAlarmCoordinator(repo, Clock.fixed(now, ZoneOffset.UTC))
            .trigger(delivery(planned, repo))!!
    }

    private fun doneIntent(row: RoutineOccurrence): Intent {
        val token = inspect { delivery(row, it) }
        val service = Robolectric.buildService(TestAlarmService::class.java).create()
        services.add(service)
        service.get().onStartCommand(TestAlarmRuntime.putToken(
            Intent(app, TestAlarmService::class.java), token), 0, 1)
        val note: Notification = shadowOf(app.getSystemService(NotificationManager::class.java))
            .getNotification(TestAlarmService.NOTIFICATION)!!
        val action = note.actions.single { it.title.toString() == "Erledigt" }
        return Intent(shadowOf(action.actionIntent).savedIntent)
    }

    private fun commitDone(row: RoutineOccurrence, value: Intent): ExecutionAction {
        val screen = open(value)
        drain(screen)
        return inspect { repo ->
            assertEquals(OccurrenceStatus.DONE, repo.get(row.id)!!.status)
            val entry = repo.history(row.id).single { it.actionType == "DONE" }
            api(repo).recover(entry.actionId)!!
        }
    }

    private fun unconfirmedState(value: Intent): Bundle {
        UiReadDatabaseGate.failNext()
        val screen = open(Intent(value))
        drain(screen)
        assertTrue(labels(screen).contains("Aktion nicht bestätigt"))
        val saved = Bundle().also { screen.saveInstanceState(it) }
        val original = OccurrenceCodec.decodeAction(saved.getString(pendingKey)!!)
        inspect { assertNull(api(it).recover(original.id)) }
        screen.destroy()
        screens.remove(screen)
        return saved
    }

    private fun savedOriginal(action: ExecutionAction) =
        Bundle().apply { putString(pendingKey, OccurrenceCodec.encode(action)) }

    private fun restoreDatabase() {
        val bytes = inspect { it.exportBackup() }
        assertTrue(app.deleteDatabase("occurrences.db"))
        inspect { it.restoreBackup(bytes) }
    }

    private fun open(value: Intent, saved: Bundle? = null): ActivityController<TestAlarmActivity> {
        val screen = Robolectric.buildActivity(TestAlarmActivity::class.java, value)
        screens.add(screen)
        screen.create(saved)
        executors.add(executor(screen))
        return screen
    }

    private fun executor(screen: ActivityController<TestAlarmActivity>): ExecutorService {
        val loader = TestAlarmActivity::class.java.getDeclaredField("reader")
            .apply { isAccessible = true }.get(screen.get())
        return loader.javaClass.declaredFields.single { ExecutorService::class.java.isAssignableFrom(it.type) }
            .apply { isAccessible = true }.get(loader) as ExecutorService
    }

    private fun drain(screen: ActivityController<TestAlarmActivity>) {
        repeat(4) {
            executor(screen).submit {}.get(10, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun <T> inspect(read: (SqliteOccurrenceRepository) -> T): T {
        val armed = UiReadDatabaseGate.armed
        UiReadDatabaseGate.armed = false
        return try { TestAlarmRuntime.repository(app).use(read) }
        finally { UiReadDatabaseGate.armed = armed }
    }

    private fun serviceStarts(): List<Intent> = generateSequence { shadowOf(app).nextStartedService }.toList()
    private fun clearServiceStarts() { serviceStarts() }
    private fun labels(screen: ActivityController<TestAlarmActivity>) =
        views(screen.get().window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
    private fun click(screen: ActivityController<TestAlarmActivity>, label: String) {
        views(screen.get().window.decorView).filterIsInstance<Button>().single { it.text == label }.performClick()
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
