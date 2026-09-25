package com.cztr.routinealarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver :
    BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {
        val shouldRestore =
            when (
                intent.action
            ) {
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                AlarmManager
                    .ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ->
                    true

                else ->
                    false
            }

        if (
            shouldRestore &&
            ScheduleState.isEnabled(
                context
            ) &&
            AlarmScheduler.canScheduleExact(
                context
            )
        ) {
            AlarmScheduler.scheduleAll(
                context
            )
        }
    }
}
