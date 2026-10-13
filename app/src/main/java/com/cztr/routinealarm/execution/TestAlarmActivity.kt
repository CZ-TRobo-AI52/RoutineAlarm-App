package com.cztr.routinealarm.execution

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Build
import android.view.WindowManager
import android.widget.*
import com.cztr.routinealarm.occurrence.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A normal history screen when no delivery is present; an identified alarm screen otherwise. */
class TestAlarmActivity : Activity() {
    private var token: AlarmDelivery? = null
    private var tokenWasSupplied = false
    private val time = DateTimeFormatter.ofPattern("dd.MM. HH:mm:ss").withZone(ZoneId.systemDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readIntent(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }
    override fun onResume() { super.onResume(); render() }

    private fun readIntent(value: Intent) {
        token = TestAlarmRuntime.token(value)
        tokenWasSupplied = value.data != null
        val isAlarm = token != null
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(isAlarm)
            setTurnScreenOn(isAlarm)
        } else {
            @Suppress("DEPRECATION")
            val lockFlags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (isAlarm) window.addFlags(lockFlags) else window.clearFlags(lockFlags)
        }
        if (isAlarm) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render()
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
            setBackgroundColor(Color.rgb(247, 248, 250))
        }
        setContentView(ScrollView(this).apply { addView(root) })
        try {
            TestAlarmRuntime.repository(this).use { repo ->
                val currentToken = token
                if (currentToken != null) {
                    val active = TestAlarmRuntime.coordinator(repo).active(currentToken)
                    root.text(active?.title ?: "Dieser Testwecker ist nicht mehr aktiv", 26f)
                    if (active != null) {
                        root.text(active.spokenText)
                        root.text("Geplant: ${time.format(active.plannedAt)}\n" +
                            "Ausgelöst: ${time.format(active.lastUpdatedAt)}")
                        root.button("Erledigt") { act(currentToken, ActionType.DONE) }
                        root.button("5 Minuten später") { act(currentToken, ActionType.SNOOZE) }
                        root.button("Überspringen") { act(currentToken, ActionType.SKIP) }
                    } else root.text("Die gespeicherte Entscheidung bleibt erhalten.")
                    root.button("Testverlauf öffnen") {
                        token = null; tokenWasSupplied = false; setIntent(Intent(this, javaClass)); render()
                    }
                } else if (tokenWasSupplied) {
                    root.text("Ungültiger Testwecker", 26f)
                    root.text("Die Alarmidentität fehlt oder wurde verändert.")
                } else {
                    root.text("Testverlauf", 26f)
                    root.text("Die letzten 20 Testwecker. Sie zählen nicht als persönliche Routinen.")
                    root.text("Nach Neustart oder einer fehlgeschlagenen Planung kannst du offene Tests hier erneut planen oder öffnen. Vergangene Weckzeiten lösen beim erneuten Planen sofort aus.")
                    val recent = repo.recentTests()
                    if (recent.isEmpty()) root.text("Noch keine Testwecker vorhanden.")
                    recent.forEach { item ->
                        root.text("${time.format(item.plannedAt)} · ${TestAlarmRuntime.label(item.status)}", 19f)
                        root.text("Snooze: ${item.snoozeCount} · gesamt ${item.totalSnoozeMinutes} Min.\n" +
                            "Aktuelle Weckzeit: ${time.format(item.currentTriggerAt)}")
                        when (item.status) {
                            OccurrenceStatus.TRIGGERED -> root.button("Offenen Testwecker öffnen") {
                                runCatching { TestAlarmRuntime.refresh(this) }.onFailure { showError(it) }
                                startActivity(TestAlarmRuntime.putToken(Intent(this, javaClass), AlarmDelivery.from(item)))
                            }
                            OccurrenceStatus.SCHEDULED, OccurrenceStatus.SNOOZED -> root.button("Erneut planen") {
                                runCatching { TestAlarmRuntime.schedule(this, item) }
                                    .onSuccess { toast("Testwecker erneut geplant. Vergangene Weckzeiten lösen sofort aus.") }
                                    .onFailure { showError(it) }
                            }
                            else -> Unit
                        }
                    }
                }
                root.button("Schließen") { finish() }
            }
        } catch (error: Exception) {
            root.text("Der Testverlauf konnte nicht gelesen werden. Die Daten bleiben erhalten.")
            root.button("Erneut versuchen") { render() }
        }
    }

    private fun act(expected: AlarmDelivery, type: ActionType) {
        try {
            val result = TestAlarmRuntime.repository(this).use {
                TestAlarmRuntime.coordinator(it).act(expected, type, if (type == ActionType.SNOOZE) 5 else null)
            }
            if (result?.outcome != Outcome.APPLIED) {
                toast("Testwecker wurde bereits geändert. Bitte aktuellen Verlauf öffnen.")
                render()
                return
            }
            // Commit first. Android scheduling can fail separately; retain SNOOZED
            // and show an explicit retry path instead of claiming the alarm is armed.
            val scheduleError = if (type == ActionType.SNOOZE)
                runCatching { TestAlarmRuntime.schedule(this, result.occurrence) }.exceptionOrNull() else null
            runCatching { TestAlarmRuntime.refresh(this) }.onFailure { showError(it) }
            if (scheduleError != null) {
                toast("Verschiebung gespeichert, Planung fehlgeschlagen. Im Testverlauf erneut planen.")
                token = null; tokenWasSupplied = false; setIntent(Intent(this, javaClass)); render()
            } else {
                toast("${TestAlarmRuntime.label(result.occurrence.status)} · gespeichert")
                finish()
            }
        } catch (error: Exception) { showError(error) }
    }

    private fun LinearLayout.text(value: String, size: Float = 16f) {
        addView(TextView(this@TestAlarmActivity).apply {
            text = value; textSize = size; setTextColor(Color.rgb(25, 30, 38))
            setPadding(0, dp(10), 0, dp(10))
        })
    }
    private fun LinearLayout.button(label: String, click: () -> Unit) {
        addView(Button(this@TestAlarmActivity).apply {
            text = label; isAllCaps = false; minHeight = dp(54)
            setOnClickListener { click() }
        }, LinearLayout.LayoutParams(-1, -2))
    }
    private fun showError(error: Throwable) {
        android.util.Log.e("TestAlarm", "Aktion fehlgeschlagen", error)
        toast("Aktion nicht abgeschlossen. Berechtigungen prüfen und erneut versuchen.")
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
