package com.cztr.routinealarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import java.util.UUID
import android.util.Log
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object AlarmScheduler {

    const val EXTRA_ROUTINE_PLAN_VERSION = "routine_plan_version"
    const val EXTRA_ROUTINE_VERSION = "routine_definition_version"
    const val EXTRA_ROUTINE_EPOCH = "routine_definition_epoch"
    const val EXTRA_EVENT_ID = "event_id"
    const val EXTRA_TITLE = "title"
    const val EXTRA_SPOKEN_TEXT = "spoken_text"
    const val EXTRA_MODE = "mode"
    const val EXTRA_CATEGORY = "category"
    const val EXTRA_RECURRING = "recurring"
    const val EXTRA_RESTORE_GENERATION = "restore_generation"

    fun canScheduleExact(
        context: Context
    ): Boolean {
        val alarmManager =
            context.getSystemService(
                Context.ALARM_SERVICE
            ) as AlarmManager

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S
        ) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    fun scheduleAll(
        context: Context
    ): Int {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            val generation = ScheduleState.restoreGeneration(context)
            ScheduleState.dispatchStorage {
                if (generation == ScheduleState.restoreGeneration(context) && ScheduleState.isEnabled(context)) scheduleAll(context)
            }
            return 0 // asynchronous request, never a fabricated scheduled-count receipt
        }
        cancelIneligibleEvents(context)
        if (!canScheduleExact(context)) {
            return 0
        }

        var scheduled = 0

        val snapshot = runCatching { ScheduleRegistry.load(context) }.getOrElse {
            Log.w("RoutineAlarm", "Gespeicherter Plan nicht lesbar; keine Planung", it)
            return 0
        }
        snapshot.scheduledEvents.filter { it.enabled }.forEach { event ->
            if (
                scheduleEventFromSnapshot(context, event, ZonedDateTime.now(), snapshot)
            ) {
                scheduled++
            }
        }

        return scheduled
    }

    fun cancelAll(context: Context) {
        val events = runCatching { ScheduleRegistry.load(context).allEvents }.getOrDefault(ScheduleRegistry.allEvents)
        cancelEvents(context, events)
    }

    fun cancelIneligibleEvents(context: Context) {
        val snapshot = runCatching { ScheduleRegistry.load(context) }.getOrNull()
        // An unreadable database cannot authorize static defaults. Cleanup never creates a token.
        cancelEvents(context, snapshot?.allEvents?.filter {
            snapshot.scheduledById(it.id)?.enabled != true
        } ?: ScheduleRegistry.allEvents)
    }

    private fun cancelEvents(context: Context, events: List<RoutineEvent>) {
        events.forEach { event ->
            try { cancelEventRegistration(context, event.id) }
            catch (error: RuntimeException) {
                Log.w("RoutineAlarm", "Alte Alarmregistrierung nicht bestätigt entfernt: " + event.id, error)
            }
        }
    }

    private fun cancelEventRegistration(context: Context, eventId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply { data = eventUri(eventId) }
        val pending = PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        alarmManager.cancel(pending)
        pending.cancel()
    }

    /** Only ScheduleState's committed, ticket-fenced explicit command calls this worker adapter.
     * No SQLite, broad plan replacement, or running-output stop occurs in this effect window.
     */
    internal fun reconcilePersonalRoutineRegistration(context: Context,
        record: com.cztr.routinealarm.occurrence.PersonalRoutineRecord) {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        check(!record.quarantined && record.version > 0 && record.planVersion > 0)
        if (!record.event.enabled) {
            cancelEventRegistration(context, record.event.id)
            return
        }
        val snapshot = ScheduleRegistry.Snapshot(listOf(record.event), listOf(record.event),
            mapOf(record.event.id to record), record.restoreEpoch)
        check(scheduleEventFromSnapshot(context, record.event, ZonedDateTime.now(), snapshot))
    }

    fun scheduleEvent(
        context: Context,
        event: RoutineEvent,
        now: ZonedDateTime =
            ZonedDateTime.now()
    ): Boolean {
        val snapshot = runCatching { ScheduleRegistry.load(context) }.getOrNull() ?: return false
        return scheduleEventFromSnapshot(context, event, now, snapshot)
    }

    private fun scheduleEventFromSnapshot(context: Context, event: RoutineEvent, now: ZonedDateTime,
        snapshot: ScheduleRegistry.Snapshot): Boolean {
        if (
            !event.enabled ||
            snapshot.scheduledById(event.id) != event ||
            !canScheduleExact(context)
        ) {
            return false
        }

        val alarmManager =
            context.getSystemService(
                Context.ALARM_SERVICE
            ) as AlarmManager

        val next =
            nextOccurrence(
                event,
                now
            )

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            next.toInstant().toEpochMilli(),
            eventPendingIntent(
                context,
                event,
                snapshot
            )
        )

        return true
    }

    fun nextOccurrence(
        event: RoutineEvent,
        now: ZonedDateTime =
            ZonedDateTime.now()
    ): ZonedDateTime {
        for (daysAhead in 0..7) {
            val date =
                now.toLocalDate()
                    .plusDays(
                        daysAhead.toLong()
                    )

            if (
                date.dayOfWeek !in
                event.daysOfWeek
            ) {
                continue
            }

            val candidate =
                date
                    .atTime(
                        event.hour,
                        event.minute
                    )
                    .atZone(
                        now.zone
                    )

            if (
                candidate.isAfter(now)
            ) {
                return candidate
            }
        }

        error(
            "Keine nächste Ausführung für ${event.id} gefunden"
        )
    }

    fun scheduleTest(
        context: Context,
        minutes: Int = 1
    ) {
        com.cztr.routinealarm.execution.TestAlarmRuntime.create(context, minutes)
    }

    /** One preparation is one logical request; retries must reuse this immutable value. */
    class PreparedSnooze private constructor(
        val requestId: String,
        val triggerAtMillis: Long,
        val title: String,
        val spokenText: String,
        val restoreGeneration: String
    ) {
        fun toBundle(): Bundle = Bundle().apply {
            putString("id", requestId)
            putLong("at", triggerAtMillis)
            putString("title", title)
            putString("speech", spokenText)
            putString("generation", restoreGeneration)
        }
        companion object {
            internal fun prepare(title: String, speech: String, minutes: Int, generation: String): PreparedSnooze {
                require(minutes in 1..5)
                return PreparedSnooze(UUID.randomUUID().toString(),
                    System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes.toLong()),
                    title, speech, generation)
            }
            /** Framework saved state only, not imported plan/restore authority. */
            fun fromBundle(saved: Bundle): PreparedSnooze? {
                val id = saved.getString("id") ?: return null
                if (runCatching { UUID.fromString(id).toString() }.getOrNull() != id) return null
                val at = saved.getLong("at", -1L)
                if (at <= 0L) return null
                val title = saved.getString("title") ?: return null
                val speech = saved.getString("speech") ?: return null
                val generation = saved.getString("generation")?.takeIf { it.isNotBlank() } ?: return null
                return PreparedSnooze(id, at, title, speech, generation)
            }
        }
    }

    fun prepareSnooze(
        context: Context, title: String, spokenText: String = title, minutes: Int = 5,
        admission: Intent? = null
    ): PreparedSnooze? {
        // Capture first. A concurrent restore must never promote an old request to its new epoch.
        val generation = if (admission == null) ScheduleState.restoreGeneration(context)
            else admission.getStringExtra(EXTRA_RESTORE_GENERATION) ?: "before-first-restore"
        if (admission != null && !ScheduleState.isDeliveryAllowed(context, admission)) return null
        return PreparedSnooze.prepare(title, spokenText, minutes, generation)
    }

    /** True means scheduling API returned, not a delivery/receipt or durable exactly-once claim. */
    fun submitSnooze(context: Context, request: PreparedSnooze): Boolean {
        if (request.restoreGeneration != ScheduleState.restoreGeneration(context) ||
            request.triggerAtMillis <= System.currentTimeMillis()) return false
        scheduleOneShot(context, "routinealarm://snooze/${request.requestId}", request.title,
            request.spokenText, EventMode.ROUTINE, request.triggerAtMillis, request.restoreGeneration)
        return true
    }

    /** Each call is a new request. Retry callers use prepareSnooze / submitSnooze instead. */
    fun scheduleSnooze(context: Context, title: String, spokenText: String = title, minutes: Int = 5) {
        submitSnooze(context, requireNotNull(prepareSnooze(context, title, spokenText, minutes)))
    }

    private fun eventUri(eventId: String): Uri =
        Uri.parse("routinealarm://event/" + Uri.encode(eventId))

    private fun eventPendingIntent(
        context: Context,
        event: RoutineEvent,
        snapshot: ScheduleRegistry.Snapshot
    ): PendingIntent {
        val intent =
            Intent(
                context,
                AlarmReceiver::class.java
            ).apply {
                data = eventUri(event.id)
                putExtra(EXTRA_ROUTINE_PLAN_VERSION, snapshot.definitions[event.id]?.planVersion ?: 0L)
                putExtra(EXTRA_ROUTINE_VERSION, snapshot.definitions[event.id]?.version ?: 0L)
                putExtra(EXTRA_ROUTINE_EPOCH, snapshot.restoreEpoch)

                putExtra(EXTRA_RESTORE_GENERATION, ScheduleState.restoreGeneration(context))

                putExtra(
                    EXTRA_EVENT_ID,
                    event.id
                )

                putExtra(
                    EXTRA_TITLE,
                    event.title
                )

                putExtra(
                    EXTRA_SPOKEN_TEXT,
                    event.spokenText
                )

                putExtra(
                    EXTRA_MODE,
                    event.mode.name
                )

                putExtra(
                    EXTRA_CATEGORY,
                    event.category.name
                )

                putExtra(
                    EXTRA_RECURRING,
                    true
                )
            }

        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun scheduleOneShot(
        context: Context,
        intentData: String,
        title: String,
        spokenText: String,
        mode: EventMode,
        triggerTime: Long,
        restoreGeneration: String
    ) {
        val alarmManager =
            context.getSystemService(
                Context.ALARM_SERVICE
            ) as AlarmManager

        val intent =
            Intent(
                context,
                AlarmReceiver::class.java
            ).apply {
                data =
                    Uri.parse(
                        intentData
                    )

                putExtra(EXTRA_RESTORE_GENERATION, restoreGeneration)

                putExtra(
                    EXTRA_TITLE,
                    title
                )

                putExtra(
                    EXTRA_SPOKEN_TEXT,
                    spokenText
                )

                putExtra(
                    EXTRA_MODE,
                    mode.name
                )

                putExtra(
                    EXTRA_RECURRING,
                    false
                )
            }

        val pendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        if (
            Build.VERSION.SDK_INT <
                Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        ) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        }
    }
}
