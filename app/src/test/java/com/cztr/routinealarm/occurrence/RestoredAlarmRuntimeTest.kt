package com.cztr.routinealarm.occurrence

import android.app.AlarmManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.cztr.routinealarm.BootReceiver
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
import org.robolectric.shadows.ShadowAlertDialog
import java.time.Clock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RestoredAlarmRuntimeTest {
    private lateinit var context: Context
    private lateinit var repo: SqliteOccurrenceRepository
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("occurrences.db")
        context.deleteDatabase("backup-source.db")
        repo = TestAlarmRuntime.repository(context)
    }
    @After fun cleanup() {
        repo.close(); context.deleteDatabase("occurrences.db"); context.deleteDatabase("backup-source.db")
    }
    private fun restore(triggered: Boolean = false, overdue: Boolean = false): RoutineOccurrence {
        SqliteOccurrenceRepository(context, Clock.systemUTC(), "backup-source.db").use { source ->
            val clock = if (triggered || overdue) Clock.offset(Clock.systemUTC(), Duration.ofMinutes(-2)) else Clock.systemUTC()
            val created = TestAlarmCoordinator(source, clock).create(1)
            val item = if (triggered) TestAlarmCoordinator(source, Clock.systemUTC())
                .trigger(AlarmDelivery.from(created))!! else created
            assertEquals(1, repo.restoreBackup(source.exportBackup()))
            return item
        }
    }
    private fun buttons(view: View): List<Button> = when(view) {
        is Button -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun importAppResumeAndSystemRecoveryDoNotSchedulePausedTests() {
        val item = restore()
        TestAlarmRuntime.restoreFuture(context)
        BootReceiver().onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
        assertFalse(repo.isDeliveryAllowed(item.id))
        assertThrows(IllegalStateException::class.java) { TestAlarmRuntime.schedule(context, item) }
        assertTrue(repo.schedulingAttempts(item.id).isEmpty())
    }

    @Test fun serviceRefreshAndTtsReadyCannotPlayImportedHistoricalTrigger() {
        val item = restore(triggered = true)
        val service = Robolectric.buildService(TestAlarmService::class.java).create()
        try {
            service.get().onStartCommand(Intent().setAction(TestAlarmService.REFRESH), 0, 1)
            service.get().onInit(android.speech.tts.TextToSpeech.SUCCESS)
            assertNull(shadowOf(context.getSystemService(NotificationManager::class.java))
                .getNotification(TestAlarmService.NOTIFICATION))
            assertEquals(OccurrenceStatus.TRIGGERED, repo.get(item.id)!!.status)
            assertFalse(repo.isDeliveryAllowed(item.id))
        } finally { service.destroy() }
    }

    @Test fun activationIssuesNewTokenAndOldTokenStaysInvalidAfterActivation() {
        val item = restore(overdue = true)
        val old = TestAlarmRuntime.putToken(Intent(context, TestAlarmReceiver::class.java), AlarmDelivery.from(item))
        assertTrue(repo.activateRestored(item.id))
        TestAlarmRuntime.schedule(context, item)
        val scheduled = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.single()
        val intent = shadowOf(scheduled.operation).savedIntent
        val newToken = TestAlarmRuntime.token(intent)!!
        assertEquals(repo.deliveryEpoch(), newToken.epoch)
        assertTrue(newToken.epoch.isNotEmpty())
        assertNotEquals(old.data, intent.data)
        TestAlarmReceiver().onReceive(context, old)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        assertEquals(OccurrenceStatus.SCHEDULED, repo.get(item.id)!!.status)
        TestAlarmReceiver().onReceive(context, intent)
        val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertEquals(newToken.epoch, TestAlarmRuntime.token(started)!!.epoch)
        val current = repo.get(item.id)!!
        assertEquals(OccurrenceStatus.TRIGGERED, current.status)
        assertNull(TestAlarmRuntime.coordinator(repo).act(AlarmDelivery.from(current), ActionType.DONE))
        assertEquals(Outcome.APPLIED, TestAlarmRuntime.coordinator(repo)
            .act(AlarmDelivery.from(current, newToken.epoch), ActionType.DONE)!!.outcome)
    }

    @Test fun historyActivationRequiresExplicitConfirmation() {
        val item = restore()
        val activity = Robolectric.buildActivity(TestAlarmActivity::class.java).setup()
        try {
            awaitUi(activity.get())
            fun clickActivate() = buttons(activity.get().window.decorView)
                .single { it.text == "Wiederhergestellten Test aktivieren" }.performClick()
            clickActivate()
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(repo.isDeliveryAllowed(item.id))
            assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
            clickActivate()
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            awaitUi(activity.get())
            assertTrue(repo.isDeliveryAllowed(item.id))
            assertEquals(1, shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
        } finally { activity.pause().stop().destroy(); awaitUiReadersStopped(activity.get()) }
    }

    @Test fun tokenEpochMustMatchBothIntentDataAndExtras() {
        val token = AlarmDelivery("test:restored", 2, 3, "restore-123")
        val intent = TestAlarmRuntime.putToken(Intent(), token)
        assertEquals(token, TestAlarmRuntime.token(intent))
        assertNull(TestAlarmRuntime.token(Intent(intent).putExtra("occurrence_epoch", "other")))
        assertNull(TestAlarmRuntime.token(Intent(intent).apply { removeExtra("occurrence_epoch") }))
        assertEquals(AlarmDelivery("legacy", 0, 0), TestAlarmRuntime.token(
            TestAlarmRuntime.putToken(Intent(), AlarmDelivery("legacy", 0, 0))
                .apply { removeExtra("occurrence_epoch") }))
    }

    @Test fun restoredTestsBeyondFirstHistoryPageRemainReachableForActivation() {
        lateinit var oldest: RoutineOccurrence
        SqliteOccurrenceRepository(context, Clock.systemUTC(), "backup-source.db").use { source ->
            repeat(21) { index ->
                val clock = Clock.offset(Clock.systemUTC(), Duration.ofSeconds(index.toLong()))
                val item = TestAlarmCoordinator(source, clock).create(1)
                if (index == 0) oldest = item
            }
            assertEquals(21, repo.restoreBackup(source.exportBackup()))
        }
        val activity = Robolectric.buildActivity(TestAlarmActivity::class.java).setup()
        try {
            awaitUi(activity.get())
            assertEquals(20, buttons(activity.get().window.decorView).count { it.text == "Wiederhergestellten Test aktivieren" })
            val next = buttons(activity.get().window.decorView).firstOrNull { it.text == "Ältere Tests" }
            assertNotNull("Every restored test needs a reachable activation entry point", next)
            next!!.performClick()
            awaitUi(activity.get())
            buttons(activity.get().window.decorView).single { it.text == "Wiederhergestellten Test aktivieren" }.performClick()
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            awaitUi(activity.get())
            assertTrue(repo.isDeliveryAllowed(oldest.id))
            assertEquals(20, repo.restorePausedCount())
            buttons(activity.get().window.decorView).single { it.text == "Neuere Tests" }.performClick()
            awaitUi(activity.get())
            assertEquals(20, buttons(activity.get().window.decorView).count { it.text == "Wiederhergestellten Test aktivieren" })
        } finally { activity.pause().stop().destroy(); awaitUiReadersStopped(activity.get()) }
    }
}
