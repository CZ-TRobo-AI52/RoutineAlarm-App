package com.cztr.routinealarm

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status:
        TextView

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(
            savedInstanceState
        )

        val layout =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL

                gravity =
                    Gravity.CENTER_HORIZONTAL

                setPadding(
                    40,
                    70,
                    40,
                    40
                )
            }

        val title =
            TextView(this).apply {
                text =
                    "RoutineAlarm"

                textSize =
                    32f
            }

        status =
            TextView(this).apply {
                textSize =
                    17f

                setPadding(
                    0,
                    40,
                    0,
                    40
                )
            }

        val notificationButton =
            Button(this).apply {
                text =
                    "BENACHRICHTIGUNGEN ERLAUBEN"

                setOnClickListener {
                    requestNotificationPermission()
                }
            }

        val exactButton =
            Button(this).apply {
                text =
                    "EXAKTE WECKER ERLAUBEN"

                setOnClickListener {
                    requestExactAlarmPermission()
                }
            }

        val scheduleButton =
            Button(this).apply {
                text =
                    "WOCHENPLAN AKTIVIEREN"

                setOnClickListener {
                    ScheduleState.setEnabled(
                        this@MainActivity,
                        true
                    )

                    if (
                        !AlarmScheduler.canScheduleExact(
                            this@MainActivity
                        )
                    ) {
                        requestExactAlarmPermission()
                        refreshStatus()
                        return@setOnClickListener
                    }

                    val count =
                        AlarmScheduler.scheduleAll(
                            this@MainActivity
                        )

                    Toast.makeText(
                        this@MainActivity,
                        "$count Ereignisse eingeplant",
                        Toast.LENGTH_LONG
                    ).show()

                    refreshStatus()
                }
            }

        val disableButton =
            Button(this).apply {
                text =
                    "WOCHENPLAN DEAKTIVIEREN"

                setOnClickListener {
                    ScheduleState.setEnabled(
                        this@MainActivity,
                        false
                    )

                    AlarmScheduler.cancelAll(
                        this@MainActivity
                    )

                    stopService(
                        Intent(
                            this@MainActivity,
                            AlarmService::class.java
                        )
                    )

                    stopService(
                        Intent(
                            this@MainActivity,
                            TrainingSessionService::class.java
                        )
                    )

                    Toast.makeText(
                        this@MainActivity,
                        "Wochenplan deaktiviert",
                        Toast.LENGTH_LONG
                    ).show()

                    refreshStatus()
                }
            }

        val testButton =
            Button(this).apply {
                text =
                    "TESTWECKER IN 1 MINUTE"

                setOnClickListener {
                    if (
                        !AlarmScheduler.canScheduleExact(
                            this@MainActivity
                        )
                    ) {
                        requestExactAlarmPermission()
                        return@setOnClickListener
                    }

                    AlarmScheduler.scheduleTest(
                        this@MainActivity,
                        1
                    )

                    Toast.makeText(
                        this@MainActivity,
                        "Testwecker für 1 Minute geplant",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

        layout.addView(
            title
        )

        layout.addView(
            status
        )

        layout.addView(
            notificationButton
        )

        layout.addView(
            exactButton
        )

        layout.addView(
            scheduleButton
        )

        layout.addView(
            disableButton
        )

        layout.addView(
            testButton
        )

        setContentView(
            layout
        )

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()

        if (
            ScheduleState.isEnabled(
                this
            ) &&
            AlarmScheduler.canScheduleExact(
                this
            )
        ) {
            AlarmScheduler.scheduleAll(
                this
            )
        }

        refreshStatus()
    }

    private fun refreshStatus() {
        val exact =
            if (
                AlarmScheduler.canScheduleExact(
                    this
                )
            ) {
                "JA"
            } else {
                "NEIN"
            }

        val notifications =
            if (
                Build.VERSION.SDK_INT <
                    Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS
                ) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                "JA"
            } else {
                "NEIN"
            }

        val schedule =
            if (
                ScheduleState.isEnabled(
                    this
                )
            ) {
                "AKTIV"
            } else {
                "INAKTIV"
            }

        status.text =
            """
            Exakte Wecker: $exact
            Benachrichtigungen: $notifications
            Wochenplan: $schedule
            Geplante Ereignistypen: ${ScheduleRegistry.enabledEvents.size}

            Trainings-Cues laufen innerhalb einer TrainingSession.
            """.trimIndent()
    }

    private fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.POST_NOTIFICATIONS
                ),
                100
            )
        }
    }

    private fun requestExactAlarmPermission() {
        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S &&
            !AlarmScheduler.canScheduleExact(
                this
            )
        ) {
            val intent =
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
                ).apply {
                    data =
                        Uri.parse(
                            "package:$packageName"
                        )
                }

            startActivity(
                intent
            )
        }
    }
}
