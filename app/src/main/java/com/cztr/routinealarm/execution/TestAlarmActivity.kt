package com.cztr.routinealarm.execution

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.cztr.routinealarm.AlarmScheduler
import com.cztr.routinealarm.ui.shell.AppNavigationState
import com.cztr.routinealarm.ui.shell.ThemePreferenceStore
import com.cztr.routinealarm.ui.testalarm.*
import com.cztr.routinealarm.occurrence.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A normal history screen when no delivery is present; an identified alarm screen otherwise. */
class TestAlarmActivity : Activity() {
    private var token: AlarmDelivery? = null
    private var tokenWasSupplied = false
    private var historyOffset = 0
    private var currentPage: TestAlarmHistoryPage? = null
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
        currentPage = null
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
        setIntent(Intent(this, javaClass))
        readIntent(intent)
    }

    private fun render() {
        val currentToken = token
        val requestedOffset = historyOffset
        if (currentToken == null && tokenWasSupplied) {
            reader.invalidate()
            show(TestAlarmUiMapper.invalid())
            return
        }
        show(TestAlarmUiMapper.loading(currentToken != null))
        val app = applicationContext
        reader.load({
            TestAlarmRuntime.repository(app).use { repo ->
                if (currentToken != null) {
                    ReadResult.Active(TestAlarmRuntime.coordinator(repo).active(currentToken))
                } else {
                    ReadResult.History(repo.historyPage(requestedOffset))
                }
            }
        }) { result ->
            if (!isFinishing && !isDestroyed) {
                result.fold(onSuccess = { read ->
                    when (read) {
                        is ReadResult.Active -> {
                            currentPage = null
                            val active = read.occurrence
                            show(TestAlarmUiMapper.active(
                                title = active?.title ?: "Dieser Testwecker ist nicht mehr aktiv",
                                spokenText = active?.spokenText,
                                plannedText = active?.let { "Geplant: ${time.format(it.plannedAt)}" },
                                triggeredText = active?.let { "Ausgelöst: ${time.format(it.lastUpdatedAt)}" },
                                active = active != null,
                            ))
                        }
                        is ReadResult.History -> {
                            currentPage = read.page
                            show(historyState(read.page, requestedOffset))
                        }
                    }
                }, onFailure = {
                    currentPage = null
                    show(TestAlarmUiMapper.error())
                })
            }
        }
    }

    private fun historyState(page: TestAlarmHistoryPage, offset: Int): TestAlarmUiState.History {
        val rows = page.items.map { row ->
            val item = row.occurrence
            val action = if (!row.deliveryAllowed) {
                TestHistoryRowAction.ACTIVATE
            } else when (item.status) {
                OccurrenceStatus.TRIGGERED -> TestHistoryRowAction.OPEN
                OccurrenceStatus.SCHEDULED, OccurrenceStatus.SNOOZED -> TestHistoryRowAction.REPLAN
                else -> null
            }
            TestHistoryUiRow(
                id = item.id,
                headline = "${time.format(item.plannedAt)} · ${TestAlarmRuntime.label(item.status)}",
                detail = "Snooze: ${item.snoozeCount} · gesamt ${item.totalSnoozeMinutes} Min.\n" +
                    "Aktuelle Weckzeit: ${time.format(item.currentTriggerAt)}",
                scheduling = TestAlarmRuntime.schedulingLabel(row.latestSchedulingAttempt, item),
                pausedNotice = if (row.deliveryAllowed) null else "Aus Sicherung übernommen · pausiert",
                action = action,
            )
        }
        return TestAlarmUiMapper.history(
            offset = offset,
            pausedCount = page.pausedCount,
            exactAllowed = AlarmScheduler.canScheduleExact(this),
            rows = rows,
            hasNewer = offset > 0,
            hasOlder = page.items.size == 20,
        )
    }

    private fun show(state: TestAlarmUiState) {
        val colors = AppNavigationState(theme = ThemePreferenceStore(this).read())
            .colors(resources.configuration)
        window.statusBarColor = colors.background
        window.navigationBarColor = colors.background
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (colors == com.cztr.routinealarm.ui.design.R52Colors.Light)
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        setContentView(TestAlarmScreenView(this, state, colors, ::handleUiAction))
    }

    private fun handleUiAction(action: TestAlarmUiAction) {
        when (action) {
            TestAlarmUiAction.CLOSE -> finish()
            TestAlarmUiAction.RETRY -> render()
            TestAlarmUiAction.OPEN_HISTORY -> openHistory()
            TestAlarmUiAction.DONE -> token?.let { act(it, ActionType.DONE) }
            TestAlarmUiAction.SKIP -> token?.let { act(it, ActionType.SKIP) }
            is TestAlarmUiAction.Snooze -> token?.let { act(it, ActionType.SNOOZE, action.minutes) }
            TestAlarmUiAction.NEWER -> {
                historyOffset = (historyOffset - 20).coerceAtLeast(0)
                render()
            }
            TestAlarmUiAction.OLDER -> {
                historyOffset += 20
                render()
            }
            is TestAlarmUiAction.HistoryRow -> handleHistoryRow(action)
        }
    }

    private fun handleHistoryRow(action: TestAlarmUiAction.HistoryRow) {
        val page = currentPage ?: return
        val item = page.items.firstOrNull { it.occurrence.id == action.id }?.occurrence ?: return
        when (action.action) {
            TestHistoryRowAction.ACTIVATE -> confirmActivation(item.id)
            TestHistoryRowAction.OPEN -> {
                runCatching { TestAlarmRuntime.refresh(this) }.onFailure { showError(it) }
                startActivity(TestAlarmRuntime.putToken(
                    Intent(this, javaClass),
                    AlarmDelivery.from(item, page.deliveryEpoch),
                ))
            }
            TestHistoryRowAction.REPLAN -> runCatching { TestAlarmRuntime.schedule(this, item) }
                .onSuccess { toast("Testwecker erneut geplant. Vergangene Weckzeiten lösen sofort aus.") }
                .onFailure { showError(it) }
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
        } catch (error: Exception) {
            showError(error)
        }
    }

    private fun showError(error: Throwable) {
        android.util.Log.e("TestAlarm", "Aktion fehlgeschlagen", error)
        toast("Aktion nicht abgeschlossen. Berechtigungen prüfen und erneut versuchen.")
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
