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
        // visible to the caller; recovery can be explicitly retried from test history.
        schedule(context, occurrence)
        return occurrence
    }

    fun schedule(context: Context, occurrence: RoutineOccurrence) {
        check(AlarmScheduler.canScheduleExact(context)) { "Bitte genaue Alarme erlauben." }
        repository(context).use { repo ->
            val current = repo.get(occurrence.id)
            check(current == occurrence && current.isTest &&
                current.status in setOf(OccurrenceStatus.SCHEDULED, OccurrenceStatus.SNOOZED)) {
                "Dieser Testwecker wurde bereits geändert."
            }
        }
        val intent = putToken(Intent(context, TestAlarmReceiver::class.java), AlarmDelivery.from(occurrence))
        val pending = PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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
