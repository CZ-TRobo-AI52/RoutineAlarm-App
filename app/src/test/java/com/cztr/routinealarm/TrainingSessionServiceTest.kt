@file:Suppress("DEPRECATION")

package com.cztr.routinealarm

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowTextToSpeech
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import java.util.SimpleTimeZone
import java.util.TimeZone

/**
 * Real service lifecycle and Handler work, with manually completed TTS requests.
 * The test changes only the default zone, not the host clock. ZonedDateTime.now() remains real;
 * the observed local time is checked before using the deliberately wide cue-time margins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], shadows = [TrainingSessionServiceTest.ControlledTextToSpeech::class])
@LooperMode(LooperMode.Mode.PAUSED)
class TrainingSessionServiceTest {
    data class Spoken(val text: String, val queueMode: Int, val id: String?)

    /** Retain the standard shadow's observations, but let each test deliver real captured IDs. */
    @Implements(TextToSpeech::class)
    class ControlledTextToSpeech : ShadowTextToSpeech() {
        val requests = mutableListOf<Spoken>()
        var stopCalls = 0
        @Implementation override fun stop(): Int { stopCalls++; return super.stop() }

        @Implementation
        override fun speak(text: CharSequence, queueMode: Int, params: Bundle?, utteranceId: String?): Int {
            requests += Spoken(text.toString(), queueMode, utteranceId)
            // A null ID prevents the stock shadow from automatically posting onDone.
            return super.speak(text, queueMode, params, null)
        }
    }

    private lateinit var originalZone: TimeZone
    private var controller: ServiceController<TrainingSessionService>? = null
    private lateinit var service: TrainingSessionService
    private lateinit var speech: ControlledTextToSpeech
    private lateinit var wakeLock: PowerManager.WakeLock
    private val main get() = shadowOf(Looper.getMainLooper())

    @Before fun setup() {
        originalZone = TimeZone.getDefault()
        placeRealLocalTimeAt(LocalTime.of(17, 20))
        ShadowTextToSpeech.addLanguageAvailability(Locale.GERMAN)
    }

    @After fun cleanup() {
        try {
            controller?.destroy()
            controller = null
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }

    private fun placeRealLocalTimeAt(target: LocalTime) {
        val utc = ZonedDateTime.now(ZoneOffset.UTC)
        val seconds = Math.floorMod(target.toSecondOfDay() - utc.toLocalTime().toSecondOfDay() +
            43_200, 86_400) - 43_200
        val offset = ZoneOffset.ofTotalSeconds(seconds)
        TimeZone.setDefault(SimpleTimeZone(seconds * 1_000,
            if (seconds == 0) "GMT" else "GMT${offset.id}"))
        val observed = ZonedDateTime.now().toLocalTime()
        assertTrue("Fixture's real local time must be near $target, was $observed",
            !observed.isBefore(target) && observed.isBefore(target.plusSeconds(30)))
    }

    private fun create() {
        controller = Robolectric.buildService(TrainingSessionService::class.java).create()
        service = controller!!.get()
        val tts = requireNotNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
        speech = Shadow.extract(tts)
        wakeLock = requireNotNull(ShadowPowerManager.getLatestWakeLock())
        assertTrue(wakeLock.isHeld)
    }

    private fun start(day: DayOfWeek) {
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(
            Intent(service, TrainingSessionService::class.java)
                .putExtra("training_day", day.name), 0, 1))
    }

    private fun ready() {
        speech.onInitListener.onInit(TextToSpeech.SUCCESS)
    }

    private fun stopFromNotification() {
        val notification = requireNotNull(shadowOf(service).lastForegroundNotification)
        val stopIntent = Intent(shadowOf(notification.actions.single().actionIntent).savedIntent)
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(stopIntent, 0, 2))
    }

    private fun assertStopped() {
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).notificationShouldRemoved)
        assertTrue(speech.isStopped)
    }

    @Test fun successfulInitSchedulesTheRealFirstCueAndUsesGermanQueueFlushSpeech() {
        create()
        start(DayOfWeek.MONDAY)
        assertTrue(speech.requests.isEmpty())
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertEquals(2001, shadowOf(service).lastForegroundNotificationId)

        ready()
        assertEquals(Locale.GERMAN, speech.currentLanguage)
        assertTrue(speech.requests.isEmpty())
        main.runToNextTask()

        val first = TrainingSessionPlan.eventsFor(DayOfWeek.MONDAY).first()
        assertEquals(first.spokenText, speech.requests.single().text)
        assertEquals(TextToSpeech.QUEUE_FLUSH, speech.requests.single().queueMode)
        assertFalse(speech.requests.single().id.isNullOrBlank())
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test fun initializationFailureStopsForegroundAndDestroyReleasesResources() {
        create()
        start(DayOfWeek.MONDAY)

        speech.onInitListener.onInit(TextToSpeech.ERROR)

        assertStopped()
        controller!!.destroy()
        controller = null
        assertFalse(wakeLock.isHeld)
        assertTrue(speech.isShutdown)
        main.idleFor(Duration.ofHours(12))
        assertTrue(speech.requests.isEmpty())
    }

    @Test fun sundayWithNoCuesStopsWhenInitializationCompletesAfterStart() {
        create()
        start(DayOfWeek.SUNDAY)

        ready()

        assertStopped()
        main.idleFor(Duration.ofHours(12))
        assertTrue(speech.requests.isEmpty())
    }

    @Test fun initializationBeforeAnyStartStillAllowsTheFirstSession() {
        create()
        ready()
        assertFalse(shadowOf(service).isStoppedBySelf)

        start(DayOfWeek.MONDAY)
        main.runToNextTask()

        assertEquals(TrainingSessionPlan.eventsFor(DayOfWeek.MONDAY).first().spokenText,
            speech.requests.single().text)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test fun completelyExpiredCuesStopWithoutSpeaking() {
        placeRealLocalTimeAt(LocalTime.of(23, 0))
        assertTrue(TrainingSessionPlan.eventsFor(DayOfWeek.MONDAY)
            .all { it.minuteOfDay < 22 * 60 })
        create()
        start(DayOfWeek.MONDAY)

        ready()

        assertStopped()
        main.idleFor(Duration.ofHours(12))
        assertTrue(speech.requests.isEmpty())
    }

    @Test fun notificationStopRemovesScheduledCuesAndDestroyReleasesWakeLock() {
        create()
        start(DayOfWeek.MONDAY)
        ready()
        main.runToNextTask()
        val before = speech.requests.toList()

        stopFromNotification()

        assertStopped()
        main.idleFor(Duration.ofHours(12))
        assertEquals(before, speech.requests)
        controller!!.destroy()
        controller = null
        assertFalse(wakeLock.isHeld)
        assertTrue(speech.isShutdown)
    }

    @Test fun destroyCancelsFutureCallbacksAndShutsDownSpeech() {
        create()
        start(DayOfWeek.MONDAY)
        ready()
        main.runToNextTask()
        val before = speech.requests.toList()

        controller!!.destroy()
        controller = null

        assertFalse(wakeLock.isHeld)
        assertTrue(speech.isStopped)
        assertTrue(speech.isShutdown)
        main.idleFor(Duration.ofHours(12))
        assertEquals(before, speech.requests)
    }

    @Test fun lateSuccessfulInitAfterNotificationStopCannotResurrectCues() {
        create()
        start(DayOfWeek.MONDAY)
        stopFromNotification()
        assertStopped()

        ready()
        main.idleFor(Duration.ofHours(12))

        assertTrue("Stopped session was spoken after delayed TTS initialization", speech.requests.isEmpty())
    }

    @Test fun replacementCancelsOldDayCallbacksBeforeTheNextCue() {
        create()
        start(DayOfWeek.MONDAY)
        ready()

        start(DayOfWeek.TUESDAY)
        main.runToNextTask()

        assertEquals(TrainingSessionPlan.eventsFor(DayOfWeek.TUESDAY).first().spokenText,
            speech.requests.single().text)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test fun staleCompletionFromSameDaySessionCannotStopItsReplacement() {
        val last = TrainingSessionPlan.eventsFor(DayOfWeek.MONDAY).last()
        placeRealLocalTimeAt(LocalTime.of(last.hour, last.minute).minusSeconds(30))
        create()
        start(DayOfWeek.MONDAY)
        ready()
        main.runToNextTask()
        assertEquals(last.spokenText, speech.requests.single().text)
        val oldId = requireNotNull(speech.requests.single().id)

        start(DayOfWeek.MONDAY)
        speech.utteranceProgressListener.onDone(oldId)
        main.idle()

        assertFalse("Old utterance completion stopped the replacement session",
            shadowOf(service).isStoppedBySelf)
        main.runToNextTask()
        val currentId = requireNotNull(speech.requests.last().id)
        assertNotEquals(oldId, currentId)
        speech.utteranceProgressListener.onDone(currentId)
        main.idle()
        assertStopped()
    }
    private val app get() = org.robolectric.RuntimeEnvironment.getApplication()
    private fun weeklyDelivery(training: Boolean = true): Intent {
        ScheduleState.setEnabled(app, true)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val event = ScheduleRegistry.enabledEvents.first {
            TrainingSessionPlan.isStartEvent(it.id) == training &&
                (!training || it.daysOfWeek.contains(DayOfWeek.MONDAY))
        }
        AlarmReceiver().onReceive(app, Intent(app, AlarmReceiver::class.java)
            .putExtra(AlarmScheduler.EXTRA_RECURRING, true)
            .putExtra(AlarmScheduler.EXTRA_EVENT_ID, event.id)
            .putExtra(AlarmScheduler.EXTRA_RESTORE_GENERATION, ScheduleState.restoreGeneration(app)))
        return requireNotNull(shadowOf(app).nextStartedService)
    }
    private fun awaitPauseWorker() {
        ScheduleState.requestEnabled(app, false) {}
        val f = ScheduleState::class.java.declaredFields.single {
            java.util.concurrent.ExecutorService::class.java.isAssignableFrom(it.type)
        }.apply { isAccessible = true }
        (f.get(null) as java.util.concurrent.ExecutorService).submit {}.get(10, java.util.concurrent.TimeUnit.SECONDS)
    }
    private fun alarmIntent(text: String) = Intent(app, AlarmService::class.java)
        .putExtra(AlarmScheduler.EXTRA_RECURRING, false)
        .putExtra(AlarmScheduler.EXTRA_RESTORE_GENERATION, ScheduleState.restoreGeneration(app))
        .putExtra(AlarmScheduler.EXTRA_SPOKEN_TEXT, text)
    private fun alarmSpeech(): ControlledTextToSpeech =
        Shadow.extract(requireNotNull(ShadowTextToSpeech.getLastTextToSpeechInstance()))

    @Test fun weeklyReceiverPreservesTrainingAdmission() {
        val delivered = weeklyDelivery()
        assertEquals(TrainingSessionService::class.java.name, delivered.component!!.className)
        assertTrue("P1_RED: receiver lost weekly admission", delivered.getBooleanExtra(AlarmScheduler.EXTRA_RECURRING, false))
        assertEquals(ScheduleState.restoreGeneration(app),
            delivered.getStringExtra(AlarmScheduler.EXTRA_RESTORE_GENERATION))
        assertNotNull(delivered.getStringExtra(AlarmScheduler.EXTRA_EVENT_ID))
    }
    @Test fun confirmedPauseStopsWeeklyTrainingAndPreservesConcurrentExplicitAlarm() {
        create(); service.onStartCommand(weeklyDelivery(), 0, 1); ready(); main.runToNextTask()
        val weeklyBefore = speech.requests.toList(); val weeklyStops = speech.stopCalls
        val alarm = Robolectric.buildService(AlarmService::class.java).create()
        val explicit = alarmSpeech()
        try {
            alarm.get().onStartCommand(alarmIntent("Explicit isolated output"), 0, 1)
            explicit.onInitListener.onInit(TextToSpeech.SUCCESS); main.idle()
            val explicitStops = explicit.stopCalls
            awaitPauseWorker(); main.idle()
            assertTrue("P1_RED: confirmed pause left weekly training alive", shadowOf(service).isStoppedBySelf)
            assertTrue(speech.stopCalls > weeklyStops)
            assertEquals(explicitStops, explicit.stopCalls)
            assertFalse(shadowOf(alarm.get()).isStoppedBySelf)
            main.idleFor(Duration.ofMinutes(5))
            assertEquals(weeklyBefore, speech.requests)
            assertTrue(explicit.requests.size > 1)
        } finally { alarm.destroy() }
    }
    @Test fun confirmedPauseImmediatelyStopsWeeklyAlarmSpeech() {
        val alarm = Robolectric.buildService(AlarmService::class.java).create()
        val spoken = alarmSpeech()
        try {
            alarm.get().onStartCommand(weeklyDelivery(false), 0, 1)
            spoken.onInitListener.onInit(TextToSpeech.SUCCESS); main.idle()
            val before = spoken.requests.toList(); val stops = spoken.stopCalls
            awaitPauseWorker(); main.idle()
            assertTrue("P1_RED: weekly alarm waits for repeat instead of stop", shadowOf(alarm.get()).isStoppedBySelf)
            assertTrue(spoken.stopCalls > stops)
            main.idleFor(Duration.ofSeconds(30)); assertEquals(before, spoken.requests)
        } finally { alarm.destroy() }
    }
    @Test fun delayedWeeklyStopCannotStopExplicitTrainingReplacement() {
        create(); service.onStartCommand(weeklyDelivery(), 0, 1); ready()
        awaitPauseWorker() // Pause acknowledged, but its main-thread stops are not drained.
        start(DayOfWeek.TUESDAY)
        val stops = speech.stopCalls
        main.idle()
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertEquals(stops, speech.stopCalls)
        main.runToNextTask(); assertTrue(speech.requests.isNotEmpty())
    }
    @Test fun delayedWeeklyStopCannotStopReenabledWeeklyTrainingReplacement() {
        create(); service.onStartCommand(weeklyDelivery(), 0, 1); ready()
        awaitPauseWorker()
        service.onStartCommand(weeklyDelivery(), 0, 2)
        val stops = speech.stopCalls
        main.idle(); assertFalse(shadowOf(service).isStoppedBySelf)
        assertEquals(stops, speech.stopCalls)
    }
    @Test fun confirmedPauseBeforeTrainingInitCannotResurrectCues() {
        create(); service.onStartCommand(weeklyDelivery(), 0, 1)
        awaitPauseWorker(); main.idle(); ready(); main.idleFor(Duration.ofHours(2))
        assertTrue("P1_RED: weekly training survived pause before TTS ready", shadowOf(service).isStoppedBySelf)
        assertTrue(speech.requests.isEmpty())
    }
    @Test fun restoreBeforeTrainingInitRevokesOriginalAdmission() {
        create(); service.onStartCommand(weeklyDelivery(), 0, 1)
        ScheduleState.disableAfterRestore(app); ready()
        assertTrue("P1_RED: training ignored original restore generation", shadowOf(service).isStoppedBySelf)
        main.idleFor(Duration.ofHours(2)); assertTrue(speech.requests.isEmpty())
    }
    @Test fun delayedWeeklyStopCannotStopExplicitAlarmReplacement() {
        val alarm = Robolectric.buildService(AlarmService::class.java).create()
        val spoken = alarmSpeech()
        try {
            alarm.get().onStartCommand(weeklyDelivery(false), 0, 1)
            spoken.onInitListener.onInit(TextToSpeech.SUCCESS); main.idle()
            awaitPauseWorker()
            alarm.get().onStartCommand(alarmIntent("Replacement explicit"), 0, 2)
            val stops = spoken.stopCalls
            main.idle()
            assertFalse(shadowOf(alarm.get()).isStoppedBySelf)
            assertEquals(stops, spoken.stopCalls)
            assertEquals("Replacement explicit", spoken.requests.last().text)
        } finally { alarm.destroy() }
    }

    private fun awaitPauseAndEnableWithoutMainDrain() {
        ScheduleState.requestEnabled(app, false) {}
        ScheduleState.requestEnabled(app, true) {}
        val field = ScheduleState::class.java.declaredFields.single {
            java.util.concurrent.ExecutorService::class.java.isAssignableFrom(it.type)
        }.apply { isAccessible = true }
        (field.get(null) as java.util.concurrent.ExecutorService).submit {}
            .get(10, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue("Both ordered commits must finish enabled before old stop is dispatched",
            ScheduleState.isEnabled(app))
    }
    @Test fun confirmedPauseStillClosesOldWeeklyAlarmAfterEnable() {
        val alarm = Robolectric.buildService(AlarmService::class.java).create()
        val spoken = alarmSpeech()
        try {
            alarm.get().onStartCommand(weeklyDelivery(false), 0, 1)
            spoken.onInitListener.onInit(TextToSpeech.SUCCESS); main.idle()
            val instance = org.robolectric.util.ReflectionHelpers.getField<String>(alarm.get(), "activeOutputInstance")
            val before = spoken.requests.toList(); val stops = spoken.stopCalls
            awaitPauseAndEnableWithoutMainDrain()
            assertEquals(instance, org.robolectric.util.ReflectionHelpers.getField<String>(alarm.get(), "activeOutputInstance"))
            main.idle()
            assertTrue("V3_RED: re-enable revoked confirmed stop of same alarm instance",
                shadowOf(alarm.get()).isStoppedBySelf)
            assertTrue(spoken.stopCalls > stops)
            main.idleFor(Duration.ofSeconds(30)); assertEquals(before, spoken.requests)
        } finally { alarm.destroy() }
    }
    @Test fun confirmedPauseStillClosesOldWeeklyTrainingAfterEnable() {
        create(); service.onStartCommand(weeklyDelivery(), 0, 1); ready(); main.runToNextTask()
        val session = org.robolectric.util.ReflectionHelpers.getField<String>(service, "sessionId")
        val before = speech.requests.toList(); val stops = speech.stopCalls
        awaitPauseAndEnableWithoutMainDrain()
        assertEquals(session, org.robolectric.util.ReflectionHelpers.getField<String>(service, "sessionId"))
        main.idle()
        assertTrue("V3_RED: re-enable revoked confirmed stop of same training session",
            shadowOf(service).isStoppedBySelf)
        assertTrue(speech.stopCalls > stops)
        main.idleFor(Duration.ofMinutes(5)); assertEquals(before, speech.requests)
    }

}
