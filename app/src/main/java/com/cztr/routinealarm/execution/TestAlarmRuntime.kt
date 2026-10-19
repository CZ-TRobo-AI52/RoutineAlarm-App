package com.cztr.routinealarm.execution

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.cztr.routinealarm.AlarmScheduler
import com.cztr.routinealarm.occurrence.*
import java.time.Clock

/** Android adapter for the M2.2a test path. Uses the existing occurrences.db. */
object TestAlarmRuntime {
    private const val ID = "occurrence_id"
    private const val GENERATION = "occurrence_generation"
    private const val REVISION = "occurrence_revision"
    private const val EPOCH = "occurrence_epoch"

    fun repository(context: Context) = SqliteOccurrenceRepository(context, Clock.systemDefaultZone())
    fun coordinator(repo: OccurrenceRepository) = TestAlarmCoordinator(repo, Clock.systemDefaultZone())

    fun putToken(intent: Intent, token: AlarmDelivery): Intent = intent.apply {
        data = Uri.Builder().scheme("routinealarm").authority("test-occurrence")
            .appendPath(token.occurrenceId).appendPath(token.generation.toString())
            .appendPath(token.revision.toString()).apply {
                if (token.epoch.isNotEmpty()) appendQueryParameter("epoch", token.epoch)
            }.build()
        putExtra(ID, token.occurrenceId)
        putExtra(GENERATION, token.generation)
        putExtra(REVISION, token.revision)
        putExtra(EPOCH, token.epoch)
    }

    fun token(intent: Intent?): AlarmDelivery? {
        if (intent == null || !intent.hasExtra(GENERATION) || !intent.hasExtra(REVISION)) return null
        val token = AlarmDelivery(intent.getStringExtra(ID) ?: return null,
            intent.getLongExtra(GENERATION, -1), intent.getLongExtra(REVISION, -1),
            intent.getStringExtra(EPOCH) ?: "")
        if (!token.valid) return null
        val expected = putToken(Intent(), token).data
        return token.takeIf { intent.data == expected }
    }

    fun create(context: Context, minutes: Int): RoutineOccurrence {
        check(AlarmScheduler.canScheduleExact(context)) { "Bitte genaue Alarme erlauben." }
        val occurrence = repository(context).use { coordinator(it).create(minutes) }
        // The projection is durable before calling Android. Scheduling failure is
        // visible to the caller; future waits are also restored on resume/system recovery.
        schedule(context, occurrence)
        return occurrence
    }

    fun schedule(context: Context, occurrence: RoutineOccurrence) {
        repository(context).use { scheduleCurrent(context, occurrence, it, futureOnly = false) }
    }

    /** Enqueue only: no occurrence database is opened on the Activity/Receiver thread. */
    fun requestRecovery(context: Context): Boolean = TestAlarmRecoveryService.request(context)

    /** Synchronous diagnostic/test drain. Production entry points use requestRecovery. */
    fun restoreFuture(context: Context) {
        val cycleId = java.util.UUID.randomUUID().toString()
        try {
            withRecovery(context) { recover ->
                var complete: Boolean
                do { complete = recover(cycleId) { false }.complete } while (!complete)
            }
        } catch (error: Exception) {
            Log.e("TestAlarm", "Testwecker-Wiederherstellung fehlgeschlagen; Daten bleiben erhalten", error)
        }
    }

    internal fun restoreBatch(context: Context, cycleId: String,
        shouldStop: () -> Boolean = { false }): RecoveryBatchResult =
        withRecovery(context) { recover -> recover(cycleId, shouldStop) }

    /** Keep one validated database open across batches; no transaction spans platform calls. */
    internal fun <T> withRecovery(context: Context,
        work: ((String, () -> Boolean) -> RecoveryBatchResult) -> T): T = repository(context).use { repo ->
        val store = AtomicRecoveryCursorStore(java.io.File(context.noBackupFilesDir, "test-recovery-cursor.bin"))
        work { cycleId, shouldStop ->
            TestAlarmRecovery(repo, Clock.systemDefaultZone(),
                schedule = { scheduleCurrent(context, it, repo, futureOnly = true) },
                cancel = { cancelObsolete(context, repo, it) },
                store = store, cycleId = cycleId,
                onFailure = { Log.e("TestAlarm", "Einzelner Wiederherstellungsschritt fehlgeschlagen", it) },
            ).runBatch(shouldStop = shouldStop)
        }
    }

    /** Best effort cleanup after a committed user action; it never undoes that action. */
    fun reconcileOccurrence(context: Context, occurrenceId: String) {
        try {
            repository(context).use { reconcileKnown(context, it, occurrenceId) }
        } catch (error: Exception) {
            Log.e("TestAlarm", "Alte Testalarm-Kennungen konnten nicht abgeglichen werden", error)
        }
    }

    private fun reconcileKnown(context: Context, repo: SqliteOccurrenceRepository, occurrenceId: String? = null) {
        try {
            TestAlarmReconciliation(repo, cancel = { cancelObsolete(context, repo, it) }, onFailure = { error ->
                Log.e("TestAlarm", "Rücknahme einer alten Testalarm-Kennung nicht bestätigt", error)
            }).reconcile(occurrenceId)
        } catch (error: Exception) {
            // A cleanup/query failure must not suppress unrelated future scheduling.
            Log.e("TestAlarm", "Abgleich alter Testalarm-Kennungen nicht abgeschlossen", error)
        }
    }

    private fun cancelObsolete(context: Context, repo: SqliteOccurrenceRepository,
        token: AlarmDelivery): CancellationObservation {
        val intent = putToken(Intent(context, TestAlarmReceiver::class.java), token)
        val pending = PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        // A handle does not prove that AlarmManager holds an alarm; recheck before the effect.
        return if (pending == null) CancellationObservation.TOKEN_NOT_FOUND
        else if (!TestAlarmReconciliation.isObsolete(repo, token)) CancellationObservation.NO_LONGER_OBSOLETE
        else {
            context.getSystemService(AlarmManager::class.java).cancel(pending)
            pending.cancel()
            CancellationObservation.CALL_RETURNED
        }
    }

    private fun scheduleCurrent(context: Context, occurrence: RoutineOccurrence,
        repo: SqliteOccurrenceRepository, futureOnly: Boolean) {
        TestAlarmScheduling(repo, Clock.systemDefaultZone(),
            canSchedule = { AlarmScheduler.canScheduleExact(context) },
            platformSchedule = { current ->
                val intent = putToken(Intent(context, TestAlarmReceiver::class.java), AlarmDelivery.from(current, repo.deliveryEpoch()))
                val pending = PendingIntent.getBroadcast(context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val manager = context.getSystemService(AlarmManager::class.java)
                TestAlarmScheduling.requireCurrentBeforeCall(repo, current, Clock.systemDefaultZone(), futureOnly)
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, current.currentTriggerAt.toEpochMilli(), pending)
            }).schedule(occurrence, futureOnly)
    }

    fun refresh(context: Context) {
        // Called by a visible Activity, or while the existing foreground service runs.
        context.startService(Intent(context, TestAlarmService::class.java).setAction(TestAlarmService.REFRESH))
    }

    fun schedulingLabel(attempt: SchedulingAttempt?, current: RoutineOccurrence): String {
        if (attempt == null) return "Keine Rückmeldung zur Android-Planung gespeichert."
        val scope = if (attempt.revision == current.revision && attempt.generation == current.generation)
            "Letzter Planungsversuch: " else "Planungsversuch für einen früheren Zustand: "
        return scope + when (attempt.receipt?.outcome) {
            null -> "Rückmeldung fehlt; Wirkung unbekannt."
            SchedulingOutcome.CALL_RETURNED -> "Android-Aufruf zurückgekehrt. Tatsächliche Ausgabe nicht bestätigt."
            SchedulingOutcome.CALL_FAILED -> "Android-Aufruf fehlgeschlagen; Wirkung unbekannt."
            SchedulingOutcome.NOT_CALLED -> when (attempt.receipt?.reason) {
                SchedulingReason.PERMISSION_DENIED -> "Nicht aufgerufen, genaue Alarme sind nicht erlaubt."
                SchedulingReason.EXPIRED -> "Nicht aufgerufen, Weckzeit inzwischen erreicht."
                else -> "Nicht aufgerufen, gespeicherter Testwecker inzwischen geändert."
            }
        }
    }

    fun label(status: OccurrenceStatus) = when (status) {
        OccurrenceStatus.SCHEDULED -> "Geplant"
        OccurrenceStatus.TRIGGERED -> "Ausgelöst – noch offen"
        OccurrenceStatus.SNOOZED -> "Verschoben"
        OccurrenceStatus.DONE -> "Erledigt"
        OccurrenceStatus.SKIPPED -> "Übersprungen"
        OccurrenceStatus.MISSED -> "Verpasst"
    }
}

class TestAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = TestAlarmRuntime.token(intent) ?: return
        try {
            val occurrence = TestAlarmRuntime.repository(context).use { TestAlarmRuntime.coordinator(it).trigger(token) }
                ?: return
            context.startForegroundService(TestAlarmRuntime.putToken(
                Intent(context, TestAlarmService::class.java), AlarmDelivery.from(occurrence, token.epoch)))
        } catch (error: Exception) {
            // Keep journal and current projection for explicit recovery. A failed
            // platform start is not evidence of personal completion or a missed task.
            Log.e("TestAlarm", "Testalarm konnte nicht ausgegeben werden", error)
        }
    }
}
