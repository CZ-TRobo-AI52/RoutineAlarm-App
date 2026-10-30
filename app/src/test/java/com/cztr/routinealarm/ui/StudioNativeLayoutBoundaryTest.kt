package com.cztr.routinealarm.ui

import android.app.AlarmManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.TextView
import com.cztr.routinealarm.MainActivity
import com.cztr.routinealarm.ScheduleState
import com.cztr.routinealarm.support.awaitUi
import com.cztr.routinealarm.support.awaitUiReadersStopped
import com.cztr.routinealarm.ui.design.R52Colors
import com.cztr.routinealarm.ui.studio.*
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
class StudioNativeLayoutBoundaryTest {
    @Before fun prepare() {
        RuntimeEnvironment.setFontScale(2f)
        val context = RuntimeEnvironment.getApplication() as Context
        context.deleteDatabase("occurrences.db")
        ScheduleState.setEnabled(context, false)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After fun restoreFontScale() { RuntimeEnvironment.setFontScale(1f) }

    @Test fun selectedFilterIsVisibleForEveryFilterAndPalette() {
        val failures = mutableListOf<String>()
        for (colors in listOf(R52Colors.Dark, R52Colors.Light)) {
            for (filter in StudioFilter.entries) {
                val view = studio(filter, colors)
                layout(view, 280)
                val strip = strip(view)
                val selected = buttons(strip).single { it.isSelected }
                if (selected.left < strip.scrollX || selected.right > strip.scrollX + strip.width)
                    failures += "$filter: ${selected.left}..${selected.right}, viewport ${strip.scrollX}..${strip.scrollX + strip.width}"
                assertReadable(selected)
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun selectedFilterRemainsVisibleAfterViewportShrinks() {
        val view = studio(StudioFilter.PAUSED, R52Colors.Dark)
        layout(view, 800)
        assertSelectedVisible(view)
        layout(view, 280)
        assertSelectedVisible(view)
    }

    @Test fun contentRelayoutPreservesManualFilterScrolling() {
        val view = studio(StudioFilter.PAUSED, R52Colors.Light)
        layout(view, 280)
        val strip = strip(view)
        strip.scrollTo(0, 0)
        view.requestLayout()
        layout(view, 280)
        assertEquals("An unchanged viewport must preserve manual scrolling", 0, strip.scrollX)
    }

    @Test fun longRoutineAndSpokenTextRemainFullyReadableInBothPalettes() {
        for (colors in listOf(R52Colors.Dark, R52Colors.Light)) {
            val view = studio(StudioFilter.ALL, colors, selected = "training-paused")
            layout(view, 280)
            val textViews = views(view).filterIsInstance<TextView>()
            assertTrue(textViews.any { it.text.toString() == longTitle })
            assertTrue(textViews.any { it.text.toString() == longSpokenText })
            textViews.forEach(::assertReadable)
            for (text in textViews.filterNot { insideStrip(it) }) {
                var left = text.left
                var parent = text.parent
                while (parent is View && parent !== view) { left += parent.left; parent = parent.parent }
                assertTrue("Text begins within the content viewport", left >= 0)
                assertTrue("Text ends within the content viewport: ${text.text}", left + text.width <= view.width)
            }
        }
    }

    @Test fun eachAvailableFilterAndRoutineClickEmitsExactlyItsTypedCallback() {
        for (colors in listOf(R52Colors.Dark, R52Colors.Light)) {
            val filters = mutableListOf<StudioFilter>()
            val routines = mutableListOf<String>()
            val view = studio(StudioFilter.ALL, colors, onFilter = { filters += it }, onRoutine = { routines += it })
            layout(view, 280)
            assertTrue(filters.isEmpty() && routines.isEmpty())
            buttons(strip(view)).single { it.text == "Alle" }.performClick()
            assertTrue("The already selected filter is not a new action", filters.isEmpty())
            buttons(strip(view)).single { it.text == "Aktiv" }.performClick()
            assertEquals(listOf(StudioFilter.ACTIVE), filters)
            val paused = buttons(strip(view)).single { it.text == "Pausiert" }
            strip(view).scrollTo(paused.left, 0)
            paused.performClick()
            assertEquals(listOf(StudioFilter.ACTIVE, StudioFilter.PAUSED), filters)
            buttons(view).single { it.tag == "studio_routine_item" && it.text.contains(longTitle) }.performClick()
            assertEquals(listOf("training-paused"), routines)
            buttons(view).single { it.tag == "studio_routine_item" && it.text.contains("Morgenroutine") }.performClick()
            assertEquals(listOf("training-paused", "routine-active"), routines)
            filters.clear()
            val filteredView = studio(StudioFilter.PAUSED, colors, onFilter = { filters += it })
            layout(filteredView, 280)
            val all = buttons(strip(filteredView)).single { it.text == "Alle" }
            strip(filteredView).scrollTo(all.left, 0)
            all.performClick()
            assertEquals("Returning to all routines emits one ALL action", listOf(StudioFilter.ALL), filters)
        }
    }

    @Test fun emptyFilterAndLockedDetailsDoNotExposeEditingActions() {
        for (colors in listOf(R52Colors.Dark, R52Colors.Light)) {
            val empty = studio(StudioFilter.ACTIVE, colors, routines = emptyList())
            layout(empty, 280)
            assertEquals(3, buttons(empty).size)
            assertTrue(views(empty).filterIsInstance<TextView>().any { it.text.contains("keine Routinen") })
            val locked = studio(StudioFilter.PAUSED, colors, selected = "training-paused")
            layout(locked, 280)
            assertEquals("Three filters and one read-only routine detail action", 4, buttons(locked).size)
            assertTrue(views(locked).filterIsInstance<EditText>().isEmpty())
            val labels = views(locked).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(labels.contains("Nur ansehen"))
            assertTrue(labels.any { it.contains("Training") && it.contains("Deaktiviert") })
        }
    }

    @Test fun activityFilterRebuildKeepsSelectionVisibleWithoutChangingPlanning() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            awaitUi(activity)
            buttons(activity.window.decorView).single { it.contentDescription.toString().startsWith("Studio öffnen") }.performClick()
            awaitUi(activity)
            val before = views(activity.window.decorView).filterIsInstance<StudioScreenView>().single()
            layout(before, 280)
            val alarms = shadowOf(activity.getSystemService(AlarmManager::class.java))
            val scheduledBefore = alarms.scheduledAlarms.toList()
            val paused = buttons(strip(before)).single { it.text == "Pausiert" }
            strip(before).scrollTo(paused.left, 0)
            paused.performClick()
            awaitUi(activity)
            val after = views(activity.window.decorView).filterIsInstance<StudioScreenView>().single()
            assertNotSame("Exercise the real host's rebuilt Studio", before, after)
            layout(after, 280)
            assertEquals("Pausiert", buttons(strip(after)).single { it.isSelected }.text.toString())
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

    private val longTitle = "Training mit ausführlicher Bewegungsanleitung und anschließender Erholung"
    private val longSpokenText = "Beginne langsam und beachte deine Atmung. ".repeat(12)
    private fun fixtures() = listOf(
        StudioProjectedRoutine("routine-active", "Morgenroutine", "Guten Morgen", 420,
            setOf(DayOfWeek.MONDAY), true, "Morgenroutine", "Routine"),
        StudioProjectedRoutine("training-paused", longTitle, longSpokenText, 1080,
            DayOfWeek.entries.toSet(), false, "Training", "Ansage"),
    )

    private fun studio(
        filter: StudioFilter, colors: R52Colors, selected: String? = null,
        routines: List<StudioProjectedRoutine> = fixtures(),
        onFilter: (StudioFilter) -> Unit = { fail("Layout emitted a filter action") },
        onRoutine: (String) -> Unit = { fail("Layout emitted a routine action") },
    ): StudioScreenView {
        val state = StudioUiMapper.map(StudioUiInput(routines, filter, selected))
        return StudioScreenView(RuntimeEnvironment.getApplication(), state, colors, onFilter, onRoutine)
    }

    private fun assertReadable(text: TextView) {
        val layout = requireNotNull(text.layout)
        val usableWidth = text.width - text.compoundPaddingLeft - text.compoundPaddingRight
        assertTrue("Text has a positive content width: ${text.text}", usableWidth > 0)
        assertTrue("Full text height: ${text.text}", layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
        assertEquals("The final text character remains laid out", text.text.length, layout.getLineEnd(layout.lineCount - 1))
        for (line in 0 until layout.lineCount) {
            assertEquals(0, layout.getEllipsisCount(line))
            assertTrue("Line fits available width: ${text.text}", layout.getLineMax(line) <= usableWidth + 1)
        }
        if (text is Button) {
            assertTrue("48dp target height", text.height >= dp(text.context, 48))
            assertTrue("48dp target width", text.width >= dp(text.context, 48))
        }
    }

    private fun insideStrip(view: View): Boolean {
        var parent = view.parent
        while (parent is View) { if (parent is HorizontalScrollView) return true; parent = parent.parent }
        return false
    }
    private fun layout(view: View, widthDp: Int) {
        val width = dp(view.context, widthDp)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, width, view.measuredHeight)
    }
    private fun assertSelectedVisible(view: StudioScreenView) {
        val strip = strip(view)
        val selected = buttons(strip).single { it.isSelected }
        assertTrue("Selected filter begins in viewport", selected.left >= strip.scrollX)
        assertTrue("Selected filter ends in viewport: ${selected.right} <= ${strip.scrollX + strip.width}",
            selected.right <= strip.scrollX + strip.width)
    }
    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun strip(view: View) = views(view).filterIsInstance<HorizontalScrollView>().single()
    private fun buttons(view: View) = views(view).filterIsInstance<Button>()
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}

