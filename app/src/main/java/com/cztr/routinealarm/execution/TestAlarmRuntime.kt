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
import java.time.Instant

/** Android adapter for the M2.2a test path. Uses the existing occurrences.db. */
object TestAlarmRuntime {
    private const val ID = "occurrence_id"
    private const val GENERATION = "occurrence_generation"
    private const val REVISION = "occurrence_revision"

    fun repository(context: Context) = SqliteOccurrenceRepository(context, Clock.systemDefaultZone())
    fun coordinator(repo: OccurrenceRepository) = TestAlarmCoordinator(repo, Clock.systemDefaultZone())

    fun putToken(intent: Intent, token: AlarmDelivery): Intent = intent.apply {
        data = Uri.Builder().scheme("routinealarm").authority("test-occurrence")
            .appendPath(token.occurrenceId).appendPath(token.generation.toString())
            .appendPath(token.revision.toString()).build()
        putExtra(ID, token.occurrenceId)
        putExtra(GENERATION, token.generation)
        putExtra(REVISION, token.revision)
    }

    fun token(intent: Intent?): AlarmDelivery? {
        if (intent == null || !intent.hasExtra(GENERATION) || !intent.hasExtra(REVISION)) return null
        val token = AlarmDelivery(intent.getStringExtra(ID) ?: return null,
            intent.getLongExtra(GENERATION, -1), intent.getLongExtra(REVISION, -1))
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

    /** Replay the waiting projection into AlarmManager, without inventing a user action.
     * Overdue/TRIGGERED entries require explicit recovery in the test history. A process
     * failure between DB commit and platform scheduling is repaired on the next entry.
     */
    fun restoreFuture(context: Context) {
        try {
            if (!AlarmScheduler.canScheduleExact(context)) return
            repository(context).use { repo ->
                repo.futureTests(Instant.now()).forEach { occurrence ->
                    try {
                        scheduleCurrent(context, occurrence, repo, futureOnly = true)
                    } catch (error: Exception) {
                        // One failed platform call must not discard the remaining durable intents.
                        Log.e("TestAlarm", "Zukünftiger Testwecker konnte nicht wiederhergestellt werden", error)
                    }
                }
            }
        } catch (error: Exception) {
            Log.e("TestAlarm", "Testwecker-Wiederherstellung fehlgeschlagen; Daten bleiben erhalten", error)
        }
    }

    private fun scheduleCurrent(context: Context, occurrence: RoutineOccurrence,
        repo: OccurrenceRepository, futureOnly: Boolean) {
        check(AlarmScheduler.canScheduleExact(context)) { "Bitte genaue Alarme erlauben." }
        val current = repo.get(occurrence.id)
        check(current == occurrence && current.isTest &&
            current.status in setOf(OccurrenceStatus.SCHEDULED, OccurrenceStatus.SNOOZED)) {
            "Dieser Testwecker wurde bereits geändert."
        }
        val intent = putToken(Intent(context, TestAlarmReceiver::class.java), AlarmDelivery.from(occurrence))
        val pending = PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Recheck time after reading the database: automatic recovery must not turn
        // a just-expired request into an immediate alarm. Manual retry is explicit.
        if (futureOnly && !occurrence.currentTriggerAt.isAfter(Instant.now())) return
        context.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, occurrence.currentTriggerAt.toEpochMilli(), pending)
    }

    fun refresh(context: Context) {
        // Called by a visible Activity, or while the existing foreground service runs.
        context.startService(Intent(context, TestAlarmService::class.java).setAction(TestAlarmService.REFRESH))
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
                Intent(context, TestAlarmService::class.java), AlarmDelivery.from(occurrence)))
        } catch (error: Exception) {
            // Keep journal and current projection for explicit recovery. A failed
            // platform start is not evidence of personal completion or a missed task.
            Log.e("TestAlarm", "Testalarm konnte nicht ausgegeben werden", error)
        }
    }
}
