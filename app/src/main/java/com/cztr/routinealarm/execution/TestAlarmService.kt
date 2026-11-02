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
import java.util.concurrent.Executors

/** A small queue of persisted test occurrences; UI never sends a global STOP. */
class TestAlarmService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val CLOSE_OUTPUT = "com.cztr.routinealarm.TEST_OUTPUT_CLOSE"
        const val STOP_KEY = "test-alarm.stop-permit.v1"
        const val REFRESH = "com.cztr.routinealarm.TEST_REFRESH"
        const val CHANNEL = "test_execution_alarm"
        const val NOTIFICATION = 1003
    }
    private val outputSession = java.util.UUID.randomUUID().toString()
    private val handler = Handler(Looper.getMainLooper())
    private val reader = Executors.newSingleThreadExecutor { task -> Thread(task, "test-output-reader") }
    private var refreshGeneration = 0L
    private var started = false
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
        TestOutputAdmission.registerSession(outputSession)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Testwecker mit Verlauf", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Testwecker und ihre gespeicherten Aktionen"
                enableVibration(true)
            })
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CLOSE_OUTPUT) {
            val accepted = TestOutputAdmission.consumeOutputClose(intent.getStringExtra(STOP_KEY),
                outputSession, current, TestAlarmRuntime.token(intent)) {
                handler.removeCallbacks(repeat)
                refreshGeneration++
                current = null
                TestOutputAdmission.retireOutput(outputSession)
                runCatching { tts?.stop() }.onFailure { Log.e("TestAlarm", "Tonende nicht bestätigt", it) }
                // Other already-triggered occurrences retain their own authority and queue.
                refreshOutput(speakNow = true)
            }
            if (!accepted && !started && current == null) stopSelf()
            return START_NOT_STICKY
        }
        try {
            // An unvalidated arrival must not erase actions of an admitted output.
            if (current == null) foreground(Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Testwecker wird geprüft").setOnlyAlertOnce(true).build())
        } catch (error: RuntimeException) {
            // Exact-alarm/foreground eligibility may have been revoked since delivery.
            // Leave the persisted occurrence available for explicit recovery.
            Log.e("TestAlarm", "Android erlaubt den Testalarmdienst nicht", error)
            shutdown()
            return START_NOT_STICKY
        }
        started = true
        refreshOutput(speakNow = true, arrival = if (intent?.action == REFRESH) null else intent,
            validateArrival = intent?.action != REFRESH)
        return START_NOT_STICKY
    }

    private fun refreshOutput(speakNow: Boolean, arrival: Intent? = null, validateArrival: Boolean = false) {
        if (destroyed || !started) return
        handler.removeCallbacks(repeat)
        val request = ++refreshGeneration
        val previous = current
        reader.execute {
            var ticket = TestOutputAdmission.ticket()
            if (ticket == null) {
                handler.postDelayed({ if (!destroyed && request == refreshGeneration)
                    refreshOutput(speakNow, arrival, validateArrival) }, 50)
                return@execute
            }
            try {
                var permittedSpeech = speakNow
                val selected = TestAlarmRuntime.repository(this).use { repo ->
                    if (validateArrival) {
                        val token = TestAlarmRuntime.token(arrival)
                        permittedSpeech = speakNow && token != null &&
                            TestAlarmRuntime.coordinator(repo).active(token) != null
                    }
                    val active = previous?.let { TestAlarmRuntime.coordinator(repo).active(it) }
                    val item = active?.takeUnless { it.outputSilencedGeneration == it.generation } ?: repo.openTests().firstOrNull {
                        it.status == OccurrenceStatus.TRIGGERED && it.outputSilencedGeneration != it.generation && repo.isDeliveryAllowed(it.id)
                    }
                    item?.let {
                        val delivery = AlarmDelivery.from(it, repo.deliveryEpoch())
                        if (!repo.bindTestOutput(delivery, outputSession) {
                                TestOutputAdmission.currentSession(outputSession) }) null
                        else {
                            ticket = TestOutputAdmission.ticket()
                            val fresh = TestAlarmRuntime.coordinator(repo).active(delivery)
                            fresh?.takeIf { row -> row.outputSilencedGeneration != row.generation &&
                                repo.outputInstance(row.id) == outputSession }?.let { row -> row to delivery }
                        }
                    }
                }
                val readTicket = ticket
                handler.post {
                    if (!destroyed && request == refreshGeneration && TestOutputAdmission.currentSession(outputSession)) {
                        val admitted = readTicket != null && TestOutputAdmission.admit(readTicket) { renderOutput(selected, permittedSpeech) }
                        if (!admitted) refreshOutput(speakNow, arrival, validateArrival)
                    }
                }
            } catch (error: Exception) {
                handler.post {
                    if (!destroyed && request == refreshGeneration) {
                        Log.e("TestAlarm", "Testverlauf ist nicht lesbar; Ausgabe beendet", error)
                        shutdown()
                    }
                }
            }
        }
    }

    private fun renderOutput(selected: Pair<RoutineOccurrence, AlarmDelivery>?, speakNow: Boolean) {
        try {
            if (selected == null) { shutdown(); return }
            val (item, token) = selected
            if (!TestOutputAdmission.publishOutput(outputSession, token)) return
            val changed = token != current
            if (changed) tts?.stop()
            current = token
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "RoutineAlarm:TestExecution").apply { acquire(30 * 60 * 1000L) }
            }
            val screen = TestAlarmRuntime.putToken(Intent(this, TestAlarmActivity::class.java), token)
                .setAction(TestAlarmNotification.SCREEN + outputSession)
                .putExtra(TestAlarmNotification.OUTPUT_INSTANCE, outputSession)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pending = PendingIntent.getActivity(this, 0, screen,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(item.title)
                .setContentText("Erledigen, überspringen oder verschieben")
                .setContentIntent(pending).setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true).setOnlyAlertOnce(!changed)
                .apply { TestAlarmNotification.actions(this@TestAlarmService, token, outputSession).forEach { addAction(it) } }
                .apply { if (changed) setFullScreenIntent(pending, true) }.build()
            foreground(notification)
            if (ready && speakNow) {
                // The admission fence rejects a snapshot crossing any repository mutation.
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
        if (started) refreshOutput(speakNow = true)
    }

    private fun foreground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        else startForeground(NOTIFICATION, notification)
    }

    private fun shutdown() {
        started = false
        refreshGeneration++
        handler.removeCallbacks(repeat)
        val hadOutput = current != null
        current = null
        TestOutputAdmission.retireOutput(outputSession)
        if (hadOutput) runCatching { tts?.stop() }.onFailure { Log.e("TestAlarm", "Tonende nicht bestätigt", it) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        val hadOutput = current != null
        destroyed = true
        TestOutputAdmission.releaseSession(outputSession)
        started = false
        refreshGeneration++
        reader.shutdownNow()
        ready = false
        current = null
        handler.removeCallbacksAndMessages(null)
        if (hadOutput) runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
}
