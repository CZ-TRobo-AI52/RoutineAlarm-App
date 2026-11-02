package com.cztr.routinealarm.ui

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cztr.routinealarm.ui.design.R52Colors
import com.cztr.routinealarm.ui.plan.*
import com.cztr.routinealarm.ui.shell.AppDestination
import com.cztr.routinealarm.ui.shell.AppShellView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.time.DayOfWeek

/** Fixed 62dp time columns wrap HH:mm at 200% and collide with the neighbouring title. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31], qualifiers = "w320dp-h480dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PlanTimeLabelReadabilityTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Before fun largeText() { RuntimeEnvironment.setFontScale(2f) }
    @After fun restoreText() { RuntimeEnvironment.setFontScale(1f) }

    @Test fun completeTimesStaySeparatedFromTitlesInLightPalette() = check(R52Colors.Light)
    @Test fun completeTimesStaySeparatedFromTitlesInDarkPalette() = check(R52Colors.Dark)

    private fun check(colors: R52Colors) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val fixtures = listOf(
                Triple("00:00", 0, "Persoenliche Abendroutine mit ruhiger Vorbereitung"),
                Triple("08:08", 488, "Morgenroutine mit Bewegung und anschliessender Erholung"),
                Triple("11:00", 660, "Bewegungspause mit einer ausfuehrlichen Anleitung"),
                Triple("23:59", 1439, "Spaetes Training mit abschliessender Entspannung"),
            )
            val items = fixtures.mapIndexed { i, f -> PlanProjectedItem("item-" + i, f.third,
                f.second, setOf(DayOfWeek.MONDAY), true, if (i < 2) "Persoenlich" else "Training") }
            val state = PlanUiMapper.map(PlanUiInput(false, true, 4, DayOfWeek.MONDAY,
                items.take(2), items.drop(2), true))
            val plan = PlanScreenView(context, state, colors,
                { fail("Layout must not enable the plan") },
                { fail("Layout must not select another day") },
                { fail("Layout must not toggle training") })
            val padded = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(24), dp(20), dp(24))
                addView(plan)
            }
            val shell = AppShellView(context, AppDestination.PLAN, colors, padded) {
                fail("Layout must not navigate")
            }
            controller.get().setContentView(shell)
            val root = shell.rootView
            root.measure(View.MeasureSpec.makeMeasureSpec(dp(320), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(480), View.MeasureSpec.EXACTLY))
            root.layout(0, 0, dp(320), dp(480))
            assertTrue(shell.isAttachedToWindow)
            assertEquals(dp(320), shell.width)
            assertEquals(dp(280), plan.width)
            val scroll = views(shell).filterIsInstance<ScrollView>().single()
            assertTrue(plan.height > scroll.height)
            val problems = mutableListOf<String>()
            for (fixture in fixtures) {
                val texts = views(plan).filterIsInstance<TextView>()
                val time = texts.single { it.text.toString() == fixture.first }
                val title = texts.single { it.text.toString() == fixture.third }
                val timeBounds = Rect(0, 0, time.width, time.height)
                val titleBounds = Rect(0, 0, title.width, title.height)
                plan.offsetDescendantRectToMyCoords(time, timeBounds)
                plan.offsetDescendantRectToMyCoords(title, titleBounds)
                val target = Rect(0, 0, time.width, time.height)
                scroll.offsetDescendantRectToMyCoords(time, target)
                scroll.scrollTo(0, target.top)
                val visible = Rect()
                assertTrue("Time must be reachable through the finite shell", time.getLocalVisibleRect(visible))
                assertEquals(time.width, visible.width())
                assertEquals(time.height, visible.height())
                val layout = requireNotNull(time.layout)
                val usable = time.width - time.compoundPaddingLeft - time.compoundPaddingRight
                if (layout.lineCount != 1) problems += fixture.first + " wraps to " + layout.lineCount + " lines"
                if (layout.getLineEnd(0) != fixture.first.length || layout.getEllipsisCount(0) != 0)
                    problems += fixture.first + " is not complete on its first line"
                if (layout.getLineMax(0) > usable + 1) problems += fixture.first + " exceeds its text width"
                if (titleBounds.left - timeBounds.right < dp(8))
                    problems += fixture.first + " has less than 8dp separation from its title"
                if (timeBounds.left < 0 || titleBounds.right > plan.width || title.width <= 0)
                    problems += fixture.first + " loses available title space"
                val titleLayout = requireNotNull(title.layout)
                assertEquals("Long neighbour title must remain complete", fixture.third.length,
                    titleLayout.getLineEnd(titleLayout.lineCount - 1))
                assertTrue(titleLayout.height <= title.height - title.compoundPaddingTop - title.compoundPaddingBottom)
                for (line in 0 until titleLayout.lineCount) {
                    assertEquals(0, titleLayout.getEllipsisCount(line))
                    assertTrue(titleLayout.getLineMax(line) <= title.width - title.compoundPaddingLeft - title.compoundPaddingRight + 1)
                }
            }
            assertTrue(problems.joinToString("\n"), problems.isEmpty())
        } finally { controller.pause().stop().destroy() }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
