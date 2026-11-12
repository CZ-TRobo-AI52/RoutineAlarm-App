package com.cztr.routinealarm

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.cztr.routinealarm.occurrence.*
import com.cztr.routinealarm.support.onRoutineStorage
import com.cztr.routinealarm.support.awaitUiReadersStopped
import com.cztr.routinealarm.ui.plan.*
import com.cztr.routinealarm.ui.home.HomeScreenView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.time.*
import java.util.concurrent.TimeUnit

/** Real SQLite definitions and real Activity surfaces; no mocked catalog. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,31])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PersonalRoutineSurfaceProjectionTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)
        (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun labels(a:MainActivity)=views(a.window.decorView).filterIsInstance<TextView>().map{it.text.toString()}
    private fun waitFor(message:String,check:()->Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(6)
        while(!check() && System.nanoTime()<deadline) { shadowOf(Looper.getMainLooper()).runOneTask();Thread.sleep(5) }
        assertTrue(message,check())
    }
    private fun nav(a:MainActivity,label:String) {
        val bar=views(a.window.decorView).filterIsInstance<com.cztr.routinealarm.ui.components.R52BottomNav>().single()
        views(bar).filterIsInstance<Button>().single{it.text==label}.performClick()
    }
    /** This fixture tests SQLite-backed view content and real host callbacks, not window layout.
     * Keep setup()'s lifecycle sequence while avoiding visible()'s unbounded main-queue drain.
     * Attached-window layout/touch coverage remains in the existing viewport test suites. */
    private fun projectionHost() = Robolectric.buildActivity(MainActivity::class.java)
        .create().start().postCreate(null).resume().topActivityResumed(true)

    private fun close(c:org.robolectric.android.controller.ActivityController<MainActivity>) {
        c.pause().stop().destroy();awaitUiReadersStopped(c.get())
    }
    private fun <T> repo(block:(SqliteOccurrenceRepository)->T):T=onRoutineStorage {
        SqliteOccurrenceRepository(app,Clock.systemDefaultZone()).use(block)
    }
    private fun save(event:RoutineEvent):PersonalRoutineRecord=repo { r ->
        val old=r.personalRoutineEditorSnapshot(event.id)
        (r.savePersonalRoutine(PersonalRoutineSaveRequest("save-"+event.id+"-"+(old?.version?:0),
            event,old?.version?:0,old?.planVersion?:0,old?.restoreEpoch?:r.deliveryEpoch())) as PersonalRoutineSaveResult.Saved).record
    }
    private fun event(id:String,title:String,days:Set<DayOfWeek> = DayOfWeek.entries.toSet()):RoutineEvent {
        val next=ZonedDateTime.now().plusMinutes(1)
        return RoutineEvent(id,days,next.hour,next.minute,title,"Testansage",
            EventCategory.TRAINING,EventMode.ROUTINE,false)
    }
    @Test fun storedPausedTrainingCategorySurvivesRestartAndIsVisibleWithoutActivation() {
        val saved=save(event("i2:paused","I2-Pausierte Bewegung"))
        var c=projectionHost()
        try {
            nav(c.get(),"Plan")
            waitFor("Persisted paused routine must be visible in Plan"){labels(c.get()).contains(saved.event.title)}
            assertTrue(labels(c.get()).any{it.contains("Pausiert")})
            assertEquals(1,labels(c.get()).count{it==saved.event.title})
            assertFalse(repo{it.readPersonalRoutine(saved.event.id)!!.event.enabled})
            assertFalse(ScheduleState.isEnabled(app))
            close(c);c=projectionHost()
            nav(c.get(),"Plan")
            waitFor("Reopened Activity must read the same saved definition"){labels(c.get()).contains(saved.event.title)}
            assertEquals(saved.version,repo{it.readPersonalRoutine(saved.event.id)!!.version})
        } finally { close(c) }
    }
    @Test fun savedDefaultOverrideAndDaySelectionUseTheStoredDefinition() {
        val day=ZonedDateTime.now().dayOfWeek
        val original=PersonalSchedule.allEvents.first{it.enabled && day in it.daysOfWeek}
        save(original.copy(title="I2-Geaenderter Standard",daysOfWeek=setOf(day)))
        val c=projectionHost()
        try {
            nav(c.get(),"Plan")
            waitFor("Saved stable-ID override must replace the static row"){labels(c.get()).contains("I2-Geaenderter Standard")}
            assertEquals(1,labels(c.get()).count{it=="I2-Geaenderter Standard"})
            val tomorrow=day.plus(1)
            val description=tomorrow.getDisplayName(java.time.format.TextStyle.FULL,java.util.Locale.GERMAN)+" auswählen"
            views(c.get().window.decorView).filterIsInstance<Button>().single{it.contentDescription?.toString()?.startsWith(description)==true}.performClick()
            waitFor("Selected day must finish loading"){views(c.get().window.decorView).any{it is PlanScreenView}}
            assertFalse(labels(c.get()).contains("I2-Geaenderter Standard"))
            assertFalse(repo{it.readPersonalRoutine(original.id)!!.event.enabled})
        } finally {close(c)}
    }
    @Test fun homeShowsExplicitlyEnabledCustomRoutineAndDropsItAfterPausedEdit() {
        val first=save(event("i2:active","I2-Aktive Bewegung"))
        repo { r -> val current=r.personalRoutineEditorSnapshot(first.event.id)!!
            assertTrue(r.setPersonalRoutineEnabled(PersonalRoutineActivationRequest("enable-i2",
                current.event,true,current.version,current.planVersion,current.restoreEpoch)) is PersonalRoutineSaveResult.Saved) }
        ScheduleState.setEnabled(app,true)
        val c=projectionHost()
        try {
            waitFor("Home must include a stored enabled custom routine"){labels(c.get()).contains(first.event.title)}
            save(first.event.copy(title="I2-Pausierte Aenderung"))
            nav(c.get(),"Plan")
            waitFor("Paused edit must remain visible in Plan"){labels(c.get()).contains("I2-Pausierte Aenderung")}
            nav(c.get(),"Heute")
            waitFor("Home refresh must finish"){views(c.get().window.decorView).any{it is HomeScreenView}}
            assertFalse(labels(c.get()).any{it==first.event.title||it=="I2-Pausierte Aenderung"})
            assertFalse(repo{it.readPersonalRoutine(first.event.id)!!.event.enabled})
        } finally {close(c)}
    }
    @Test fun disabledLegacyRowsRemainFilteredWithoutStoredDefinitionEvidence() {
        val input=PlanProjectionSource.from(false,true,0,DayOfWeek.MONDAY,
            listOf(event("legacy:disabled","Legacy",setOf(DayOfWeek.MONDAY))),emptyList(),false)
        assertTrue(PlanUiMapper.map(input).events.isEmpty())
    }
}

