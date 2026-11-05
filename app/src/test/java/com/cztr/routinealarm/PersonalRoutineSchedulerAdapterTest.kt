package com.cztr.routinealarm

import android.app.AlarmManager
import android.content.Context
import android.os.Looper
import com.cztr.routinealarm.occurrence.*
import com.cztr.routinealarm.support.onRoutineStorage
import java.time.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import org.junit.Assert.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28,31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PersonalRoutineSchedulerAdapterTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val manager get() = app.getSystemService(AlarmManager::class.java)
    private val event get() = RoutineEvent("user:targeted", DayOfWeek.entries.toSet(), 20, 15,
        "Target", "Speech", EventCategory.PERSONAL, EventMode.ROUTINE, false)
    private fun repo() = SqliteOccurrenceRepository(app, Clock.systemDefaultZone())
    @Before fun prepare() {
        app.deleteDatabase("occurrences.db")
        app.getSharedPreferences("routinealarm_schedule",Context.MODE_PRIVATE).edit().clear().commit()
        ShadowAlarmManager.setCanScheduleExactAlarms(true); ScheduleState.setEnabled(app,true)
        repo().use { it.savePersonalRoutine(PersonalRoutineSaveRequest("create",event,0,0,it.deliveryEpoch())) }
    }
    @After fun cleanup() { onRoutineStorage { Unit }; shadowOf(Looper.getMainLooper()).idle(); app.deleteDatabase("occurrences.db") }
    private fun method() = ScheduleState::class.java.methods.firstOrNull { it.name == "requestPersonalRoutineEnabled" }
        .also { assertNotNull("Missing asynchronous targeted personal routine adapter",it) }!!
    private fun request(id:String="activate", enabled:Boolean=true):PersonalRoutineActivationRequest = repo().use {
        val r=it.personalRoutineEditorSnapshot(event.id)!!
        PersonalRoutineActivationRequest(id,r.event,enabled,r.version,r.planVersion,r.restoreEpoch)
    }
    private fun invoke(request:PersonalRoutineActivationRequest, reply:(Any)->Unit) { method().invoke(ScheduleState,app,request,reply) }
    private fun command(request:PersonalRoutineActivationRequest):Any {
        val result=AtomicReference<Any>();invoke(request) { assertEquals(Looper.getMainLooper(),Looper.myLooper());result.set(it) }
        onRoutineStorage { Unit };shadowOf(Looper.getMainLooper()).idle();return requireNotNull(result.get())
    }
    private fun effect(result:Any) = result.javaClass.getMethod("getEffect").invoke(result).toString()
    private fun persistence(result:Any) = result.javaClass.getMethod("getPersistence").invoke(result) as PersonalRoutineSaveResult?
    private fun alarms() = shadowOf(manager).scheduledAlarms.mapNotNull { a -> a.operation?.let { shadowOf(it).savedIntent } }
    private fun ids() = alarms().mapNotNull { it.getStringExtra(AlarmScheduler.EXTRA_EVENT_ID) }
    private fun sentinel():String {
        val other=PersonalSchedule.allEvents.first { it.enabled }
        assertTrue(onRoutineStorage { AlarmScheduler.scheduleEvent(app,other) });return other.id
    }
    @Test fun activationCommitsBeforeTargetedRegistrationAndNeverClaimsPhysicalConfirmation() {
        method();val other=sentinel();val result=command(request());val saved=persistence(result) as PersonalRoutineSaveResult.Saved
        assertTrue(saved.record.event.enabled);assertEquals("UNKNOWN_EFFECT",effect(result))
        assertEquals(setOf(other,event.id),ids().toSet())
        val intent=alarms().single { it.getStringExtra(AlarmScheduler.EXTRA_EVENT_ID)==event.id }
        assertEquals(saved.record.version,intent.getLongExtra(AlarmScheduler.EXTRA_ROUTINE_VERSION,-1))
        assertEquals(saved.record.restoreEpoch,intent.getStringExtra(AlarmScheduler.EXTRA_ROUTINE_EPOCH))
    }
    @Test fun pauseCancelsOnlyItsRegistrationAndNeverSendsAnOutputStop() {
        method();val other=sentinel();command(request());assertTrue(event.id in ids())
        val result=command(request("pause",false));assertEquals("UNKNOWN_EFFECT",effect(result))
        assertFalse(event.id in ids());assertTrue(other in ids())
        assertNull(shadowOf(app).nextStartedService);assertNull(shadowOf(app).nextStoppedService)
    }
    @Test fun historicalRetryAfterLaterPauseDoesNotRearmOrRewrite() {
        method();val original=request();command(original);command(request("pause",false))
        val before=repo().use { it.exportBackup() };val result=command(original)
        assertTrue((persistence(result) as PersonalRoutineSaveResult.Saved).duplicate)
        assertEquals("NOT_ATTEMPTED",effect(result));assertFalse(event.id in ids())
        assertArrayEquals(before,repo().use { it.exportBackup() })
    }
    @Test fun stalePauseCannotCancelNewerActiveRegistration() {
        method();command(request());val stale=request("stale-pause",false)
        repo().use { r -> r.setPersonalRoutineEnabled(request("newer",true)) }
        assertTrue(onRoutineStorage { AlarmScheduler.scheduleEvent(app,repo().use { it.readPersonalRoutine(event.id)!!.event }) })
        val before=alarms().single { it.getStringExtra(AlarmScheduler.EXTRA_EVENT_ID)==event.id }.getLongExtra(AlarmScheduler.EXTRA_ROUTINE_VERSION,-1)
        val result=command(stale);assertTrue(persistence(result) is PersonalRoutineSaveResult.Conflict)
        assertEquals("NOT_ATTEMPTED",effect(result));assertEquals(before,alarms().single { it.getStringExtra(AlarmScheduler.EXTRA_EVENT_ID)==event.id }.getLongExtra(AlarmScheduler.EXTRA_ROUTINE_VERSION,-1))
        assertNull(shadowOf(app).nextStartedService);assertNull(shadowOf(app).nextStoppedService)
    }
    @Test fun personalActivationNeverSilentlyEnablesGlobalWeeklyPlan() {
        method();ScheduleState.setEnabled(app,false);val result=command(request())
        assertTrue((persistence(result) as PersonalRoutineSaveResult.Saved).record.event.enabled)
        assertEquals("NOT_ATTEMPTED",effect(result));assertFalse(ScheduleState.isEnabled(app));assertTrue(ids().isEmpty())
    }
    @Test fun occupiedWorkerDoesNotBlockMainAndPayloadIsFrozenAtSubmission() {
        method();val original=request();val days=original.event.daysOfWeek.toMutableSet();val input=original.copy(event=original.event.copy(daysOfWeek=days))
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val returned=CountDownLatch(1);val blocked=AtomicBoolean(false);val result=AtomicReference<Any>()
        ScheduleState.dispatchStorage { entered.countDown();check(release.await(5,TimeUnit.SECONDS)) };assertTrue(entered.await(2,TimeUnit.SECONDS))
        val caller=Thread.currentThread();val controller=Thread { try { if(!returned.await(300,TimeUnit.MILLISECONDS)) blocked.set(caller.stackTrace.any { it.className.contains("FutureTask") && it.methodName in listOf("get","awaitDone") }) } finally { release.countDown() } };controller.start()
        try { invoke(input) { result.set(it) };days.clear();days.add(DayOfWeek.TUESDAY) } finally { returned.countDown();controller.join(2000);release.countDown() }
        onRoutineStorage { Unit };shadowOf(Looper.getMainLooper()).idle()
        assertFalse("Main waited for occupied storage worker",blocked.get());assertEquals("UNKNOWN_EFFECT",effect(requireNotNull(result.get())))
        assertEquals(event.daysOfWeek,repo().use { it.readPersonalRoutine(event.id)!!.event.daysOfWeek })
    }
    @Test fun queuedPreRestoreGenerationDoesNotCommitOrReachPlatform() {
        method();val original=request();val entered=CountDownLatch(1);val release=CountDownLatch(1);val result=AtomicReference<Any>()
        ScheduleState.dispatchStorage { entered.countDown();check(release.await(5,TimeUnit.SECONDS)) };assertTrue(entered.await(2,TimeUnit.SECONDS))
        try { invoke(original) { result.set(it) };ScheduleState.disableAfterRestore(app) } finally { release.countDown() }
        onRoutineStorage { Unit };shadowOf(Looper.getMainLooper()).idle()
        assertEquals("NOT_ATTEMPTED",effect(requireNotNull(result.get())));assertEquals(1L,repo().use { it.readPersonalRoutine(event.id)!!.version });assertTrue(ids().isEmpty())
    }
    @Test fun conflictingReusedRequestDoesNotReplaceActiveRegistration() {
        method();val original=request();command(original);val before=repo().use { it.exportBackup() }
        val result=command(original.copy(enabled=false));assertEquals("NOT_ATTEMPTED",effect(result));assertTrue(event.id in ids());assertArrayEquals(before,repo().use { it.exportBackup() })
    }
    @Test fun laterPlanReplacementRejectsPreviouslyRegisteredDelivery() {
        method();command(request());val delivery=android.content.Intent(alarms().single { it.getStringExtra(AlarmScheduler.EXTRA_EVENT_ID)==event.id })
        repo().use { r ->
            val authority=r.personalPlanAuthority(OccurrenceScope.PersonalEvent(event.id))
            r.replacePersonalPlan(OccurrenceScope.PersonalEvent(event.id),authority.version,emptyList(),
                "later-plan",ZoneId.systemDefault(),Instant.now())
        }
        assertFalse("Later plan authority admitted an old registration",onRoutineStorage { ScheduleState.isDeliveryAllowed(app,delivery) })
    }

}
