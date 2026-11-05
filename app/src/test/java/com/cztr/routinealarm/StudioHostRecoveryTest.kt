package com.cztr.routinealarm

import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.cztr.routinealarm.occurrence.*
import com.cztr.routinealarm.ui.studio.*
import com.cztr.routinealarm.ui.design.R52Colors
import com.cztr.routinealarm.support.onRoutineStorage
import com.cztr.routinealarm.support.awaitUiReadersStopped
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.Shadows.shadowOf
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,31])
@LooperMode(LooperMode.Mode.PAUSED)
class StudioHostRecoveryTest {
 private val app get()=RuntimeEnvironment.getApplication()
 private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
 private fun button(a:MainActivity,tag:String)=all(a.window.decorView).filterIsInstance<Button>().firstOrNull{it.tag==tag}
 private fun waitFor(check:()->Boolean){val until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);while(!check()&&System.nanoTime()<until){shadowOf(Looper.getMainLooper()).runOneTask();Thread.sleep(5)};assertTrue("UI operation did not finish",check())}
 private fun editor(a:MainActivity)=all(a.window.decorView).filterIsInstance<StudioEditorView>().single()
 private fun <T> repo(block:(SqliteOccurrenceRepository)->T):T=onRoutineStorage{SqliteOccurrenceRepository(app,Clock.systemDefaultZone()).use(block)}
 private fun close(c:org.robolectric.android.controller.ActivityController<MainActivity>){c.pause().stop().destroy();awaitUiReadersStopped(c.get())}
 private fun savedState(commit:Boolean):Pair<Bundle,StudioSaveRequest>{
  val epoch=repo{it.deliveryEpoch()};val draft=StudioEditorDraft.create(0,epoch).copy(title="C1 receipt original",spokenText="Original speech",days=setOf(DayOfWeek.MONDAY))
  var request:StudioSaveRequest?=null
  val view=StudioEditorView(app,draft,R52Colors.Dark,{request=it},{})
  all(view).filterIsInstance<Button>().single{it.tag=="studio_save"}.performClick()
  val original=requireNotNull(request)
  if(commit)repo{r->r.savePersonalRoutine(PersonalRoutineSaveRequest(original.requestId,original.event,original.expectedVersion,original.expectedPlanVersion,original.expectedRestoreEpoch))}
  return Bundle().apply{putString("r52_destination","STUDIO");putBundle("r52_studio_editor",view.saveDraftState());putString("r52_studio_session","saved-session");putString("r52_studio_save_request",original.requestId)} to original
 }
 @Test fun builtinEditorUsesAtomicCurrentPlanVersion(){
  val event=PersonalSchedule.allEvents.first()
  repo{r->assertTrue(r.replacePersonalPlan(OccurrenceScope.PersonalEvent(event.id),0,emptyList(),"seed-authority",ZoneId.systemDefault(),Instant.now()) is PersonalPlanDecision.Ready)}
  val c=Robolectric.buildActivity(MainActivity::class.java).create(Bundle().apply{putString("r52_destination","STUDIO");putString("r52_studio_selected_id",event.id)}).start().resume()
  try{
   waitFor{button(c.get(),"studio_edit")!=null};button(c.get(),"studio_edit")!!.performClick();waitFor{all(c.get().window.decorView).any{it is StudioEditorView}}
   assertEquals(1L,editor(c.get()).currentDraft().expectedPlanVersion)
   button(c.get(),"studio_save")!!.performClick();waitFor{repo{it.readPersonalRoutine(event.id)}!=null}
   val saved=repo{it.readPersonalRoutine(event.id)}!!;assertEquals(1L,saved.version);assertEquals(2L,saved.planVersion);assertFalse(saved.event.enabled)
  }finally{close(c)}
 }
 @Test fun lostReplyRecoveryAcknowledgesOriginalWithoutOverwritingNewerDefinition(){
  val (state,request)=savedState(true)
  repo{it.savePersonalRoutine(PersonalRoutineSaveRequest("later-edit",request.event.copy(title="Current newer title"),1,1,request.expectedRestoreEpoch))}
  val c=Robolectric.buildActivity(MainActivity::class.java).create(state).start().resume()
  try{
   assertFalse(button(c.get(),"studio_save")!!.isEnabled)
   val check=button(c.get(),"studio_save_status_check");assertNotNull("Recovery must be an explicit read-only action",check)
   val before=repo{it.exportBackup()};check!!.performClick();waitFor{button(c.get(),"studio_create")!=null}
   assertArrayEquals(before,repo{it.exportBackup()});assertEquals(2L,repo{it.readPersonalRoutine(request.event.id)}!!.version)
   assertTrue(all(c.get().window.decorView).filterIsInstance<Button>().any{it.tag=="studio_routine_item"&&it.text.contains("Current newer title")})
   assertFalse(ScheduleState.isEnabled(app))
  }finally{close(c)}
 }
 @Test fun missingReceiptNeverAutomaticallySavesOrRefreshesRetainedFences(){
  val (state,request)=savedState(false)
  val c=Robolectric.buildActivity(MainActivity::class.java).create(state).start().resume()
  try{
   val check=button(c.get(),"studio_save_status_check");assertNotNull("Unknown outcome needs read-only lookup",check)
   val before=editor(c.get()).currentDraft();val backup=repo{it.exportBackup()};check!!.performClick()
   waitFor{all(c.get().window.decorView).filterIsInstance<TextView>().any{it.text.contains("Noch kein Speicherbeleg")}}
   assertEquals(before,editor(c.get()).currentDraft());assertEquals(request.expectedRestoreEpoch,before.expectedRestoreEpoch)
   assertArrayEquals(backup,repo{it.exportBackup()});assertNull(repo{it.readPersonalRoutine(request.event.id)});assertFalse(button(c.get(),"studio_save")!!.isEnabled)
   assertTrue(button(c.get(),"studio_save_status_check")!!.isEnabled)
  }finally{close(c)}
 }
}
