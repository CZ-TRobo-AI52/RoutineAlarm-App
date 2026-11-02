package com.cztr.routinealarm

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.cztr.routinealarm.execution.*
import com.cztr.routinealarm.occurrence.*
import com.cztr.routinealarm.ui.notification.*
import com.cztr.routinealarm.support.awaitTestOutput
import com.cztr.routinealarm.support.awaitTestOutputStopped
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.*
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowTextToSpeech
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Independent C2 acceptance entrance and controls, not a replacement close runtime.
 * Only the first method is a new desired acceptance RED on the current product.
 * Other methods preserve navigation, replacement-before-close and restore/TTS controls.
 * Durable restart/handle/ack cases remain explicitly pending a real C6 successor API.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31], shadows = [C2V2AsyncFixtureAcceptanceTest.ObservedSpeech::class])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class C2V2AsyncFixtureAcceptanceTest {
    @Implements(TextToSpeech::class)
    class ObservedSpeech : ShadowTextToSpeech() {
        val spoken = mutableListOf<String>()
        @Implementation
        override fun speak(text: CharSequence, queueMode: Int, params: Bundle?, utteranceId: String?): Int {
            spoken += text.toString()
            return super.speak(text, queueMode, params, null)
        }
    }

    private lateinit var app: Application
    private val screens = mutableListOf<ActivityController<TestAlarmActivity>>()
    private val services = mutableListOf<ServiceController<TestAlarmService>>()
    private val executors = mutableListOf<ExecutorService>()

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        app.deleteDatabase("occurrences.db")
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ShadowTextToSpeech.addLanguageAvailability(Locale.GERMAN)
    }

    @After fun cleanup() {
        screens.forEach { it.destroy() }
        executors.forEach { assertTrue(it.awaitTermination(10, TimeUnit.SECONDS)) }
        services.forEach { it.destroy(); awaitTestOutputStopped(it) }
        shadowOf(Looper.getMainLooper()).idle()
        app.deleteDatabase("occurrences.db")
    }

    @Test fun notificationMustOfferDistinctOutputCloseWithoutUsingDoneSkip() {
        val item = fixture()
        start(item)
        val note = notification()
        val options = note.actions.single { it.title.toString() == "Optionen" }
        val intent = Intent(shadowOf(options.actionIntent).savedIntent)
        assertNotNull("Existing real notification options transport must remain valid",
            TestAlarmNotification.decode(intent))
        val selection = NotificationMenu.select(MenuStage.OPTIONS, MenuChoice.CLOSE,
            TestAlarmNotification.allowed)
        inspect { repo ->
            assertEquals(OccurrenceStatus.TRIGGERED, repo.get(item.id)!!.status)
            assertEquals(2, repo.history(item.id).size)
        }
        assertEquals("P0: explicit output-close is unavailable; screen navigation is not durable suppression",
            MenuSelection.Request(NotificationOperation.CLOSE_OUTPUT), selection)
    }

    @Test fun screenCloseIsNavigationAndDoesNotSilenceOrCompleteOccurrence() {
        val item = fixture()
        val (_, speech) = start(item)
        ready(speech)
        assertEquals(1, speech.spoken.size)
        val value = Intent(shadowOf(requireNotNull(notification().contentIntent)).savedIntent)
        val screen = Robolectric.buildActivity(TestAlarmActivity::class.java, value).create()
        screens += screen
        val executor = executor(screen.get())
        executors += executor
        repeat(4) {
            executor.submit {}.get(10, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
        }
        views(screen.get().window.decorView).filterIsInstance<Button>()
            .single { it.text.toString() == "Schließen" }.performClick()
        assertTrue(screen.get().isFinishing)
        inspect { repo ->
            assertEquals(OccurrenceStatus.TRIGGERED, repo.get(item.id)!!.status)
            assertEquals(2, repo.history(item.id).size)
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
        services.forEach { awaitTestOutput(it) }
        assertEquals("Screen close must not be implemented as implicit output close", 2, speech.spoken.size)
    }

    @Test fun sameDeliveryReplacementBeforeAnyCloseIsAnotherRealOutput() {
        val item = fixture()
        val (old, oldSpeech) = start(item)
        ready(oldSpeech)
        assertEquals(1, oldSpeech.spoken.size)
        old.destroy()
        awaitTestOutputStopped(old)
        services.remove(old)
        val (_, freshSpeech) = start(item)
        assertNotSame(oldSpeech, freshSpeech)
        ready(freshSpeech)
        old.get().onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
        services.forEach { awaitTestOutput(it) }
        assertEquals("Destroyed service callback resurrected old speech", 1, oldSpeech.spoken.size)
        assertEquals(2, freshSpeech.spoken.size)
        inspect { repo ->
            assertEquals(OccurrenceStatus.TRIGGERED, repo.get(item.id)!!.status)
            assertEquals(2, repo.history(item.id).size)
        }
    }

    @Test fun restoreBeforeTtsReadyRejectsOldDeliveryAndDoesNotCompleteIt() {
        val item = fixture()
        val (service, speech) = start(item)
        val epoch = inspect { it.deliveryEpoch() }
        val backup = inspect { it.exportBackup() }
        assertTrue(app.deleteDatabase("occurrences.db"))
        inspect { it.restoreBackup(backup) }
        ready(speech)
        assertTrue("Old deferred TTS spoke after restore", speech.spoken.isEmpty())
        assertTrue(shadowOf(service.get()).isStoppedBySelf)
        inspect { repo ->
            assertNotEquals(epoch, repo.deliveryEpoch())
            assertFalse(repo.isDeliveryAllowed(item.id))
            assertFalse(repo.history(item.id).any { it.actionType in setOf("DONE", "SKIP") })
        }
    }

    private fun fixture(): RoutineOccurrence = inspect { repo ->
        val now = Instant.ofEpochMilli(System.currentTimeMillis())
        val planned = TestAlarmCoordinator(repo, Clock.fixed(now.minusSeconds(61), ZoneOffset.UTC)).create(1)
        TestAlarmCoordinator(repo, Clock.fixed(now, ZoneOffset.UTC))
            .trigger(AlarmDelivery.from(planned, repo.deliveryEpoch()))!!
    }

    private fun start(item: RoutineOccurrence): Pair<ServiceController<TestAlarmService>, ObservedSpeech> {
        val controller = Robolectric.buildService(TestAlarmService::class.java).create()
        services += controller
        val speech: ObservedSpeech =
            Shadow.extract(requireNotNull(ShadowTextToSpeech.getLastTextToSpeechInstance()))
        val token = inspect { AlarmDelivery.from(item, it.deliveryEpoch()) }
        controller.get().onStartCommand(TestAlarmRuntime.putToken(
            Intent(app, TestAlarmService::class.java), token), 0, 1)
        awaitTestOutput(controller)
        return controller to speech
    }

    private fun ready(speech: ObservedSpeech) {
        speech.onInitListener.onInit(TextToSpeech.SUCCESS)
        services.forEach { awaitTestOutput(it) }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun notification(): Notification = requireNotNull(
        shadowOf(app.getSystemService(NotificationManager::class.java))
            .getNotification(TestAlarmService.NOTIFICATION))

    private fun executor(activity: TestAlarmActivity): ExecutorService {
        val reader = TestAlarmActivity::class.java.getDeclaredField("reader")
            .apply { isAccessible = true }.get(activity)
        return reader.javaClass.declaredFields.single {
            ExecutorService::class.java.isAssignableFrom(it.type)
        }.apply { isAccessible = true }.get(reader) as ExecutorService
    }

    private fun <T> inspect(read: (SqliteOccurrenceRepository) -> T): T =
        TestAlarmRuntime.repository(app).use(read)

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
