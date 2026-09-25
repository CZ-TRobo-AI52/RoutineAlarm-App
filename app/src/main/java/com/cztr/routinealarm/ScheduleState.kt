package com.cztr.routinealarm

import android.content.Context

object ScheduleState {

    private const val PREFS =
        "routinealarm_schedule"

    private const val KEY_ENABLED =
        "weekly_schedule_enabled"

    fun isEnabled(context: Context): Boolean =
        context
            .getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
            .getBoolean(
                KEY_ENABLED,
                false
            )

    fun setEnabled(
        context: Context,
        enabled: Boolean
    ) {
        context
            .getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
            .edit()
            .putBoolean(
                KEY_ENABLED,
                enabled
            )
            .apply()
    }
}
