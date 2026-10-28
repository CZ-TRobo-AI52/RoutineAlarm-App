package com.cztr.routinealarm.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.cztr.routinealarm.MainActivity
import com.cztr.routinealarm.ScheduleState
import com.cztr.routinealarm.ui.components.R52BottomNav
import com.cztr.routinealarm.ui.design.R52Colors
import com.cztr.routinealarm.ui.shell.AppDestination
import com.cztr.routinealarm.execution.TestAlarmActivity
import com.cztr.routinealarm.execution.TestAlarmRuntime
import com.cztr.routinealarm.support.awaitUi
import com.cztr.routinealarm.support.awaitUiReadersStopped
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MainShellNavigationTest {
    @Before fun prepare() {
        val app = RuntimeEnvironment.getApplication() as Context
        app.deleteDatabase("occurrences.db")
        ScheduleState.setEnabled(app, false)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @Test fun fiveDestinationsHaveAccessibleTargetsAndOneSelection() = withMain { a ->
        val nav = listOf("Heute", "Plan", "Studio", "Verlauf", "Mehr").map { destination(a, it) }
        assertEquals(1, nav.count { it.isSelected })
        assertTrue(nav.first().isSelected)
        nav.forEach { assertTrue(it.minimumHeight >= (48 * a.resources.displayMetrics.density).toInt()) }
    }

    @Test fun planNavigationKeepsWeeklyPlanPausedAndHidesUnrelatedTestCard() = withMain { a ->
        destination(a, "Plan").performClick()
        assertTrue(labels(a).contains("Woche"))
        assertFalse(labels(a).contains("TESTWECKER"))
        assertFalse(ScheduleState.isEnabled(a))
        assertTrue(destination(a, "Plan").isSelected)
    }

    @Test fun savedDestinationAndChosenDaySurviveNewActivity() {
        val saved = Bundle()
        withMain { a ->
            destination(a, "Plan").performClick()
            views(a.window.decorView).single { it.contentDescription == "Mittwoch auswählen" }.performClick()
            a.javaClass.getDeclaredMethod("onSaveInstanceState", Bundle::class.java).apply { isAccessible = true }.invoke(a, saved)
        }
        withMain(saved) { a ->
            assertTrue(destination(a, "Plan").isSelected)
            assertTrue(views(a.window.decorView).single { it.contentDescription == "Mittwoch auswählen" }.isSelected)
            assertFalse(ScheduleState.isEnabled(a))
        }
    }

    @Test fun historyEntryDelegatesWithoutAnAlarmIdentity() = withMain { a ->
        destination(a, "Verlauf").performClick()
        views(a.window.decorView).filterIsInstance<Button>().single { it.text == "Testverlauf öffnen" }.performClick()
        val intent = requireNotNull(shadowOf(a).nextStartedActivity)
        assertEquals(TestAlarmActivity::class.java.name, intent.component!!.className)
        assertNull(TestAlarmRuntime.token(intent))
        assertNull(intent.data)
    }

    @Test fun lightThemeKeepsDestinationAndReturnsToRealTodayContent() = withMain { a ->
        destination(a, "Mehr").performClick()
        views(a.window.decorView).filterIsInstance<Button>().single { it.text == "Helles Design" }.performClick()
        assertTrue(destination(a, "Mehr").isSelected)
        val shell = views(a.window.decorView).single { it.tag == "r52_shell" }
        assertEquals(Color.parseColor("#F5F8FB"), (shell.background as ColorDrawable).color)
        destination(a, "Heute").performClick()
        assertTrue(labels(a).contains("Wochenplan pausiert"))
        assertFalse(ScheduleState.isEnabled(a))
    }

    @Test fun backFromPlanReturnsHomeWithoutChangingSchedule() = withMain { a ->
        destination(a, "Plan").performClick()
        @Suppress("DEPRECATION") a.onBackPressed()
        assertFalse(a.isFinishing)
        assertTrue(destination(a, "Heute").isSelected)
        assertFalse(ScheduleState.isEnabled(a))
    }

    @Test fun largeTextRetainsEveryNavigationAction() {
        RuntimeEnvironment.setFontScale(2f)
        try { withMain { a ->
            for (label in listOf("Heute", "Plan", "Studio", "Verlauf", "Mehr")) {
                val action = destination(a, label)
                assertNull(action.ellipsize)
                assertTrue(action.performClick())
                assertTrue(destination(a, label).isSelected)
            }
        } } finally { RuntimeEnvironment.setFontScale(1f) }
    }

    @Test fun selectedOverflowDestinationAndItsFullLabelRemainVisibleAtLargeFont() {
        RuntimeEnvironment.setFontScale(2f)
        try {
            val context = RuntimeEnvironment.getApplication() as Context
            val width = (320 * context.resources.displayMetrics.density).toInt()
            for (selected in AppDestination.entries) {
                val nav = R52BottomNav(context, selected, R52Colors.Dark) {}
                nav.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                nav.layout(0, 0, width, nav.measuredHeight)
                val button = views(nav).filterIsInstance<Button>().single { it.isSelected }
                assertTrue("${selected.label} starts within the viewport", button.left >= nav.scrollX)
                assertTrue("${selected.label} ends within the viewport", button.right <= nav.scrollX + nav.width)
                val text = requireNotNull(button.layout)
                for (line in 0 until text.lineCount) assertEquals(0, text.getEllipsisCount(line))
                assertTrue("full label height", text.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                assertTrue("48dp target", button.height >= (48 * context.resources.displayMetrics.density).toInt())
            }
        } finally { RuntimeEnvironment.setFontScale(1f) }
    }

    private fun withMain(saved: Bundle? = null, check: (MainActivity) -> Unit) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create(saved).start().resume().visible()
        try { awaitUi(controller.get()); check(controller.get()) }
        finally { controller.pause().stop().destroy(); awaitUiReadersStopped(controller.get()) }
    }
    private fun destination(a: Activity, title: String): Button = views(a.window.decorView)
        .filterIsInstance<Button>().single { it.contentDescription == "$title öffnen" }
    private fun labels(a: Activity) = views(a.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
}
