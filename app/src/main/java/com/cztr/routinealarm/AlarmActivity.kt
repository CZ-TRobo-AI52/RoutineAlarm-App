package com.cztr.routinealarm

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.*

class AlarmActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        val title =
            intent.getStringExtra(AlarmScheduler.EXTRA_TITLE)
                ?: "RoutineAlarm"

        val layout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(48, 48, 48, 48)
            }

        val heading =
            TextView(this).apply {
                text = "ROUTINEALARM"
                textSize = 30f
                gravity = Gravity.CENTER
                setTextColor(Color.BLACK)
            }

        val task =
            TextView(this).apply {
                text = title
                textSize = 24f
                gravity = Gravity.CENTER
                setPadding(0, 60, 0, 60)
                setTextColor(Color.BLACK)
            }

        val done =
            Button(this).apply {
                text = "ERLEDIGT"
                setOnClickListener {
                    stopAlarm()
                    finish()
                }
            }

        val snooze =
            Button(this).apply {
                text = "5 MINUTEN SPÄTER"
                setOnClickListener {
                    AlarmScheduler.scheduleSnooze(
                        this@AlarmActivity,
                        title,
                        5
                    )
                    stopAlarm()
                    finish()
                }
            }

        val skip =
            Button(this).apply {
                text = "ÜBERSPRINGEN"
                setOnClickListener {
                    stopAlarm()
                    finish()
                }
            }

        layout.addView(heading)
        layout.addView(task)
        layout.addView(done)
        layout.addView(snooze)
        layout.addView(skip)

        setContentView(layout)
    }

    private fun stopAlarm() {
        val stopIntent =
            Intent(this, AlarmService::class.java).apply {
                action = AlarmService.ACTION_STOP
            }

        startService(stopIntent)
    }
}
