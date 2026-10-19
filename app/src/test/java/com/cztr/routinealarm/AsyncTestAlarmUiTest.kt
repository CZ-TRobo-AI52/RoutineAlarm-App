package com.cztr.routinealarm

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.cztr.routinealarm.execution.TestAlarmActivity
import com.cztr.routinealarm.execution.TestAlarmRuntime
import com.cztr.routinealarm.occurrence.AlarmDelivery
import com.cztr.routinealarm.occurrence.RoutineOccurrence
import com.cztr.routinealarm.occurrence.TestAlarmCoordinator
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.RealObject
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real activities, repository queries and native SQLite. Only the first helper access
 * in each read is delayed/faulted, like a slow open; no production test hooks or fake rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], shadows = [UiReadDatabaseGate::class])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AsyncTestAlarmUiTest {
    private lateinit var context: Context
    private val activities = mutableListOf<ActivityController<out Activity>>()

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        UiReadDatabaseGate.reset()
        context.deleteDatabase("occurrences.db")
        ScheduleState.setEnabled(context, false)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After fun cleanup() {
        val executors = activities.mapNotNull { readExecutor(it.get()) }
        activities.forEach { it.destroy() }
        UiReadDatabaseGate.releaseAll()
        executors.forEach { assertTrue("UI reader did not stop", it.awaitTermination(10, TimeUnit.SECONDS)) }
        shadowOf(Looper.getMainLooper()).idle()
        UiReadDatabaseGate.reset()
        context.deleteDatabase("occurrences.db")
    }

    @Test fun mainStatusReadCannotBlockConstructionOrNavigation() {
        fixture("main-future")
        val gate = UiReadDatabaseGate.holdNext()
        val controller = main()
        assertOffMain()
        gate.awaitEntered()
        val activity = controller.get()
        assertTrue(labels(activity).contains("Teststatus wird geladen …"))
        assertMainEventRuns()
        button(activity, "Testverlauf öffnen").performClick()
        assertEquals(TestAlarmActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component!!.className)
        gate.release.countDown()
        awaitUi { labels(activity).contains("1 offener Testwecker") }
        assertOffMain()
    }

    @Test fun historyReadCannotBlockConstructionOrMainEvents() {
        fixture("history-future")
        val gate = UiReadDatabaseGate.holdNext()
        val controller = history()
        assertOffMain()
        gate.awaitEntered()
        assertTrue(labels(controller.get()).contains("Testverlauf wird geladen …"))
        assertMainEventRuns()
        gate.release.countDown()
        awaitUi { buttons(controller.get()).any { it.text == "Erneut planen" } }
        assertFalse(labels(controller.get()).contains("Keine weiteren Testwecker vorhanden."))
        assertOffMain()
    }

    @Test fun newAlarmIntentRejectsTheOlderReadWhileTheNewReadIsStillBlocked() {
        val first = fixture("first-active", active = true)
        val second = fixture("second-active", active = true)
        val old = UiReadDatabaseGate.holdNext()
        val current = UiReadDatabaseGate.holdNext()
        val controller = history(alarmIntent(first))
        assertOffMain()
        old.awaitEntered()
        controller.newIntent(alarmIntent(second))
        old.release.countDown()
        current.awaitEntered() // The serialized first task has now posted its callback.
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("Old alarm must not replace the newer intent", labels(controller.get()).contains(first.title))
        assertTrue(labels(controller.get()).contains("Testwecker wird geprüft …"))
        current.release.countDown()
        awaitUi { labels(controller.get()).contains(second.title) }
        assertFalse(labels(controller.get()).contains(first.title))
    }

    @Test fun historyPageResultCannotReplaceAnAlarmOpenedWhileThatPageLoads() {
        repeat(21) { fixture("history-$it", futureSeconds = 600L + it) }
        val active = fixture("opened-during-page", active = true)
        UiReadDatabaseGate.armed = true
        val controller = history()
        assertOffMain()
        awaitUi { buttons(controller.get()).any { it.text == "Ältere Tests" } }
        val page = UiReadDatabaseGate.holdNext()
        val alarm = UiReadDatabaseGate.holdNext()
        button(controller.get(), "Ältere Tests").performClick()
        page.awaitEntered()
        controller.newIntent(alarmIntent(active))
        page.release.countDown()
        alarm.awaitEntered()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("A stale page must not restore the history screen",
            labels(controller.get()).any { it.startsWith("Testwecker ab Eintrag 21") })
        assertTrue(labels(controller.get()).contains("Testwecker wird geprüft …"))
        alarm.release.countDown()
        awaitUi { labels(controller.get()).contains(active.title) }
    }

    @Test fun stoppedMainActivityRejectsAReadThatFinishesAfterStop() {
        fixture("stopped-future")
        val gate = UiReadDatabaseGate.holdNext()
        val controller = main().start()
        assertOffMain()
        gate.awaitEntered()
        val executor = requireNotNull(readExecutor(controller.get()))
        controller.stop()
        val stoppedUi = labels(controller.get())
        gate.release.countDown()
        executor.submit {}.get(10, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Stopped screen received a late status result", stoppedUi, labels(controller.get()))
        assertFalse(labels(controller.get()).contains("1 offener Testwecker"))
    }

    @Test fun destroyedHistoryCannotBeRepopulatedByItsPendingRead() {
        fixture("destroyed-history")
        val gate = UiReadDatabaseGate.holdNext()
        val controller = history()
        assertOffMain()
        gate.awaitEntered()
        val activity = controller.get()
        val executor = requireNotNull(readExecutor(activity))
        controller.destroy()
        activities.remove(controller)
        val destroyedUi = labels(activity)
        gate.release.countDown()
        assertTrue("Destroyed activity retained its reader", executor.awaitTermination(10, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Destroyed screen received a late history result", destroyedUi, labels(activity))
        assertFalse(buttons(activity).any { it.text == "Erneut planen" })
    }

    @Test fun mainReadFailureIsDisplayedAsUnavailableInsteadOfAnEmptyStore() {
        fixture("unavailable-main")
        val gate = UiReadDatabaseGate.failNext()
        val controller = main()
        assertOffMain()
        gate.awaitEntered()
        awaitUi { labels(controller.get()).contains("Teststatus nicht verfügbar") }
        assertFalse(labels(controller.get()).contains("Keine offenen Testwecker"))
    }

    @Test fun historyReadFailureOffersRetryInsteadOfAnEmptyHistory() {
        fixture("unavailable-history")
        val gate = UiReadDatabaseGate.failNext()
        val controller = history()
        assertOffMain()
        gate.awaitEntered()
        awaitUi { labels(controller.get()).contains("Der Testverlauf konnte nicht gelesen werden. Die Daten bleiben erhalten.") }
        assertFalse(labels(controller.get()).contains("Keine weiteren Testwecker vorhanden."))
        button(controller.get(), "Erneut versuchen").performClick()
        awaitUi { buttons(controller.get()).any { it.text == "Erneut planen" } }
        assertOffMain()
    }

    private fun main() = Robolectric.buildActivity(MainActivity::class.java).also {
        activities.add(it)
        it.create()
    }

    private fun history(intent: Intent = Intent(context, TestAlarmActivity::class.java)) =
        Robolectric.buildActivity(TestAlarmActivity::class.java, intent).also {
            activities.add(it)
            it.create()
        }

    private fun fixture(id: String, active: Boolean = false, futureSeconds: Long = 600): RoutineOccurrence {
        val now = Instant.ofEpochMilli(System.currentTimeMillis())
        val planned = now.plusSeconds(if (active) -60 else futureSeconds).atZone(ZoneId.systemDefault())
        val item = RoutineOccurrence(id = "test:$id", eventId = "test:$id", scheduleRevision = "async-ui-test",
            nominalDate = planned.toLocalDate(), nominalTime = planned.toLocalTime(),
            zoneId = planned.zone.id, offsetSeconds = planned.offset.totalSeconds,
            plannedAt = planned.toInstant(), currentTriggerAt = planned.toInstant(),
            lastUpdatedAt = planned.toInstant().minusSeconds(60), traceId = "trace:$id",
            title = id, spokenText = "Spoken $id", category = EventCategory.PERSONAL,
            mode = EventMode.ROUTINE, isTest = true)
        return TestAlarmRuntime.repository(context).use { repo ->
            repo.materialize(listOf(item))
            if (active) requireNotNull(TestAlarmCoordinator(repo, Clock.systemDefaultZone())
                .trigger(AlarmDelivery.from(item, repo.deliveryEpoch()))) else item
        }
    }

    private fun alarmIntent(item: RoutineOccurrence) = TestAlarmRuntime.putToken(
        Intent(context, TestAlarmActivity::class.java), AlarmDelivery.from(item))

    private fun assertOffMain() = assertEquals("Occurrence database access ran on the main/UI thread",
        0, UiReadDatabaseGate.mainAccesses.get())

    private fun assertMainEventRuns() {
        var handled = false
        Handler(Looper.getMainLooper()).post { handled = true }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Main events must run while the database reader is blocked", handled)
    }

    private fun awaitUi(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("The expected UI result was not delivered")
    }

    /** Test-only barrier/termination access, following the existing recovery executor tests. */
    private fun readExecutor(activity: Activity): ExecutorService? {
        val field = activity.javaClass.declaredFields.firstOrNull { it.type.simpleName == "LatestUiLoader" }
            ?: return null
        field.isAccessible = true
        val loader = field.get(activity) ?: return null
        val executor = loader.javaClass.declaredFields.firstOrNull {
            ExecutorService::class.java.isAssignableFrom(it.type)
        } ?: return null
        executor.isAccessible = true
        return executor.get(loader) as ExecutorService
    }

    private fun labels(activity: Activity) = views(activity.window.decorView)
        .filterIsInstance<TextView>().map { it.text.toString() }
    private fun buttons(activity: Activity) = views(activity.window.decorView).filterIsInstance<Button>()
    private fun button(activity: Activity, text: String) = buttons(activity).single { it.text == text }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}

/** Observe/delay each repository's first real database access; later SQL is unchanged.
 * A main-thread attempt throws promptly so the pre-fix RED run cannot deadlock the test.
 * Worker interruptions are deliberately ignored while held, modelling noncancellable I/O. */
@Implements(SQLiteOpenHelper::class)
class UiReadDatabaseGate {
    @RealObject lateinit var real: SQLiteOpenHelper

    @Implementation fun getReadableDatabase(): SQLiteDatabase {
        beforeAccess()
        return Shadow.directlyOn(real, SQLiteOpenHelper::class.java, "getReadableDatabase")
    }

    @Implementation fun getWritableDatabase(): SQLiteDatabase {
        beforeAccess()
        return Shadow.directlyOn(real, SQLiteOpenHelper::class.java, "getWritableDatabase")
    }

    private fun beforeAccess() {
        if (!armed || real.databaseName != "occurrences.db") return
        if (!seen.add(real)) return
        if (Thread.currentThread() === Looper.getMainLooper().thread) {
            mainAccesses.incrementAndGet()
            throw SQLiteException("UI test rejected a blocking main-thread read")
        }
        val gate = pending.poll() ?: return
        gate.entered.countDown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (gate.release.count != 0L && System.nanoTime() < deadline) {
            try { gate.release.await(50, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
        }
        check(gate.release.count == 0L) { "UI test did not release the database read" }
        if (gate.fail) throw SQLiteException("Injected occurrence read failure")
    }

    class Gate(val fail: Boolean = false) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(if (fail) 0 else 1)
        fun awaitEntered() = assertTrue("Background reader never reached SQLite",
            entered.await(10, TimeUnit.SECONDS))
    }

    companion object {
        @Volatile var armed = false
        val mainAccesses = AtomicInteger()
        private val seen = Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap<SQLiteOpenHelper, Boolean>()))
        private val pending = ConcurrentLinkedQueue<Gate>()
        private val all = ConcurrentLinkedQueue<Gate>()
        fun holdNext(): Gate = add(Gate())
        fun failNext(): Gate = add(Gate(fail = true))
        private fun add(gate: Gate): Gate {
            armed = true
            all.add(gate)
            pending.add(gate)
            return gate
        }
        fun releaseAll() { all.forEach { it.release.countDown() } }
        fun reset() {
            releaseAll()
            armed = false
            mainAccesses.set(0)
            seen.clear(); pending.clear(); all.clear()
        }
    }
}
