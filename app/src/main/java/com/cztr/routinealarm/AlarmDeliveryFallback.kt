package com.cztr.routinealarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

internal object AlarmDeliveryFallback {
    const val CHANNEL_ID = "routine_alarm_fallback_channel"
    const val NOTIFICATION_ID = 1003

    fun post(context: Context, title: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "RoutineAlarm Fallback",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Fallback notification when foreground-service start is rejected"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }

        val intent = Intent(context, AlarmActivity::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_TITLE, title)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("RoutineAlarm")
                .setContentText(title)
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_MAX)
                .setAutoCancel(true)
                .setFullScreenIntent(pendingIntent, true)
                .build()
        } else {
            Notification.Builder(context)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("RoutineAlarm")
                .setContentText(title)
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_MAX)
                .setAutoCancel(true)
                .setFullScreenIntent(pendingIntent, true)
                .build()
        }

        manager.notify(NOTIFICATION_ID, notification)
    }
}
