package com.cztr.routinealarm.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.cztr.routinealarm.ui.design.R52Colors
import com.cztr.routinealarm.ui.notification.MenuStage
import com.cztr.routinealarm.ui.testalarm.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native component geometry; no TalkBack, device or complete Activity acceptance claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TestAlarmLargeFontLayoutTest {
    @Test fun restoredAndOpenHistoryActionsFitAtTwoHundredPercent() {
        val state = TestAlarmUiMapper.history(0, 1, false,
            listOf(
                TestHistoryUiRow("paused", "Aus Sicherung übernommener Testwecker", "Snooze: 0 Minuten",
                    "Keine aktuelle Planung bestätigt", "Aus Sicherung übernommen · pausiert", TestHistoryRowAction.ACTIVATE),
                TestHistoryUiRow("open", "Ausgelöst – noch offen", "Snooze: 2 Minuten",
                    "Die Ausgabe wurde nicht bestätigt", action = TestHistoryRowAction.OPEN),
                TestHistoryUiRow("future", "Geplant", "Snooze: 0 Minuten",
                    "Planung prüfen", action = TestHistoryRowAction.REPLAN),
            ), false, false)
        measureEveryControl(state, required = setOf("Wiederhergestellten Test aktivieren",
            "Offenen Testwecker öffnen", "Erneut planen", "Schließen"))
    }

    @Test fun everyNotificationMenuStageFitsAtTwoHundredPercent() {
        for (stage in MenuStage.entries) {
            measureEveryControl(TestAlarmUiState.NotificationActions(
                "Ein Testwecker mit einem längeren verständlichen Titel", stage), setOf("Testverlauf öffnen", "Schließen"))
        }
    }

    private fun measureEveryControl(state: TestAlarmUiState, required: Set<String>) {
        RuntimeEnvironment.setFontScale(2f)
        try {
            val context = RuntimeEnvironment.getApplication() as Context
            assertEquals(2f, context.resources.configuration.fontScale, 0.01f)
            for (colors in listOf(R52Colors.Dark, R52Colors.Light)) for (widthDp in listOf(320, 280)) {
                val view = TestAlarmScreenView(context, state, colors) { }
                val density = context.resources.displayMetrics.density
                val width = (widthDp * density).toInt()
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                view.layout(0, 0, width, view.measuredHeight)
                val controls = views(view).filterIsInstance<Button>()
                assertTrue("Fixture must measure the actual reachable actions", controls.map { it.text.toString() }.containsAll(required))
                for (text in views(view).filterIsInstance<TextView>()) {
                    val layout = requireNotNull(text.layout) { "Unmeasured text: ${text.text}" }
                    assertTrue("Native glyph widths must be measured: ${text.text}", layout.getLineWidth(0) > 0f)
                    val availableWidth = text.width - text.compoundPaddingLeft - text.compoundPaddingRight
                    val availableHeight = text.height - text.compoundPaddingTop - text.compoundPaddingBottom
                    assertTrue("Clipped height at ${widthDp}dp: ${text.text}", layout.height <= availableHeight)
                    for (line in 0 until layout.lineCount) {
                        assertEquals("Ellipsis at ${widthDp}dp: ${text.text}", 0, layout.getEllipsisCount(line))
                        // getLineWidth includes invisible trailing spaces on wrapped lines.
                        // getLineMax measures the visible line extent, including leading margin.
                        assertTrue("Clipped visible width ${layout.getLineMax(line)}>$availableWidth at ${widthDp}dp: ${text.text}",
                            layout.getLineMax(line) <= availableWidth + 1f)
                    }
                    if (text is Button) {
                        assertTrue("Short touch target: ${text.text}", text.height >= 48 * density)
                        assertTrue("Narrow touch target: ${text.text}", text.width >= 48 * density)
                    }
                }
            }
        } finally { RuntimeEnvironment.setFontScale(1f) }
    }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
