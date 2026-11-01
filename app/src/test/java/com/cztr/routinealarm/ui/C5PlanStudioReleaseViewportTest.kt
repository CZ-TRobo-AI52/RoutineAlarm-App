package com.cztr.routinealarm.ui

import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cztr.routinealarm.MainActivity
import com.cztr.routinealarm.ScheduleRegistry
import com.cztr.routinealarm.ScheduleState
import com.cztr.routinealarm.support.awaitUi
import com.cztr.routinealarm.support.awaitUiReadersStopped
import com.cztr.routinealarm.ui.design.R52Colors
import com.cztr.routinealarm.ui.plan.*
import com.cztr.routinealarm.ui.shell.*
import com.cztr.routinealarm.ui.studio.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowAlarmManager
import java.time.DayOfWeek
import java.time.Duration

/** Catches shell clipping/overlap or wrong callbacks that unbounded-height tests cannot detect. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31], qualifiers = "w320dp-h480dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class C5PlanStudioReleaseViewportTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun prepare() {
        RuntimeEnvironment.setFontScale(2f)
        context.deleteDatabase("occurrences.db")
        ScheduleState.setEnabled(context, false)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After fun restoreFontScale() { RuntimeEnvironment.setFontScale(1f) }

    @Test fun planCriticalControlsRemainTouchReachableInEveryAvailabilityAndPalette() {
        for (colors in listOf(R52Colors.Light, R52Colors.Dark)) {
            for ((enabled, exact) in listOf(false to true, true to true, true to false)) {
                val actions = mutableListOf<String>()
                val items = (1..8).map { PlanProjectedItem("routine-$it",
                    "Routine $it mit Vorbereitung und anschließender Erholung", 420 + it,
                    setOf(DayOfWeek.MONDAY), true, "Persönlich") }
                val state = PlanUiMapper.map(PlanUiInput(enabled, exact, 8, DayOfWeek.MONDAY,
                    items, listOf(PlanProjectedItem("training", "Ruhig bewegen", 1080,
                        DayOfWeek.entries.toSet(), true, "Training")), false))
                val plan = PlanScreenView(context, state, colors,
                    { actions += "primary" }, { actions += it.name }, { actions += "training" })
                inShell(plan, AppDestination.PLAN, colors) { shell ->
                    assertTrue(actions.isEmpty())
                    assertTrue("Content must exceed the finite viewport", plan.height > scroll(shell).height)
                    val primary = buttons(plan).single { it.tag == "plan_primary_action" }
                    revealAndTap(shell, primary)
                    assertEquals(listOf("primary"), actions)
                    val days = buttons(plan).filter { it.contentDescription.toString().contains("auswählen") }
                    assertEquals(7, days.size)
                    for ((index, button) in days.withIndex()) {
                        val before = actions.toList()
                        reveal(shell, button)
                        assertEquals("Scrolling does not select a day", before, actions)
                        tap(shell, button)
                        if (index == 0) assertEquals("Selected Monday is not a new action", before, actions)
                        else assertEquals(before + listOf("TUESDAY", "WEDNESDAY", "THURSDAY",
                            "FRIDAY", "SATURDAY", "SUNDAY")[index - 1], actions)
                    }
                    val training = buttons(plan).single { it.text == "Training ansehen" }
                    val before = actions.toList()
                    reveal(shell, training)
                    assertTrue("Training action lies beyond the initial viewport", scroll(shell).scrollY > 0)
                    assertEquals(before, actions)
                    tap(shell, training)
                    assertEquals(before + "training", actions)
                }
            }
        }
    }

    @Test fun studioFiltersAndLastRoutineRemainTouchReachableInBothPalettes() {
        for (colors in listOf(R52Colors.Light, R52Colors.Dark)) {
            val actions = mutableListOf<String>()
            val routines = (1..8).map { StudioProjectedRoutine("routine-$it",
                "Routine $it mit Bewegung und anschließender Erholung", "Ruhig und langsam beginnen.",
                420 + it, setOf(DayOfWeek.MONDAY), it % 2 == 0, "Persönlich", "Routine") }
            val state = StudioUiMapper.map(StudioUiInput(routines, StudioFilter.ALL, "routine-8"))
            val studio = StudioScreenView(context, state, colors,
                { actions += it.name }, { actions += it })
            inShell(studio, AppDestination.STUDIO, colors) { shell ->
                assertTrue(actions.isEmpty())
                assertTrue(studio.height > scroll(shell).height)
                val strip = views(studio).filterIsInstance<HorizontalScrollView>().single()
                for ((label, expected) in listOf("Alle" to null, "Aktiv" to "ACTIVE", "Pausiert" to "PAUSED")) {
                    val before = actions.toList()
                    val button = buttons(strip).single { it.text == label }
                    reveal(shell, button)
                    assertEquals(before, actions)
                    tap(shell, button)
                    assertEquals(if (expected == null) before else before + expected, actions)
                }
                val routineButtons = buttons(studio).filter { it.tag == "studio_routine_item" }
                assertEquals(8, routineButtons.size)
                for ((index, button) in routineButtons.withIndex()) {
                    val before = actions.toList()
                    reveal(shell, button)
                    assertEquals("Scrolling cannot open another routine", before, actions)
                    tap(shell, button)
                    assertEquals(before + "routine-${index + 1}", actions)
                }
                assertTrue(scroll(shell).scrollY > 0)
                assertEquals("Only existing filters and read-only routine actions", 11, buttons(studio).size)
            }
        }
    }

    @Test fun actualPlanNavigationAndDaySelectionRebuildInsideFiniteViewportWithoutScheduling() {
        for (theme in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) {
            withHost(theme) { activity ->
                val scheduled = shadowOf(activity.getSystemService(AlarmManager::class.java)).scheduledAlarms.toList()
                val events = ScheduleRegistry.enabledEvents.toList()
                openDestination(activity, "Plan")
                var shell = shell(activity)
                val before = views(shell).filterIsInstance<PlanScreenView>().single()
                val days = buttons(before).filter { it.contentDescription.toString().contains("auswählen") }
                val chosen = days.first { !it.isSelected }
                val chosenDayLabel = chosen.text.toString()
                revealAndTap(shell, chosen)
                awaitUi(activity)
                shell = shell(activity)
                layout(shell)
                val after = views(shell).filterIsInstance<PlanScreenView>().single()
                assertNotSame("Touch must reach the actual host day callback", before, after)
                val selected = buttons(after).single { it.isSelected && it.contentDescription.toString().contains("auswählen") }
                assertEquals("The touched day remains selected after rebuilding", chosenDayLabel, selected.text.toString())
                reveal(shell, selected)
                val training = buttons(after).single { it.text == "Training ansehen" }
                revealAndTap(shell, training)
                awaitUi(activity)
                val expandedShell = shell(activity)
                layout(expandedShell)
                val collapse = buttons(expandedShell).single { it.text == "Training einklappen" }
                reveal(expandedShell, collapse)
                assertFalse(ScheduleState.isEnabled(activity))
                assertEquals(events, ScheduleRegistry.enabledEvents.toList())
                assertEquals(scheduled, shadowOf(activity.getSystemService(AlarmManager::class.java)).scheduledAlarms.toList())
                assertNoLaunch(activity)
            }
        }
    }

    @Test fun actualStudioNavigationFilterAndDetailStayReadOnlyInsideFiniteViewport() {
        for (theme in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) {
            withHost(theme) { activity ->
                val scheduled = shadowOf(activity.getSystemService(AlarmManager::class.java)).scheduledAlarms.toList()
                val events = ScheduleRegistry.enabledEvents.toList()
                openDestination(activity, "Studio")
                var shell = shell(activity)
                val before = views(shell).filterIsInstance<StudioScreenView>().single()
                revealAndTap(shell, buttons(before).single { it.text == "Aktiv" })
                awaitUi(activity)
                shell = shell(activity)
                layout(shell)
                val filtered = views(shell).filterIsInstance<StudioScreenView>().single()
                assertNotSame(before, filtered)
                assertTrue(buttons(filtered).single { it.text == "Aktiv" }.isSelected)
                val routine = buttons(filtered).filter { it.tag == "studio_routine_item" }.last()
                revealAndTap(shell, routine)
                awaitUi(activity)
                shell = shell(activity)
                layout(shell)
                val detail = views(shell).filterIsInstance<StudioScreenView>().single()
                assertNotSame("Routine touch must reach the real host selection", filtered, detail)
                val authority = views(detail).filterIsInstance<TextView>().single { it.text == "Nur ansehen" }
                reveal(shell, authority, requireTouchTarget = false)
                assertTrue("The selected detail is reachable below the routine list", scroll(shell).scrollY > 0)
                assertFalse(ScheduleState.isEnabled(activity))
                assertEquals(events, ScheduleRegistry.enabledEvents.toList())
                assertEquals(scheduled, shadowOf(activity.getSystemService(AlarmManager::class.java)).scheduledAlarms.toList())
                assertNoLaunch(activity)
            }
        }
    }

    private fun withHost(theme: ThemeChoice, check: (MainActivity) -> Unit) {
        ThemePreferenceStore(context).write(theme)
        // create/visible isolates navigation from onResume's separately owned recovery contract.
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().visible()
        val activity = controller.get()
        try {
            awaitUi(activity)
            layout(shell(activity))
            assertEquals(if (theme == ThemeChoice.LIGHT) R52Colors.Light.background else R52Colors.Dark.background,
                activity.window.statusBarColor)
            check(activity)
        } finally { controller.destroy(); awaitUiReadersStopped(activity) }
    }

    private fun assertNoLaunch(activity: Activity) {
        assertNull(shadowOf(activity).nextStartedActivity)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }

    private fun openDestination(activity: MainActivity, label: String) {
        val root = shell(activity)
        val target = buttons(root).single { it.contentDescription.toString().startsWith("$label öffnen") }
        revealAndTap(root, target)
        awaitUi(activity)
        assertNotSame("Bottom navigation must rebuild the actual shell", root, shell(activity))
        layout(shell(activity))
    }

    private fun inShell(content: View, destination: AppDestination, colors: R52Colors, check: (AppShellView) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val padded = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(24), dp(20), dp(24))
                addView(content)
            }
            val shell = AppShellView(context, destination, colors, padded) { fail("Content touch reached bottom navigation") }
            controller.get().setContentView(shell)
            layout(shell)
            assertEquals("Production padding leaves 280dp content", dp(280), content.width)
            check(shell)
        } finally { controller.pause().stop().destroy() }
    }

    private fun layout(shell: AppShellView) {
        val root = shell.rootView
        root.measure(View.MeasureSpec.makeMeasureSpec(dp(320), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(480), View.MeasureSpec.EXACTLY))
        root.layout(0, 0, dp(320), dp(480))
        assertTrue(shell.isAttachedToWindow)
        assertEquals(dp(320), shell.width)
        assertTrue("Shell height is finite", shell.height in dp(1)..dp(480))
        assertTrue("Bottom navigation must leave a usable content viewport", scroll(shell).height >= dp(48))
    }

    private fun revealAndTap(shell: AppShellView, target: Button) { reveal(shell, target); tap(shell, target) }

    private fun reveal(shell: AppShellView, target: View, requireTouchTarget: Boolean = true) {
        var parent = target.parent
        while (parent is ViewGroup && parent !== shell) {
            val bounds = Rect(0, 0, target.width, target.height)
            parent.offsetDescendantRectToMyCoords(target, bounds)
            when (parent) {
                is HorizontalScrollView -> parent.scrollTo(bounds.left, 0)
                is ScrollView -> parent.scrollTo(0, bounds.top)
            }
            parent = parent.parent
        }
        val visible = Rect()
        assertTrue("Control must be reachable inside the real clipped viewport: ${(target as? TextView)?.text}", target.getLocalVisibleRect(visible))
        assertEquals("Full control width visible", target.width, visible.width())
        assertEquals("Full control height visible", target.height, visible.height())
        if (requireTouchTarget) {
            assertTrue("48dp visible target width", visible.width() >= dp(48))
            assertTrue("48dp visible target height", visible.height() >= dp(48))
        }
    }

    private fun tap(shell: AppShellView, target: Button) {
        val bounds = Rect(0, 0, target.width, target.height)
        shell.offsetDescendantRectToMyCoords(target, bounds)
        assertTrue(bounds.centerX() in 0 until shell.width && bounds.centerY() in 0 until shell.height)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            if (action == MotionEvent.ACTION_UP) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try { shell.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun shell(activity: Activity) = views(activity.window.decorView).filterIsInstance<AppShellView>().single()
    private fun scroll(view: View) = views(view).filterIsInstance<ScrollView>().single()
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun buttons(view: View) = views(view).filterIsInstance<Button>()
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
