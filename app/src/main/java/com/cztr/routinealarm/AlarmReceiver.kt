package com.cztr.routinealarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {
        val recurring =
            intent.getBooleanExtra(
                AlarmScheduler.EXTRA_RECURRING,
                false
            )

        val eventId =
            intent.getStringExtra(
                AlarmScheduler.EXTRA_EVENT_ID
            )

        val event =
            if (
                recurring &&
                !eventId.isNullOrBlank()
            ) {
                ScheduleRegistry.byId(
                    eventId
                )
            } else {
                null
            }

        if (recurring) {
            if (
                !ScheduleState.isEnabled(
                    context
                )
            ) {
                return
            }

            if (
                event == null ||
                !event.enabled
            ) {
                return
            }

            AlarmScheduler.scheduleEvent(
                context,
                event
            )
        }

        if (
            event != null &&
            TrainingSessionPlan.isStartEvent(
                event.id
            )
        ) {
            TrainingSessionService.start(
                context,
                event
            )

            return
        }

        val title =
            event?.title
                ?: intent.getStringExtra(
                    AlarmScheduler.EXTRA_TITLE
                )
                ?: "RoutineAlarm"

        val spokenText =
            event?.spokenText
                ?: intent.getStringExtra(
                    AlarmScheduler.EXTRA_SPOKEN_TEXT
                )
                ?: title

        val mode =
            event?.mode?.name
                ?: intent.getStringExtra(
                    AlarmScheduler.EXTRA_MODE
                )
                ?: EventMode.ROUTINE.name

        val serviceIntent =
            Intent(
                context,
                AlarmService::class.java
            ).apply {
                putExtra(
                    AlarmScheduler.EXTRA_EVENT_ID,
                    eventId
                )

                putExtra(
                    AlarmScheduler.EXTRA_TITLE,
                    title
                )

                putExtra(
                    AlarmScheduler.EXTRA_SPOKEN_TEXT,
                    spokenText
                )

                putExtra(
                    AlarmScheduler.EXTRA_MODE,
                    mode
                )
            }

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {
            context.startForegroundService(
                serviceIntent
            )
        } else {
            context.startService(
                serviceIntent
            )
        }
    }
}
