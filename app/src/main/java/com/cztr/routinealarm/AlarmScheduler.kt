package com.cztr.routinealarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.concurrent.TimeUnit

object AlarmScheduler {

    const val EXTRA_TITLE = "title"
    const val EXTRA_RECURRING = "recurring"

    fun canScheduleExact(context: Context): Boolean {
        val alarmManager =
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    fun scheduleTest(context: Context, minutes: Int = 1) {
        val triggerTime =
            System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes.toLong())

        scheduleOneShot(
            context = context,
            requestCode = 900001,
            title = "RoutineAlarm Test. Der Wecker funktioniert.",
            triggerTime = triggerTime
        )
    }

    fun scheduleSnooze(context: Context, title: String, minutes: Int = 5) {
        val triggerTime =
            System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes.toLong())

        val requestCode =
            (System.currentTimeMillis() % Int.MAX_VALUE).toInt()

        scheduleOneShot(
            context = context,
            requestCode = requestCode,
            title = title,
            triggerTime = triggerTime
        )
    }

    private fun scheduleOneShot(
        context: Context,
        requestCode: Int,
        title: String,
        triggerTime: Long
    ) {
        val alarmManager =
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_RECURRING, false)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
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
