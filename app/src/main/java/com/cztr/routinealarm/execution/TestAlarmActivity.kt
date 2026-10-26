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
    private var historyOffset = 0
    private val time = DateTimeFormatter.ofPattern("dd.MM. HH:mm:ss").withZone(ZoneId.systemDefault())
    private val reader = LatestUiLoader()

    private sealed class ReadResult {
        data class Active(val occurrence: RoutineOccurrence?) : ReadResult()
        data class History(val page: TestAlarmHistoryPage) : ReadResult()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readIntent(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }
    override fun onResume() {
        super.onResume()
        TestAlarmRuntime.requestRecovery(this)
        render()
    }

    override fun onStop() {
        reader.invalidate()
        super.onStop()
    }

    override fun onDestroy() {
        reader.close()
        super.onDestroy()
    }

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

    private fun openHistory() {
        historyOffset = 0
        val historyIntent = Intent(this, javaClass)
        setIntent(historyIntent)
        readIntent(historyIntent)
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
            setBackgroundColor(Color.rgb(247, 248, 250))
        }
        setContentView(ScrollView(this).apply { addView(root) })
        val currentToken = token
        val requestedOffset = historyOffset
        if (currentToken == null && tokenWasSupplied) {
            reader.invalidate()
            root.text("Ungültiger Testwecker", 26f)
            root.text("Die Alarmidentität fehlt oder wurde verändert.")
            root.button("Schließen") { finish() }
            return
        }
        root.text(if (currentToken == null) "Testverlauf wird geladen …" else "Testwecker wird geprüft …", 26f)
        root.button("Schließen") { finish() }
        val app = applicationContext
        reader.load({
            TestAlarmRuntime.repository(app).use { repo ->
                if (currentToken != null) ReadResult.Active(TestAlarmRuntime.coordinator(repo).active(currentToken))
                else ReadResult.History(repo.historyPage(requestedOffset))
            }
        }) { result ->
            if (!isFinishing && !isDestroyed) {
                root.removeAllViews()
                result.fold(onSuccess = { read ->
                    when (read) {
                        is ReadResult.Active -> showActive(root, currentToken!!, read.occurrence)
                        is ReadResult.History -> showHistory(root, read.page, requestedOffset)
                    }
                }, onFailure = {
                    root.text("Der Testverlauf konnte nicht gelesen werden. Die Daten bleiben erhalten.")
                    root.button("Erneut versuchen") { render() }
                })
                root.button("Schließen") { finish() }
            }
        }
    }

    private fun showActive(root: LinearLayout, expected: AlarmDelivery, active: RoutineOccurrence?) {
        root.text(active?.title ?: "Dieser Testwecker ist nicht mehr aktiv", 26f)
        if (active != null) {
            root.text(active.spokenText)
            root.text("Geplant: ${time.format(active.plannedAt)}\n" +
                "Ausgelöst: ${time.format(active.lastUpdatedAt)}")
            root.button("Erledigt") { act(expected, ActionType.DONE) }
            for (minutes in 1..5) {
                val label = if (minutes == 1) "1 Minute später" else "$minutes Minuten später"
                root.button(label) { act(expected, ActionType.SNOOZE, minutes) }
            }
            root.button("Überspringen") { act(expected, ActionType.SKIP) }
        } else root.text("Die gespeicherte Entscheidung bleibt erhalten.")
        root.button("Testverlauf öffnen") { openHistory() }
    }

    private fun showHistory(root: LinearLayout, page: TestAlarmHistoryPage, offset: Int) {
        root.text("Testverlauf", 26f)
        root.text("Testwecker ab Eintrag ${offset + 1}, höchstens 20 je Seite. Sie zählen nicht als persönliche Routinen.")
        if (page.pausedCount > 0) root.text(
            "${page.pausedCount} übernommene Ereignisse sind pausiert. Hier werden Testwecker angezeigt. Gesicherte Planungsrückmeldungen gehören zum früheren Gerät bzw. Datenstand und bestätigen keine aktuelle Planung.")
        root.text("Zukünftige Testwecker werden bei App-Rückkehr und nach einem Neustart automatisch wieder eingeplant, sobald genaue Alarme erlaubt sind. Offene und überfällige Tests kannst du hier selbst erneut planen oder öffnen. Vergangene Weckzeiten lösen nur beim manuellen erneuten Planen sofort aus.")
        if (!com.cztr.routinealarm.AlarmScheduler.canScheduleExact(this)) {
            root.text("Genaue Alarme sind nicht erlaubt. Bitte die Berechtigung in den Systemeinstellungen aktivieren; gespeicherte Tests bleiben erhalten.")
        }
        if (page.items.isEmpty()) root.text("Keine weiteren Testwecker vorhanden.")
        page.items.forEach { row ->
            val item = row.occurrence
            root.text("${time.format(item.plannedAt)} · ${TestAlarmRuntime.label(item.status)}", 19f)
            root.text("Snooze: ${item.snoozeCount} · gesamt ${item.totalSnoozeMinutes} Min.\n" +
                "Aktuelle Weckzeit: ${time.format(item.currentTriggerAt)}")
            root.text(TestAlarmRuntime.schedulingLabel(row.latestSchedulingAttempt, item))
            if (!row.deliveryAllowed) {
                root.text("Aus Sicherung übernommen · pausiert")
                root.button("Wiederhergestellten Test aktivieren") { confirmActivation(item.id) }
            } else when (item.status) {
                OccurrenceStatus.TRIGGERED -> root.button("Offenen Testwecker öffnen") {
                    runCatching { TestAlarmRuntime.refresh(this) }.onFailure { showError(it) }
                    startActivity(TestAlarmRuntime.putToken(Intent(this, javaClass), AlarmDelivery.from(item, page.deliveryEpoch)))
                }
                OccurrenceStatus.SCHEDULED, OccurrenceStatus.SNOOZED -> root.button("Erneut planen") {
                    runCatching { TestAlarmRuntime.schedule(this, item) }
                        .onSuccess { toast("Testwecker erneut geplant. Vergangene Weckzeiten lösen sofort aus.") }
                        .onFailure { showError(it) }
                }
                else -> Unit
            }
        }
        if (offset > 0) root.button("Neuere Tests") {
            historyOffset = (offset - 20).coerceAtLeast(0)
            render()
        }
        if (page.items.size == 20) root.button("Ältere Tests") {
            historyOffset = offset + 20
            render()
        }
    }

    private fun confirmActivation(id: String) {
        android.app.AlertDialog.Builder(this).setTitle("Testwecker aktivieren")
            .setMessage("Dieser übernommene Test wird wieder aktiv. Vergangene Weckzeiten und bereits ausgelöste Tests können sofort klingeln. Die übrigen übernommenen Tests bleiben pausiert.")
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Aktivieren") { _, _ ->
                try {
                    val restored = TestAlarmRuntime.repository(this).use { repo ->
                        check(repo.activateRestored(id)) { "Dieser Test wurde bereits aktiviert." }
                        val item = requireNotNull(repo.get(id))
                        item to AlarmDelivery.from(item, repo.deliveryEpoch())
                    }
                    if (restored.first.status == OccurrenceStatus.TRIGGERED) {
                        TestAlarmRuntime.refresh(this)
                        startActivity(TestAlarmRuntime.putToken(Intent(this, javaClass), restored.second))
                    } else TestAlarmRuntime.schedule(this, restored.first)
                    toast("Aktivierung gespeichert. Tatsächliche Alarmausgabe bitte prüfen.")
                } catch (_: Exception) {
                    toast("Aktivierung oder Planung nicht bestätigt. Aktuellen Verlauf prüfen; eine gespeicherte Aktivierung bleibt erhalten.")
                }
                render()
            }.show()
    }

    private fun act(expected: AlarmDelivery, type: ActionType, snoozeMinutes: Int? = null) {
        try {
            val result = TestAlarmRuntime.repository(this).use {
                TestAlarmRuntime.coordinator(it).act(expected, type, snoozeMinutes)
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
            TestAlarmRuntime.reconcileOccurrence(this, result.occurrence.id)
            runCatching { TestAlarmRuntime.refresh(this) }.onFailure { showError(it) }
            if (scheduleError != null) {
                toast("Verschiebung gespeichert, Planung fehlgeschlagen. Im Testverlauf erneut planen.")
                openHistory()
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
