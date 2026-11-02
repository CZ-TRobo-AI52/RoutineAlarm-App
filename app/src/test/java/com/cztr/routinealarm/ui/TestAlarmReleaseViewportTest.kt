package com.cztr.routinealarm.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.cztr.routinealarm.ui.design.R52Colors
import com.cztr.routinealarm.ui.notification.MenuChoice
import com.cztr.routinealarm.ui.notification.MenuStage
import com.cztr.routinealarm.ui.testalarm.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

/** Finite native viewport and callback boundaries, not device/TalkBack or acoustic acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31], qualifiers = "w320dp-h480dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class TestAlarmReleaseViewportTest {
    @Test fun activeLight() = active(R52Colors.Light, "light")
    @Test fun activeDark() = active(R52Colors.Dark, "dark")
    @Test fun outputOptionsLight() = options(R52Colors.Light, "light")
    @Test fun outputOptionsDark() = options(R52Colors.Dark, "dark")

    private fun active(colors: R52Colors, name: String) = check(
        TestAlarmUiState.Active("Ein Testwecker mit gut lesbaren Aktionen", "Ausgabe nicht physisch bestaetigt",
            "Ausgeloest, noch offen", true, (1..5).toList(), "Ausgeloest bedeutet nicht gehoert"), colors, "active-$name",
        mapOf("Erledigt" to TestAlarmUiAction.DONE, "Ueberspringen".replace("Ue", "Ü") to TestAlarmUiAction.SKIP,
            "1 Minute später" to TestAlarmUiAction.Snooze(1), "2 Minuten später" to TestAlarmUiAction.Snooze(2),
            "3 Minuten später" to TestAlarmUiAction.Snooze(3), "4 Minuten später" to TestAlarmUiAction.Snooze(4),
            "5 Minuten später" to TestAlarmUiAction.Snooze(5), "Testverlauf öffnen" to TestAlarmUiAction.OPEN_HISTORY,
            "Schließen" to TestAlarmUiAction.CLOSE))

    private fun options(colors: R52Colors, name: String) = check(
        TestAlarmUiState.NotificationActions("Testwecker: Ausgabe gezielt beenden", MenuStage.OPTIONS, true),
        colors, "options-$name", mapOf("Ausgabe beenden" to TestAlarmUiAction.NotificationChoice(MenuChoice.CLOSE),
            "Testverlauf öffnen" to TestAlarmUiAction.OPEN_HISTORY, "Schließen" to TestAlarmUiAction.CLOSE))

    private fun check(state: TestAlarmUiState, colors: R52Colors, name: String, expected: Map<String, TestAlarmUiAction>) {
        RuntimeEnvironment.setFontScale(2f)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val seen = mutableListOf<TestAlarmUiAction>()
            val view = TestAlarmScreenView(controller.get(), state, colors) { seen += it }
            controller.get().setContentView(view)
            val density = view.resources.displayMetrics.density
            fun dp(n: Int) = (n * density).toInt()
            val root = view.rootView
            root.measure(View.MeasureSpec.makeMeasureSpec(dp(320), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(480), View.MeasureSpec.EXACTLY))
            root.layout(0, 0, dp(320), dp(480))
            assertTrue(view.isAttachedToWindow)
            assertTrue(view.height <= dp(480))
            assertEquals(2f, view.resources.configuration.fontScale, 0.01f)
            assertTrue("Rendering must not invoke any action", seen.isEmpty())
            capture(root, "$name-top")
            val buttons = descendants(view).filterIsInstance<Button>()
            assertTrue(buttons.map { it.text.toString() }.containsAll(expected.keys))
            for (text in descendants(view).filterIsInstance<TextView>()) {
                val layout = requireNotNull(text.layout)
                assertTrue("Text height clipped: ${text.text}", layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
                for (line in 0 until layout.lineCount) {
                    assertEquals("Ellipsis: ${text.text}", 0, layout.getEllipsisCount(line))
                    assertTrue("Text width clipped: ${text.text}", layout.getLineMax(line) <= text.width - text.compoundPaddingLeft - text.compoundPaddingRight + 1f)
                }
            }
            for (button in buttons) {
                assertTrue("48dp height: ${button.text}", button.height >= dp(48))
                assertTrue("48dp width: ${button.text}", button.width >= dp(48))
                button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), true)
                val visible = Rect()
                assertTrue("Unreachable: ${button.text}", button.getLocalVisibleRect(visible))
                assertEquals("Clipped control width: ${button.text}", button.width, visible.width())
                assertEquals("Clipped control height: ${button.text}", button.height, visible.height())
                expected[button.text.toString()]?.let { action ->
                    seen.clear(); assertTrue(button.performClick()); assertEquals(listOf(action), seen)
                }
            }
            capture(root, "$name-bottom")
        } finally { controller.pause().stop().destroy(); RuntimeEnvironment.setFontScale(1f) }
    }
    private fun descendants(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
    private fun capture(v: View, label: String) {
        val dir = System.getProperty("c3.visual.output") ?: return
        val file = File(dir, "testalarm-$label-api${android.os.Build.VERSION.SDK_INT}.png").apply { requireNotNull(parentFile).mkdirs() }
        val bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bitmap)); file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
    }
}