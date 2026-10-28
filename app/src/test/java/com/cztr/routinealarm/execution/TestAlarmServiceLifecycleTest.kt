@file:Suppress("DEPRECATION")

package com.cztr.routinealarm.execution

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.speech.tts.TextToSpeech
import com.cztr.routinealarm.occurrence.*
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
import org.robolectric.annotation.*
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowTextToSpeech
import java.time.Clock
import java.time.Duration
import java.util.Locale

/** Real service/SQLite/Handler lifecycle; records speech requests, not acoustic output. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31], shadows = [TestAlarmServiceLifecycleTest.ObservedSpeech::class])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TestAlarmServiceLifecycleTest {
    @Implements(TextToSpeech::class)
    class ObservedSpeech : ShadowTextToSpeech() {
        val requests = mutableListOf<String>()
        @Implementation
        override fun speak(text: CharSequence, queueMode: Int, params: Bundle?, utteranceId: String?): Int {
            requests += text.toString()
            return super.speak(text, queueMode, params, null)
        }
    }

    private val context get() = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<ServiceController<TestAlarmService>>()
    private lateinit var repo: SqliteOccurrenceRepository
    private lateinit var token: AlarmDelivery
    private lateinit var triggered: RoutineOccurrence

    @Before fun setup() {
        context.deleteDatabase("occurrences.db")
        ShadowTextToSpeech.addLanguageAvailability(Locale.GERMAN)
        repo = TestAlarmRuntime.repository(context)
        val created = TestAlarmCoordinator(repo,
            Clock.offset(Clock.systemDefaultZone(), Duration.ofMinutes(-2))).create(1)
        triggered = TestAlarmRuntime.coordinator(repo).trigger(AlarmDelivery.from(created, repo.deliveryEpoch()))!!
        token = AlarmDelivery.from(triggered, repo.deliveryEpoch())
    }

    private fun start(): Pair<ServiceController<TestAlarmService>, ObservedSpeech> {
        val controller = Robolectric.buildService(TestAlarmService::class.java).create()
        controllers += controller
        val speech: ObservedSpeech = Shadow.extract(requireNotNull(ShadowTextToSpeech.getLastTextToSpeechInstance()))
        controller.get().onStartCommand(TestAlarmRuntime.putToken(
            Intent(context, TestAlarmService::class.java), token), 0, 1)
        return controller to speech
    }

    private fun destroy(controller: ServiceController<TestAlarmService>) {
        controller.destroy()
        controllers.remove(controller)
    }

    @After fun cleanup() {
        controllers.toList().forEach(::destroy)
        repo.close()
        context.deleteDatabase("occurrences.db")
    }

    @Test fun lateSuccessfulInitializationCannotSpeakAfterDestroy() {
        val (controller, speech) = start()
        val history = repo.history(triggered.id)
        destroy(controller)
        speech.onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        assertTrue("Destroyed service submitted speech after late initialization", speech.requests.isEmpty())
        assertEquals(triggered, repo.get(triggered.id))
        assertEquals(history, repo.history(triggered.id))
    }

    @Test fun lateFailedInitializationCannotRestartAnAlreadyStoppedOutput() {
        val (controller, speech) = start()
        speech.onInitListener.onInit(TextToSpeech.SUCCESS)
        assertEquals(1, speech.requests.size)
        destroy(controller)
        speech.onInitListener.onInit(TextToSpeech.ERROR)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        assertEquals("Late failure callback restarted a destroyed service", 1, speech.requests.size)
        assertEquals(triggered, repo.get(triggered.id))
    }

    @Test fun oldCallbackCannotSpeakAlongsideReplacementService() {
        val (old, oldSpeech) = start()
        destroy(old)
        val (_, newSpeech) = start()
        newSpeech.onInitListener.onInit(TextToSpeech.SUCCESS)
        assertEquals(1, newSpeech.requests.size)
        oldSpeech.onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        assertTrue("Old service competed with replacement", oldSpeech.requests.isEmpty())
        assertEquals("Replacement must retain its normal repetition", 2, newSpeech.requests.size)
        assertEquals(triggered, repo.get(triggered.id))
    }

    @Test fun liveServiceStillInitializesAndRepeatsNormally() {
        val (_, speech) = start()
        speech.onInitListener.onInit(TextToSpeech.SUCCESS)
        assertEquals(listOf(triggered.spokenText), speech.requests)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        assertEquals(listOf(triggered.spokenText, triggered.spokenText), speech.requests)
        assertEquals(triggered, repo.get(triggered.id))
    }

    @Test fun backgroundInitializationWaitsForMainThreadBeforeOutput() {
        val (_, speech) = start()
        Thread { speech.onInitListener.onInit(TextToSpeech.SUCCESS) }.apply { start(); join() }
        assertTrue("Background callback must not mutate service output", speech.requests.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(triggered.spokenText), speech.requests)
    }

    @Test fun queuedInitializationCannotOutliveServiceDestruction() {
        val (controller, speech) = start()
        Thread { speech.onInitListener.onInit(TextToSpeech.SUCCESS) }.apply { start(); join() }
        destroy(controller)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        assertTrue("Initialization work survived its service", speech.requests.isEmpty())
        assertEquals(triggered, repo.get(triggered.id))
    }
}
