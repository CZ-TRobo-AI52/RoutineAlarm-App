package com.cztr.routinealarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {

        val title =
            intent.getStringExtra(AlarmScheduler.EXTRA_TITLE)
                ?: "RoutineAlarm"

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !AlarmScheduler.canScheduleExact(context)
        ) {
            postInexactAlarmFallback(context, title)
            return
        }

        val serviceIntent =
            Intent(context, AlarmService::class.java).apply {
                putExtra(AlarmScheduler.EXTRA_TITLE, title)
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                context.startForegroundService(serviceIntent)
            } catch (exception: ForegroundServiceStartNotAllowedException) {
                // Exact-alarm access is a snapshot; the OS may still reject the FGS launch.
                postInexactAlarmFallback(context, title)
            }
        } else {
            context.startService(serviceIntent)
        }
    }

    private fun postInexactAlarmFallback(
        context: Context,
        title: String
    ) {
        val channelId = AlarmService.CHANNEL_ID
        val manager = context.getSystemService(NotificationManager::class.java)

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(channelId) == null
        ) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "RoutineAlarm Wecker",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Wichtige RoutineAlarm Erinnerungen"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    enableVibration(true)
                }
            )
        }

        val fullScreenIntent =
            Intent(context, AlarmActivity::class.java).apply {
                putExtra(AlarmScheduler.EXTRA_TITLE, title)
                flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

        val fullScreen =
            PendingIntent.getActivity(
                context,
                1002,
                fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        val notification =
            Notification.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("RoutineAlarm")
                .setContentText(title)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_MAX)
                .setOngoing(true)
                .setAutoCancel(false)
                .setFullScreenIntent(fullScreen, true)
                .build()

        manager.notify(AlarmService.NOTIFICATION_ID, notification)
    }
}
