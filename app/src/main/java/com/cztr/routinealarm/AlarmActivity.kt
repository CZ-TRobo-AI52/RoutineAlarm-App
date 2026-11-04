package com.cztr.routinealarm

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.cztr.routinealarm.ui.alarm.AlarmScreenView
import com.cztr.routinealarm.ui.alarm.AlarmUiAction
import com.cztr.routinealarm.ui.alarm.AlarmUiInput
import com.cztr.routinealarm.ui.alarm.AlarmUiMapper
import com.cztr.routinealarm.ui.design.R52Colors
import com.cztr.routinealarm.ui.shell.AppNavigationState
import com.cztr.routinealarm.ui.shell.ThemePreferenceStore
import java.time.LocalDateTime

class AlarmActivity : Activity() {
    private var preparedSnooze: AlarmScheduler.PreparedSnooze? = null
    private var preparedSnoozeMinutes: Int? = null
    private var invalidSavedSnooze = false
    private var admissionSequence = 0L
    private var active = false
    private var snoozePending = false
    private lateinit var originalAdmission: Intent
    private lateinit var title: String
    private lateinit var spokenText: String

    override fun onSaveInstanceState(outState: Bundle) {
        invalidateAdmission()
        outState.putParcelable(ORIGINAL_ADMISSION, Intent(originalAdmission))
        if (invalidSavedSnooze) outState.putBundle(PREPARED_SNOOZE, Bundle())
        preparedSnooze?.let { outState.putBundle(PREPARED_SNOOZE, it.toBundle()) }
        preparedSnoozeMinutes?.let { outState.putInt(PREPARED_SNOOZE_MINUTES, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val restoredAdmission = savedInstanceState?.getParcelable<Intent>(ORIGINAL_ADMISSION)
        originalAdmission = Intent(restoredAdmission ?: intent)
        if (savedInstanceState?.containsKey(PREPARED_SNOOZE) == true) {
            preparedSnooze = savedInstanceState.getBundle(PREPARED_SNOOZE)?.let {
                AlarmScheduler.PreparedSnooze.fromBundle(it)
            }
            preparedSnoozeMinutes = savedInstanceState.getInt(PREPARED_SNOOZE_MINUTES, -1)
                .takeIf { it in 1..5 }
            invalidSavedSnooze = preparedSnooze == null || preparedSnoozeMinutes == null
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        title = originalAdmission.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: "RoutineAlarm"
        spokenText = originalAdmission.getStringExtra(AlarmScheduler.EXTRA_SPOKEN_TEXT) ?: title
        renderAlarm(false, checking = true)
    }

    override fun onResume() {
        super.onResume()
        active = true
        refreshAdmission()
    }

    override fun onPause() {
        active = false
        invalidateAdmission()
        super.onPause()
    }

    override fun onDestroy() {
        active = false
        invalidateAdmission()
        super.onDestroy()
    }

    override fun finish() {
        active = false
        invalidateAdmission()
        super.finish()
    }

    private fun invalidateAdmission() {
        admissionSequence++
        snoozePending = false
    }

    private fun isCurrent(request: Long) =
        active && !isFinishing && !isDestroyed && admissionSequence == request

    private fun refreshAdmission() {
        val request = ++admissionSequence
        renderAlarm(false, checking = !invalidSavedSnooze)
        if (invalidSavedSnooze || !isCurrent(request)) return
        ScheduleState.requestDeliveryAdmission(this, originalAdmission, {
            if (isCurrent(request)) renderAlarm(true)
        }, {
            if (isCurrent(request)) renderAlarm(false)
        })
    }

    @Deprecated("Platform back navigation")
    override fun onBackPressed() {
        handleAlarmUiAction(AlarmUiAction.CLOSE_SCREEN)
    }

    private fun renderAlarm(deliveryAllowed: Boolean, checking: Boolean = false) {
        val theme = ThemePreferenceStore(this).read()
        val colors = AppNavigationState(theme = theme).colors(resources.configuration)
        val state = AlarmUiMapper.map(AlarmUiInput(
            title = title,
            spokenText = spokenText,
            now = LocalDateTime.now(),
            deliveryAllowed = !invalidSavedSnooze && deliveryAllowed,
            pendingSnoozeMinutes = preparedSnoozeMinutes,
            outputInstanceAvailable = originalAdmission
                .getStringExtra(AlarmService.EXTRA_OUTPUT_INSTANCE)
                ?.isNotBlank() == true,
        ))
        window.statusBarColor = colors.background
        window.navigationBarColor = colors.background
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (colors == R52Colors.Light)
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        val visibleState = if (checking) state.copy(warning = "Alarmberechtigung wird geprüft …") else state
        setContentView(AlarmScreenView(this, visibleState, colors, ::handleAlarmUiAction))
    }

    private fun handleAlarmUiAction(action: AlarmUiAction) {
        if (!active || isFinishing || isDestroyed) return
        when (action) {
            AlarmUiAction.CLOSE_SCREEN -> finish()
            AlarmUiAction.CLOSE_OUTPUT -> {
                stopAlarm()
                finish()
            }
            AlarmUiAction.DONE -> {
                stopAlarm()
                finish()
            }
            AlarmUiAction.SKIP -> {
                stopAlarm()
                finish()
            }
            is AlarmUiAction.Snooze -> submitSnooze(action.minutes)
        }
    }

    private fun submitSnooze(minutes: Int) {
        if (snoozePending || !active || isFinishing || isDestroyed) return
        if (invalidSavedSnooze) { refreshAdmission(); return }
        snoozePending = true
        val request = ++admissionSequence
        renderAlarm(false, checking = true)
        // Display state is not authority: every explicit tap admits the original delivery again.
        ScheduleState.requestDeliveryAdmission(this, originalAdmission, {
            if (isCurrent(request) && snoozePending) {
                snoozePending = false
                submitAdmittedSnooze(minutes)
            }
        }, {
            if (isCurrent(request) && snoozePending) {
                snoozePending = false
                Toast.makeText(this, "Diese Alarmausgabe ist nicht mehr aktuell.", Toast.LENGTH_LONG).show()
                renderAlarm(false)
            }
        })
    }

    private fun submitAdmittedSnooze(minutes: Int) {
        if (invalidSavedSnooze || !ScheduleState.isDeliveryAllowed(this, originalAdmission)) {
            Toast.makeText(this, "Diese Alarmausgabe ist nicht mehr aktuell.", Toast.LENGTH_LONG).show()
            refreshAdmission()
            return
        }
        val existingMinutes = preparedSnoozeMinutes
        if (preparedSnooze != null && existingMinutes != null && existingMinutes != minutes) {
            Toast.makeText(
                this,
                "Vorherige Verschiebung unklar. Bitte $existingMinutes Minuten erneut versuchen.",
                Toast.LENGTH_LONG,
            ).show()
            refreshAdmission()
            return
        }
        val request = preparedSnooze ?: AlarmScheduler.prepareSnooze(
            this,
            title,
            spokenText,
            minutes,
            originalAdmission,
        )?.also {
            preparedSnooze = it
            preparedSnoozeMinutes = minutes
        } ?: run {
            Toast.makeText(this, "Verschiebung nicht autorisiert.", Toast.LENGTH_LONG).show()
            refreshAdmission()
            return
        }
        val result = runCatching { AlarmScheduler.submitSnooze(this, request) }
        if (result.isFailure) {
            Toast.makeText(
                this,
                "Planungswirkung unklar. Bitte dieselbe Dauer erneut versuchen.",
                Toast.LENGTH_LONG,
            ).show()
            refreshAdmission()
            return
        }
        if (!result.getOrDefault(false)) {
            preparedSnooze = null
            preparedSnoozeMinutes = null
            Toast.makeText(
                this,
                "Die vorbereitete Verschiebung ist nicht mehr gültig. Aktuellen Zustand prüfen.",
                Toast.LENGTH_LONG,
            ).show()
            refreshAdmission()
            return
        }
        stopAlarm()
        finish()
    }

    private fun stopAlarm() {
        val stopIntent = Intent(this, AlarmService::class.java).apply {
            action = AlarmService.ACTION_STOP
            putExtra(
                AlarmService.EXTRA_OUTPUT_INSTANCE,
                originalAdmission.getStringExtra(AlarmService.EXTRA_OUTPUT_INSTANCE),
            )
        }
        startService(stopIntent)
    }

    companion object {
        private const val ORIGINAL_ADMISSION = "original_alarm_admission"
        private const val PREPARED_SNOOZE = "prepared_snooze"
        private const val PREPARED_SNOOZE_MINUTES = "prepared_snooze_minutes"
    }
}
