package com.cztr.routinealarm

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import com.cztr.routinealarm.occurrence.SqliteOccurrenceRepository
import com.cztr.routinealarm.support.awaitUiReadersStopped
import com.cztr.routinealarm.support.onRoutineStorage
import com.cztr.routinealarm.ui.components.R52BottomNav
import com.cztr.routinealarm.ui.home.HomeScreenView
import com.cztr.routinealarm.ui.plan.PlanScreenView
import com.cztr.routinealarm.ui.studio.StudioEditorView
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowChoreographer
import java.time.Clock
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Catches lost stable IDs, implicit activation, stale projections and reopen-only loss.
 * Real host callbacks and SQLite; no repository seeding or direct activation.
 * Content/lifecycle proof only: no attached-window, IME, physical touch or device claim.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class StudioRoutineJourneyTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun preparePlatform() {
        ShadowChoreographer.setPaused(true)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun labels(a: MainActivity) = views(a.window.decorView)
        .filterIsInstance<TextView>().map { it.text.toString() }
    private fun button(a: MainActivity, tag: String) = views(a.window.decorView)
        .filterIsInstance<Button>().firstOrNull { it.tag == tag }
    private fun waitFor(message: String, check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!check() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).runOneTask()
            Thread.sleep(5)
        }
        assertTrue(message, check())
    }
    private fun start() = Robolectric.buildActivity(MainActivity::class.java)
        .create().start().postCreate(null).resume().topActivityResumed(true)
    private fun close(c: ActivityController<MainActivity>) {
        c.pause().stop().destroy()
        awaitUiReadersStopped(c.get())
    }
    private fun nav(a: MainActivity, label: String) {
        val bar = views(a.window.decorView).filterIsInstance<R52BottomNav>().single()
        assertTrue(views(bar).filterIsInstance<Button>().single { it.text == label }.performClick())
    }
    private fun studio(a: MainActivity) {
        nav(a, "Studio")
        waitFor("Studio catalog must finish loading") { button(a, "studio_create") != null }
    }
    private fun detail(a: MainActivity, title: String) {
        studio(a)
        val row = views(a.window.decorView).filterIsInstance<Button>()
            .single { it.tag == "studio_routine_item" && it.text.contains(title) }
        assertTrue(row.performClick())
        waitFor("Selected routine must open") { button(a, "studio_edit") != null }
    }
    private fun editor(a: MainActivity) {
        waitFor("Editor must open from actual host action") {
            views(a.window.decorView).any { it is StudioEditorView }
        }
    }
    private fun field(a: MainActivity, tag: String) = views(a.window.decorView)
        .filterIsInstance<EditText>().single { it.tag == tag }
    private fun records() = onRoutineStorage {
        SqliteOccurrenceRepository(app, Clock.systemDefaultZone()).use { it.listPersonalRoutines() }
    }
    private fun plan(a: MainActivity, title: String) {
        nav(a, "Plan")
        waitFor("Plan must contain the same persisted routine") {
            views(a.window.decorView).any { it is PlanScreenView } && labels(a).contains(title)
        }
        assertEquals("No duplicate row", 1, labels(a).count { it == title })
    }
    private fun home(a: MainActivity) {
        nav(a, "Heute")
        waitFor("Home catalog must finish loading") {
            views(a.window.decorView).any { it is HomeScreenView }
        }
    }

    private fun homeProjection(message: String, a: MainActivity, title: String, due: ZonedDateTime) {
        try {
            waitFor(message) { labels(a).contains(title) }
        } catch (failure: AssertionError) {
            assertTrue("Wall-clock fixture expired; no product failure inferred", ZonedDateTime.now().isBefore(due))
            throw failure
        }
    }

    @Test fun sameUiCreatedRoutineSurvivesEditActivationReopenAndAllThreeSurfaces() {
        assertTrue("No seeded personal routine", records().isEmpty())
        assertFalse("Global schedule starts paused", ScheduleState.isEnabled(app))
        val initialTitle = "C1 I3 Eigene Bewegung"
        val editedTitle = "C1 I3 Eigene Bewegung neu"
        val speech = "Jetzt eine kurze Bewegungspause"
        // The real host uses wall time. Keep a five-minute fixture margin and
        // verify that no default schedule can displace this item from Home's five slots.
        val fixtureNow = ZonedDateTime.now()
        val due = fixtureNow.plusMinutes(5).withSecond(0).withNano(0)
        val existing = onRoutineStorage { ScheduleRegistry.load(app).scheduledEvents }
        val preceding = com.cztr.routinealarm.ui.home.HomeProjectionSource.from(
            fixtureNow, true, true, existing).projected.count { !it.projectedAt.isAfter(due) }
        assertTrue("Time fixture must leave a Home slot for this routine", preceding < 5)
        var c = start()
        try {
            studio(c.get())
            assertTrue(button(c.get(), "studio_create")!!.performClick())
            editor(c.get())
            field(c.get(), "title").setText(initialTitle)
            field(c.get(), "speech").setText(speech)
            field(c.get(), "hour").setText(due.hour.toString())
            field(c.get(), "minute").setText(due.minute.toString())
            views(c.get().window.decorView).filterIsInstance<CheckBox>()
                .filter { it.tag.toString().startsWith("day:") }.forEach { it.isChecked = true }
            assertTrue(button(c.get(), "studio_save")!!.performClick())
            waitFor("Create must persist exactly once and leave editor") {
                records().size == 1 && button(c.get(), "studio_create") != null
            }
            val first = records().single()
            assertEquals(1L, first.version)
            assertEquals(initialTitle, first.event.title)
            assertEquals(speech, first.event.spokenText)
            assertEquals(DayOfWeek.entries.toSet(), first.event.daysOfWeek)
            assertEquals(due.hour, first.event.hour)
            assertEquals(due.minute, first.event.minute)
            assertFalse(first.event.enabled)
            plan(c.get(), initialTitle)
            assertEquals(first, records().single())
            close(c); c = start()

            detail(c.get(), initialTitle)
            assertTrue(button(c.get(), "studio_edit")!!.performClick())
            editor(c.get())
            field(c.get(), "title").setText(editedTitle)
            assertTrue(button(c.get(), "studio_save")!!.performClick())
            waitFor("Edit must preserve one record and advance its version") {
                records().size == 1 && records().single().version == 2L &&
                    button(c.get(), "studio_create") != null
            }
            val edited = records().single()
            assertEquals(first.event.id, edited.event.id)
            assertEquals(editedTitle, edited.event.title)
            assertEquals(speech, edited.event.spokenText)
            assertFalse(edited.event.enabled)
            assertFalse(ScheduleState.isEnabled(app))
            plan(c.get(), editedTitle)
            assertFalse("Old title must disappear", labels(c.get()).contains(initialTitle))

            detail(c.get(), editedTitle)
            assertTrue(button(c.get(), "studio_activate")!!.performClick())
            waitFor("Explicit activation must persist and render its status") {
                records().single().version == 3L && button(c.get(), "studio_edit") != null &&
                    button(c.get(), "studio_activate") == null &&
                    labels(c.get()).any { it.contains("Aktiviert") }
            }
            val active = records().single()
            assertEquals(first.event.id, active.event.id)
            assertTrue(active.event.enabled)
            assertFalse("Routine activation must not enable the weekly plan", ScheduleState.isEnabled(app))
            assertTrue(labels(c.get()).any { it.contains("Aktiviert") })
            home(c.get())
            assertFalse("No active preview while global schedule paused", labels(c.get()).contains(editedTitle))

            plan(c.get(), editedTitle)
            val weekly = button(c.get(), "plan_primary_action")!!
            assertFalse(weekly.isSelected)
            assertTrue("Separate explicit weekly-plan action", weekly.performClick())
            waitFor("Weekly activation must finish") { ScheduleState.isEnabled(app) }
            home(c.get())
            homeProjection("Home must project the UI-created and UI-activated routine", c.get(), editedTitle, due)
            assertEquals(active, records().single())
            close(c); c = start()

            homeProjection("Normal Activity reopen must retain the same Home projection", c.get(), editedTitle, due)
            plan(c.get(), editedTitle)
            assertFalse(labels(c.get()).contains(initialTitle))
            detail(c.get(), editedTitle)
            assertNull(button(c.get(), "studio_activate"))
            assertTrue(labels(c.get()).any { it.contains("Aktiviert") })
            assertEquals("All navigation and reopen are read-only", active, records().single())
            assertEquals(1, records().size)
            assertTrue(ScheduleState.isEnabled(app))
            assertTrue("Wall-clock fixture expired; no product failure inferred", ZonedDateTime.now().isBefore(due))
        } finally { close(c) }
    }
}

