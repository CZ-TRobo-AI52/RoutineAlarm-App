package com.cztr.routinealarm.execution

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.*
import android.speech.tts.TextToSpeech
import android.util.Log
import com.cztr.routinealarm.occurrence.*
import java.util.Locale

/** A small queue of persisted test occurrences; UI never sends a global STOP. */
class TestAlarmService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val REFRESH = "com.cztr.routinealarm.TEST_REFRESH"
        const val CHANNEL = "test_execution_alarm"
        const val NOTIFICATION = 1003
    }
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var destroyed = false
    private var ready = false
    private var current: AlarmDelivery? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val repeat = object : Runnable {
        override fun run() {
            // Revalidate even after the receiver check: a user may have completed
            // this occurrence before service start, TTS initialization or this tick.
            refreshOutput(speakNow = true)
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Testwecker mit Verlauf", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Testwecker und ihre gespeicherten Aktionen"
                enableVibration(true)
            })
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            foreground(Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Testwecker wird geprüft").setOnlyAlertOnce(true).build())
        } catch (error: RuntimeException) {
            // Exact-alarm/foreground eligibility may have been revoked since delivery.
            // Leave the persisted occurrence available for explicit recovery.
            Log.e("TestAlarm", "Android erlaubt den Testalarmdienst nicht", error)
            shutdown()
            return START_NOT_STICKY
        }
        if (intent?.action != REFRESH) {
            val token = TestAlarmRuntime.token(intent)
            val valid = token != null && runCatching {
                TestAlarmRuntime.repository(this).use { TestAlarmRuntime.coordinator(it).active(token) != null }
            }.getOrDefault(false)
            if (!valid) {
                // A stale arrival may not replace an already playing valid item.
                refreshOutput(speakNow = false)
                return START_NOT_STICKY
            }
        }
        refreshOutput(speakNow = true)
        return START_NOT_STICKY
    }

    private fun refreshOutput(speakNow: Boolean) {
        if (destroyed) return
        handler.removeCallbacks(repeat)
        try {
            val selected = TestAlarmRuntime.repository(this).use { repo ->
                val active = current?.let { TestAlarmRuntime.coordinator(repo).active(it) }
                val item = active ?: repo.openTests().firstOrNull {
                    it.status == OccurrenceStatus.TRIGGERED && repo.isDeliveryAllowed(it.id)
                }
                item?.let { it to AlarmDelivery.from(it, repo.deliveryEpoch()) }
            }
            if (selected == null) { shutdown(); return }
            val (item, token) = selected
            val changed = token != current
            if (changed) tts?.stop()
            current = token
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "RoutineAlarm:TestExecution").apply { acquire(30 * 60 * 1000L) }
            }
            val screen = TestAlarmRuntime.putToken(Intent(this, TestAlarmActivity::class.java), token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pending = PendingIntent.getActivity(this, 0, screen,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(item.title)
                .setContentText("Erledigen, überspringen oder verschieben")
                .setContentIntent(pending).setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true).setOnlyAlertOnce(!changed)
                .apply { if (changed) setFullScreenIntent(pending, true) }.build()
            foreground(notification)
            if (ready && speakNow) {
                // Main-thread Activity actions and speech are serialized in this process.
                tts?.speak(item.spokenText, TextToSpeech.QUEUE_FLUSH, null,
                    "test:${item.id}:${item.generation}")
            }
            handler.postDelayed(repeat, 8000)
        } catch (error: Exception) {
            Log.e("TestAlarm", "Testverlauf ist nicht lesbar; Ausgabe beendet", error)
            shutdown()
        }
    }

    override fun onInit(status: Int) {
        // Serialize TTS callbacks with service teardown and scheduled output.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { initializeSpeech(status) }
        } else {
            initializeSpeech(status)
        }
    }

    private fun initializeSpeech(status: Int) {
        if (destroyed) return
        if (status == TextToSpeech.SUCCESS) {
            val language = tts?.setLanguage(Locale.GERMAN)
            ready = language != null && language >= TextToSpeech.LANG_AVAILABLE
            tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        }
        if (current != null) refreshOutput(speakNow = true)
    }

    private fun foreground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        else startForeground(NOTIFICATION, notification)
    }

    private fun shutdown() {
        handler.removeCallbacks(repeat)
        tts?.stop()
        current = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        destroyed = true
        ready = false
        current = null
        handler.removeCallbacksAndMessages(null)
        tts?.stop()
        tts?.shutdown()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
}
