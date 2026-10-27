package com.cztr.routinealarm

import android.content.Context
import android.content.Intent
import java.util.UUID

object ScheduleState {

    private const val PREFS =
        "routinealarm_schedule"

    private const val KEY_ENABLED =
        "weekly_schedule_enabled"

    private const val KEY_RESTORE_GENERATION = "restore_generation"
    private const val BEFORE_FIRST_RESTORE = "before-first-restore"

    internal fun restoreGeneration(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RESTORE_GENERATION, BEFORE_FIRST_RESTORE) ?: BEFORE_FIRST_RESTORE

    /** Shared receiver/service fence. A weekly pause does not revoke an explicit one-shot. */
    internal fun isDeliveryAllowed(context: Context, intent: Intent?): Boolean {
        if (intent == null) return false
        // Preserve pre-upgrade requests only until the first completed restore.
        val generation = if (intent.hasExtra(AlarmScheduler.EXTRA_RESTORE_GENERATION)) {
            intent.getStringExtra(AlarmScheduler.EXTRA_RESTORE_GENERATION)
        } else BEFORE_FIRST_RESTORE
        if (generation != restoreGeneration(context)) return false
        if (!intent.getBooleanExtra(AlarmScheduler.EXTRA_RECURRING, false)) return true
        val id = intent.getStringExtra(AlarmScheduler.EXTRA_EVENT_ID) ?: return false
        return isEnabled(context) && ScheduleRegistry.scheduledById(id)?.enabled == true
    }

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
            .edit().putBoolean(KEY_ENABLED, false)
            // A restored backup must never recreate the identity of an older admission.
            .putString(KEY_RESTORE_GENERATION, UUID.randomUUID().toString()).commit()) {
            "Could not persist disabled weekly schedule after restore"
        }
    }
}
