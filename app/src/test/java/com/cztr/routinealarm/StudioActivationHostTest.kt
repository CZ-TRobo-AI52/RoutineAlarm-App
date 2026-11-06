package com.cztr.routinealarm

import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.cztr.routinealarm.occurrence.*
import com.cztr.routinealarm.support.onRoutineStorage
import com.cztr.routinealarm.support.awaitUiReadersStopped
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.Shadows.shadowOf
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Catches missing host action, stale authority promotion, false paused state and recreation replay. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,31])
@LooperMode(LooperMode.Mode.PAUSED)
class StudioActivationHostTest {
 @org.junit.Before fun pauseSyntheticFrames(){org.robolectric.shadows.ShadowChoreographer.setPaused(true)}
 private val app get()=RuntimeEnvironment.getApplication()
 private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
 private fun button(a:MainActivity,t:String)=all(a.window.decorView).filterIsInstance<Button>().firstOrNull{it.tag==t}
 private fun text(a:MainActivity)=all(a.window.decorView).filterIsInstance<TextView>().joinToString("\n"){it.text.toString()}
 private fun <T> repo(b:(SqliteOccurrenceRepository)->T):T=onRoutineStorage{SqliteOccurrenceRepository(app,Clock.systemDefaultZone()).use(b)}
 private fun waitFor(b:()->Boolean){val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!b()&&System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5)};assertTrue("UI not ready",b())}
 private fun seed():PersonalRoutineRecord=repo { r -> val event=PersonalSchedule.allEvents.first().copy(id="host-activation",title="Eigene Aktivierung");(r.savePersonalRoutine(PersonalRoutineSaveRequest("seed",event,0,0,r.deliveryEpoch())) as PersonalRoutineSaveResult.Saved).record }
 private fun start(id:String)=Robolectric.buildActivity(MainActivity::class.java).create(Bundle().apply{putString("r52_destination","STUDIO");putString("r52_studio_selected_id",id)}).start().resume().also{c->waitFor{button(c.get(),"studio_edit")!=null}}
 private fun close(c:org.robolectric.android.controller.ActivityController<MainActivity>){c.pause().stop().destroy();awaitUiReadersStopped(c.get())}
 @Test fun explicitTapSavesOnceWithoutEnablingGlobalPlanOrClaimingRegistration(){
  val original=seed();val c=start(original.event.id)
  try {
   assertFalse(repo{it.readPersonalRoutine(original.event.id)!!}.event.enabled)
   val action=button(c.get(),"studio_activate");assertNotNull("Saved routine needs explicit activation",action)
   action!!.performClick();action.performClick()
   waitFor{repo{it.readPersonalRoutine(original.event.id)!!}.version==2L && text(c.get()).contains("Wochenplan")}
   assertTrue(repo{it.readPersonalRoutine(original.event.id)!!}.event.enabled);assertFalse(ScheduleState.isEnabled(app))
   waitFor{button(c.get(),"studio_edit")!=null && text(c.get()).contains("Aktiviert")}
   assertNull(button(c.get(),"studio_activate"));assertTrue(text(c.get()).contains("Aktiviert"))
  } finally {close(c)}
 }
 @Test fun staleDisplayedRecordCannotActivateLaterEdit(){
  val original=seed();val c=start(original.event.id)
  try {
   val action=button(c.get(),"studio_activate");assertNotNull(action)
   repo{it.savePersonalRoutine(PersonalRoutineSaveRequest("newer",original.event.copy(title="Neuer Entwurf"),1,1,original.restoreEpoch))}
   action!!.performClick();waitFor{text(c.get()).contains("zwischenzeitlich")}
   val current=repo{it.readPersonalRoutine(original.event.id)!!};assertEquals(2L,current.version);assertEquals("Neuer Entwurf",current.event.title);assertFalse(current.event.enabled)
  } finally {close(c)}
 }
 @Test fun storedEnabledStateIsNotDisplayedAsPaused(){
  val original=seed()
  repo{it.setPersonalRoutineEnabled(PersonalRoutineActivationRequest("activate-before-open",original.event,true,1,1,original.restoreEpoch))}
  val c=start(original.event.id)
  try {assertTrue("Persisted enabled state must be visible",text(c.get()).contains("Aktiviert"));assertNull(button(c.get(),"studio_activate"));assertEquals(2L,repo{it.readPersonalRoutine(original.event.id)!!}.version)} finally {close(c)}
 }
 @Test fun recreationDoesNotReplayAnInFlightActivation(){
  val original=seed();var c=start(original.event.id)
  val entered=CountDownLatch(1);val release=CountDownLatch(1)
  try {
   val action=button(c.get(),"studio_activate");assertNotNull(action)
   ScheduleState.dispatchStorage{entered.countDown();check(release.await(5,TimeUnit.SECONDS))};assertTrue(entered.await(2,TimeUnit.SECONDS))
   action!!.performClick();val state=Bundle();c.saveInstanceState(state)
   release.countDown();close(c)
   c=Robolectric.buildActivity(MainActivity::class.java).create(state).start().resume()
   waitFor{button(c.get(),"studio_edit")!=null}
   assertEquals(2L,repo{it.readPersonalRoutine(original.event.id)!!}.version)
   assertTrue(text(c.get()).contains("unbestätigt"));assertNotNull(button(c.get(),"studio_activation_retry"))
   button(c.get(),"studio_activation_retry")!!.performClick();waitFor{text(c.get()).contains("Frühere")}
   assertEquals(2L,repo{it.readPersonalRoutine(original.event.id)!!}.version);assertFalse(ScheduleState.isEnabled(app))
  } finally {release.countDown();close(c)}
 }
}
