package com.cztr.routinealarm.execution

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.job.JobWorkItem
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.cztr.routinealarm.occurrence.RecoveryBatchResult
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Android owns the work queue and its completion race. Each item identifies one recovery cycle.
 * Jobs are intentionally not persisted (API 26 compatibility); BOOT requests a fresh cycle because
 * AlarmManager lost even the alarms preceding a saved cursor. The cursor itself is in noBackupFilesDir.
 */
class TestAlarmRecoveryService : JobService() {
    private class Run(val params: JobParameters) { val stopped = AtomicBoolean(false) }
    private var active: Run? = null // JobService callbacks and finish notifications run on main.
    private val main = Handler(Looper.getMainLooper())

    override fun onStartJob(params: JobParameters): Boolean {
        active?.stopped?.set(true)
        val run = Run(params)
        active = run
        val app = applicationContext
        worker.execute {
            try {
                if (run.stopped.get()) return@execute
                TestAlarmRuntime.withRecovery(app) { recover ->
                    RecoveryWorkProcessor(
                        dequeue = { synchronized(queueLock) {
                            params.dequeueWork()?.also { item ->
                                if (queuedCycle == item.intent.getStringExtra(CYCLE)) queuedCycle = null
                            }
                        } },
                        cycleId = { item: JobWorkItem -> requireNotNull(item.intent.getStringExtra(CYCLE)) },
                        recover = recover,
                        complete = { item -> params.completeWork(item) },
                    ).run { run.stopped.get() }
                }
                // dequeueWork(null) finishes atomically in Android. Calling jobFinished(false)
                // here could drop a request enqueued at exactly that boundary.
            } catch (error: Exception) {
                Log.e("TestAlarm", "Hintergrund-Recovery unterbrochen; Fortschritt bleibt erhalten", error)
                main.post {
                    if (active === run && !run.stopped.get()) {
                        // Unfinished work is retained on reschedule, unlike normal completion.
                        jobFinished(params, true)
                        active = null
                    }
                }
            } finally {
                main.post { if (active === run) active = null }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        // onStop parameters arrive through another Binder parcel, not the onStart object.
        active?.takeIf { it.params.jobId == params.jobId }?.let { it.stopped.set(true); active = null }
        return true // Android requeues incomplete and pending work, with its normal backoff.
    }

    override fun onDestroy() {
        active?.stopped?.set(true)
        active = null
        super.onDestroy()
    }

    companion object {
        internal const val JOB_ID = 5208
        internal const val CYCLE = "recovery_cycle"
        private val queueLock = Any()
        private var queuedCycle: String? = null
        // One process-wide worker also serializes a stopped service with a replacement instance.
        private val worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "test-alarm-recovery").apply { isDaemon = true }
        }

        fun request(context: Context): Boolean = synchronized(queueLock) {
            try {
                val scheduler = context.getSystemService(JobScheduler::class.java)
                if (scheduler.getPendingJob(JOB_ID) == null) queuedCycle = null
                // Only an item not yet dequeued can cover a new request. An active cycle may
                // have passed the changed item already, so it needs one subsequent cycle.
                if (queuedCycle != null) return@synchronized true
                val cycle = UUID.randomUUID().toString()
                val info = JobInfo.Builder(JOB_ID,
                    ComponentName(context.applicationContext, TestAlarmRecoveryService::class.java))
                    .setOverrideDeadline(0)
                    .setBackoffCriteria(10_000, JobInfo.BACKOFF_POLICY_LINEAR)
                    .build()
                val result = scheduler.enqueue(info, JobWorkItem(Intent().putExtra(CYCLE, cycle)))
                if (result == JobScheduler.RESULT_SUCCESS) { queuedCycle = cycle; true }
                else { Log.e("TestAlarm", "Android hat die Recovery-Anforderung abgelehnt"); false }
            } catch (error: Exception) {
                // Never fall back to a synchronous database drain on the caller thread.
                Log.e("TestAlarm", "Recovery konnte nicht angefordert werden", error)
                false
            }
        }
    }
}

/** Shared queue protocol, independent of Android Binder so stop/completion races are testable. */
internal class RecoveryWorkProcessor<W>(
    private val dequeue: () -> W?,
    private val cycleId: (W) -> String,
    private val recover: (String, () -> Boolean) -> RecoveryBatchResult,
    private val complete: (W) -> Unit,
) {
    fun run(shouldStop: () -> Boolean) {
        while (!shouldStop()) {
            val item = dequeue() ?: return
            while (!shouldStop()) {
                val result = recover(cycleId(item), shouldStop)
                if (shouldStop()) return
                if (result.complete) { complete(item); break }
            }
        }
    }
}
