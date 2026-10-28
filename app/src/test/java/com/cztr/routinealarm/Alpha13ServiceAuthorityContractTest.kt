@file:Suppress("DEPRECATION")

package com.cztr.routinealarm

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.backup.BackupAgent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.speech.tts.TextToSpeech
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowTextToSpeech
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.util.Locale
import java.time.Duration

/** Real receiver/service/restore callback; TTS records requests without playing audio. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31], shadows = [Alpha13ServiceAuthorityContractTest.ObservedSpeech::class])
@LooperMode(LooperMode.Mode.PAUSED)
class Alpha13ServiceAuthorityContractTest {
    @Implements(TextToSpeech::class)
    class ObservedSpeech : ShadowTextToSpeech() {
        val spoken = mutableListOf<String>()
        val utteranceIds = mutableListOf<String?>()
        @Implementation
        override fun speak(text: CharSequence, queueMode: Int, params: Bundle?, utteranceId: String?): Int {
            spoken += text.toString()
            utteranceIds += utteranceId
            return super.speak(text, queueMode, params, null)
        }
    }

    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(AlarmManager::class.java)
    private var controller: ServiceController<AlarmService>? = null
    private lateinit var service: AlarmService
    private lateinit var speech: ObservedSpeech

    @Before fun setup() {
        assertTrue(context.getSharedPreferences("routinealarm_schedule", Context.MODE_PRIVATE)
            .edit().clear().commit())
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ShadowTextToSpeech.addLanguageAvailability(Locale.GERMAN)
        ScheduleState.setEnabled(context, true)
    }

    @After fun cleanup() {
        controller?.destroy()
        shadowOf(manager).scheduledAlarms.toList().forEach {
            it.operation?.let { pending -> manager.cancel(pending); pending.cancel() }
        }
        assertTrue(context.getSharedPreferences("routinealarm_schedule", Context.MODE_PRIVATE)
            .edit().clear().commit())
    }

    private fun capturedDelivery(recurring: Boolean = false, mode: EventMode? = null): Intent {
        if (recurring) {
            val event = ScheduleRegistry.enabledEvents.first {
                !TrainingSessionPlan.isStartEvent(it.id) && (mode == null || it.mode == mode)
            }
            assertTrue(AlarmScheduler.scheduleEvent(context, event))
        } else {
            AlarmScheduler.scheduleSnooze(context, "Synthetic final fence", "Synthetic final speech")
        }
        val pending = requireNotNull(shadowOf(manager).scheduledAlarms.single {
            shadowOf(requireNotNull(it.operation)).savedIntent
                .getBooleanExtra(AlarmScheduler.EXTRA_RECURRING, false) == recurring
        }.operation)
        val intent = Intent(shadowOf(pending).savedIntent)
        assertEquals(recurring, intent.getBooleanExtra(AlarmScheduler.EXTRA_RECURRING, false))
        manager.cancel(pending)
        pending.cancel()
        return intent
    }

    private fun receiverAdmits(delivery: Intent): Intent {
        AlarmReceiver().onReceive(context, delivery)
        val intent = requireNotNull(shadowOf(context).nextStartedService)
        assertEquals(AlarmService::class.java.name, intent.component?.className)
        return intent
    }

    private fun createService() {
        controller = Robolectric.buildService(AlarmService::class.java).create()
        service = controller!!.get()
        speech = Shadow.extract(requireNotNull(ShadowTextToSpeech.getLastTextToSpeechInstance()))
    }

    private fun speechReady() {
        speech.onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun restoreFinished() {
        val name = requireNotNull(context.applicationInfo.backupAgentName)
        val agent = Class.forName(name).getDeclaredConstructor().newInstance() as BackupAgent
        ReflectionHelpers.callInstanceMethod<Void>(agent, "attach",
            ClassParameter.from(Context::class.java, context))
        agent.onCreate()
        agent.onRestoreFinished()
        agent.onDestroy()
        assertFalse(ScheduleState.isEnabled(context))
    }

    private fun assertStoppedWithoutNotification() {
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications
            .none { it.id == AlarmService.NOTIFICATION_ID })
    }

    @Test fun restoreAfterReceiverAdmissionMustBlockServiceOutput() {
        val admitted = receiverAdmits(capturedDelivery())
        restoreFinished()
        createService()
        service.onStartCommand(admitted, 0, 1)
        speechReady()
        assertTrue("Service spoke a one-shot admitted before restore", speech.spoken.isEmpty())
        assertStoppedWithoutNotification()
    }

    @Test fun restoreAfterServiceStartBeforeTtsReadyMustBlockDeferredOutput() {
        val admitted = receiverAdmits(capturedDelivery())
        createService()
        service.onStartCommand(admitted, 0, 1)
        assertTrue(speech.spoken.isEmpty())
        restoreFinished()
        speechReady()
        assertTrue("Delayed TTS callback spoke after restore invalidated admission", speech.spoken.isEmpty())
        assertStoppedWithoutNotification()
    }

    @Test fun weeklyPauseAfterReceiverAdmissionMustBlockRecurringServiceOutput() {
        val admitted = receiverAdmits(capturedDelivery(recurring = true))
        ScheduleState.setEnabled(context, false)
        createService()
        service.onStartCommand(admitted, 0, 1)
        speechReady()
        assertTrue("Paused recurring delivery spoke after receiver/service race", speech.spoken.isEmpty())
        assertStoppedWithoutNotification()
    }

    @Test fun normalWeeklyPauseStillAllowsExplicitOneShotOutput() {
        val admitted = receiverAdmits(capturedDelivery())
        ScheduleState.setEnabled(context, false)
        createService()
        service.onStartCommand(admitted, 0, 1)
        speechReady()
        assertEquals(listOf("Synthetic final speech"), speech.spoken)
    }

    @Test fun newExplicitSnoozeAfterRestoreStillAllowsOutput() {
        restoreFinished()
        val admitted = receiverAdmits(capturedDelivery())
        createService()
        service.onStartCommand(admitted, 0, 1)
        speechReady()
        assertEquals(listOf("Synthetic final speech"), speech.spoken)
    }

    @Test fun restoreMustStopNextRepeatEvenAfterWeeklyReenable() {
        val admitted = receiverAdmits(capturedDelivery())
        createService()
        service.onStartCommand(admitted, 0, 1)
        speechReady()
        assertEquals(1, speech.spoken.size)
        restoreFinished()
        ScheduleState.setEnabled(context, true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(24))
        assertEquals("Restore must revoke the active repeat payload", 1, speech.spoken.size)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun staleStartMustNotReplaceOrStopCurrentValidOutput() {
        val stale = receiverAdmits(capturedDelivery()).putExtra(AlarmScheduler.EXTRA_SPOKEN_TEXT, "Stale speech")
        restoreFinished()
        val fresh = receiverAdmits(capturedDelivery())
        createService()
        service.onStartCommand(fresh, 0, 1)
        speechReady()
        service.onStartCommand(stale, 0, 2)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
        assertEquals(listOf("Synthetic final speech", "Synthetic final speech"), speech.spoken)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test fun stopBeforeSpeechReadyMustNotResurrectOutput() {
        val admitted = receiverAdmits(capturedDelivery())
        createService()
        service.onStartCommand(admitted, 0, 1)
        val output = shadowOf(context.getSystemService(NotificationManager::class.java))
            .getNotification(AlarmService.NOTIFICATION_ID).fullScreenIntent
        val instance = shadowOf(output).savedIntent.getStringExtra(AlarmService.EXTRA_OUTPUT_INSTANCE)
        assertNotNull(instance)
        service.onStartCommand(Intent(context, AlarmService::class.java).setAction(AlarmService.ACTION_STOP)
            .putExtra(AlarmService.EXTRA_OUTPUT_INSTANCE, instance), 0, 2)
        speechReady()
        assertTrue(speech.spoken.isEmpty())
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun nullRestartMustNotCreateDefaultOutput() {
        createService()
        assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        speechReady()
        assertTrue(speech.spoken.isEmpty())
    }

    @Test fun weeklyPauseBeforeSpeechReadyMustBlockRecurringOutput() {
        val admitted = receiverAdmits(capturedDelivery(recurring = true))
        createService()
        service.onStartCommand(admitted, 0, 1)
        ScheduleState.setEnabled(context, false)
        speechReady()
        assertTrue(speech.spoken.isEmpty())
    }

    @Test fun restoredRecurringAdmissionMustStayRevokedAfterReenableAndServiceRecreation() {
        val admitted = receiverAdmits(capturedDelivery(recurring = true))
        createService()
        service.onStartCommand(admitted, 0, 1)
        controller!!.destroy()
        restoreFinished()
        ScheduleState.setEnabled(context, true)
        createService()
        service.onStartCommand(admitted, 0, 2)
        speechReady()
        assertTrue(speech.spoken.isEmpty())
        assertStoppedWithoutNotification()
    }

    private fun startAnnouncement(): String {
        val admitted = receiverAdmits(capturedDelivery(recurring = true, mode = EventMode.ANNOUNCEMENT))
        if (controller == null) {
            createService()
            service.onStartCommand(admitted, 0, 1)
            speechReady()
        } else {
            service.onStartCommand(admitted, 0, 2)
            shadowOf(Looper.getMainLooper()).idle()
        }
        return requireNotNull(speech.utteranceIds.last())
    }

    private fun startFreshSnoozeAfterRestore() {
        restoreFinished()
        service.onStartCommand(receiverAdmits(capturedDelivery()), 0, 2)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Synthetic final speech", speech.spoken.last())
    }

    private fun assertCurrentOutputStillActive() {
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("Historical TTS callback stopped the current payload", shadowOf(service).isStoppedBySelf)
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications
            .any { it.id == AlarmService.NOTIFICATION_ID })
    }

    @Test fun oldAnnouncementDoneAfterRestoreMustNotStopFreshSnooze() {
        val oldId = startAnnouncement()
        startFreshSnoozeAfterRestore()
        speech.utteranceProgressListener.onDone(oldId)
        assertCurrentOutputStillActive()
        val before = speech.spoken.size
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
        assertEquals(before + 1, speech.spoken.size)
    }

    @Test fun oldAnnouncementErrorAfterRestoreMustNotStopFreshSnooze() {
        val oldId = startAnnouncement()
        startFreshSnoozeAfterRestore()
        speech.utteranceProgressListener.onError(oldId)
        assertCurrentOutputStillActive()
    }

    @Test fun oldDetailedErrorAfterRestoreMustNotStopFreshSnooze() {
        val oldId = startAnnouncement()
        startFreshSnoozeAfterRestore()
        speech.utteranceProgressListener.onError(oldId, TextToSpeech.ERROR)
        assertCurrentOutputStillActive()
    }

    @Test fun completionQueuedBeforeReplacementMustRecheckOnHandlerExecution() {
        val oldId = startAnnouncement()
        speech.utteranceProgressListener.onDone(oldId)
        // Do not drain the Handler between callback receipt and replacement admission.
        restoreFinished()
        service.onStartCommand(receiverAdmits(capturedDelivery()), 0, 2)
        assertCurrentOutputStillActive()
    }

    @Test fun oldCompletionMustNotStopReplacementAnnouncement() {
        val oldId = startAnnouncement()
        val currentId = startAnnouncement()
        assertNotEquals("Every announcement needs an individual callback identity", oldId, currentId)
        speech.utteranceProgressListener.onDone(oldId)
        assertCurrentOutputStillActive()
        speech.utteranceProgressListener.onDone(currentId)
        shadowOf(Looper.getMainLooper()).idle()
        assertStoppedWithoutNotification()
    }

    @Test fun currentAnnouncementDoneMustStillStopItsOutput() {
        speechReadyAfterAnnouncementDone(false)
    }

    @Test fun currentAnnouncementErrorMustStillStopItsOutput() {
        speechReadyAfterAnnouncementDone(true)
    }

    private fun speechReadyAfterAnnouncementDone(error: Boolean) {
        val id = startAnnouncement()
        if (error) speech.utteranceProgressListener.onError(id, TextToSpeech.ERROR)
        else speech.utteranceProgressListener.onDone(id)
        shadowOf(Looper.getMainLooper()).idle()
        assertStoppedWithoutNotification()
    }

    @Test fun nullOrUnknownCallbackMustNotStopCurrentAnnouncement() {
        startAnnouncement()
        speech.utteranceProgressListener.onDone(null)
        speech.utteranceProgressListener.onError(null)
        speech.utteranceProgressListener.onDone("announcement_once_unknown")
        speech.utteranceProgressListener.onError("announcement_once_unknown", TextToSpeech.ERROR)
        assertCurrentOutputStillActive()
    }
}
