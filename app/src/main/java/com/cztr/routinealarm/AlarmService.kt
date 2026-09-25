package com.cztr.routinealarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class AlarmService :
    Service(),
    TextToSpeech.OnInitListener {

    companion object {
        const val ROUTINE_CHANNEL_ID =
            "routine_alarm_channel"

        const val ANNOUNCEMENT_CHANNEL_ID =
            "routine_announcement_channel"

        const val NOTIFICATION_ID =
            1001

        const val ACTION_STOP =
            "com.cztr.routinealarm.STOP"
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var hasPayload = false

    private var currentTitle =
        "RoutineAlarm"

    private var currentSpokenText =
        "RoutineAlarm"

    private var currentMode =
        EventMode.ROUTINE

    private val handler =
        Handler(
            Looper.getMainLooper()
        )

    private var wakeLock:
        PowerManager.WakeLock? = null

    private val repeatRunnable =
        object : Runnable {
            override fun run() {
                speak(
                    currentSpokenText,
                    "routine_repeat_" +
                        System.currentTimeMillis()
                )

                handler.postDelayed(
                    this,
                    8000
                )
            }
        }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannels()

        val powerManager =
            getSystemService(
                Context.POWER_SERVICE
            ) as PowerManager

        wakeLock =
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "RoutineAlarm:AlarmWakeLock"
            )

        wakeLock?.acquire(
            30 * 60 * 1000L
        )

        tts =
            TextToSpeech(
                this,
                this
            )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (
            intent?.action ==
            ACTION_STOP
        ) {
            stopAlarm()
            return START_NOT_STICKY
        }

        currentTitle =
            intent
                ?.getStringExtra(
                    AlarmScheduler.EXTRA_TITLE
                )
                ?: "RoutineAlarm"

        currentSpokenText =
            intent
                ?.getStringExtra(
                    AlarmScheduler.EXTRA_SPOKEN_TEXT
                )
                ?: currentTitle

        currentMode =
            runCatching {
                EventMode.valueOf(
                    intent
                        ?.getStringExtra(
                            AlarmScheduler.EXTRA_MODE
                        )
                        ?: EventMode.ROUTINE.name
                )
            }.getOrDefault(
                EventMode.ROUTINE
            )

        hasPayload = true

        startForegroundCompat(
            createNotification(
                currentTitle,
                currentSpokenText,
                currentMode
            )
        )

        if (ttsReady) {
            beginOutput()
        }

        return START_NOT_STICKY
    }

    override fun onInit(
        status: Int
    ) {
        if (
            status !=
            TextToSpeech.SUCCESS
        ) {
            stopAlarm()
            return
        }

        tts?.language =
            Locale.GERMAN

        tts?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(
                    AudioAttributes.USAGE_ALARM
                )
                .setContentType(
                    AudioAttributes.CONTENT_TYPE_SPEECH
                )
                .build()
        )

        tts?.setOnUtteranceProgressListener(
            object :
                UtteranceProgressListener() {

                override fun onStart(
                    utteranceId: String?
                ) = Unit

                override fun onDone(
                    utteranceId: String?
                ) {
                    if (
                        utteranceId
                            ?.startsWith(
                                "announcement_once_"
                            ) == true
                    ) {
                        handler.post {
                            stopAlarm()
                        }
                    }
                }

                @Deprecated(
                    "Deprecated in Android"
                )
                override fun onError(
                    utteranceId: String?
                ) {
                    onSpeechError(
                        utteranceId
                    )
                }

                override fun onError(
                    utteranceId: String?,
                    errorCode: Int
                ) {
                    onSpeechError(
                        utteranceId
                    )
                }
            }
        )

        ttsReady = true

        if (hasPayload) {
            beginOutput()
        }
    }

    private fun onSpeechError(
        utteranceId: String?
    ) {
        if (
            utteranceId
                ?.startsWith(
                    "announcement_once_"
                ) == true
        ) {
            handler.post {
                stopAlarm()
            }
        }
    }

    private fun beginOutput() {
        handler.removeCallbacks(
            repeatRunnable
        )

        tts?.stop()

        when (currentMode) {
            EventMode.ROUTINE -> {
                handler.post(
                    repeatRunnable
                )
            }

            EventMode.ANNOUNCEMENT -> {
                speak(
                    currentSpokenText,
                    "announcement_once_" +
                        System.currentTimeMillis()
                )
            }
        }
    }

    private fun speak(
        text: String,
        utteranceId: String
    ) {
        tts?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            utteranceId
        )
    }

    private fun startForegroundCompat(
        notification: Notification
    ) {
        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    private fun createNotification(
        title: String,
        spokenText: String,
        mode: EventMode
    ): Notification {
        val channelId =
            if (
                mode ==
                EventMode.ROUTINE
            ) {
                ROUTINE_CHANNEL_ID
            } else {
                ANNOUNCEMENT_CHANNEL_ID
            }

        val builder =
            Notification.Builder(
                this,
                channelId
            )
                .setSmallIcon(
                    android.R.drawable
                        .ic_lock_idle_alarm
                )
                .setContentTitle(
                    "RoutineAlarm"
                )
                .setContentText(
                    title
                )
                .setVisibility(
                    Notification
                        .VISIBILITY_PUBLIC
                )

        if (
            mode ==
            EventMode.ROUTINE
        ) {
            val fullScreenIntent =
                Intent(
                    this,
                    AlarmActivity::class.java
                ).apply {
                    putExtra(
                        AlarmScheduler.EXTRA_TITLE,
                        title
                    )

                    putExtra(
                        AlarmScheduler.EXTRA_SPOKEN_TEXT,
                        spokenText
                    )

                    flags =
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                }

            val fullScreenPendingIntent =
                PendingIntent.getActivity(
                    this,
                    1002,
                    fullScreenIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
                )

            builder
                .setCategory(
                    Notification.CATEGORY_ALARM
                )
                .setPriority(
                    Notification.PRIORITY_MAX
                )
                .setOngoing(true)
                .setAutoCancel(false)
                .setFullScreenIntent(
                    fullScreenPendingIntent,
                    true
                )
        } else {
            builder
                .setCategory(
                    Notification.CATEGORY_REMINDER
                )
                .setPriority(
                    Notification.PRIORITY_DEFAULT
                )
                .setOngoing(true)
        }

        return builder.build()
    }

    private fun createNotificationChannels() {
        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {
            val routine =
                NotificationChannel(
                    ROUTINE_CHANNEL_ID,
                    "RoutineAlarm Wecker",
                    NotificationManager
                        .IMPORTANCE_HIGH
                ).apply {
                    description =
                        "Wichtige RoutineAlarm Erinnerungen"

                    lockscreenVisibility =
                        Notification
                            .VISIBILITY_PUBLIC

                    enableVibration(true)
                }

            val announcement =
                NotificationChannel(
                    ANNOUNCEMENT_CHANNEL_ID,
                    "RoutineAlarm Ansagen",
                    NotificationManager
                        .IMPORTANCE_LOW
                ).apply {
                    description =
                        "Kurze gesprochene Routinehinweise"

                    lockscreenVisibility =
                        Notification
                            .VISIBILITY_PUBLIC
                }

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager.createNotificationChannel(
                routine
            )

            manager.createNotificationChannel(
                announcement
            )
        }
    }

    private fun stopAlarm() {
        handler.removeCallbacks(
            repeatRunnable
        )

        tts?.stop()

        stopForeground(
            STOP_FOREGROUND_REMOVE
        )

        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(
            repeatRunnable
        )

        tts?.stop()
        tts?.shutdown()

        if (
            wakeLock?.isHeld ==
            true
        ) {
            wakeLock?.release()
        }

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ) = null
}
