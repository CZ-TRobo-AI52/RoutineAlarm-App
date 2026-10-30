package com.cztr.routinealarm.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.cztr.routinealarm.MainActivity
import com.cztr.routinealarm.ScheduleState
import com.cztr.routinealarm.support.awaitUi
import com.cztr.routinealarm.support.awaitUiReadersStopped
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class StudioScreenProjectionTest {
    @Before fun prepare() {
        val context = RuntimeEnvironment.getApplication() as Context
        context.deleteDatabase("occurrences.db")
        ScheduleState.setEnabled(context, false)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @Test fun selectingRoutineOnlyOpensReadOnlyDetails() = withMain { activity ->
        destination(activity, "Studio").performClick()
        val item = buttons(activity).first { it.tag == "studio_routine_item" }
        item.performClick()

        assertTrue(labels(activity).contains("Nur ansehen"))
        assertTrue(labels(activity).any { it.contains("Bearbeiten und Speichern werden erst") })
        assertFalse(ScheduleState.isEnabled(activity))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun filtersDoNotMutateTheWeeklyPlan() = withMain { activity ->
        destination(activity, "Studio").performClick()
        buttons(activity).single { it.text == "Aktiv" }.performClick()

        assertFalse(ScheduleState.isEnabled(activity))
        assertTrue(buttons(activity).single { it.text == "Aktiv" }.isSelected)
    }

    @Test fun routineTargetsRemainReachableAtTwoHundredPercentFontScale() {
        RuntimeEnvironment.setFontScale(2f)
        try {
            withMain { activity ->
                destination(activity, "Studio").performClick()
                val item = buttons(activity).first { it.tag == "studio_routine_item" }
                assertTrue(item.minimumHeight >= (48 * activity.resources.displayMetrics.density).toInt())
                assertNull(item.ellipsize)
            }
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    private fun withMain(check: (MainActivity) -> Unit) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try { awaitUi(controller.get()); check(controller.get()) }
        finally { controller.pause().stop().destroy(); awaitUiReadersStopped(controller.get()) }
    }
    private fun destination(a: MainActivity, label: String) = buttons(a).single { it.contentDescription == "$label öffnen" }
    private fun labels(a: MainActivity) = views(a.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
    private fun buttons(a: MainActivity) = views(a.window.decorView).filterIsInstance<Button>()
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
}
