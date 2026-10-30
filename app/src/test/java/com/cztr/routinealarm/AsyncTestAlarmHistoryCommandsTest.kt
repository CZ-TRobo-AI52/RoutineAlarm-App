package com.cztr.routinealarm

import android.app.AlarmManager
import android.app.AlertDialog
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
import org.robolectric.shadows.ShadowAlertDialog
import java.time.Clock
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31], shadows = [UiReadDatabaseGate::class])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AsyncTestAlarmHistoryCommandsTest {
    private lateinit var context: Application
    private var screen: ActivityController<TestAlarmActivity>? = null
    private var worker: ExecutorService? = null

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        UiReadDatabaseGate.reset()
        context.deleteDatabase("occurrences.db")
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }
    @After fun cleanup() {
        UiReadDatabaseGate.releaseAll()
        screen?.destroy()
        worker?.let { assertTrue(it.awaitTermination(10, TimeUnit.SECONDS)) }
        shadowOf(Looper.getMainLooper()).idle()
        UiReadDatabaseGate.reset()
        context.deleteDatabase("occurrences.db")
    }

    @Test fun slowHistoryReplanKeepsMainResponsiveAndDoesNotScheduleBeforeReadback() {
        val item = fixture(restored = false)
        open()
        val gate = UiReadDatabaseGate.holdNext()
        click("Erneut planen")
        assertEquals("History replan still opens SQLite on UI", 0, UiReadDatabaseGate.mainAccesses.get())
        gate.awaitEntered()
        assertMainEvent()
        assertTrue(alarms().isEmpty())
        gate.release.countDown()
        drain()
        val scheduled = alarms().single()
        assertEquals(item.currentTriggerAt.toEpochMilli(), scheduled.triggerAtTime)
        inspect { repo -> assertEquals(AlarmDelivery.from(item, repo.deliveryEpoch()),
            TestAlarmRuntime.token(shadowOf(requireNotNull(scheduled.operation)).savedIntent)) }
    }

    @Test fun slowConfirmedActivationKeepsQuarantineUntilItsBackgroundCommit() {
        val item = fixture(restored = true)
        open()
        click("Wiederhergestellten Test aktivieren")
        val gate = UiReadDatabaseGate.holdNext()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Activation still opens SQLite on UI", 0, UiReadDatabaseGate.mainAccesses.get())
        gate.awaitEntered()
        assertMainEvent()
        inspect { repo -> assertFalse(repo.isDeliveryAllowed(item.id)) }
        assertTrue(alarms().isEmpty())
        gate.release.countDown()
        drain()
        inspect { repo -> assertTrue(repo.isDeliveryAllowed(item.id)) }
        assertEquals(1, alarms().size)
    }

    @Test fun staleConfirmationCannotActivateTheSameRowFromAnotherRestoreEpoch() {
        val item = fixture(restored = true)
        open()
        click("Wiederhergestellten Test aktivieren")
        val oldEpoch = inspect { it.deliveryEpoch() }
        val bytes = inspect { it.exportBackup() }
        context.deleteDatabase("occurrences.db")
        inspect { it.restoreBackup(bytes) }
        assertNotEquals(oldEpoch, inspect { it.deliveryEpoch() })
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        drain()
        inspect { repo -> assertFalse("Old confirmation unpaused a new restore", repo.isDeliveryAllowed(item.id)) }
        assertTrue(alarms().isEmpty())
        assertNull(shadowOf(context).nextStartedService)
    }

    private fun fixture(restored: Boolean): RoutineOccurrence {
        val item = inspect { TestAlarmCoordinator(it, Clock.systemUTC()).create(30) }
        if (restored) {
            val bytes = inspect { it.exportBackup() }
            context.deleteDatabase("occurrences.db")
            inspect { it.restoreBackup(bytes) }
        }
        return item
    }
    private fun open() {
        val c = Robolectric.buildActivity(TestAlarmActivity::class.java).create()
        screen = c
        val field = TestAlarmActivity::class.java.getDeclaredField("reader").apply { isAccessible = true }
        val loader = field.get(c.get())
        worker = loader.javaClass.declaredFields.single { ExecutorService::class.java.isAssignableFrom(it.type) }
            .apply { isAccessible = true }.get(loader) as ExecutorService
        drain()
    }
    private fun drain() {
        repeat(3) {
            worker!!.submit {}.get(10, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
    private fun assertMainEvent() {
        var handled = false
        Handler(Looper.getMainLooper()).post { handled = true }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(handled)
    }
    private fun <T> inspect(read: (SqliteOccurrenceRepository) -> T): T {
        val armed = UiReadDatabaseGate.armed
        UiReadDatabaseGate.armed = false
        return try { TestAlarmRuntime.repository(context).use(read) } finally { UiReadDatabaseGate.armed = armed }
    }
    private fun alarms() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.toList()
    private fun click(label: String) = views(screen!!.get().window.decorView).filterIsInstance<Button>()
        .single { it.text == label }.performClick()
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
