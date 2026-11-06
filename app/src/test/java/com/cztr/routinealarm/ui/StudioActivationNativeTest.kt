package com.cztr.routinealarm.ui

import android.app.AlarmManager
import android.content.Context
import android.graphics.Rect
import android.util.SparseArray
import android.os.Parcelable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import com.cztr.routinealarm.ui.design.R52Colors
import com.cztr.routinealarm.ui.studio.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StudioActivationNativeTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val detail = StudioRoutineDetail("saved", "Meine gespeicherte Routine", "08:00", "Montag",
        "Pausiert", "Routine", "Ansage", "Guten Morgen", "Nur ansehen", "Gespeichert")
    private val state get() = StudioUiState(StudioFilter.ALL, StudioFilter.entries, emptyList(), detail)
    @After fun resetFont() { RuntimeEnvironment.setFontScale(1f) }

    @Test fun activationRequiresExplicitTapAndDoesNotEmitOtherActions() {
        val calls = mutableListOf<String>()
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        val before = alarms.scheduledAlarms.toList()
        val view = screen(onActivate = { calls += it })
        assertTrue(calls.isEmpty())
        val button = activation(view)
        assertTrue(button.isEnabled)
        button.performClick()
        button.performClick()
        assertEquals(listOf("saved"), calls)
        assertFalse(button.isEnabled)
        assertEquals(before, alarms.scheduledAlarms.toList())
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }

    @Test fun busyBlocksEvenProgrammaticClicks() {
        val view = screen(busy = true)
        val button = activation(view)
        assertFalse(button.isEnabled)
        button.performClick()
        assertTrue(button.text.toString().contains("angefragt"))
    }

    @Test fun hostMustBindTheSelectedRoutineAndCallback() {
        assertFalse(hasActivation(screen(eligibleId = null)))
        assertFalse(hasActivation(screen(eligibleId = "other")))
        assertFalse(hasActivation(screen(onActivate = null)))
        val absent = StudioScreenView(context, state.copy(selected = null), R52Colors.Dark,
            {}, {}, onActivateRoutine = { fail("No selection must not activate") }, activationRoutineId = "saved")
        assertFalse(hasActivation(absent))
    }

    @Test fun recreationDoesNotRestorePermissionOrAutomaticallyActivate() {
        val calls = mutableListOf<String>()
        val original = screen(onActivate = { calls += it }).apply { id = 9001 }
        val saved = SparseArray<Parcelable>()
        original.saveHierarchyState(saved)
        val unbound = screen(eligibleId = null, onActivate = { calls += it }).apply { id = 9001 }
        unbound.restoreHierarchyState(saved)
        assertFalse(hasActivation(unbound))
        val rebound = screen(onActivate = { calls += it }).apply { id = 9001 }
        rebound.restoreHierarchyState(saved)
        assertTrue(calls.isEmpty())
        activation(rebound).performClick()
        assertEquals(listOf("saved"), calls)
    }

    @Test fun defaultPreviewRemainsUnchangedAndDoesNotExposeActivation() {
        val preview = StudioScreenView(context, state, R52Colors.Dark, {}, {})
        assertFalse(hasActivation(preview))
        assertTrue(views(preview).filterIsInstance<android.widget.TextView>()
            .any { it.text.toString().contains("SICHERE VORSCHAU") })
    }

    @Test fun activationIsReadableAndScrollableAt320dpAnd200PercentFont() {
        RuntimeEnvironment.setFontScale(2f)
        for (colors in listOf(R52Colors.Dark, R52Colors.Light)) {
            val view = screen(colors = colors)
            val scroll = ScrollView(context).apply { addView(view) }
            val width = dp(320); val height = dp(640)
            scroll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            scroll.layout(0, 0, width, height)
            val button = activation(view)
            val layout = requireNotNull(button.layout)
            assertTrue(button.width >= dp(48) && button.height >= dp(48))
            val usable = button.width - button.compoundPaddingLeft - button.compoundPaddingRight
            assertTrue(layout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
            assertEquals(button.text.length, layout.getLineEnd(layout.lineCount - 1))
            for (line in 0 until layout.lineCount) {
                assertEquals(0, layout.getEllipsisCount(line))
                assertTrue(layout.getLineMax(line) <= usable + 1)
            }
            button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), true)
            val bounds = Rect(0, 0, button.width, button.height)
            scroll.offsetDescendantRectToMyCoords(button, bounds)
            assertTrue(bounds.left >= 0 && bounds.right <= width)
            assertTrue("Activation scrolls fully into viewport", bounds.top >= scroll.scrollY && bounds.bottom <= scroll.scrollY + height)
        }
    }

    private fun screen(eligibleId: String? = "saved", busy: Boolean = false,
        colors: R52Colors = R52Colors.Dark,
        onActivate: ((String) -> Unit)? = { fail("Unexpected activation") }) =
        StudioScreenView(context, state, colors,
            { fail("Unexpected filter") }, { fail("Unexpected selection") },
            onCreateRoutine = { fail("Unexpected create") }, onEditRoutine = { fail("Unexpected edit") },
            onActivateRoutine = onActivate, activationRoutineId = eligibleId, activationBusy = busy)
    private fun activation(v: View) = views(v).filterIsInstance<Button>().single { it.tag == "studio_activate" }
    private fun hasActivation(v: View) = views(v).any { it.tag == "studio_activate" }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
}
