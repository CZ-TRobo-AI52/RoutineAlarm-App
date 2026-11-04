package com.cztr.routinealarm
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.cztr.routinealarm.occurrence.SqliteOccurrenceRepository
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,31])
@LooperMode(LooperMode.Mode.PAUSED)
class C1RecurringAlarmSurfaceAdmissionTest {
 private fun <T> worker(action:()->T):T { val e=Executors.newSingleThreadExecutor();return try { e.submit<T>{action()}.get(10,TimeUnit.SECONDS) } finally { e.shutdown() } }
 private val app get() = RuntimeEnvironment.getApplication()
 private val manager get() = app.getSystemService(android.app.AlarmManager::class.java)
 private fun source():Intent {
  ScheduleState.setEnabled(app,true)
  org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
  val event=PersonalSchedule.allEvents.first{it.enabled}
  val epoch=worker{SqliteOccurrenceRepository(app,Clock.systemDefaultZone()).use{it.deliveryEpoch()}}
  return Intent(app,AlarmActivity::class.java)
   .putExtra(AlarmScheduler.EXTRA_RECURRING,true).putExtra(AlarmScheduler.EXTRA_EVENT_ID,event.id)
   .putExtra(AlarmScheduler.EXTRA_RESTORE_GENERATION,ScheduleState.restoreGeneration(app))
   .putExtra("routine_definition_version",0L).putExtra("routine_definition_epoch",epoch)
   .putExtra(AlarmScheduler.EXTRA_TITLE,event.title).putExtra(AlarmScheduler.EXTRA_SPOKEN_TEXT,event.spokenText)
   .putExtra(AlarmService.EXTRA_OUTPUT_INSTANCE,"c1-valid-original-output")
 }
 private fun drain() = com.cztr.routinealarm.support.drainRoutineAdmissions()
 private fun alarms() = org.robolectric.Shadows.shadowOf(manager).scheduledAlarms
 private fun snooze(c:AlarmActivity) = buttons(c.window.decorView).single{it.text=="5 Minuten später"}
 private fun holdWorker():java.util.concurrent.CountDownLatch {
  val entered=java.util.concurrent.CountDownLatch(1); val release=java.util.concurrent.CountDownLatch(1)
  ScheduleState.dispatchStorage { entered.countDown(); check(release.await(10,TimeUnit.SECONDS)) }
  check(entered.await(10,TimeUnit.SECONDS)); return release
 }
 private fun buttons(v:View):List<Button> = when(v) { is Button -> listOf(v);is ViewGroup -> (0 until v.childCount).flatMap{buttons(v.getChildAt(it))};else -> emptyList() }
 @Test fun validRecurringDeliveryKeepsExplicitSnoozeReachableOnActivity() {
  val app=RuntimeEnvironment.getApplication()
  ScheduleState.setEnabled(app,true)
  val event=PersonalSchedule.allEvents.first{it.enabled}
  val epoch=worker{SqliteOccurrenceRepository(app,Clock.systemDefaultZone()).use{it.deliveryEpoch()}}
  val original=Intent(app,AlarmActivity::class.java)
   .putExtra(AlarmScheduler.EXTRA_RECURRING,true).putExtra(AlarmScheduler.EXTRA_EVENT_ID,event.id)
   .putExtra(AlarmScheduler.EXTRA_RESTORE_GENERATION,ScheduleState.restoreGeneration(app))
   .putExtra("routine_definition_version",0L).putExtra("routine_definition_epoch",epoch)
   .putExtra(AlarmScheduler.EXTRA_TITLE,event.title).putExtra(AlarmScheduler.EXTRA_SPOKEN_TEXT,event.spokenText)
   .putExtra(AlarmService.EXTRA_OUTPUT_INSTANCE,"c1-valid-original-output")
  assertTrue("The exact original delivery is valid on the storage worker",worker{ScheduleState.isDeliveryAllowed(app,original)})
  val c=Robolectric.buildActivity(AlarmActivity::class.java,original).setup()
  com.cztr.routinealarm.support.drainRoutineAdmissions()
  try { assertTrue("A valid recurring delivery must expose an enabled explicit Snooze action on its real Activity",buttons(c.get().window.decorView).any{it.isEnabled && it.text.toString().contains("später")}) }
  finally { c.pause().stop().destroy() }
 }

 @Test fun explicitTapUsesOriginalPayloadAndStopsOnlyOriginalOutputOnce() {
  val original=source(); val speech=original.getStringExtra(AlarmScheduler.EXTRA_SPOKEN_TEXT)
  val c=Robolectric.buildActivity(AlarmActivity::class.java,original).setup(); drain()
  val button=snooze(c.get()); val release=holdWorker()
  try {
   button.performClick(); button.performClick()
   c.get().intent.putExtra(AlarmScheduler.EXTRA_SPOKEN_TEXT,"MUTATED")
   c.get().intent.putExtra(AlarmService.EXTRA_OUTPUT_INSTANCE,"new-output")
   assertTrue(alarms().isEmpty())
  } finally { release.countDown() }
  drain()
  assertEquals(1,alarms().size)
  val effect=org.robolectric.Shadows.shadowOf(requireNotNull(alarms().single().operation)).savedIntent
  assertEquals(speech,effect.getStringExtra(AlarmScheduler.EXTRA_SPOKEN_TEXT))
  val stop=org.robolectric.Shadows.shadowOf(app).nextStartedService
  assertEquals("c1-valid-original-output",stop?.getStringExtra(AlarmService.EXTRA_OUTPUT_INSTANCE))
  assertEquals(AlarmService.ACTION_STOP,stop?.action)
  assertNull(org.robolectric.Shadows.shadowOf(app).nextStartedService)
  assertTrue(c.get().isFinishing); c.pause().stop().destroy()
 }
 @Test fun closeScreenWhileTapWaitsDoesNotScheduleOrStopOutput() {
  val c=Robolectric.buildActivity(AlarmActivity::class.java,source()).setup();drain()
  val release=holdWorker()
  try { snooze(c.get()).performClick(); c.get().onBackPressed() } finally {release.countDown()}
  drain();assertTrue(c.get().isFinishing);assertTrue(alarms().isEmpty());assertNull(org.robolectric.Shadows.shadowOf(app).nextStartedService)
  c.pause().stop().destroy()
 }
 @Test fun destructionWhileTapWaitsCannotResurrectEffect() {
  val c=Robolectric.buildActivity(AlarmActivity::class.java,source()).setup();drain()
  val release=holdWorker()
  try { snooze(c.get()).performClick();c.pause().stop().destroy() } finally {release.countDown()}
  drain();assertTrue(alarms().isEmpty());assertNull(org.robolectric.Shadows.shadowOf(app).nextStartedService)
 }
 @Test fun revokedDeliveryAfterVisibleAdmissionCannotSchedule() {
  val c=Robolectric.buildActivity(AlarmActivity::class.java,source()).setup();drain()
  val button=snooze(c.get());ScheduleState.disableAfterRestore(app);button.performClick();drain()
  assertTrue(alarms().isEmpty());assertNull(org.robolectric.Shadows.shadowOf(app).nextStartedService)
  assertTrue(buttons(c.get().window.decorView).none{it.isEnabled && it.text.toString().contains("später")})
  c.pause().stop().destroy()
 }
 @Test fun rotationPreservesOriginalIntentAndDoesNotReplayPendingTap() {
  val original=source();val c=Robolectric.buildActivity(AlarmActivity::class.java,original).setup();drain()
  val saved=android.os.Bundle();val release=holdWorker()
  try {snooze(c.get()).performClick();c.saveInstanceState(saved).pause().stop().destroy()}finally{release.countDown()}
  drain();assertTrue(alarms().isEmpty())
  val replacement=Intent(original).putExtra(AlarmScheduler.EXTRA_SPOKEN_TEXT,"NEW").putExtra(AlarmService.EXTRA_OUTPUT_INSTANCE,"new-output")
  val d=Robolectric.buildActivity(AlarmActivity::class.java,replacement).create(saved).start().resume().visible();drain()
  assertTrue(alarms().isEmpty());snooze(d.get()).performClick();drain()
  assertEquals(1,alarms().size)
  assertEquals("c1-valid-original-output",org.robolectric.Shadows.shadowOf(app).nextStartedService?.getStringExtra(AlarmService.EXTRA_OUTPUT_INSTANCE))
  d.pause().stop().destroy()
 }
}
