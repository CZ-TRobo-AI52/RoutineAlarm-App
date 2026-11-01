@file:Suppress("DEPRECATION")

package com.cztr.routinealarm.execution

import com.cztr.routinealarm.UiReadDatabaseGate
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Real service/SQLite/Handler lifecycle; records speech requests, not acoustic output. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31], shadows = [TestAlarmServiceLifecycleTest.ObservedSpeech::class, UiReadDatabaseGate::class])
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
        UiReadDatabaseGate.reset()
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
        executor(controller).awaitTermination(10, TimeUnit.SECONDS)
        controllers.remove(controller)
    }

    @After fun cleanup() {
        UiReadDatabaseGate.reset()
        controllers.toList().forEach(::destroy)
        repo.close()
        context.deleteDatabase("occurrences.db")
    }

    private fun executor(controller: ServiceController<TestAlarmService>): ExecutorService {
        val field = TestAlarmService::class.java.getDeclaredField("reader")
        field.isAccessible = true
        return field.get(controller.get()) as ExecutorService
    }

    private fun drain() {
        repeat(4) {
            controllers.forEach { executor(it).submit {}.get(10, TimeUnit.SECONDS) }
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun staleArrivalCannotEraseTheAlreadyAdmittedNotification() {
        val (controller, _) = start()
        drain()
        val notes = shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
        val before = notes.getNotification(TestAlarmService.NOTIFICATION)!!
        controller.get().onStartCommand(TestAlarmRuntime.putToken(Intent(context,
            TestAlarmService::class.java), token.copy(generation = token.generation + 99)), 0, 2)
        assertEquals("A stale arrival erased admitted notification actions before validation",
            before.contentIntent, notes.getNotification(TestAlarmService.NOTIFICATION)!!.contentIntent)
        drain()
        assertEquals(token, TestAlarmRuntime.token(shadowOf(
            notes.getNotification(TestAlarmService.NOTIFICATION)!!.contentIntent).savedIntent))
        assertEquals(triggered, repo.get(triggered.id))
    }

    @Test fun committedSnoozeRejectsAnAlreadyQueuedSpeechSnapshot() {
        val (controller, speech) = start()
        speech.onInitListener.onInit(TextToSpeech.SUCCESS)
        executor(controller).submit {}.get(10, TimeUnit.SECONDS)
        val action = StableTestAlarmActions(repo, Clock.systemUTC()).prepare(
            "test-user:queued-output-snooze", token, ActionType.SNOOZE, 1)
        assertEquals(Outcome.APPLIED, StableTestAlarmActions(repo, Clock.systemUTC()).submit(action).outcome)
        drain()
        assertTrue("A prior-generation snapshot spoke after Snooze", speech.requests.isEmpty())
        assertEquals(OccurrenceStatus.SNOOZED, repo.get(triggered.id)!!.status)
        assertEquals(token.generation + 1, repo.get(triggered.id)!!.generation)
    }

    @Test fun restoredEpochRejectsAnAlreadyQueuedSpeechSnapshot() {
        val backup = repo.exportBackup()
        val (controller, speech) = start()
        speech.onInitListener.onInit(TextToSpeech.SUCCESS)
        executor(controller).submit {}.get(10, TimeUnit.SECONDS)
        repo.close()
        context.deleteDatabase("occurrences.db")
        repo = TestAlarmRuntime.repository(context)
        assertEquals(1, repo.restoreBackup(backup))
        assertNotEquals(token.epoch, repo.deliveryEpoch())
        drain()
        assertTrue("Pre-restore queued speech crossed the epoch/quarantine boundary", speech.requests.isEmpty())
        assertFalse(repo.isDeliveryAllowed(triggered.id))
        assertEquals(triggered, repo.get(triggered.id))
    }

    @Test fun committedDoneRejectsAnAlreadyQueuedSpeechSnapshot() {
        val (controller, speech) = start()
        speech.onInitListener.onInit(TextToSpeech.SUCCESS)
        executor(controller).submit {}.get(10, TimeUnit.SECONDS)
        val action = StableTestAlarmActions(repo, Clock.systemUTC()).prepare(
            "test-user:queued-output-done", token, ActionType.DONE)
        assertEquals(Outcome.APPLIED, StableTestAlarmActions(repo, Clock.systemUTC()).submit(action).outcome)
        drain()
        assertTrue("A pre-commit snapshot spoke after DONE", speech.requests.isEmpty())
        assertEquals(OccurrenceStatus.DONE, repo.get(triggered.id)!!.status)
    }

    @Test fun destroyedServiceDiscardsAnUncancellableDatabaseReply() {
        val gate = UiReadDatabaseGate.holdNext()
        val (controller, speech) = start()
        gate.awaitEntered()
        controller.get().onDestroy()
        gate.release.countDown()
        executor(controller).awaitTermination(10, TimeUnit.SECONDS)
        controllers.remove(controller)
        UiReadDatabaseGate.reset()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(speech.requests.isEmpty())
        assertEquals(triggered, repo.get(triggered.id))
    }

    @Test fun serviceStartNeverOpensSQLiteOnMainThread() {
        UiReadDatabaseGate.armed = true
        start()
        assertEquals("Service start performed SQLite work on the main thread", 0,
            UiReadDatabaseGate.mainAccesses.get())
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
        drain()
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
        drain()
        assertEquals(1, newSpeech.requests.size)
        oldSpeech.onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        assertTrue("Old service competed with replacement", oldSpeech.requests.isEmpty())
        drain()
        assertEquals("Replacement must retain its normal repetition", 2, newSpeech.requests.size)
        assertEquals(triggered, repo.get(triggered.id))
    }

    @Test fun liveServiceStillInitializesAndRepeatsNormally() {
        val (_, speech) = start()
        speech.onInitListener.onInit(TextToSpeech.SUCCESS)
        drain()
        assertEquals(listOf(triggered.spokenText), speech.requests)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        drain()
        assertEquals(listOf(triggered.spokenText, triggered.spokenText), speech.requests)
        assertEquals(triggered, repo.get(triggered.id))
    }

    @Test fun backgroundInitializationWaitsForMainThreadBeforeOutput() {
        val (_, speech) = start()
        Thread { speech.onInitListener.onInit(TextToSpeech.SUCCESS) }.apply { start(); join() }
        assertTrue("Background callback must not mutate service output", speech.requests.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        drain()
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
