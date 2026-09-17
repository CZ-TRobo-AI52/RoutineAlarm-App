package com.cztr.routinealarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {

        val title =
            intent.getStringExtra(AlarmScheduler.EXTRA_TITLE)
                ?: "RoutineAlarm"

        val serviceIntent =
            Intent(context, AlarmService::class.java).apply {
                putExtra(AlarmScheduler.EXTRA_TITLE, title)
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
