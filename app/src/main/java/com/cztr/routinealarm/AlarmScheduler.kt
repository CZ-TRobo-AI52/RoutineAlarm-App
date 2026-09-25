package com.cztr.routinealarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object AlarmScheduler {

    const val EXTRA_EVENT_ID = "event_id"
    const val EXTRA_TITLE = "title"
    const val EXTRA_SPOKEN_TEXT = "spoken_text"
    const val EXTRA_MODE = "mode"
    const val EXTRA_CATEGORY = "category"
    const val EXTRA_RECURRING = "recurring"

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
        if (!canScheduleExact(context)) {
            return 0
        }

        var scheduled = 0

        ScheduleRegistry.enabledEvents.forEach { event ->
            if (
                scheduleEvent(
                    context,
                    event
                )
            ) {
                scheduled++
            }
        }

        return scheduled
    }

    fun cancelAll(
        context: Context
    ) {
        val alarmManager =
            context.getSystemService(
                Context.ALARM_SERVICE
            ) as AlarmManager

        ScheduleRegistry.scheduledEvents.forEach { event ->
            val pendingIntent =
                eventPendingIntent(
                    context,
                    event
                )

            alarmManager.cancel(
                pendingIntent
            )

            pendingIntent.cancel()
        }
    }

    fun scheduleEvent(
        context: Context,
        event: RoutineEvent,
        now: ZonedDateTime =
            ZonedDateTime.now()
    ): Boolean {
        if (
            !event.enabled ||
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
                event
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
        val triggerTime =
            System.currentTimeMillis() +
                TimeUnit.MINUTES.toMillis(
                    minutes.toLong()
                )

        scheduleOneShot(
            context = context,
            intentData =
                "routinealarm://test/${System.currentTimeMillis()}",
            title =
                "RoutineAlarm Test",
            spokenText =
                "RoutineAlarm Test. Der Wecker funktioniert.",
            mode =
                EventMode.ROUTINE,
            triggerTime =
                triggerTime
        )
    }

    fun scheduleSnooze(
        context: Context,
        title: String,
        spokenText: String = title,
        minutes: Int = 5
    ) {
        val triggerTime =
            System.currentTimeMillis() +
                TimeUnit.MINUTES.toMillis(
                    minutes.toLong()
                )

        scheduleOneShot(
            context = context,
            intentData =
                "routinealarm://snooze/${System.currentTimeMillis()}",
            title = title,
            spokenText = spokenText,
            mode = EventMode.ROUTINE,
            triggerTime = triggerTime
        )
    }

    private fun eventPendingIntent(
        context: Context,
        event: RoutineEvent
    ): PendingIntent {
        val intent =
            Intent(
                context,
                AlarmReceiver::class.java
            ).apply {
                data =
                    Uri.parse(
                        "routinealarm://event/" +
                            Uri.encode(
                                event.id
                            )
                    )

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
        triggerTime: Long
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
