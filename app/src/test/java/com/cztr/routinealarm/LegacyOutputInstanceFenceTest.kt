@file:Suppress("DEPRECATION")
package com.cztr.routinealarm

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.junit.After
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
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowTextToSpeech
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.Locale

/** Local instance addressing, no physical audio or durable CloseAck claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28,31], shadows = [Alpha13ServiceAuthorityContractTest.ObservedSpeech::class])
@LooperMode(LooperMode.Mode.PAUSED)
class LegacyOutputInstanceFenceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private lateinit var controller: ServiceController<AlarmService>
    private val service get() = controller.get()
    @Before fun setup() {
        assertTrue(context.getSharedPreferences("routinealarm_schedule",Context.MODE_PRIVATE).edit().clear().commit())
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ShadowTextToSpeech.addLanguageAvailability(Locale.GERMAN)
        controller = Robolectric.buildService(AlarmService::class.java).create()
    }
    @After fun cleanup() {
        controller.destroy()
        val alarms=context.getSystemService(AlarmManager::class.java)
        shadowOf(alarms).scheduledAlarms.toList().forEach { it.operation?.let { p -> alarms.cancel(p);p.cancel() } }
    }
    private fun delivery(name:String) = Intent(context,AlarmService::class.java)
        .setData(Uri.parse("routinealarm://snooze/$name"))
        .putExtra(AlarmScheduler.EXTRA_TITLE,name)
        .putExtra(AlarmScheduler.EXTRA_SPOKEN_TEXT,name)
        .putExtra(AlarmScheduler.EXTRA_MODE,EventMode.ROUTINE.name)
        .putExtra(AlarmScheduler.EXTRA_RECURRING,false)
        .putExtra(AlarmScheduler.EXTRA_RESTORE_GENERATION,ScheduleState.restoreGeneration(context))
    private fun activityIntent():Intent = Intent(shadowOf(shadowOf(notifications)
        .getNotification(AlarmService.NOTIFICATION_ID).fullScreenIntent).savedIntent)
    private fun buttons(v:View):List<Button> = when(v) {
        is Button -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { buttons(v.getChildAt(it)) }
        else -> emptyList()
    }
    private fun clickAndDeliver(intent:Intent,label:String) {
        val screen=Robolectric.buildActivity(AlarmActivity::class.java,intent).setup()
        buttons(screen.get().window.decorView).single { it.text==label }.performClick()
        val stop=requireNotNull(shadowOf(context).nextStartedService)
        assertEquals(AlarmService.ACTION_STOP,stop.action)
        service.onStartCommand(stop,0,3)
        screen.pause().stop().destroy()
    }
    private fun speech():Alpha13ServiceAuthorityContractTest.ObservedSpeech =
        Shadow.extract(ReflectionHelpers.getField<TextToSpeech>(service,"tts"))
    private fun replacementSurvives(label:String,sameIdentity:Boolean=false) {
        val first=delivery("A")
        service.onStartCommand(first,0,1)
        val old=activityIntent()
        val second=if(sameIdentity) Intent(first) else delivery("B")
        service.onStartCommand(second,0,2)
        service.onInit(TextToSpeech.SUCCESS)
        val observed=speech();observed.spoken.clear()
        clickAndDeliver(old,label)
        assertFalse("Old screen must not stop replacement",shadowOf(service).isStoppedBySelf)
        assertNotNull(shadowOf(notifications).getNotification(AlarmService.NOTIFICATION_ID))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
        assertTrue("Replacement still produces its repeat",observed.spoken.contains(if(sameIdentity) "A" else "B"))
    }
    @Test fun oldDoneCannotStopReplacement() = replacementSurvives("Erledigt")
    @Test fun oldSkipCannotStopReplacement() = replacementSurvives("Überspringen")
    @Test fun oldSnoozeCannotStopReplacement() {
        replacementSurvives("5 Minuten später")
        assertEquals(1,shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
    }
    @Test fun sameDeliveryAndGenerationStillRequireNewOutputIdentity() = replacementSurvives("Erledigt",true)
    @Test fun currentScreenCanStopOwnPendingOutput() {
        service.onStartCommand(delivery("Current"),0,1)
        clickAndDeliver(activityIntent(),"Erledigt")
        service.onInit(TextToSpeech.SUCCESS)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(speech().spoken.isEmpty())
    }
    @Test fun unboundStopCannotStopActiveOutput() {
        service.onStartCommand(delivery("Current"),0,1)
        service.onStartCommand(Intent(context,AlarmService::class.java).setAction(AlarmService.ACTION_STOP),0,2)
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertNotNull(shadowOf(notifications).getNotification(AlarmService.NOTIFICATION_ID))
    }
    @Test fun priorServiceInstanceCannotCloseRestartedService() {
        service.onStartCommand(delivery("Same"),0,1);val old=activityIntent()
        controller.destroy();controller=Robolectric.buildService(AlarmService::class.java).create()
        service.onStartCommand(delivery("Same"),0,2)
        clickAndDeliver(old,"Erledigt")
        assertFalse(shadowOf(service).isStoppedBySelf)
    }
    @Test fun oldNotificationHandleCannotAcquireNewInstanceAuthority() {
        service.onStartCommand(delivery("Same"),0,1)
        val old=shadowOf(notifications).getNotification(AlarmService.NOTIFICATION_ID).fullScreenIntent
        service.onStartCommand(delivery("Same"),0,2)
        val newer=shadowOf(notifications).getNotification(AlarmService.NOTIFICATION_ID).fullScreenIntent
        assertNotEquals("Different output instances need separate screen PendingIntents",old,newer)
        clickAndDeliver(Intent(shadowOf(old).savedIntent),"Erledigt")
        assertFalse(shadowOf(service).isStoppedBySelf)
    }
}
