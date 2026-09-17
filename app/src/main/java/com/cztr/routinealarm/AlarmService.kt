package com.cztr.routinealarm

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.*
import android.speech.tts.TextToSpeech
import java.util.Locale

class AlarmService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val CHANNEL_ID = "routine_alarm_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.cztr.routinealarm.STOP"
    }

    private var tts: TextToSpeech? = null
    private var currentTitle: String = "RoutineAlarm"

    private val handler = Handler(Looper.getMainLooper())

    private var wakeLock: PowerManager.WakeLock? = null

    private val repeatRunnable = object : Runnable {
        override fun run() {
            speak()
            handler.postDelayed(this, 8000)
        }
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        val powerManager =
            getSystemService(Context.POWER_SERVICE) as PowerManager

        wakeLock =
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "RoutineAlarm:AlarmWakeLock"
            )

        wakeLock?.acquire(10 * 60 * 1000L)

        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent?.action == ACTION_STOP) {
            stopAlarm()
            return START_NOT_STICKY
        }

        currentTitle =
            intent?.getStringExtra(AlarmScheduler.EXTRA_TITLE)
                ?: "RoutineAlarm"

        startForeground(
            NOTIFICATION_ID,
            createNotification(currentTitle)
        )

        handler.removeCallbacks(repeatRunnable)

        if (tts != null) {
            handler.postDelayed(repeatRunnable, 1000)
        }

        return START_NOT_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {

            tts?.language = Locale.GERMAN

            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )

            handler.removeCallbacks(repeatRunnable)
            handler.post(repeatRunnable)
        }
    }

    private fun speak() {
        tts?.speak(
            currentTitle,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "routine_alarm_speech"
        )
    }

    private fun createNotification(title: String): Notification {

        val fullScreenIntent =
            Intent(this, AlarmActivity::class.java).apply {
                putExtra(AlarmScheduler.EXTRA_TITLE, title)
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

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("RoutineAlarm")
            .setContentText(title)
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .build()
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "RoutineAlarm Wecker",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description =
                        "Laute RoutineAlarm Erinnerungen"
                    lockscreenVisibility =
                        Notification.VISIBILITY_PUBLIC
                    enableVibration(true)
                }

            val manager =
                getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(channel)
        }
    }

    private fun stopAlarm() {
        handler.removeCallbacks(repeatRunnable)
        tts?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(repeatRunnable)

        tts?.stop()
        tts?.shutdown()

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
