package com.cztr.routinealarm

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class AlarmActivity : Activity() {

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        val title =
            intent.getStringExtra(
                AlarmScheduler.EXTRA_TITLE
            )
                ?: "RoutineAlarm"

        val spokenText =
            intent.getStringExtra(
                AlarmScheduler.EXTRA_SPOKEN_TEXT
            )
                ?: title

        val now =
            LocalDateTime.now()

        val root =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL

                gravity =
                    Gravity.CENTER_HORIZONTAL

                setPadding(
                    dp(24),
                    dp(44),
                    dp(24),
                    dp(30)
                )

                setBackgroundColor(
                    Color.rgb(
                        247,
                        248,
                        250
                    )
                )
            }

        val clock =
            TextView(this).apply {
                text =
                    now.format(
                        DateTimeFormatter.ofPattern(
                            "HH:mm",
                            Locale.GERMAN
                        )
                    )

                textSize =
                    52f

                gravity =
                    Gravity.CENTER

                setTextColor(
                    Color.rgb(
                        20,
                        23,
                        28
                    )
                )

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )
            }

        val date =
            TextView(this).apply {
                text =
                    now.format(
                        DateTimeFormatter.ofPattern(
                            "EEEE, d. MMMM",
                            Locale.GERMAN
                        )
                    )
                        .replaceFirstChar {
                            it.titlecase(
                                Locale.GERMAN
                            )
                        }

                textSize =
                    15f

                gravity =
                    Gravity.CENTER

                setTextColor(
                    Color.rgb(
                        104,
                        109,
                        118
                    )
                )

                setPadding(
                    0,
                    dp(2),
                    0,
                    dp(28)
                )
            }

        val card =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL

                gravity =
                    Gravity.CENTER_HORIZONTAL

                setPadding(
                    dp(22),
                    dp(24),
                    dp(22),
                    dp(24)
                )

                background =
                    roundedBackground(
                        Color.WHITE,
                        22
                    )

                elevation =
                    dp(2).toFloat()
            }

        val label =
            TextView(this).apply {
                text =
                    "JETZT"

                textSize =
                    12f

                letterSpacing =
                    0.12f

                setTextColor(
                    Color.rgb(
                        51,
                        82,
                        235
                    )
                )

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )
            }

        val task =
            TextView(this).apply {
                text =
                    title

                textSize =
                    28f

                gravity =
                    Gravity.CENTER

                setTextColor(
                    Color.rgb(
                        20,
                        23,
                        28
                    )
                )

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )

                setPadding(
                    0,
                    dp(12),
                    0,
                    0
                )
            }

        card.addView(label)
        card.addView(task)

        if (
            spokenText.isNotBlank() &&
            spokenText != title
        ) {
            val spoken =
                TextView(this).apply {
                    text =
                        spokenText

                    textSize =
                        16f

                    gravity =
                        Gravity.CENTER

                    setTextColor(
                        Color.rgb(
                            95,
                            100,
                            109
                        )
                    )

                    setPadding(
                        0,
                        dp(12),
                        0,
                        0
                    )
                }

            card.addView(spoken)
        }

        val spacer =
            Space(this)

        val done =
            actionButton(
                "Erledigt",
                primary = true
            ).apply {
                setOnClickListener {
                    stopAlarm()
                    finish()
                }
            }

        val snooze =
            actionButton(
                "5 Minuten später",
                primary = false
            ).apply {
                setOnClickListener {
                    AlarmScheduler.scheduleSnooze(
                        context =
                            this@AlarmActivity,
                        title =
                            title,
                        spokenText =
                            spokenText,
                        minutes =
                            5
                    )

                    stopAlarm()
                    finish()
                }
            }

        val skip =
            actionButton(
                "Überspringen",
                primary = false
            ).apply {
                setOnClickListener {
                    stopAlarm()
                    finish()
                }
            }

        root.addView(clock)
        root.addView(date)

        root.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            spacer,
            LinearLayout.LayoutParams(
                1,
                0,
                1f
            )
        )

        root.addView(
            done,
            buttonParams(
                top = 20
            )
        )

        root.addView(
            snooze,
            buttonParams(
                top = 10
            )
        )

        root.addView(
            skip,
            buttonParams(
                top = 10
            )
        )

        setContentView(root)
    }

    private fun actionButton(
        label: String,
        primary: Boolean
    ): Button =
        Button(this).apply {
            text =
                label

            isAllCaps =
                false

            textSize =
                17f

            minHeight =
                dp(58)

            setTypeface(
                typeface,
                Typeface.BOLD
            )

            setTextColor(
                if (
                    primary
                ) {
                    Color.WHITE
                } else {
                    Color.rgb(
                        40,
                        45,
                        54
                    )
                }
            )

            background =
                roundedBackground(
                    if (
                        primary
                    ) {
                        Color.rgb(
                            51,
                            82,
                            235
                        )
                    } else {
                        Color.WHITE
                    },
                    16
                )
        }

    private fun buttonParams(
        top: Int
    ): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(58)
        ).apply {
            topMargin =
                dp(top)
        }

    private fun roundedBackground(
        color: Int,
        radiusDp: Int
    ): GradientDrawable =
        GradientDrawable().apply {
            shape =
                GradientDrawable.RECTANGLE

            setColor(
                color
            )

            cornerRadius =
                dp(
                    radiusDp
                )
                    .toFloat()
        }

    private fun stopAlarm() {
        val stopIntent =
            Intent(
                this,
                AlarmService::class.java
            ).apply {
                action =
                    AlarmService.ACTION_STOP
            }

        startService(
            stopIntent
        )
    }

    private fun dp(
        value: Int
    ): Int =
        (
            value *
                resources
                    .displayMetrics
                    .density
            )
            .toInt()
}
