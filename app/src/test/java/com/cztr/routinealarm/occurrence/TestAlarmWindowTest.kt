package com.cztr.routinealarm.occurrence

import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import com.cztr.routinealarm.execution.TestAlarmActivity
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
@Config(sdk = [26, 31])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TestAlarmWindowTest {
    private lateinit var context: Context
    private lateinit var repo: SqliteOccurrenceRepository

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("occurrences.db")
        repo = TestAlarmRuntime.repository(context)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After fun cleanup() { repo.close(); context.deleteDatabase("occurrences.db") }

    private fun fired(): RoutineOccurrence {
        val planned = TestAlarmCoordinator(repo,
            Clock.offset(Clock.systemDefaultZone(), Duration.ofMinutes(-2))).create(1)
        return TestAlarmRuntime.coordinator(repo).trigger(AlarmDelivery.from(planned))!!
    }

    private fun buttons(view: View): List<Button> = when (view) {
        is Button -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Suppress("DEPRECATION")
    private fun assertAlarmWindow(activity: TestAlarmActivity, expected: Boolean) {
        val flags = activity.window.attributes.flags
        assertEquals("keep screen on", expected,
            flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        if (Build.VERSION.SDK_INT >= 27) {
            assertEquals("show when locked", expected, shadowOf(activity).showWhenLocked)
            assertEquals("turn screen on", expected, shadowOf(activity).turnScreenOn)
        } else {
            assertEquals("show when locked", expected,
                flags and WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED != 0)
            assertEquals("turn screen on", expected,
                flags and WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON != 0)
        }
    }

    @Test fun historyOpenedDirectlyDoesNotWakeOrCoverTheLockScreen() {
        val controller = Robolectric.buildActivity(TestAlarmActivity::class.java).setup()
        try { assertAlarmWindow(controller.get(), false) }
        finally { controller.pause().stop().destroy() }
    }

    @Test fun openingHistoryFromAlarmClearsAllAlarmWindowFlags() {
        val item = fired()
        val controller = Robolectric.buildActivity(TestAlarmActivity::class.java,
            TestAlarmRuntime.putToken(Intent(context, TestAlarmActivity::class.java),
                AlarmDelivery.from(item))).setup()
        try {
            val activity = controller.get()
            assertAlarmWindow(activity, true)
            buttons(activity.window.decorView).single { it.text == "Testverlauf öffnen" }.performClick()
            assertAlarmWindow(activity, false)
            assertNull(activity.intent.data)
            assertEquals(item, repo.get(item.id))
            assertTrue(buttons(activity.window.decorView).any { it.text == "Offenen Testwecker öffnen" })
        } finally { controller.pause().stop().destroy() }
    }

    @Test @Config(sdk = [31])
    fun snoozePlanningFailureKeepsSavedSnoozeAndClearsAlarmWindowFlags() {
        val item = fired()
        val controller = Robolectric.buildActivity(TestAlarmActivity::class.java,
            TestAlarmRuntime.putToken(Intent(context, TestAlarmActivity::class.java),
                AlarmDelivery.from(item))).setup()
        try {
            val activity = controller.get()
            assertAlarmWindow(activity, true)
            ShadowAlarmManager.setCanScheduleExactAlarms(false)
            buttons(activity.window.decorView).single { it.text == "5 Minuten später" }.performClick()
            assertAlarmWindow(activity, false)
            assertNull(activity.intent.data)
            assertFalse(activity.isFinishing)
            assertEquals(OccurrenceStatus.SNOOZED, repo.get(item.id)!!.status)
            assertTrue(buttons(activity.window.decorView).any { it.text == "Erneut planen" })
        } finally { controller.pause().stop().destroy() }
    }
}
