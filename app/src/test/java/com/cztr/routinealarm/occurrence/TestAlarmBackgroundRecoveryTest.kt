package com.cztr.routinealarm.occurrence

import android.app.AlarmManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobWorkItem
import android.content.Context
import android.content.Intent
import android.os.Looper
import com.cztr.routinealarm.BootReceiver
import com.cztr.routinealarm.ScheduleState
import com.cztr.routinealarm.execution.TestAlarmRecoveryService
import com.cztr.routinealarm.execution.TestAlarmRuntime
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowJobScheduler
import org.robolectric.shadows.ShadowJobService
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], shadows = [RecoveryJobSchedulerShadow::class, RecoveryJobParametersShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TestAlarmBackgroundRecoveryTest {
    private lateinit var context: Context
    private lateinit var scheduler: JobScheduler
    private lateinit var queue: RecoveryJobSchedulerShadow

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("occurrences.db")
        scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler.cancelAll()
        queue = Shadow.extract(scheduler)
        ScheduleState.setEnabled(context, false)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After fun cleanup() {
        awaitRecoveryWorker()
        scheduler.cancelAll()
        context.deleteDatabase("occurrences.db")
    }

    private fun create() = TestAlarmRuntime.repository(context).use { TestAlarmRuntime.coordinator(it).create(5) }
    private fun tokens() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.map {
        TestAlarmRuntime.token(shadowOf(it.operation).savedIntent)
    }

    @Test @Config(sdk = [26, 31, 35])
    fun bootQueuesRecoveryWithoutPerformingDatabaseOrAlarmWorkOnReceiverThread() {
        val item = create()
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        TestAlarmRuntime.repository(context).use { repo ->
            assertTrue("Receiver must return before scheduling attempts", repo.schedulingAttempts(item.id).isEmpty())
        }
        assertTrue(tokens().isEmpty())
        assertEquals(1, scheduler.allPendingJobs.size)
        assertEquals(TestAlarmRecoveryService::class.java.name, scheduler.allPendingJobs.single().service.className)

        runQueuedRecovery(context)

        assertEquals(listOf(AlarmDelivery.from(item)), tokens())
        TestAlarmRuntime.repository(context).use { repo ->
            assertEquals(SchedulingOutcome.CALL_RETURNED, repo.schedulingAttempts(item.id).single().receipt!!.outcome)
            assertEquals(listOf("SCHEDULE"), repo.history(item.id).map { it.actionType })
        }
    }

    @Test fun unrelatedBroadcastNeitherQueuesWorkNorCreatesTheDatabase() {
        BootReceiver().onReceive(context, Intent("unrelated.action"))
        assertTrue(scheduler.allPendingJobs.isEmpty())
        assertTrue(tokens().isEmpty())
        assertFalse(context.getDatabasePath("occurrences.db").exists())
    }

    @Test fun rejectedEnqueueDoesNotFallBackToSynchronousRecovery() {
        val item = create()
        queue.failOnJob(TestAlarmRecoveryService.JOB_ID)
        assertFalse(TestAlarmRuntime.requestRecovery(context))
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(scheduler.allPendingJobs.isEmpty())
        assertTrue(tokens().isEmpty())
        TestAlarmRuntime.repository(context).use { assertTrue(it.schedulingAttempts(item.id).isEmpty()) }
    }

    @Test fun requestsBeforeDequeueCoalesceWithoutDuplicateSchedulingAttempts() {
        val item = create()
        repeat(20) { assertTrue(TestAlarmRuntime.requestRecovery(context)) }
        val processed = runQueuedRecovery(context)
        assertEquals(1, processed.completed.size)
        assertEquals(listOf(AlarmDelivery.from(item)), tokens())
        TestAlarmRuntime.repository(context).use { assertEquals(1, it.schedulingAttempts(item.id).size) }
    }

    @Test fun requestWhileWorkerIsActiveRunsAnotherCycleForNewDesiredState() {
        val first = create()
        assertTrue(TestAlarmRuntime.requestRecovery(context))
        var second: RoutineOccurrence? = null
        val params = newRecoveryParameters(queue)
        val observed = Shadow.extract<RecoveryJobParametersShadow>(params)
        observed.onComplete = {
            if (second == null) {
                second = create()
                BootReceiver().onReceive(context, Intent(Intent.ACTION_TIME_CHANGED))
            }
        }
        runQueuedRecovery(context, params)
        assertEquals(setOf(AlarmDelivery.from(first), AlarmDelivery.from(second!!)), tokens().toSet())
        assertEquals(2, observed.completed.map { it.intent.getStringExtra(TestAlarmRecoveryService.CYCLE) }.distinct().size)
        TestAlarmRuntime.repository(context).use { assertEquals(1, it.schedulingAttempts(second!!.id).size) }
    }

    @Test fun requestAtEmptyQueueBoundarySurvivesNormalJobCompletion() {
        val first = create()
        assertTrue(TestAlarmRuntime.requestRecovery(context))
        val params = newRecoveryParameters(queue)
        val observed = Shadow.extract<RecoveryJobParametersShadow>(params)
        lateinit var late: RoutineOccurrence
        observed.onEmpty = {
            observed.onEmpty = null
            late = create()
            assertTrue(TestAlarmRuntime.requestRecovery(context))
        }
        runQueuedRecovery(context, params)
        assertEquals(listOf(AlarmDelivery.from(first)), tokens())
        assertEquals(1, scheduler.allPendingJobs.size)
        runQueuedRecovery(context)
        assertEquals(setOf(AlarmDelivery.from(first), AlarmDelivery.from(late)), tokens().toSet())
    }

    @Test fun stopWithReparcelledParametersLeavesDequeuedWorkUnacknowledged() {
        val item = create()
        assertTrue(TestAlarmRuntime.requestRecovery(context))
        val params = newRecoveryParameters(queue)
        val observed = Shadow.extract<RecoveryJobParametersShadow>(params)
        val dequeued = CountDownLatch(1)
        val release = CountDownLatch(1)
        observed.afterDequeue = { work ->
            if (work != null) {
                dequeued.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Stop test did not release worker" }
            }
        }
        val controller = Robolectric.buildService(TestAlarmRecoveryService::class.java).create()
        try {
            assertTrue(controller.get().onStartJob(params))
            assertTrue("Worker did not dequeue", dequeued.await(10, TimeUnit.SECONDS))
            val reparcelled = newRecoveryParameters(queue)
            assertNotSame(params, reparcelled)
            assertEquals(params.jobId, reparcelled.jobId)
            assertTrue(controller.get().onStopJob(reparcelled))
            release.countDown()
            awaitRecoveryWorker()
            assertTrue(observed.completed.isEmpty())
            assertTrue(tokens().isEmpty())
            TestAlarmRuntime.repository(context).use { assertTrue(it.schedulingAttempts(item.id).isEmpty()) }
        } finally {
            release.countDown()
            awaitRecoveryWorker()
            controller.destroy()
        }
    }

    @Test fun workerFailureRequestsRetryWithoutAcknowledgingItsWorkItem() {
        assertFailureCallback(stopBeforeCallback = false)
    }

    @Test fun stoppedRunDoesNotRescheduleFromAnAlreadyPostedErrorCallback() {
        assertFailureCallback(stopBeforeCallback = true)
    }

    private fun assertFailureCallback(stopBeforeCallback: Boolean) {
        val item = create()
        assertTrue(TestAlarmRuntime.requestRecovery(context))
        val params = newRecoveryParameters(queue)
        val observed = Shadow.extract<RecoveryJobParametersShadow>(params)
        observed.afterDequeue = { throw IllegalStateException("Binder dequeue failed after taking work") }
        val controller = Robolectric.buildService(TestAlarmRecoveryService::class.java).create()
        try {
            assertTrue(controller.get().onStartJob(params))
            awaitRecoveryWorker(idleMain = false)
            if (stopBeforeCallback) assertTrue(controller.get().onStopJob(newRecoveryParameters(queue)))
            shadowOf(Looper.getMainLooper()).idle()
            val serviceShadow = Shadow.extract<ShadowJobService>(controller.get())
            assertEquals(!stopBeforeCallback, serviceShadow.isJobFinished)
            assertEquals(!stopBeforeCallback, serviceShadow.isRescheduleNeeded)
            assertTrue(observed.completed.isEmpty())
            assertTrue(tokens().isEmpty())
            TestAlarmRuntime.repository(context).use { assertTrue(it.schedulingAttempts(item.id).isEmpty()) }
        } finally {
            controller.destroy()
        }
    }
}

/** Robolectric's default enqueue stores JobInfo but discards JobWorkItem. Preserve just
 * that queue boundary; app service/executor/adapter and native SQLite execute normally.
 * AlarmManager is Robolectric's platform shadow. OS/Binder redelivery is not simulated. */
@Implements(className = "android.app.JobSchedulerImpl", isInAndroidSdk = false)
class RecoveryJobSchedulerShadow : ShadowJobScheduler.ShadowJobSchedulerImpl() {
    val work = ConcurrentLinkedQueue<JobWorkItem>()

    @Implementation override fun enqueue(job: JobInfo, item: JobWorkItem): Int {
        val result = super.enqueue(job, item)
        if (result == JobScheduler.RESULT_SUCCESS) work.add(item)
        return result
    }

    @Implementation override fun cancelAll() {
        super.cancelAll()
        work.clear()
    }
}

/** Binder is absent in a host test. These callbacks implement dequeue/ack only. */
@Implements(JobParameters::class)
class RecoveryJobParametersShadow {
    lateinit var queue: RecoveryJobSchedulerShadow
    val completed = mutableListOf<JobWorkItem>()
    var afterDequeue: ((JobWorkItem?) -> Unit)? = null
    var onComplete: (() -> Unit)? = null
    var onEmpty: (() -> Unit)? = null

    @Implementation fun getJobId(): Int = TestAlarmRecoveryService.JOB_ID
    @Implementation fun dequeueWork(): JobWorkItem? {
        val item = queue.work.poll()
        if (item == null) {
            // Android atomically completes an empty dequeue. A subsequent enqueue
            // starts pending work again; the service must not finish it explicitly.
            queue.cancel(TestAlarmRecoveryService.JOB_ID)
            onEmpty?.invoke()
        }
        afterDequeue?.invoke(item)
        return item
    }
    @Implementation fun completeWork(item: JobWorkItem) {
        completed.add(item)
        onComplete?.invoke()
    }
}

internal fun newRecoveryParameters(queue: RecoveryJobSchedulerShadow): JobParameters =
    Shadow.newInstanceOf(JobParameters::class.java).also {
        Shadow.extract<RecoveryJobParametersShadow>(it).queue = queue
    }

/** A barrier joins the production single executor; no polling, sleep or extra recovery call. */
internal fun awaitRecoveryWorker(idleMain: Boolean = true) {
    ReflectionHelpers.getStaticField<ExecutorService>(TestAlarmRecoveryService::class.java, "worker")
        .submit {}.get(10, TimeUnit.SECONDS)
    if (idleMain) shadowOf(Looper.getMainLooper()).idle()
}

internal fun runQueuedRecovery(context: Context, parameters: JobParameters? = null): RecoveryJobParametersShadow {
    val scheduler = context.getSystemService(JobScheduler::class.java)
    assertNotNull("The entry point must request background recovery", scheduler.getPendingJob(TestAlarmRecoveryService.JOB_ID))
    val params = parameters ?: newRecoveryParameters(Shadow.extract(scheduler))
    val controller = Robolectric.buildService(TestAlarmRecoveryService::class.java).create()
    try {
        assertTrue(controller.get().onStartJob(params))
        awaitRecoveryWorker()
        val serviceShadow = Shadow.extract<ShadowJobService>(controller.get())
        assertFalse("Normal empty-queue completion must not finish newly enqueued work", serviceShadow.isJobFinished)
        return Shadow.extract(params)
    } finally {
        controller.destroy()
    }
}
