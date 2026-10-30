package com.cztr.routinealarm.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.cztr.routinealarm.ui.design.R52Colors
import com.cztr.routinealarm.ui.shell.ThemeChoice
import com.cztr.routinealarm.ui.system.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Measured real system controls, not a source-text check or device/TalkBack claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SystemLargeFontLayoutTest {
    @Test fun darkSystemActionsStayReadableAtTwoHundredPercent() = verify(R52Colors.Dark, 2f)
    @Test fun lightSystemActionsStayReadableAtTwoHundredPercent() = verify(R52Colors.Light, 2f)
    @Test fun standardFontRetainsEverySystemAction() = verify(R52Colors.Dark, 1f)

    private fun verify(colors: R52Colors, scale: Float) {
        RuntimeEnvironment.setFontScale(scale)
        try {
            val context = RuntimeEnvironment.getApplication() as Context
            assertEquals(scale, context.resources.configuration.fontScale, 0.01f)
            val state = SystemUiMapper.map(SystemUiInput(true, false, false, false, true,
                "ui-test", "com.cztr.routinealarm.dev", ThemeChoice.DARK))
            val received = mutableListOf<SystemAction>()
            val screen = SystemScreenView(context, state, colors, received::add)
            val density = context.resources.displayMetrics.density
            // 320dp handset, minus the real host's two20dp horizontal margins.
            val width = (280 * density).toInt()
            screen.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            screen.layout(0, 0, width, screen.measuredHeight)
            val buttons = descendants(screen).filterIsInstance<Button>()
            val expected = listOf(SystemAction.REQUEST_NOTIFICATIONS, SystemAction.REQUEST_EXACT_ALARMS,
                SystemAction.OPEN_HISTORY, SystemAction.OPEN_BACKUP, SystemAction.SCHEDULE_TEST_ALARM,
                SystemAction.SET_THEME_DARK, SystemAction.SET_THEME_LIGHT, SystemAction.SET_THEME_SYSTEM)
            assertEquals("Fixture must expose all actual permission/recovery/backup/test/theme controls", 8, buttons.size)
            val problems = mutableListOf<String>()
            for (button in buttons) {
                assertTrue(button.isEnabled)
                assertTrue(button.contentDescription.isNotBlank())
                val layout = requireNotNull(button.layout)
                val usableWidth = button.width - button.compoundPaddingLeft - button.compoundPaddingRight
                val usableHeight = button.height - button.compoundPaddingTop - button.compoundPaddingBottom
                if (layout.height > usableHeight) problems += "Clipped height: ${button.text}"
                if (layout.getLineEnd(layout.lineCount - 1) != button.text.length)
                    problems += "Missing text: ${button.text}"
                for (line in 0 until layout.lineCount) {
                    if (layout.getEllipsisCount(line) > 0) problems += "Ellipsis: ${button.text}"
                    if (layout.getLineMax(line) > usableWidth + 1f) problems += "Clipped width: ${button.text}"
                }
                if (button.width < 48 * density || button.height < 48 * density)
                    problems += "Touch target below48dp: ${button.text}"
                button.performClick()
            }
            assertEquals("Readable controls retain their original typed routing", expected, received)
            assertTrue(problems.joinToString("\n"), problems.isEmpty())
        } finally { RuntimeEnvironment.setFontScale(1f) }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
