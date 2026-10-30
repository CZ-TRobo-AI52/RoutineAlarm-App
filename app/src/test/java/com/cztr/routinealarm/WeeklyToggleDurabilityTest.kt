package com.cztr.routinealarm

import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.cztr.routinealarm.support.awaitUiReadersStopped
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.util.ReflectionHelpers
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.io.File
import java.io.IOException

/** Desired contract; prior 10-case legacy-memory characterization remains archived unchanged. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31])
@LooperMode(LooperMode.Mode.PAUSED)
class WeeklyToggleDurabilityTest {
    @Implements(Handler::class)
    class ConcurrentPublishHandler {
        @RealObject lateinit var handler: Handler
        @Implementation fun post(task: Runnable): Boolean {
            if (Thread.currentThread().name == pausedThread) {
                publishReached.countDown()
                check(releasePublish.await(10, TimeUnit.SECONDS)) { "Publish gate timed out" }
            }
            return handler.postDelayed(task, 0L)
        }
        companion object {
            @Volatile var pausedThread: String? = null
            var publishReached = CountDownLatch(1)
            var releasePublish = CountDownLatch(1)
        }
    }

    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("routinealarm_schedule", Context.MODE_PRIVATE)
    private val manager get() = app.getSystemService(AlarmManager::class.java)
    @Before fun prepare() {
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        drain()
        assertTrue(prefs.edit().clear().commit())
        ScheduleState.setEnabled(app, false)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }
    private fun worker(): ExecutorService? = ScheduleState::class.java.declaredFields
        .firstOrNull { ExecutorService::class.java.isAssignableFrom(it.type) }
        ?.let { it.isAccessible = true; it.get(null) as ExecutorService }
    private fun drain() {
        worker()?.submit {}?.get(10, TimeUnit.SECONDS)
        ReflectionHelpers.callStaticMethod<Void>(Class.forName("android.app.QueuedWork"), "waitToFinish")
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    // Exercise the current Plan action, including its actual rendered requested state.
    private fun toggle(a: Activity) = object {
        private fun button() = views(a.window.decorView).filterIsInstance<Button>()
            .single { it.tag == "plan_primary_action" }
        var isChecked: Boolean
            get() = button().isSelected
            set(enabled) { if (isChecked != enabled) assertTrue(button().performClick()) }
    }
    private fun labels(a: Activity) = views(a.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
    private fun openPlan(a: Activity) {
        val nav = views(a.window.decorView)
            .filterIsInstance<com.cztr.routinealarm.ui.components.R52BottomNav>().single()
        views(nav).filterIsInstance<Button>().single { it.text == "Plan" }.performClick()
    }
    private fun withMain(check: (org.robolectric.android.controller.ActivityController<MainActivity>) -> Unit) {
        val c = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try { openPlan(c.get()); check(c) }
        finally {
            drain()
            if (!c.get().isDestroyed) c.pause().stop().destroy()
            awaitUiReadersStopped(c.get())
            shadowOf(manager).scheduledAlarms.toList().forEach { it.operation?.let { p -> manager.cancel(p); p.cancel() } }
        }
    }
    private fun denied(check: () -> Unit) {
        drain()
        val dir = File(app.applicationInfo.dataDir, "shared_prefs").toPath()
        // Windows has no POSIX attribute view. Exercise a real failed atomic-backup
        // rename instead: an existing regular file cannot be a parent directory.
        // Keep the original XML untouched so all durable-state assertions still run.
        if (!Files.getFileStore(dir).supportsFileAttributeView("posix")) {
            val blocker = dir.resolve("verified-backup-parent-blocker").toFile()
            blocker.writeText("not a directory")
            val originalBackup = ReflectionHelpers.getField<File>(prefs, "mBackupFile")
            var denied = false
            try {
                try { File(blocker, "probe").writeText("probe") }
                catch (_: IOException) { denied = true }
                assertTrue("FIXTURE_INVALID: backup parent remained writable", denied)
                ReflectionHelpers.setField(prefs, "mBackupFile", File(blocker, "schedule.xml.bak"))
                check()
            } finally {
                drain()
                ReflectionHelpers.setField(prefs, "mBackupFile", originalBackup)
                assertTrue("Fixture blocker must be removable", blocker.delete())
            }
            return
        }
        val original = Files.getPosixFilePermissions(dir)
        Files.setPosixFilePermissions(dir, original - setOf(PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE))
        try {
            var denied = false
            val probe = dir.resolve("verified-denial-probe").toFile()
            try { probe.writeText("probe") } catch (_: IOException) { denied = true }
            finally { probe.delete() }
            assertTrue("FIXTURE_INVALID: actual host FS remained writable", denied)
            check()
        } finally { drain(); Files.setPosixFilePermissions(dir, original) }
    }
    private fun diskEnabled(): Boolean = File(app.applicationInfo.dataDir,
        "shared_prefs/routinealarm_schedule.xml").readText().contains("name=\"weekly_schedule_enabled\" value=\"true\"")
    private fun blockedDisk(check: () -> Unit) {
        drain()
        val lock = ReflectionHelpers.getField<Any>(prefs, "mWritingToDiskLock")
        val ready = CountDownLatch(1); val release = CountDownLatch(1)
        val holder = Thread { synchronized(lock) { ready.countDown(); release.await(10, TimeUnit.SECONDS) } }
        holder.start(); assertTrue(ready.await(5, TimeUnit.SECONDS))
        try { check() } finally { release.countDown(); holder.join(10000); drain() }
    }

    @Test fun normalActivationAndPauseAreDurable() = withMain { c ->
        toggle(c.get()).isChecked = true; drain()
        assertTrue(diskEnabled()); assertTrue(ScheduleState.isEnabled(app))
        assertTrue(shadowOf(manager).scheduledAlarms.isNotEmpty())
        toggle(c.get()).isChecked = false; drain()
        assertFalse(diskEnabled()); assertFalse(ScheduleState.isEnabled(app))
        assertTrue(shadowOf(manager).scheduledAlarms.isEmpty())
    }
    @Test fun failedEnableReportsFailureAndBlocksMemoryAdmission() {
        denied {
            val result = runCatching { ScheduleState.setEnabled(app, true) }
            drain()
            assertNotNull("PRODUCT_RED: write failure not acknowledged", result.exceptionOrNull())
            assertFalse(ScheduleState.isEnabled(app)); assertFalse(diskEnabled())
        }
    }
    @Test fun failedPauseReportsFailureAndBlocksMemoryAdmission() {
        ScheduleState.setEnabled(app, true)
        denied {
            val result = runCatching { ScheduleState.setEnabled(app, false) }
            drain()
            assertNotNull("PRODUCT_RED: pause failure not acknowledged", result.exceptionOrNull())
            assertFalse(ScheduleState.isEnabled(app)); assertTrue(diskEnabled())
        }
    }
    @Test fun failedUiEnableNeverSchedulesAndResumeCannotPromoteMemory() = withMain { c ->
        denied {
            toggle(c.get()).isChecked = true; drain()
            assertTrue("PRODUCT_RED: unconfirmed activation scheduled", shadowOf(manager).scheduledAlarms.isEmpty())
            c.pause().resume(); drain()
            assertFalse(ScheduleState.isEnabled(app))
            assertTrue(labels(c.get()).any { it.contains("nicht bestätigt") })
            assertFalse(diskEnabled())
        }
    }
    @Test fun failedUiPauseIsVisibleAndConservative() {
        ScheduleState.setEnabled(app, true)
        withMain { c ->
            denied {
                toggle(c.get()).isChecked = false; drain()
                assertTrue(diskEnabled()); assertFalse(ScheduleState.isEnabled(app))
                assertTrue("PRODUCT_RED: false pause success", labels(c.get()).any { it.contains("nicht bestätigt") })
                c.pause().resume(); drain()
                assertFalse(ScheduleState.isEnabled(app))
            }
        }
    }
    @Test fun blockedWriteKeepsMainResponsiveAndCannotScheduleBeforeAck() = withMain { c ->
        blockedDisk {
            val start = System.nanoTime()
            toggle(c.get()).isChecked = true
            assertTrue("UI blocked by disk commit", System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(500))
            var handled = false; Handler(Looper.getMainLooper()).post { handled = true }
            shadowOf(Looper.getMainLooper()).idle(); assertTrue(handled)
            assertTrue("PRODUCT_RED: effects before disk acknowledgement", shadowOf(manager).scheduledAlarms.isEmpty())
            assertFalse(ScheduleState.isEnabled(app))
            assertTrue(toggle(c.get()).isChecked)
            assertTrue(labels(c.get()).any { it.contains("Änderung wird gespeichert") })
            assertFalse("A pending request is not an active plan", labels(c.get()).contains("Wochenplan aktiv"))
        }
        assertTrue(ScheduleState.isEnabled(app)); assertTrue(diskEnabled())
    }
    @Test fun lifecycleDoesNotDiscardAnAcceptedWrite() = withMain { c ->
        blockedDisk {
            toggle(c.get()).isChecked = true
            c.pause().stop().destroy()
        }
        assertTrue(diskEnabled()); assertTrue(ScheduleState.isEnabled(app))
        assertTrue(shadowOf(manager).scheduledAlarms.isNotEmpty())
    }
    @Test fun rapidActivationThenPauseKeepsOrderedFinalState() = withMain { c ->
        blockedDisk {
            val switch = toggle(c.get())
            switch.isChecked = true
            switch.isChecked = false
        }
        assertFalse(diskEnabled()); assertFalse(ScheduleState.isEnabled(app))
        assertTrue(shadowOf(manager).scheduledAlarms.isEmpty())
    }
    @Test fun restoreRejectsQueuedPreRestoreActivation() = withMain { c ->
        val ready = CountDownLatch(1); val release = CountDownLatch(1)
        val f = worker()?.submit { ready.countDown(); release.await(10, TimeUnit.SECONDS) }
        if (f != null) assertTrue(ready.await(5, TimeUnit.SECONDS))
        try {
            toggle(c.get()).isChecked = true
            ScheduleState.disableAfterRestore(app)
        } finally { release.countDown(); f?.get(10, TimeUnit.SECONDS); drain() }
        assertFalse(diskEnabled()); assertFalse(ScheduleState.isEnabled(app))
        // Restore admission must reject old command before any new alarm registration.
        assertTrue(shadowOf(manager).scheduledAlarms.isEmpty())
    }
    @Test fun explicitOneShotIsIndependentOfWeeklyPauseAndRestoreStillRevokes() {
        AlarmScheduler.scheduleSnooze(app, "Explicit one shot")
        val pending = requireNotNull(shadowOf(manager).scheduledAlarms.single().operation)
        val delivery = shadowOf(pending).savedIntent
        withMain { c ->
            ScheduleState.setEnabled(app, true); c.pause().resume(); openPlan(c.get())
            toggle(c.get()).isChecked = false; drain()
            assertTrue(ScheduleState.isDeliveryAllowed(app, delivery))
            assertTrue(shadowOf(manager).scheduledAlarms.any { it.operation == pending })
            ScheduleState.disableAfterRestore(app)
            assertFalse(ScheduleState.isDeliveryAllowed(app, delivery))
        }
    }
    private fun rotatedWrite(enabled: Boolean, failure: Boolean) {
        ScheduleState.setEnabled(app, !enabled)
        var successor: org.robolectric.android.controller.ActivityController<MainActivity>? = null
        withMain { old ->
            val check = {
                blockedDisk {
                    toggle(old.get()).isChecked = enabled
                    old.pause().stop().destroy()
                    successor = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
                    openPlan(successor!!.get())
                    assertTrue(labels(successor!!.get()).any { it.contains("Änderung wird gespeichert") })
                }
                val current = successor!!.get()
                assertFalse("P2_RED: successor retained pending after completion",
                    labels(current).any { it.contains("Änderung wird gespeichert") })
                if (failure) {
                    assertTrue("P2_RED: successor lost failure receipt",
                        labels(current).any { it.contains("nicht bestätigt") })
                    assertFalse(toggle(current).isChecked)
                } else {
                    assertEquals(enabled, toggle(current).isChecked)
                    assertFalse(labels(current).any { it.contains("nicht bestätigt") })
                }
            }
            try { if (failure) denied(check) else check() }
            finally {
                successor?.let { c ->
                    c.pause().stop().destroy()
                    awaitUiReadersStopped(c.get())
                }
            }
        }
    }
    @Test fun successorReceivesSuccessfulEnableWithoutAnotherResume() = rotatedWrite(true, false)
    @Test fun successorReceivesSuccessfulPauseWithoutAnotherResume() = rotatedWrite(false, false)
    @Test fun successorReceivesFailedEnableWithoutAnotherResume() = rotatedWrite(true, true)
    @Test fun successorReceivesFailedPauseWithoutAnotherResume() = rotatedWrite(false, true)

    private fun concurrentLatestRequestWins(latestEnabled: Boolean) {
        val ready = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val executor = requireNotNull(worker())
        val heldWorker = executor.submit {
            ready.countDown()
            check(releaseWorker.await(10, TimeUnit.SECONDS)) { "Worker gate timed out" }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        ConcurrentPublishHandler.publishReached = CountDownLatch(1)
        ConcurrentPublishHandler.releasePublish = CountDownLatch(1)
        ConcurrentPublishHandler.pausedThread = "c6-first-weekly-request"
        val firstFailure = AtomicReference<Throwable?>()
        val latestReply = AtomicReference<Result<Int>?>()
        val first = Thread({
            try { ScheduleState.requestEnabled(app, !latestEnabled) {} }
            catch (error: Throwable) { firstFailure.set(error) }
        }, "c6-first-weekly-request")
        try {
            first.start()
            assertTrue("First request must reach the gated real Handler.post",
                ConcurrentPublishHandler.publishReached.await(5, TimeUnit.SECONDS))
            ScheduleState.requestEnabled(app, latestEnabled) { latestReply.set(it) }
        } finally {
            ConcurrentPublishHandler.releasePublish.countDown()
            first.join(10000)
            ConcurrentPublishHandler.pausedThread = null
            releaseWorker.countDown()
            heldWorker.get(10, TimeUnit.SECONDS)
            drain()
        }
        assertFalse("Caller must terminate", first.isAlive)
        assertNull(firstFailure.get())
        assertTrue("Latest request must receive its real completion", latestReply.get()?.isSuccess == true)
        assertEquals("Sequence assignment and executor admission must have one order",
            latestEnabled, diskEnabled())
        assertEquals(latestEnabled, ScheduleState.isEnabled(app))
        assertEquals(latestEnabled, shadowOf(manager).scheduledAlarms.isNotEmpty())
        shadowOf(manager).scheduledAlarms.toList().forEach { it.operation?.let { p -> manager.cancel(p); p.cancel() } }
    }

    @Test @Config(shadows = [ConcurrentPublishHandler::class])
    fun concurrentLatestEnableWinsEvenWhenEarlierPausePublisherStalls() = concurrentLatestRequestWins(true)

    @Test @Config(shadows = [ConcurrentPublishHandler::class])
    fun concurrentLatestPauseWinsEvenWhenEarlierEnablePublisherStalls() = concurrentLatestRequestWins(false)

}
