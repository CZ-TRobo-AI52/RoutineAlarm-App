package com.cztr.routinealarm.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.cztr.routinealarm.MainActivity
import com.cztr.routinealarm.ScheduleState
import com.cztr.routinealarm.support.awaitUi
import com.cztr.routinealarm.support.awaitUiReadersStopped
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[31])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class HomeScreenProjectionTest {
    @Before fun prepare() {
        val context=RuntimeEnvironment.getApplication() as Context
        context.deleteDatabase("occurrences.db")
        ScheduleState.setEnabled(context,false)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }
    @Test fun pausedPlanHasNoUpcomingExecutionPromises() = withHome { a ->
        assertTrue(labels(a).contains("Wochenplan pausiert"))
        assertTrue(labels(a).contains("WOCHENPLAN · VORSCHAU"))
        assertTrue(views(a.window.decorView).none { it.tag=="home_upcoming_item" })
        assertFalse(ScheduleState.isEnabled(a))
    }
    @Test fun personalProgressIsExplicitlyUnavailableInsteadOfInvented() = withHome { a ->
        assertTrue(labels(a).contains("Persönlicher Fortschritt noch nicht verfügbar"))
        assertTrue(labels(a).any { it.contains("keine Erledigt- oder Snooze-Zähler") })
    }
    @Test fun enabledHomeShowsFiveBoundedProjectedSteps() {
        ScheduleState.setEnabled(RuntimeEnvironment.getApplication(),true)
        withHome { a ->
            val items=views(a.window.decorView).filter { it.tag=="home_upcoming_item" }
            assertEquals(5,items.size)
            assertTrue(labels(a).contains("Nächste Schritte"))
            assertTrue(labels(a).any { it.contains("bestätigt keine Zustellung") })
        }
    }
    @Test fun missingExactPermissionIsVisibleWithoutChangingPlan() {
        ScheduleState.setEnabled(RuntimeEnvironment.getApplication(),true)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        withHome { a ->
            assertTrue(labels(a).contains("Exakte Wecker fehlen"))
            assertTrue(ScheduleState.isEnabled(a))
            views(a.window.decorView).filterIsInstance<Button>().single { it.text=="System prüfen" }.performClick()
            assertTrue(views(a.window.decorView).single { it.contentDescription=="Mehr öffnen" }.isSelected)
            assertNull(shadowOf(a).nextStartedActivity)
        }
    }
    @Test fun showAllOnlyNavigatesAndKeepsPausedPlan() = withHome { a ->
        views(a.window.decorView).filterIsInstance<Button>().single { it.text=="Alle anzeigen" }.performClick()
        assertTrue(views(a.window.decorView).single { it.contentDescription=="Plan öffnen" }.isSelected)
        assertFalse(ScheduleState.isEnabled(a))
        assertNull(shadowOf(a).nextStartedActivity)
    }
    private fun withHome(check:(MainActivity)->Unit) {
        // create only: the assertions concern rendering, never onResume recovery effects.
        val c=Robolectric.buildActivity(MainActivity::class.java).create()
        try { awaitUi(c.get());check(c.get()) }
        finally { c.destroy();awaitUiReadersStopped(c.get()) }
    }
    private fun labels(a:MainActivity)=views(a.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
}
