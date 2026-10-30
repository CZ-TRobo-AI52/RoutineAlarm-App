package com.cztr.routinealarm.ui

import android.app.AlarmManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.cztr.routinealarm.MainActivity
import com.cztr.routinealarm.ScheduleState
import com.cztr.routinealarm.backup.BackupActivity
import com.cztr.routinealarm.support.awaitUi
import com.cztr.routinealarm.support.awaitUiReadersStopped
import com.cztr.routinealarm.ui.design.R52Colors
import com.cztr.routinealarm.ui.home.HomeProjectedItem
import com.cztr.routinealarm.ui.home.HomeScreenView
import com.cztr.routinealarm.ui.home.HomeUiInput
import com.cztr.routinealarm.ui.home.HomeUiMapper
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
import java.time.ZonedDateTime

/**
 * C1 f881744 behavior required on the unchanged C6 1001e62 source.
 * Missing C1 actions are intentional convergence REDs, never permission to repair production.
 * Scope: create-only Activity navigation and measured Home component, not device/onResume recovery.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class HomeOwnerBoundaryConvergenceTest {
    @Before fun prepare() {
        val app = RuntimeEnvironment.getApplication() as Context
        app.deleteDatabase("occurrences.db")
        ScheduleState.setEnabled(app, false)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    // Controls exercise actual C6 semantic tags, so a renamed tab label is not a false RED.
    @Test fun existingPlanActionOnlyNavigatesWithoutSchedulingOrOutput() = withHome { activity ->
        navigateWithoutEffects(activity, action(home(activity), "OPEN_PLAN", "plan")) {
            assertSelectedDestination(activity, "Plan öffnen")
            assertNull(shadowOf(activity).nextStartedActivity)
        }
    }

    @Test fun existingSystemActionOnlyNavigatesWithoutSchedulingOrOutput() {
        // OPEN_SYSTEM is offered only when an enabled plan lacks exact-alarm permission.
        ScheduleState.setEnabled(RuntimeEnvironment.getApplication(), true)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        withHome { activity ->
            navigateWithoutEffects(activity, action(home(activity), "OPEN_SYSTEM", "system")) {
                assertSelectedDestination(activity, "Mehr öffnen")
                assertNull(shadowOf(activity).nextStartedActivity)
            }
        }
    }

    @Test fun testSetupShortcutDoesNotEnqueueAnAlarmOnNavigation() = withHome { activity ->
        navigateWithoutEffects(activity, action(home(activity), "OPEN_TEST", "testwecker", "testalarm")) {
            assertSelectedDestination(activity, "Mehr öffnen")
            val setup = views(activity.window.decorView).filterIsInstance<Button>().any {
                it.text.toString().contains("TESTWECKER IN 1 MINUTE", ignoreCase = true)
            }
            assertTrue("Navigation must expose the existing test setup before any scheduling", setup)
            assertNull(shadowOf(activity).nextStartedActivity)
        }
    }

    @Test fun trainingShortcutOpensPlanWithoutStartingTraining() = withHome { activity ->
        navigateWithoutEffects(activity, action(home(activity), "OPEN_TRAINING", "training")) {
            assertSelectedDestination(activity, "Plan öffnen")
            assertNull(shadowOf(activity).nextStartedActivity)
        }
    }

    @Test fun backupShortcutOpensExistingBackupWithoutActivatingPlan() = withHome { activity ->
        navigateWithoutEffects(activity, action(home(activity), "OPEN_BACKUP", "backup", "sicherung")) {
            val intent = shadowOf(activity).nextStartedActivity
            assertNotNull("Home must open the existing BackupActivity", intent)
            assertEquals(BackupActivity::class.java.name, intent!!.component!!.className)
        }
    }

    @Test fun unavailableRoutineEditorIsVisibleDisabledAndCannotCauseEffects() = withHome { activity ->
        val editor = action(home(activity), "CREATE_ROUTINE", "neue routine", "routine erstellen")
        assertFalse("An unavailable routine editor must not offer an enabled action", editor.isEnabled)
        val words = "${editor.text} ${editor.contentDescription}".lowercase()
        assertTrue("Unavailable editor must explain its unavailability",
            words.contains("nicht verfügbar") || words.contains("unavailable"))
        // A disabled Android control is not invoked programmatically: performClick bypasses hit gating.
        assertNoOutput()
        assertFalse(ScheduleState.isEnabled(activity))
    }

    @Test fun homeProjectionExposesAnExplicitObservationTimestamp() = withHome { activity ->
        val labels = views(home(activity)).filterIsInstance<TextView>().map { it.text.toString() }
        val time = Regex("\\b\\d{2}:\\d{2}(?::\\d{2})?\\b")
        assertTrue("Home projection needs an explicit observation time, not an event's future time: $labels",
            labels.any {
                (it.contains("Vorschau vom", ignoreCase = true) ||
                    it.contains("Momentaufnahme", ignoreCase = true)) && time.containsMatchIn(it)
            })
    }

    @Test fun explicitRefreshReprojectsChangedInputWithoutSchedulingOrOutput() = withHome { activity ->
        assertTrue(views(home(activity)).none { it.tag == "home_upcoming_item" })
        val refresh = action(home(activity), "REFRESH", "aktualis", "refresh")
        assertTrue("Explicit refresh must be available", refresh.isEnabled)
        // create-only Activity has no onStart observer; the explicit action must reread persisted input.
        ScheduleState.setEnabled(activity, true)
        val alarms = shadowOf(activity.getSystemService(AlarmManager::class.java))
        val before = alarms.scheduledAlarms.toList()
        refresh.performClick()
        awaitUi(activity)
        assertTrue("Refresh must render newly enabled plan projections",
            views(home(activity)).any { it.tag == "home_upcoming_item" })
        assertTrue("Refresh must preserve user state", ScheduleState.isEnabled(activity))
        assertEquals("Refreshing is not scheduling", before, alarms.scheduledAlarms.toList())
        assertNoOutput()
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun darkHomeLongTextAndReachableControlsFitAtTwoHundredPercent() =
        assertMeasuredHome(R52Colors.Dark)

    @Test fun lightHomeLongTextAndReachableControlsFitAtTwoHundredPercent() =
        assertMeasuredHome(R52Colors.Light)

    private fun assertMeasuredHome(colors: R52Colors) {
        RuntimeEnvironment.setFontScale(2f)
        try {
            val context = RuntimeEnvironment.getApplication() as Context
            assertEquals(2f, context.resources.configuration.fontScale, 0.01f)
            val now = ZonedDateTime.parse("2026-10-30T09:00:00+01:00[Europe/Berlin]")
            val longTitle = "Eine sehr lange persönliche Routine mit ausreichend Zeit für Frühstück und Vorbereitung"
            val longInput = HomeUiInput(
                now = now, planEnabled = true, exactAllowed = true,
                projected = listOf(HomeProjectedItem("long", longTitle, "Morgen", now.plusHours(1))),
            )
            val problems = mutableListOf<String>()
            for (input in listOf(longInput, longInput.copy(planEnabled = false),
                longInput.copy(exactAllowed = false), longInput.copy(projected = emptyList()))) {
                val view = HomeScreenView(context, HomeUiMapper.map(input), colors) { }
                val density = context.resources.displayMetrics.density
                val width = (280 * density).toInt()
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                view.layout(0, 0, width, view.measuredHeight)
                val textViews = views(view).filterIsInstance<TextView>()
                if (input.planEnabled && input.projected.isNotEmpty())
                    assertTrue("Fixture must reach the real Home projection", textViews.any { it.text.toString() == longTitle })
                val buttons = textViews.filterIsInstance<Button>()
                assertTrue("Measure actual reachable actions, not an empty layout", buttons.isNotEmpty())
                // Preserve C1's control-count assertion while still measuring every existing control.
                if (buttons.size < 7) problems += "Missing C1 Home controls: ${buttons.size}<7"
                for (text in textViews) {
                    if (text.visibility != View.VISIBLE) continue
                    val layout = text.layout
                    if (layout == null) {
                        problems += "Unmeasured: ${text.text}"
                        continue
                    }
                    val contentWidth = text.width - text.compoundPaddingLeft - text.compoundPaddingRight
                    val contentHeight = text.height - text.compoundPaddingTop - text.compoundPaddingBottom
                    if (layout.height > contentHeight)
                        problems += "Clipped height ${layout.height}>$contentHeight: ${text.text}"
                    for (line in 0 until layout.lineCount) {
                        if (layout.getEllipsisCount(line) != 0)
                            problems += "Ellipsis line$line: ${text.text}"
                        if (layout.getLineWidth(line) > contentWidth + 1f)
                            problems += "Clipped width ${layout.getLineWidth(line)}>$contentWidth: ${text.text}"
                    }
                    if ((text is Button || (text.isEnabled && text.isClickable)) &&
                        (text.height < 48 * density || text.width < 48 * density))
                        problems += "Touch target ${text.width}x${text.height} below48dp: ${text.text}"
                }
            }
            assertTrue(problems.joinToString("\n"), problems.isEmpty())
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    private fun navigateWithoutEffects(activity: MainActivity, button: Button, verifyDestination: () -> Unit) {
        assertTrue("Navigation must be reachable: ${button.text}", button.isEnabled)
        val alarms = shadowOf(activity.getSystemService(AlarmManager::class.java))
        val before = alarms.scheduledAlarms.toList()
        assertNoOutput()
        val enabledBefore = ScheduleState.isEnabled(activity)
        button.performClick()
        awaitUi(activity)
        verifyDestination()
        assertEquals("Navigation must preserve weekly scheduling state", enabledBefore, ScheduleState.isEnabled(activity))
        assertEquals("Navigation must not enqueue or cancel alarms", before, alarms.scheduledAlarms.toList())
        assertNoOutput()
    }

    private fun assertNoOutput() {
        assertNull("Navigation must not launch training/alarm output",
            shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }

    private fun assertSelectedDestination(activity: MainActivity, description: String) {
        val selected = views(activity.window.decorView).filter { it.contentDescription == description }
        assertEquals("Semantic destination must be present: $description", 1, selected.size)
        assertTrue("Wrong destination: $description", selected.single().isSelected)
    }

    private fun home(activity: MainActivity): HomeScreenView =
        views(activity.window.decorView).filterIsInstance<HomeScreenView>().single()

    private fun action(root: HomeScreenView, key: String, vararg concepts: String): Button {
        val buttons = views(root).filterIsInstance<Button>()
        val tagged = buttons.firstOrNull { it.tag == "home_quick_action:$key" }
        if (tagged != null) return tagged
        // C1 has no tags for its new actions. Match user-facing concepts, not obsolete full labels.
        val matches = buttons.filter { button ->
            val semantic = "${button.text} ${button.contentDescription}".lowercase()
            concepts.any { semantic.contains(it.lowercase()) }
        }
        assertTrue("Missing Home action $key; actual actions: ${buttons.map { it.text }}", matches.isNotEmpty())
        assertEquals("Ambiguous Home action $key", 1, matches.size)
        return matches.single()
    }

    private fun withHome(check: (MainActivity) -> Unit) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try { awaitUi(controller.get()); check(controller.get()) }
        finally { controller.destroy(); awaitUiReadersStopped(controller.get()) }
    }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
