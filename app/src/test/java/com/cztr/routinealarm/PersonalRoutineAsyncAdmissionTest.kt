package com.cztr.routinealarm

import android.content.Context
import android.content.Intent
import com.cztr.routinealarm.occurrence.*
import java.util.concurrent.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28,31])
@LooperMode(LooperMode.Mode.PAUSED)
class PersonalRoutineAsyncAdmissionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun delivery() = Intent().putExtra(AlarmScheduler.EXTRA_RECURRING,true)
        .putExtra(AlarmScheduler.EXTRA_EVENT_ID,PersonalSchedule.allEvents.first { it.enabled }.id)
        .putExtra(AlarmScheduler.EXTRA_RESTORE_GENERATION,ScheduleState.restoreGeneration(app))
    private fun doesNotWait(action: () -> Unit) {
        app.deleteDatabase("occurrences.db");ScheduleState.setEnabled(app,true)
        val worker=ScheduleState::class.java.getDeclaredField("writes").apply { isAccessible=true }.get(null) as ExecutorService
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val returned=CountDownLatch(1)
        worker.submit { entered.countDown();check(release.await(10,TimeUnit.SECONDS)) }
        assertTrue(entered.await(2,TimeUnit.SECONDS))
        val caller=Thread.currentThread();val waiting=java.util.concurrent.atomic.AtomicBoolean(false)
        val controller=Thread {
            try { if (!returned.await(300,TimeUnit.MILLISECONDS)) waiting.set(caller.stackTrace.any {
                it.className.contains("FutureTask") && (it.methodName=="get"||it.methodName=="awaitDone") }) }
            finally { release.countDown() }
        }
        controller.start()
        try { action() } finally { returned.countDown();release.countDown();controller.join(2000) }
        worker.submit {}.get(5,TimeUnit.SECONDS)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse("Main callback waited for the occupied storage executor",waiting.get())
    }
    @Test fun receiverReturnsWhileStorageIsOccupied() = doesNotWait { AlarmReceiver().onReceive(app,delivery()) }
    @Test fun bootReturnsWhileStorageIsOccupied() = doesNotWait { BootReceiver().onReceive(app,Intent(Intent.ACTION_BOOT_COMPLETED)) }
    @Test fun alarmServiceReturnsWhileStorageIsOccupied() {
        val c=Robolectric.buildService(AlarmService::class.java).create()
        try { doesNotWait { c.get().onStartCommand(delivery(),0,1) } } finally { c.destroy() }
    }
    @Test fun trainingServiceReturnsWhileStorageIsOccupied() {
        val c=Robolectric.buildService(TrainingSessionService::class.java).create()
        try { doesNotWait { c.get().onStartCommand(delivery(),0,1) } } finally { c.destroy() }
    }
}
