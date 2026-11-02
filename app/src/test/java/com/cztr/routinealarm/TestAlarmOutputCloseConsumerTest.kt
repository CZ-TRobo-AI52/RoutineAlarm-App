@file:Suppress("DEPRECATION")
package com.cztr.routinealarm

import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.cztr.routinealarm.execution.*
import com.cztr.routinealarm.occurrence.*
import com.cztr.routinealarm.support.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.*
import org.robolectric.annotation.*
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowTextToSpeech
import java.time.*
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,31],shadows=[TestAlarmOutputCloseConsumerTest.Speech::class,UiReadDatabaseGate::class])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TestAlarmOutputCloseConsumerTest {
    @Implements(TextToSpeech::class)
    class Speech:ShadowTextToSpeech() {
        var stops=0;val spoken=mutableListOf<String>()
        @Implementation override fun stop():Int { stops++;return super.stop() }
        @Implementation override fun speak(text:CharSequence,queueMode:Int,params:Bundle?,utteranceId:String?):Int {
            spoken+=text.toString();return super.speak(text,queueMode,params,null)
        }
    }
    private val app get()=RuntimeEnvironment.getApplication()
    private val services=mutableListOf<ServiceController<TestAlarmService>>()
    private val screens=mutableListOf<ActivityController<TestAlarmActivity>>()
    private lateinit var repo:SqliteOccurrenceRepository
    private lateinit var row:RoutineOccurrence
    private lateinit var delivery:AlarmDelivery
    @Before fun setup() {
        UiReadDatabaseGate.reset();app.deleteDatabase("occurrences.db")
        ShadowTextToSpeech.addLanguageAvailability(Locale.GERMAN)
        repo=TestAlarmRuntime.repository(app)
        val created=TestAlarmCoordinator(repo,Clock.offset(Clock.systemUTC(),Duration.ofMinutes(-2))).create(1)
        row=TestAlarmRuntime.coordinator(repo).trigger(AlarmDelivery.from(created,repo.deliveryEpoch()))!!
        delivery=AlarmDelivery.from(row,repo.deliveryEpoch())
    }
    @After fun cleanup() {
        UiReadDatabaseGate.reset()
        screens.forEach { it.destroy();awaitUiReadersStopped(it.get()) }
        services.forEach { it.destroy();awaitTestOutputStopped(it) }
        repo.close();app.deleteDatabase("occurrences.db")
    }
    private fun start():Pair<ServiceController<TestAlarmService>,Speech> {
        val s=Robolectric.buildService(TestAlarmService::class.java).create();services+=s
        val t:Speech=Shadow.extract(requireNotNull(ShadowTextToSpeech.getLastTextToSpeechInstance()))
        s.get().onStartCommand(TestAlarmRuntime.putToken(Intent(app,TestAlarmService::class.java),delivery),0,1)
        t.onInitListener.onInit(TextToSpeech.SUCCESS);awaitTestOutput(s)
        return s to t
    }
    private fun options():Intent {
        val note=shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(TestAlarmService.NOTIFICATION)!!
        return Intent(shadowOf(note.actions.single { it.title.toString()=="Optionen" }.actionIntent).savedIntent)
    }
    private fun open(value:Intent,saved:Bundle?=null):ActivityController<TestAlarmActivity> {
        val a=Robolectric.buildActivity(TestAlarmActivity::class.java,value).create(saved);screens+=a;awaitUi(a.get());return a
    }
    private fun buttons(v:View):List<Button> = when(v) {
        is Button->listOf(v)
        is ViewGroup->(0 until v.childCount).flatMap { buttons(v.getChildAt(it)) }
        else->emptyList()
    }
    private fun button(a:ActivityController<TestAlarmActivity>,label:String)=buttons(a.get().window.decorView).single { it.text.toString()==label }
    @Test fun outputCloseIsAnExplicitSeparateMenuAction() {
        start();val a=open(options())
        assertTrue("Explicit output close is absent from the actual options consumer",
            buttons(a.get().window.decorView).any { it.text.toString()=="Ausgabe beenden" })
        assertTrue(buttons(a.get().window.decorView).any { it.text.toString()=="Schließen" })
        assertEquals(row,repo.get(row.id));assertEquals(2,repo.history(row.id).size)
    }
    private fun chooseClose(value:Intent=options()):ActivityController<TestAlarmActivity> {
        val a=open(value);button(a,"Ausgabe beenden").performClick();awaitUi(a.get());return a
    }
    private fun takeStop():Intent {
        val value=shadowOf(app).nextStartedService
        assertNotNull("Committed Close did not enqueue its targeted service transport",value)
        assertEquals(TestAlarmService.CLOSE_OUTPUT,value!!.action)
        return value
    }
    @Test fun closeCommitsBeforeOneShotStopAndSurvivesServiceRestart() {
        val (service,speech)=start();val before=speech.stops;val value=options();chooseClose(value)
        val stop=takeStop();val item=repo.get(row.id)!!
        assertEquals(OccurrenceStatus.TRIGGERED,item.status)
        assertEquals(row.generation,item.outputSilencedGeneration)
        assertEquals(listOf("SCHEDULE","TRIGGER","CLOSE_OUTPUT"),repo.history(row.id).map { it.actionType })
        assertEquals("UNKNOWN_EFFECT",repo.history(row.id).last().reason)
        service.get().onStartCommand(stop,0,2);awaitTestOutput(service)
        assertEquals(before+1,speech.stops)
        val after=speech.spoken.toList();service.get().onStartCommand(stop,0,3);awaitTestOutput(service)
        service.get().onInit(TextToSpeech.SUCCESS);awaitTestOutput(service)
        assertEquals(before+1,speech.stops);assertEquals(after,speech.spoken)
        service.destroy();awaitTestOutputStopped(service);services.remove(service)
        val (fresh,freshSpeech)=start();assertTrue(freshSpeech.spoken.isEmpty())
        assertTrue(shadowOf(fresh.get()).isStoppedBySelf)
        assertFalse(repo.history(row.id).any { it.actionType in setOf("DONE","SKIP") })
    }
    @Test fun screenCloseRemainsNavigationWithoutSuppressionOrStop() {
        val (service,speech)=start();val before=speech.stops
        val note=shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(TestAlarmService.NOTIFICATION)!!
        val a=open(Intent(shadowOf(note.contentIntent).savedIntent));button(a,"Schließen").performClick()
        assertTrue(a.get().isFinishing);assertEquals(row,repo.get(row.id));assertEquals(2,repo.history(row.id).size)
        assertEquals(before,speech.stops);assertNull(shadowOf(app).nextStartedService)
        service.get().onInit(TextToSpeech.SUCCESS);awaitTestOutput(service)
        assertNull(repo.get(row.id)!!.outputSilencedGeneration)
    }
    @Test fun oldImmutableNotificationHandleCannotCloseReplacementSession() {
        val (old,oldSpeech)=start();val oldOptions=options()
        old.destroy();awaitTestOutputStopped(old);services.remove(old)
        val (_,freshSpeech)=start();val freshOptions=options();val before=freshSpeech.stops
        assertNotEquals(oldOptions.action,freshOptions.action)
        assertNotEquals(oldOptions.getStringExtra(TestAlarmNotification.OUTPUT_INSTANCE),
            freshOptions.getStringExtra(TestAlarmNotification.OUTPUT_INSTANCE))
        chooseClose(oldOptions)
        assertEquals(row,repo.get(row.id));assertEquals(2,repo.history(row.id).size)
        assertEquals(before,freshSpeech.stops);assertNull(shadowOf(app).nextStartedService)
        old.get().onInit(TextToSpeech.SUCCESS)
        assertEquals(1,oldSpeech.spoken.size);assertEquals(1,freshSpeech.spoken.size)
    }
    @Test fun failedCommitRetryPreservesOriginalSavedPayloadAndInstance() {
        val (_,speech)=start();val value=options();val a=open(value)
        UiReadDatabaseGate.failNext();button(a,"Ausgabe beenden").performClick();awaitUi(a.get())
        UiReadDatabaseGate.reset()
        val saved=Bundle().also { a.saveInstanceState(it) }
        val encoded= saved.getString("test-alarm.pending-action.v1")!!
        val original=OccurrenceCodec.decodeAction(encoded)
        assertEquals(ActionType.CLOSE_OUTPUT,original.type)
        assertEquals(value.getStringExtra(TestAlarmNotification.OUTPUT_INSTANCE),original.outputInstanceId)
        assertNull(StableTestAlarmActions(repo,Clock.systemUTC()).recover(original.id))
        assertEquals(row,repo.get(row.id));assertNull(shadowOf(app).nextStartedService)
        a.destroy();awaitUiReadersStopped(a.get());screens.remove(a)
        val restored=open(value,saved)
        val again=Bundle().also { restored.saveInstanceState(it) }
        assertEquals(encoded,again.getString("test-alarm.pending-action.v1"))
        button(restored,"Erneut versuchen").performClick();awaitUi(restored.get())
        assertEquals(original,StableTestAlarmActions(repo,Clock.systemUTC()).recover(original.id))
        assertEquals(3,repo.history(row.id).size);assertEquals(1,speech.spoken.size)
        takeStop()
    }
    @Test fun missingSavedOriginalCannotTurnRetainedOptionsIntentIntoCloseConsent() {
        start();val value=options();val a=open(value)
        UiReadDatabaseGate.failNext();button(a,"Ausgabe beenden").performClick();awaitUi(a.get());UiReadDatabaseGate.reset()
        val saved=Bundle().also { a.saveInstanceState(it) };assertNotNull(saved.getString("test-alarm.pending-action.v1"))
        saved.remove("test-alarm.pending-action.v1")
        a.destroy();awaitUiReadersStopped(a.get());screens.remove(a)
        val restored=open(value,saved)
        assertFalse(buttons(restored.get().window.decorView).any { it.text.toString()=="Ausgabe beenden" })
        assertEquals(row,repo.get(row.id));assertEquals(2,repo.history(row.id).size)
        assertNull(shadowOf(app).nextStartedService)
    }
    @Test fun historicalSavedCloseReceiptCannotIssueAnotherStopPermit() {
        start();val value=options();chooseClose(value);takeStop() // Simulate loss of the queued stop delivery.
        val id=repo.history(row.id).last().actionId
        val original=StableTestAlarmActions(repo,Clock.systemUTC()).recover(id)!!
        val saved=Bundle().apply { putString("test-alarm.pending-action.v1",OccurrenceCodec.encode(original)) }
        val restored=open(value,saved);button(restored,"Erneut versuchen").performClick();awaitUi(restored.get())
        assertNull(shadowOf(app).nextStartedService)
        assertEquals(3,repo.history(row.id).size);assertEquals(original,StableTestAlarmActions(repo,Clock.systemUTC()).recover(id))
    }
    @Test fun delayedOldStopCannotAffectNewGenerationAndOutputSession() {
        val (old,_)=start();chooseClose();val oldStop=takeStop()
        val closed=repo.get(row.id)!!;val api=StableTestAlarmActions(repo,Clock.offset(Clock.systemUTC(),Duration.ofSeconds(1)))
        val snoozed=api.submit(api.prepare("test-user:close-then-new-snooze",AlarmDelivery.from(closed,repo.deliveryEpoch()),ActionType.SNOOZE,1)).occurrence
        row=TestAlarmCoordinator(repo,Clock.offset(Clock.systemUTC(),Duration.ofMinutes(2))).trigger(AlarmDelivery.from(snoozed,repo.deliveryEpoch()))!!
        delivery=AlarmDelivery.from(row,repo.deliveryEpoch())
        old.destroy();awaitTestOutputStopped(old);services.remove(old)
        val (fresh,speech)=start();val before=speech.stops
        val note=shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(TestAlarmService.NOTIFICATION)!!
        fresh.get().onStartCommand(oldStop,0,3);awaitTestOutput(fresh)
        assertEquals(before,speech.stops);assertEquals(1,speech.spoken.size)
        assertEquals(note.contentIntent,shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(TestAlarmService.NOTIFICATION)!!.contentIntent)
        assertEquals(OccurrenceStatus.TRIGGERED,repo.get(row.id)!!.status)
        assertNotEquals(row.generation,row.outputSilencedGeneration)
    }
    @Test fun targetedStopOnMainNeverReadsSQLite() {
        val (service,_)=start();chooseClose();val stop=takeStop()
        UiReadDatabaseGate.armed=true
        service.get().onStartCommand(stop,0,2);awaitTestOutput(service)
        assertEquals(0,UiReadDatabaseGate.mainAccesses.get())
        UiReadDatabaseGate.reset()
        assertEquals("UNKNOWN_EFFECT",repo.history(row.id).last().reason)
    }
    @Test fun corruptSavedCloseInstanceCannotBecomeFreshRequestOnNewIntent() {
        start();val value=options();val a=open(value)
        UiReadDatabaseGate.failNext();button(a,"Ausgabe beenden").performClick();awaitUi(a.get());UiReadDatabaseGate.reset()
        val saved=Bundle().also { a.saveInstanceState(it) }
        val json=org.json.JSONObject(saved.getString("test-alarm.pending-action.v1")!!).put("outputInstanceId","not-an-instance")
        saved.putString("test-alarm.pending-action.v1",json.toString())
        a.destroy();awaitUiReadersStopped(a.get());screens.remove(a)
        val restored=open(value,saved);restored.newIntent(value);awaitUi(restored.get())
        assertEquals(row,repo.get(row.id));assertEquals(2,repo.history(row.id).size)
        assertNull(shadowOf(app).nextStartedService)
    }

}
