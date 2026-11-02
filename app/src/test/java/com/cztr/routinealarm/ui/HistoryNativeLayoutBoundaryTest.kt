package com.cztr.routinealarm.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.cztr.routinealarm.TestAlarmStatusPresenter
import com.cztr.routinealarm.occurrence.ActionType
import com.cztr.routinealarm.occurrence.AlarmDelivery
import com.cztr.routinealarm.occurrence.SqliteOccurrenceRepository
import com.cztr.routinealarm.occurrence.TestAlarmCoordinator
import com.cztr.routinealarm.ui.design.R52Colors
import com.cztr.routinealarm.ui.history.HistoryLoadState
import com.cztr.routinealarm.ui.history.HistoryScreenView
import com.cztr.routinealarm.ui.history.HistoryUiInput
import com.cztr.routinealarm.ui.history.HistoryUiMapper
import com.cztr.routinealarm.ui.history.HistoryUiState
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Real measured History views; host-native coverage, not device or audible-delivery proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class HistoryNativeLayoutBoundaryTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private val database = "history-native-layout-boundary.db"
    private val now = Instant.parse("2026-10-30T12:00:00Z")

    @Before fun prepare() {
        RuntimeEnvironment.setFontScale(2f)
        context.deleteDatabase(database)
    }

    @After fun cleanup() {
        context.deleteDatabase(database)
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test fun darkAvailableHistoryKeepsCompleteLabelsAndPermissionRouting() =
        available(R52Colors.Dark)

    @Test fun lightAvailableHistoryKeepsCompleteLabelsAndPermissionRouting() =
        available(R52Colors.Light)

    @Test fun darkLongEventTextRemainsFullyReadableWithoutClaimingDelivery() =
        longEvent(R52Colors.Dark)

    @Test fun lightLongEventTextRemainsFullyReadableWithoutClaimingDelivery() =
        longEvent(R52Colors.Light)

    @Test fun darkEmptyLoadingAndFailedReadsRemainDistinctAndReachable() =
        readStates(R52Colors.Dark)

    @Test fun lightEmptyLoadingAndFailedReadsRemainDistinctAndReachable() =
        readStates(R52Colors.Light)

    @Test fun realJournalProjectionRetainsCountsAndNeverMutatesJournalOnRender() {
        SqliteOccurrenceRepository(context, Clock.fixed(now, ZoneOffset.UTC), database).use { repo ->
            val past = TestAlarmCoordinator(repo, Clock.fixed(now.minusSeconds(120), ZoneOffset.UTC))
            val triggered = past.create(1)
            past.create(1) // One waiting test is overdue at the observation time.
            val current = TestAlarmCoordinator(repo, Clock.fixed(now, ZoneOffset.UTC))
            assertNotNull(current.trigger(AlarmDelivery.from(triggered, repo.deliveryEpoch())))
            current.create(30)
            val before = repo.exportBackup()
            val input = fromJournal(repo)
            assertEquals(3, input.openCount)
            assertEquals(1, input.triggeredCount)
            assertEquals(1, input.overdueCount)
            assertEquals("Nächster gespeicherter Test: heute, 12:30 Uhr", input.nextText)
            for (colors in listOf(R52Colors.Dark, R52Colors.Light)) {
                val state = HistoryUiMapper.map(input)
                val view = screen(state, colors, mutableListOf())
                readable(view)
                assertTrue(labels(view).contains("3 offen · 1 ausgelöst · 1 überfällig\nNächster gespeicherter Test: heute, 12:30 Uhr"))
                assertTrue(labels(view).contains("Ausgelöst bedeutet gespeicherter Triggerzustand und bestätigt keine hörbare Ausgabe oder erledigte Handlung."))
                assertArrayEquals("History projection and layout must not append to or rewrite the journal", before, repo.exportBackup())
            }
        }
    }

    @Test fun completedAndRestorePausedTestsDoNotBecomeOpenOrDeliveredClaims() {
        val bytes = SqliteOccurrenceRepository(context, Clock.fixed(now, ZoneOffset.UTC), database).use { repo ->
            val coordinator = TestAlarmCoordinator(repo, Clock.fixed(now, ZoneOffset.UTC))
            val planned = TestAlarmCoordinator(repo, Clock.fixed(now.minusSeconds(120), ZoneOffset.UTC)).create(1)
            val completed = requireNotNull(coordinator.trigger(AlarmDelivery.from(planned, repo.deliveryEpoch())))
            val result = requireNotNull(coordinator.act(AlarmDelivery.from(completed, repo.deliveryEpoch()), ActionType.DONE))
            assertEquals(com.cztr.routinealarm.occurrence.Outcome.APPLIED, result.outcome)
            val completedInput = fromJournal(repo)
            assertEquals(0, completedInput.openCount)
            assertEquals(0, completedInput.triggeredCount)
            coordinator.create(30)
            repo.exportBackup()
        }
        context.deleteDatabase(database)
        SqliteOccurrenceRepository(context, Clock.fixed(now, ZoneOffset.UTC), database).use { repo ->
            repo.restoreBackup(bytes)
            val before = repo.exportBackup()
            val input = fromJournal(repo)
            assertEquals(0, input.openCount)
            assertEquals(0, input.triggeredCount)
            assertEquals(0, input.overdueCount)
            assertNull(input.nextText)
            val view = screen(HistoryUiMapper.map(input), R52Colors.Dark, mutableListOf())
            readable(view)
            assertTrue(labels(view).contains("0 offen · 0 ausgelöst · 0 überfällig"))
            assertArrayEquals(before, repo.exportBackup())
        }
    }

    @Test fun darkHistoryActionsAndHeadingsRemainReachableInFiniteShell() =
        finiteShell(R52Colors.Dark)

    @Test fun lightHistoryActionsAndHeadingsRemainReachableInFiniteShell() =
        finiteShell(R52Colors.Light)

    private fun finiteShell(colors: R52Colors) {
        val inputs = listOf(HistoryUiInput.loading(), HistoryUiInput.unavailable(),
            HistoryUiInput.available(3, 1, 1,
                "Nächster gespeicherter Test: morgen, 08:45 Uhr, mit einem langen gespeicherten Hinweis bis zum sichtbaren Ende", false))
        for (input in inputs) {
            val state = HistoryUiMapper.map(input)
            val actions = mutableListOf<String>()
            val history = screen(state, colors, actions)
            val density = context.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            val content = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(dp(20), 0, dp(20), 0)
                addView(history, android.widget.LinearLayout.LayoutParams(-1, -2))
            }
            val shell = com.cztr.routinealarm.ui.shell.AppShellView(context,
                com.cztr.routinealarm.ui.shell.AppDestination.HISTORY, colors, content) {
                fail("Layout or scrolling must not change the selected destination")
            }
            shell.measure(View.MeasureSpec.makeMeasureSpec(dp(320), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(480), View.MeasureSpec.EXACTLY))
            shell.layout(0, 0, shell.measuredWidth, shell.measuredHeight)
            val viewport = descendants(shell).filterIsInstance<android.widget.ScrollView>().single()
            assertEquals("Actual shell keeps 280dp history content", dp(280), history.width)
            assertTrue("Fixture must genuinely overflow the finite content viewport", content.height > viewport.height)
            for (button in buttons(history)) {
                val bounds = android.graphics.Rect(0, 0, button.width, button.height)
                content.offsetDescendantRectToMyCoords(button, bounds)
                viewport.scrollTo(0, bounds.top)
                assertTrue("Action starts inside scrolled viewport: ${button.text}", bounds.top >= viewport.scrollY)
                assertTrue("Entire action ends inside scrolled viewport: ${button.text}",
                    bounds.bottom <= viewport.scrollY + viewport.height)
            }
            assertTrue("Merely measuring and exploring the history must dispatch no action", actions.isEmpty())
            val expectedHeadings = setOf(state.testTitle, "Persönliche Routinen", "Training")
            val headings = descendants(history).filterIsInstance<TextView>()
                .filter { it.text.toString() in expectedHeadings }
            assertEquals(expectedHeadings, headings.map { it.text.toString() }.toSet())
            headings.forEach { assertTrue("History heading must have native semantics: ${it.text}", it.isAccessibilityHeading) }
        }
    }


    private fun available(colors: R52Colors) {
        val state = HistoryUiMapper.map(HistoryUiInput.available(3, 2, 1, "Nächster gespeicherter Test: morgen, 08:45 Uhr", false))
        val actions = mutableListOf<String>()
        val view = screen(state, colors, actions)
        readable(view)
        assertTrue("Constructing and laying out History must not dispatch an action", actions.isEmpty())
        assertEquals(listOf("System prüfen", "Testverlauf öffnen"), buttons(view).map { it.text.toString() })
        buttons(view).forEach { assertTrue(it.performClick()) }
        assertEquals(listOf("system", "history"), actions)
    }

    private fun longEvent(colors: R52Colors) {
        val event = "Nächster gespeicherter Test: Überprüfung der persönlichen Morgenroutine mit sehr langem Ereignistext, Ergänzungen und eindeutigem Ende: ENDE-1234567890"
        val state = HistoryUiMapper.map(HistoryUiInput.available(1, 0, 0, event, true))
        val view = screen(state, colors, mutableListOf())
        readable(view)
        assertTrue(labels(view).contains("1 offen · 0 ausgelöst · 0 überfällig\n$event"))
        assertEquals(listOf("Testverlauf öffnen"), buttons(view).map { it.text.toString() })
    }

    private fun readStates(colors: R52Colors) {
        val empty = HistoryUiMapper.map(HistoryUiInput.available(0, 0, 0, null, true))
        val loading = HistoryUiMapper.map(HistoryUiInput.loading())
        val failed = HistoryUiMapper.map(HistoryUiInput.unavailable())
        assertEquals(HistoryLoadState.AVAILABLE, empty.loadState)
        assertEquals(0, empty.openCount)
        assertNull(loading.openCount)
        assertNull(failed.openCount)
        for (state in listOf(empty, loading, failed)) {
            val actions = mutableListOf<String>()
            val view = screen(state, colors, actions)
            readable(view)
            assertTrue(actions.isEmpty())
            if (state === empty) assertTrue(labels(view).contains("0 offen · 0 ausgelöst · 0 überfällig"))
            else assertFalse(labels(view).any { it.contains("0 offen") })
            val expected = if (state === failed) listOf("Erneut versuchen", "Testverlauf öffnen") else listOf("Testverlauf öffnen")
            assertEquals(expected, buttons(view).map { it.text.toString() })
            buttons(view).forEach { it.performClick() }
            assertEquals(if (state === failed) listOf("retry", "history") else listOf("history"), actions)
        }
    }

    private fun fromJournal(repo: SqliteOccurrenceRepository): HistoryUiInput {
        val status = TestAlarmStatusPresenter.presentSummary(repo.statusSummary(now), now, ZoneId.of("UTC"), true)
        return HistoryUiInput.available(requireNotNull(status.openCount), requireNotNull(status.triggeredCount),
            requireNotNull(status.overdueCount), status.nextText, !status.needsExactPermission)
    }

    private fun screen(state: HistoryUiState, colors: R52Colors, actions: MutableList<String>) =
        HistoryScreenView(context, state, colors, { actions += "history" }, { actions += "retry" }, { actions += "system" })

    private fun readable(view: HistoryScreenView) {
        assertEquals(2f, context.resources.configuration.fontScale, 0.01f)
        val density = context.resources.displayMetrics.density
        val width = (280 * density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, width, view.measuredHeight)
        for (text in descendants(view).filterIsInstance<TextView>()) {
            val layout = requireNotNull(text.layout)
            val usableWidth = text.width - text.compoundPaddingLeft - text.compoundPaddingRight
            val usableHeight = text.height - text.compoundPaddingTop - text.compoundPaddingBottom
            assertTrue("Clipped height: ${text.text}", layout.height <= usableHeight)
            assertEquals("Missing suffix: ${text.text}", text.text.length, layout.getLineEnd(layout.lineCount - 1))
            for (line in 0 until layout.lineCount) {
                assertEquals("Ellipsized line: ${text.text}", 0, layout.getEllipsisCount(line))
                assertTrue("Clipped width: ${text.text}", layout.getLineMax(line) <= usableWidth + 1f)
            }
            if (text is Button) {
                assertTrue(text.isEnabled)
                assertTrue(text.contentDescription.isNotBlank())
                assertTrue(text.width >= 48 * density)
                assertTrue(text.height >= 48 * density)
            }
        }
    }

    private fun labels(view: View) = descendants(view).filterIsInstance<TextView>().map { it.text.toString() }
    private fun buttons(view: View) = descendants(view).filterIsInstance<Button>()
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
