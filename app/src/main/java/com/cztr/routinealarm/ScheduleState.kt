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

    /** OS restore is data recovery, not a renewed request to run the weekly schedule. */
    fun disableAfterRestore(context: Context) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, false).commit()) {
            "Could not persist disabled weekly schedule after restore"
        }
    }
}
