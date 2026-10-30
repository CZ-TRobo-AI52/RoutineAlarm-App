package com.cztr.routinealarm.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.cztr.routinealarm.*
import com.cztr.routinealarm.backup.BackupActivity
import com.cztr.routinealarm.ui.home.*
import com.cztr.routinealarm.ui.design.R52Colors
import com.cztr.routinealarm.support.awaitUi
import com.cztr.routinealarm.support.awaitUiReadersStopped
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowAlarmManager
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[31])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class HomeActionsAndLayoutTest {
    @Before fun prepare() {
        val app=RuntimeEnvironment.getApplication() as Context
        app.deleteDatabase("occurrences.db")
        ScheduleState.setEnabled(app,false)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }
    @Test fun backupShortcutOpensExistingBackupWithoutActivatingPlan() = withHome { a ->
        button(a,"Backup öffnen").performClick()
        assertEquals(BackupActivity::class.java.name,shadowOf(a).nextStartedActivity.component!!.className)
        assertFalse(ScheduleState.isEnabled(a))
    }
    @Test fun trainingShortcutOpensPlanWithoutStartingSession() = withHome { a ->
        button(a,"Training ansehen").performClick()
        assertTrue(views(a.window.decorView).single { it.contentDescription=="Plan öffnen" }.isSelected)
        assertFalse(ScheduleState.isEnabled(a))
        assertNull(shadowOf(a).nextStartedActivity)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }
    @Test fun testShortcutOpensExistingSetupWithoutSchedulingOnNavigation() = withHome { a ->
        val alarms=shadowOf(a.getSystemService(android.app.AlarmManager::class.java))
        val before=alarms.scheduledAlarms.toList()
        button(a,"Testwecker öffnen").performClick()
        assertEquals(before,alarms.scheduledAlarms)
        assertTrue(views(a.window.decorView).single { it.contentDescription=="Mehr öffnen" }.isSelected)
        assertNotNull(button(a,"Testwecker in 1 Minute"))
        assertFalse(ScheduleState.isEnabled(a))
        assertNull(shadowOf(a).nextStartedActivity)
    }
    @Test fun unavailableEditorCannotCreateOrEnableAnything() = withHome { a ->
        assertFalse(button(a,"Neue Routine · noch nicht verfügbar").isEnabled)
        assertFalse(ScheduleState.isEnabled(a))
        assertNull(shadowOf(a).nextStartedActivity)
    }
    @Test fun homeLargeTextFitsLongTitlesAndEveryActionInBothPalettes() {
        RuntimeEnvironment.setFontScale(2f)
        try {
            val context=RuntimeEnvironment.getApplication() as Context
            val now=ZonedDateTime.parse("2026-10-29T09:00:00+01:00[Europe/Berlin]")
            val event=RoutineEvent("long",setOf(now.dayOfWeek),10,0,
                "Eine sehr lange persönliche Routine mit ausreichend Zeit für Frühstück und Vorbereitung",
                category=EventCategory.MORNING,mode=EventMode.ROUTINE)
            val state=HomeUiMapper.map(HomeProjectionSource.from(now,true,true,listOf(event)))
            for(colors in listOf(R52Colors.Dark,R52Colors.Light)) {
                val home=HomeScreenView(context,state,colors,{})
                val width=(280*context.resources.displayMetrics.density).toInt() //320dp viewport minus shell padding
                home.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                home.layout(0,0,width,home.measuredHeight)
                assertTrue(views(home).filterIsInstance<Button>().size>=7)
                for(v in views(home).filterIsInstance<TextView>()) {
                    val layout=requireNotNull(v.layout)
                    assertTrue("Full text height: ${v.text}",layout.height<=v.height-v.compoundPaddingTop-v.compoundPaddingBottom)
                    for(line in 0 until layout.lineCount) {
                        assertEquals(0,layout.getEllipsisCount(line))
                        assertTrue("Full text width: ${v.text}",layout.getLineWidth(line)<=v.width-v.compoundPaddingLeft-v.compoundPaddingRight+1)
                    }
                    if(v is Button) assertTrue(v.height >= (48*context.resources.displayMetrics.density).toInt())
                }
            }
        } finally { RuntimeEnvironment.setFontScale(1f) }
    }
    @Test fun projectionTimestampHasExplicitRefreshWithoutChangingPlan() = withHome { a ->
        assertTrue(views(a.window.decorView).filterIsInstance<TextView>().any { it.text.startsWith("Vorschau vom ") })
        button(a,"Vorschau aktualisieren").performClick()
        assertTrue(views(a.window.decorView).single { it.contentDescription=="Heute öffnen" }.isSelected)
        assertFalse(ScheduleState.isEnabled(a))
    }
    private fun button(a:MainActivity,label:String)=views(a.window.decorView).filterIsInstance<Button>().single { it.text==label }
    private fun withHome(check:(MainActivity)->Unit) {
        val c=Robolectric.buildActivity(MainActivity::class.java).create()
        try { awaitUi(c.get());check(c.get()) } finally { c.destroy();awaitUiReadersStopped(c.get()) }
    }
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
}
