package com.cztr.routinealarm.ui

import android.app.AlarmManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import com.cztr.routinealarm.MainActivity
import com.cztr.routinealarm.ScheduleState
import com.cztr.routinealarm.support.awaitUi
import com.cztr.routinealarm.support.awaitUiReadersStopped
import com.cztr.routinealarm.ui.design.R52Colors
import com.cztr.routinealarm.ui.plan.PlanScreenView
import com.cztr.routinealarm.ui.plan.PlanUiInput
import com.cztr.routinealarm.ui.plan.PlanUiMapper
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowAlarmManager
import java.time.DayOfWeek

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PlanDaySelectionVisibilityTest {
    @Before fun prepare() {
        RuntimeEnvironment.setFontScale(2f)
        val context = RuntimeEnvironment.getApplication() as Context
        context.deleteDatabase("occurrences.db")
        ScheduleState.setEnabled(context, false)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After fun restoreFontScale() { RuntimeEnvironment.setFontScale(1f) }

    @Test fun everySelectedDayIsVisibleInDarkPalette() = assertAllDays(R52Colors.Dark)
    @Test fun everySelectedDayIsVisibleInLightPalette() = assertAllDays(R52Colors.Light)

    @Test fun selectedDayRemainsVisibleAfterViewportShrinks() {
        val view = plan(DayOfWeek.SUNDAY, R52Colors.Dark)
        layout(view, 800)
        assertSelectedVisible(view)
        layout(view, 280)
        assertSelectedVisible(view)
    }

    @Test fun unchangedViewportDoesNotUndoManualScrolling() {
        val view = plan(DayOfWeek.SUNDAY, R52Colors.Light)
        layout(view, 280)
        assertSelectedVisible(view)
        val strip = strip(view)
        strip.scrollTo(0, 0)
        assertEquals(0, strip.scrollX)
        view.requestLayout()
        layout(view, 280)
        assertEquals("A content relayout must preserve the user's manual scroll", 0, strip.scrollX)
    }

    @Test fun realActivityRerenderKeepsSelectedDayVisibleWithoutScheduling() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            awaitUi(activity)
            views(activity.window.decorView).filterIsInstance<Button>()
                .single { it.contentDescription.toString().startsWith("Plan öffnen") }.performClick()
            awaitUi(activity)
            val before = views(activity.window.decorView).filterIsInstance<PlanScreenView>().single()
            layout(before, 280)
            val sunday = views(strip(before)).filterIsInstance<Button>()
                .single { it.contentDescription.toString().startsWith("Sonntag auswählen") }
            strip(before).scrollTo(sunday.left, 0)
            val alarms = shadowOf(activity.getSystemService(AlarmManager::class.java))
            val scheduledBefore = alarms.scheduledAlarms.toList()
            assertFalse(ScheduleState.isEnabled(activity))
            sunday.performClick()
            awaitUi(activity)
            val after = views(activity.window.decorView).filterIsInstance<PlanScreenView>().single()
            assertNotSame("Day selection must exercise the real rebuilt Plan view", before, after)
            layout(after, 280)
            // API28 appends the selection state to the description; API31 has stateDescription.
            assertTrue(selected(after).contentDescription.toString().startsWith("Sonntag auswählen"))
            assertSelectedVisible(after)
            assertFalse(ScheduleState.isEnabled(activity))
            assertEquals(scheduledBefore, alarms.scheduledAlarms.toList())
            assertNull(shadowOf(activity).nextStartedActivity)
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        } finally {
            controller.destroy()
            awaitUiReadersStopped(activity)
        }
    }

    private fun assertAllDays(colors: R52Colors) {
        val failures = mutableListOf<String>()
        for (day in DayOfWeek.entries) {
            val view = plan(day, colors)
            layout(view, 280)
            val strip = strip(view)
            val selected = selected(view)
            val left = selected.left - strip.scrollX
            val right = selected.right - strip.scrollX
            if (left < 0 || right > strip.width)
                failures += "$day: selected bounds $left..$right outside viewport 0..${strip.width}"
            assertTrue("48dp target height", selected.height >= dp(view.context, 48))
            assertTrue("48dp target width", selected.width >= dp(view.context, 48))
            val text = requireNotNull(selected.layout)
            assertTrue("Full label height", text.height <= selected.height - selected.compoundPaddingTop - selected.compoundPaddingBottom)
            for (line in 0 until text.lineCount) assertEquals(0, text.getEllipsisCount(line))
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun plan(day: DayOfWeek, colors: R52Colors): PlanScreenView {
        val context = RuntimeEnvironment.getApplication() as Context
        val state = PlanUiMapper.map(PlanUiInput(
            planEnabled = false, exactAllowed = true, activeEventCount = 0,
            selectedDay = day, events = emptyList(), training = emptyList(), showTrainingDetails = false,
        ))
        return PlanScreenView(context, state, colors,
            { fail("Layout must not activate the plan") },
            { fail("Layout must not change the selected day") },
            { fail("Layout must not change training visibility") })
    }

    private fun layout(view: View, widthDp: Int) {
        val width = dp(view.context, widthDp)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, width, view.measuredHeight)
    }

    private fun assertSelectedVisible(view: PlanScreenView) {
        val strip = strip(view)
        val button = selected(view)
        assertTrue("Selected day starts inside viewport: ${button.left} >= ${strip.scrollX}",
            button.left >= strip.scrollX)
        assertTrue("Selected day ends inside viewport: ${button.right} <= ${strip.scrollX + strip.width}",
            button.right <= strip.scrollX + strip.width)
    }

    private fun strip(view: PlanScreenView) =
        views(view).filterIsInstance<HorizontalScrollView>().single()
    private fun selected(view: PlanScreenView) =
        views(strip(view)).filterIsInstance<Button>().single { it.isSelected }
    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
