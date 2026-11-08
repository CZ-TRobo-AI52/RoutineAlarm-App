package com.cztr.routinealarm

import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.cztr.routinealarm.occurrence.*
import com.cztr.routinealarm.ui.studio.StudioEditorView
import com.cztr.routinealarm.support.onRoutineStorage
import com.cztr.routinealarm.support.awaitUiReadersStopped
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.Shadows.shadowOf
import java.time.Clock

/** Real Activity actions and durable SQLite outcomes, no mocked save implementation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,31])
@LooperMode(LooperMode.Mode.PAUSED)
class StudioHostTest {
 private val app get()=RuntimeEnvironment.getApplication()
 private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
 private fun button(a:MainActivity,tag:String)=all(a.window.decorView).filterIsInstance<Button>().firstOrNull{it.tag==tag}
 private fun waitFor(check:()->Boolean){val until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);while(!check()&&System.nanoTime()<until){shadowOf(Looper.getMainLooper()).runOneTask();Thread.sleep(5)};assertTrue("UI operation did not finish",check())}
 private fun studio(a:MainActivity){all(a.window.decorView).filterIsInstance<com.cztr.routinealarm.ui.components.R52BottomNav>().single().let{nav->all(nav).filterIsInstance<Button>().single{it.text=="Studio"}.performClick()};waitFor{button(a,"studio_create")!=null}}
 private fun editor(a:MainActivity)=all(a.window.decorView).filterIsInstance<StudioEditorView>().single()
 private fun field(a:MainActivity,k:String)=all(editor(a)).filterIsInstance<EditText>().single{it.tag==k}
 private fun fill(a:MainActivity,title:String){field(a,"title").setText(title);field(a,"speech").setText("Bitte Wasser trinken");field(a,"hour").setText("09");field(a,"minute").setText("17");all(editor(a)).filterIsInstance<CheckBox>().single{it.tag=="day:MONDAY"}.isChecked=true}
 private fun records()=onRoutineStorage{SqliteOccurrenceRepository(app,Clock.systemDefaultZone()).use{it.listPersonalRoutines()}}
 private fun close(c:org.robolectric.android.controller.ActivityController<MainActivity>){c.pause().stop().destroy();awaitUiReadersStopped(c.get())}
 @Test fun createAndEditAreReachableAndPersistPausedAcrossActivityRestart(){
  var c=Robolectric.buildActivity(MainActivity::class.java).setup()
  try {
   studio(c.get());assertTrue(button(c.get(),"studio_create")!!.performClick());waitFor{all(c.get().window.decorView).any{it is StudioEditorView}}
   fill(c.get(),"C1-HOST-Wasser");button(c.get(),"studio_save")!!.performClick();waitFor{records().size==1 && button(c.get(),"studio_create")!=null}
   val first=records().single();assertEquals("C1-HOST-Wasser",first.event.title);assertFalse(first.event.enabled);assertEquals(1L,first.version)
   close(c);c=Robolectric.buildActivity(MainActivity::class.java).setup();studio(c.get())
   all(c.get().window.decorView).filterIsInstance<Button>().single{it.tag=="studio_routine_item"&&it.text.contains("C1-HOST-Wasser")}.performClick()
   waitFor{button(c.get(),"studio_edit")!=null};button(c.get(),"studio_edit")!!.performClick();waitFor{all(c.get().window.decorView).any{it is StudioEditorView}}
   field(c.get(),"title").setText("C1-HOST-Wasser neu");button(c.get(),"studio_save")!!.performClick();waitFor{records().single().version==2L}
   val saved=records().single();assertEquals(first.event.id,saved.event.id);assertEquals("C1-HOST-Wasser neu",saved.event.title);assertFalse(saved.event.enabled);assertFalse(ScheduleState.isEnabled(app))
  } finally {close(c)}
 }
 @Test fun rotationKeepsDraftIdentityAndCancelDoesNotWrite(){
  var c=Robolectric.buildActivity(MainActivity::class.java).setup()
  try{
   studio(c.get());button(c.get(),"studio_create")!!.performClick();waitFor{all(c.get().window.decorView).any{it is StudioEditorView}};fill(c.get(),"Entwurf")
   val before=editor(c.get()).currentDraft();val state=Bundle();c.saveInstanceState(state);close(c)
   c=Robolectric.buildActivity(MainActivity::class.java).create(state).start().resume();waitFor{all(c.get().window.decorView).any{it is StudioEditorView}}
   assertEquals(before,editor(c.get()).currentDraft());button(c.get(),"studio_cancel")!!.performClick();waitFor{button(c.get(),"studio_create")!=null};assertTrue(records().isEmpty())
  }finally{close(c)}
 }
 @Test fun staleEditorCannotOverwriteNewerDefinition(){
  val c=Robolectric.buildActivity(MainActivity::class.java).setup()
  try{
   studio(c.get());button(c.get(),"studio_create")!!.performClick();waitFor{all(c.get().window.decorView).any{it is StudioEditorView}};fill(c.get(),"Alter Entwurf")
   val d=editor(c.get()).currentDraft()
   onRoutineStorage{SqliteOccurrenceRepository(app,Clock.systemDefaultZone()).use{r->r.savePersonalRoutine(PersonalRoutineSaveRequest("other-writer",d.event().copy(title="Neuer Stand"),0,0,d.expectedRestoreEpoch))}}
   button(c.get(),"studio_save")!!.performClick();waitFor{all(c.get().window.decorView).filterIsInstance<TextView>().any{it.text.contains("zwischenzeitlich")}}
   assertEquals("Neuer Stand",records().single().event.title);assertEquals(1L,records().single().version);assertEquals("Alter Entwurf",field(c.get(),"title").text.toString());assertFalse(button(c.get(),"studio_save")!!.isEnabled)
  }finally{close(c)}
 }

 @Test fun waitForReturnsWhenReadyDespiteSelfRepostingCallbacks(){
  val handler=android.os.Handler(Looper.getMainLooper())
  val stopped=java.util.concurrent.atomic.AtomicBoolean(false)
  val calls=java.util.concurrent.atomic.AtomicInteger(0)
  val callback=object:Runnable{override fun run(){calls.incrementAndGet();if(!stopped.get())handler.post(this)}}
  val escape=Thread({try{Thread.sleep(10000);stopped.set(true)}catch(_:InterruptedException){}},"test-queue-bounded-escape").apply{isDaemon=true}
  val started=System.nanoTime();handler.post(callback);escape.start()
  try{waitFor{calls.get()>=3};assertTrue("Satisfied condition must return inside original8s bound",System.nanoTime()-started<java.util.concurrent.TimeUnit.SECONDS.toNanos(8))}
  finally{stopped.set(true);handler.removeCallbacks(callback);escape.interrupt()}
 }
}
