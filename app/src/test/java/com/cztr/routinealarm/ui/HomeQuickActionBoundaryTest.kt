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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cztr.routinealarm.MainActivity
import com.cztr.routinealarm.ScheduleState
import com.cztr.routinealarm.support.awaitUi
import com.cztr.routinealarm.support.awaitUiReadersStopped
import com.cztr.routinealarm.ui.design.R52Colors
import com.cztr.routinealarm.ui.home.*
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Duration
import java.time.ZonedDateTime

/** Bounded production shell and real touch dispatch, not direct performClick or a device result. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31], qualifiers = "w320dp-h480dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class HomeQuickActionBoundaryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val now = ZonedDateTime.parse("2026-10-30T09:00:00+01:00[Europe/Berlin]")

    @Before fun prepare() {
        RuntimeEnvironment.setFontScale(2f)
        context.deleteDatabase("occurrences.db")
        ScheduleState.setEnabled(context, false)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }
    @After fun restoreFontScale() { RuntimeEnvironment.setFontScale(1f) }

    @Test fun previewActionsAreTouchReachablePastFiveLongPreviews() = checkState(HomePlanState.PREVIEW)
    @Test fun pausedActionsAreTouchReachableWithoutPreviewPromises() = checkState(HomePlanState.PAUSED)
    @Test fun emptyActionsAreTouchReachableWithoutInventingRoutines() = checkState(HomePlanState.EMPTY)
    @Test fun permissionActionsRemainTouchReachableAsPreviewOnly() = checkState(HomePlanState.PERMISSION_REQUIRED)

    @Test fun realHostRefreshFromScrolledHomeRebuildsWithoutScheduling() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().visible()
        val activity = controller.get()
        try {
            awaitUi(activity)
            val shell = views(activity.window.decorView).filterIsInstance<AppShellView>().single()
            layout(shell)
            val before = views(shell).filterIsInstance<HomeScreenView>().single()
            val refresh = buttons(before).single { it.tag == "home_quick_action:REFRESH" }
            val scroll = scroll(shell)
            reveal(shell, refresh)
            assertTrue("The refresh action requires real vertical scrolling", scroll.scrollY > 0)
            val alarms = shadowOf(activity.getSystemService(AlarmManager::class.java))
            val scheduledBefore = alarms.scheduledAlarms.toList()
            tap(shell, refresh)
            awaitUi(activity)
            val rebuilt = views(activity.window.decorView).filterIsInstance<AppShellView>().single()
            assertNotSame("Touch must reach the actual host refresh callback", shell, rebuilt)
            layout(rebuilt)
            assertNotSame(before, views(rebuilt).filterIsInstance<HomeScreenView>().single())
            assertFalse(ScheduleState.isEnabled(activity))
            assertEquals(scheduledBefore, alarms.scheduledAlarms.toList())
            assertNull(shadowOf(activity).nextStartedActivity)
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        } finally { controller.destroy(); awaitUiReadersStopped(activity) }
    }

    private fun checkState(planState: HomePlanState) {
        for (colors in listOf(R52Colors.Dark, R52Colors.Light)) {
            val controller = Robolectric.buildActivity(Activity::class.java).setup()
            try {
                val input = HomeUiInput(now, planState != HomePlanState.PAUSED,
                    planState != HomePlanState.PERMISSION_REQUIRED,
                    if (planState == HomePlanState.EMPTY) emptyList() else (1..9).map {
                        HomeProjectedItem("routine-$it",
                            "Routine $it mit ausführlicher Vorbereitung und einer langen persönlichen Bewegungsanleitung",
                            "Training und Erholung", now.plusMinutes(it.toLong()))
                    })
                val state = HomeUiMapper.map(input)
                assertEquals(planState, state.planState)
                val actions = mutableListOf<HomeQuickAction>()
                val home = HomeScreenView(context, state, colors) { actions += it }
                val content = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(24), dp(20), dp(24))
                    addView(home)
                }
                val shell = AppShellView(context, AppDestination.TODAY, colors, content) {
                    fail("Home touch must not leak into bottom navigation")
                }
                controller.get().setContentView(shell)
                layout(shell)
                assertTrue("Touch callbacks need a real attached window", shell.isAttachedToWindow)
                assertEquals("Real shell leaves 280dp Home content", dp(280), home.width)
                assertEquals(if (planState == HomePlanState.PAUSED || planState == HomePlanState.EMPTY) 0 else 5,
                    views(home).count { it.tag == "home_upcoming_item" })
                val snapshot = views(home).filterIsInstance<TextView>().single { it.text.startsWith("Vorschau vom ") }
                val originalSnapshot = snapshot.text.toString()
                assertTrue(originalSnapshot.contains("30.10."))
                assertTrue(originalSnapshot.contains("09:00:00"))
                val scroll = scroll(shell)
                assertTrue("Fixture exceeds a bounded viewport", content.height > scroll.height)
                assertTrue("Layout and scroll must not dispatch actions", actions.isEmpty())
                val expected = mapOf(
                    "home_quick_action:OPEN_PLAN" to HomeQuickAction.OPEN_PLAN,
                    "home_quick_action:REFRESH" to HomeQuickAction.REFRESH,
                    "home_quick_action:OPEN_TEST" to HomeQuickAction.OPEN_TEST,
                    "home_quick_action:OPEN_TRAINING" to HomeQuickAction.OPEN_TRAINING,
                    "home_quick_action:OPEN_BACKUP" to HomeQuickAction.OPEN_BACKUP,
                    "home_quick_action:OPEN_HISTORY" to HomeQuickAction.OPEN_HISTORY,
                    "home_quick_action:OPEN_SYSTEM" to HomeQuickAction.OPEN_SYSTEM,
                )
                val enabled = buttons(home).filter { it.isEnabled }
                assertEquals(if (planState == HomePlanState.PERMISSION_REQUIRED) 8 else 7, enabled.size)
                dragUpAcross(shell, enabled.first())
                assertTrue("Dragging across a real action must scroll without invoking it", actions.isEmpty())
                val tagged = enabled.mapNotNull { it.tag as? String }.toSet()
                assertEquals(expected.keys - if (planState == HomePlanState.PERMISSION_REQUIRED) emptySet() else setOf("home_quick_action:OPEN_SYSTEM"), tagged)
                for (button in buttons(home)) {
                    val countBefore = actions.size
                    reveal(shell, button)
                    assertEquals("Scrolling alone must not trigger a callback", countBefore, actions.size)
                    tap(shell, button)
                    if (!button.isEnabled) {
                        assertEquals("Disabled editor touch has no action", countBefore, actions.size)
                    } else {
                        val action = expected[button.tag] ?: run {
                            assertEquals("The existing untagged show-all action", "Alle anzeigen", button.text.toString())
                            HomeQuickAction.OPEN_PLAN
                        }
                        assertEquals("One callback per physical touch: ${button.text}", countBefore + 1, actions.size)
                        assertEquals("Correct typed action for ${button.text}", action, actions.last())
                    }
                    assertEquals("Scroll and selection do not silently change the observation time",
                        originalSnapshot, snapshot.text.toString())
                }
                val editor = buttons(home).single { !it.isEnabled }
                assertTrue(editor.text.contains("nicht verfügbar"))
                assertTrue("Bottom actions were reached through scrolling", scroll.scrollY > 0)
            } finally { controller.pause().stop().destroy() }
        }
    }

    private fun reveal(shell: AppShellView, target: Button) {
        val scroll = scroll(shell)
        val position = Rect(0, 0, target.width, target.height)
        scroll.offsetDescendantRectToMyCoords(target, position)
        // Coordinates in the ScrollView itself are already content coordinates.
        scroll.scrollTo(0, position.top)
        val visible = Rect()
        assertTrue("Reachable after scrolling: ${target.text}", target.getLocalVisibleRect(visible))
        assertEquals("Whole button width visible", target.width, visible.width())
        assertEquals("Whole button height visible", target.height, visible.height())
        assertTrue("48dp visible touch width", visible.width() >= dp(48))
        assertTrue("48dp visible touch height", visible.height() >= dp(48))
    }

    private fun tap(shell: AppShellView, target: Button) {
        val bounds = Rect(0, 0, target.width, target.height)
        shell.offsetDescendantRectToMyCoords(target, bounds)
        assertTrue("Touch inside bounded shell", bounds.centerY() in 0 until shell.height)
        val start = SystemClock.uptimeMillis()
        for ((action, delay) in listOf(MotionEvent.ACTION_DOWN to 0L, MotionEvent.ACTION_UP to 20L)) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(delay))
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try { shell.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun dragUpAcross(shell: AppShellView, target: Button) {
        reveal(shell, target)
        val scroll = scroll(shell)
        val before = scroll.scrollY
        val bounds = Rect(0, 0, target.width, target.height)
        shell.offsetDescendantRectToMyCoords(target, bounds)
        val distance = (bounds.centerY() - scroll.top - dp(8)).coerceAtMost(dp(120))
        assertTrue("Swipe exceeds touch slop inside the scroll viewport", distance >= dp(16))
        val start = SystemClock.uptimeMillis()
        var previousElapsed = 0L
        for (step in 0..7) {
            val action = when (step) {
                0 -> MotionEvent.ACTION_DOWN
                7 -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            // Pause before release to avoid a fling affecting the subsequent independent taps.
            val elapsed = if (step == 7) 600L else step * 20L
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(elapsed - previousElapsed))
            previousElapsed = elapsed
            val y = bounds.exactCenterY() - distance * step.coerceAtMost(6) / 6f
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), y, 0)
            try { shell.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertTrue("The production ScrollView must consume a real upward swipe", scroll.scrollY > before)
    }

    private fun layout(view: View) {
        val w = dp(320); val h = dp(480)
        val root = view.rootView
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun scroll(view: View) = views(view).filterIsInstance<ScrollView>().single()
    private fun buttons(view: View) = views(view).filterIsInstance<Button>()
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
