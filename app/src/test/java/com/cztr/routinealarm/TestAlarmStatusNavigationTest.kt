package com.cztr.routinealarm

import android.content.Context
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.cztr.routinealarm.execution.TestAlarmActivity
import com.cztr.routinealarm.execution.TestAlarmRuntime
import com.cztr.routinealarm.occurrence.SqliteOccurrenceRepository
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
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TestAlarmStatusNavigationTest {
    private lateinit var context: Context
    private lateinit var repo: SqliteOccurrenceRepository

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("occurrences.db")
        repo = TestAlarmRuntime.repository(context)
        ScheduleState.setEnabled(context, false)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After fun cleanup() {
        repo.close()
        context.deleteDatabase("occurrences.db")
    }

    @Test fun visibleTestCardOpensHistoryWithoutAnAlarmToken() = withMain { activity ->
        assertTrue(text(activity, "TESTWECKER").isShown)
        assertTrue(text(activity, "Keine offenen Testwecker").isShown)
        button(activity, "Testverlauf öffnen").performClick()

        val intent = requireNotNull(shadowOf(activity).nextStartedActivity)
        assertEquals(TestAlarmActivity::class.java.name, intent.component?.className)
        assertNull(TestAlarmRuntime.token(intent))
        assertNull(intent.data)
        assertTrue(intent.extras == null || intent.extras!!.isEmpty)
    }

    @Test fun missingExactAlarmPermissionOpensAppSpecificSystemSettings() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        withMain { activity ->
            assertTrue(text(activity,
                "Exakte Wecker sind nicht erlaubt. Gespeicherte Testwecker benötigen diese Freigabe.").isShown)
            button(activity, "Exakte Wecker erlauben").performClick()

            val intent = requireNotNull(shadowOf(activity).nextStartedActivity)
            assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, intent.action)
            assertEquals(Uri.parse("package:${activity.packageName}"), intent.data)
        }
    }

    @Test fun pausedWeeklyPlanStillShowsFutureTestAndItsStoredTime() {
        val future = TestAlarmRuntime.coordinator(repo).create(5)
        val expectedTime = requireNotNull(TestAlarmStatusPresenter.present(
            listOf(future), Instant.now(), ZoneId.systemDefault(), exactAllowed = true).nextText)
        withMain { activity ->
            assertTrue(text(activity, "Wochenplan pausiert").isShown)
            assertTrue(text(activity, "1 offener Testwecker").isShown)
            assertTrue(text(activity, expectedTime).isShown)
            assertTrue(text(activity, "Testwecker laufen unabhängig vom Wochenplan.").isShown)
            assertTrue(button(activity, "Testverlauf öffnen").isShown)
            assertFalse(ScheduleState.isEnabled(context))
        }
    }

    private fun withMain(check: (MainActivity) -> Unit) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try { awaitUi(controller.get()); check(controller.get()) }
        finally { controller.pause().stop().destroy(); awaitUiReadersStopped(controller.get()) }
    }

    private fun text(activity: MainActivity, label: String): TextView =
        views(activity.window.decorView).filterIsInstance<TextView>().single { it.text.toString() == label }

    private fun button(activity: MainActivity, label: String): Button =
        views(activity.window.decorView).filterIsInstance<Button>().single { it.text.toString() == label }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
