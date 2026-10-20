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
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZonedDateTime
import java.util.Locale
import java.util.UUID

class TrainingSessionService :
    Service(),
    TextToSpeech.OnInitListener {

    companion object {
        private const val CHANNEL_ID =
            "routine_training_channel"

        private const val NOTIFICATION_ID =
            2001

        private const val EXTRA_DAY =
            "training_day"

        private const val ACTION_STOP =
            "com.cztr.routinealarm.STOP_TRAINING"

        fun start(
            context: Context,
            startEvent: RoutineEvent
        ) {
            val day =
                startEvent.daysOfWeek.single()

            val intent =
                Intent(
                    context,
                    TrainingSessionService::class.java
                ).apply {
                    putExtra(
                        EXTRA_DAY,
                        day.name
                    )
                }

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
            ) {
                context.startForegroundService(
                    intent
                )
            } else {
                context.startService(
                    intent
                )
            }
        }
    }

    private val handler =
        Handler(
            Looper.getMainLooper()
        )

    private var tts:
        TextToSpeech? = null

    private var ttsReady =
        false

    private var sessionDay:
        DayOfWeek? = null

    private var sessionEvents:
        List<RoutineEvent> =
        emptyList()

    private var sessionId:
        String? = null

    private var destroyed = false

    private var lastScheduledUtteranceId:
        String? = null

    private var wakeLock:
        PowerManager.WakeLock? =
        null

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        val powerManager =
            getSystemService(
                Context.POWER_SERVICE
            ) as PowerManager

        wakeLock =
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "RoutineAlarm:TrainingWakeLock"
            )

        wakeLock?.acquire(
            3 * 60 * 60 * 1000L
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
            stopSession()
            return START_NOT_STICKY
        }

        handler.removeCallbacksAndMessages(null)
        tts?.stop()
        sessionId = UUID.randomUUID().toString()
        lastScheduledUtteranceId = null

        sessionDay =
            runCatching {
                DayOfWeek.valueOf(
                    intent
                        ?.getStringExtra(
                            EXTRA_DAY
                        )
                        ?: ""
                )
            }.getOrNull()
                ?: ZonedDateTime
                    .now()
                    .dayOfWeek

        sessionEvents =
            TrainingSessionPlan
                .eventsFor(
                    sessionDay!!
                )
                .sortedBy {
                    it.minuteOfDay
                }

        startForegroundCompat(
            createNotification(
                "Training startet"
            )
        )

        if (sessionEvents.isEmpty()) {
            stopSession()
        } else if (ttsReady) {
            scheduleCues()
        }

        return START_NOT_STICKY
    }

    override fun onInit(
        status: Int
    ) {
        if (destroyed) return

        if (
            status !=
            TextToSpeech.SUCCESS
        ) {
            stopSession()
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
                    // TTS callbacks can arrive after stop or after another start.
                    // Check the current session on the main thread, when acted on.
                    handler.post {
                        if (sessionId != null && utteranceId != null &&
                            utteranceId == lastScheduledUtteranceId) {
                            stopSession()
                        }
                    }
                }

                @Deprecated(
                    "Deprecated in Android"
                )
                override fun onError(
                    utteranceId: String?
                ) = Unit

                override fun onError(
                    utteranceId: String?,
                    errorCode: Int
                ) = Unit
            }
        )

        ttsReady = true

        if (
            sessionId != null
        ) {
            scheduleCues()
        }
    }

    private fun scheduleCues() {
        val scheduledSession = sessionId ?: return
        handler.removeCallbacksAndMessages(
            null
        )

        val now =
            ZonedDateTime.now()

        val today =
            now.toLocalDate()

        val pending =
            sessionEvents.mapNotNull { event ->
                val target =
                    today
                        .atTime(
                            event.hour,
                            event.minute
                        )
                        .atZone(
                            now.zone
                        )

                val delay =
                    Duration
                        .between(
                            now,
                            target
                        )
                        .toMillis()

                if (
                    delay <
                    -60_000L
                ) {
                    null
                } else {
                    event to
                        maxOf(
                            0L,
                            delay
                        )
                }
            }

        if (pending.isEmpty()) {
            stopSession()
            return
        }

        lastScheduledUtteranceId = utteranceId(scheduledSession, pending.last().first)

        pending.forEach {
            (event, delay) ->

            handler.postDelayed(
                {
                    announce(
                        event,
                        scheduledSession
                    )
                },
                delay
            )
        }

        val finalDelay =
            pending.maxOf {
                it.second
            }

        handler.postDelayed(
            {
                if (sessionId == scheduledSession) stopSession()
            },
            finalDelay +
                90_000L
        )
    }

    private fun announce(
        event: RoutineEvent,
        scheduledSession: String
    ) {
        if (sessionId != scheduledSession || destroyed) return
        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        manager.notify(
            NOTIFICATION_ID,
            createNotification(
                event.title
            )
        )

        tts?.speak(
            event.spokenText,
            TextToSpeech.QUEUE_FLUSH,
            null,
            utteranceId(scheduledSession, event)
        )
    }

    private fun utteranceId(session: String, event: RoutineEvent) =
        "training:$session:${event.id}"

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
        text: String
    ): Notification {
        val stopIntent =
            Intent(
                this,
                TrainingSessionService::class.java
            ).apply {
                action =
                    ACTION_STOP
            }

        val stopPendingIntent =
            PendingIntent.getService(
                this,
                2002,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        return Notification.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(
                android.R.drawable
                    .ic_media_play
            )
            .setContentTitle(
                "RoutineAlarm Training"
            )
            .setContentText(
                text
            )
            .setCategory(
                Notification.CATEGORY_SERVICE
            )
            .setOngoing(true)
            .addAction(
                android.R.drawable
                    .ic_menu_close_clear_cancel,
                "Training beenden",
                stopPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "RoutineAlarm Training",
                    NotificationManager
                        .IMPORTANCE_LOW
                ).apply {
                    description =
                        "Laufende minutengenaue Trainingsansagen"
                }

            getSystemService(
                NotificationManager::class.java
            ).createNotificationChannel(
                channel
            )
        }
    }

    private fun stopSession() {
        sessionId = null
        sessionEvents = emptyList()
        lastScheduledUtteranceId = null
        handler.removeCallbacksAndMessages(
            null
        )

        tts?.stop()

        stopForeground(
            STOP_FOREGROUND_REMOVE
        )

        stopSelf()
    }

    override fun onDestroy() {
        destroyed = true
        sessionId = null
        sessionEvents = emptyList()
        lastScheduledUtteranceId = null
        handler.removeCallbacksAndMessages(
            null
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
