package com.cztr.routinealarm.execution

import android.app.Activity
import android.content.Intent
import android.content.Context
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
import com.cztr.routinealarm.ui.notification.*
import java.time.ZoneId
import java.time.Instant
import java.time.format.DateTimeFormatter

/** A normal history screen when no delivery is present; an identified alarm screen otherwise. */
class TestAlarmActivity : Activity() {
    private companion object {
        const val PENDING_ACTION = "test-alarm.pending-action.v1"
        const val NOTIFICATION_STAGE = "test-alarm.notification-stage.v1"
    }
    private var token: AlarmDelivery? = null
    private var tokenWasSupplied = false
    private var historyOffset = 0
    private var currentPage: TestAlarmHistoryPage? = null
    private val time = DateTimeFormatter.ofPattern("dd.MM. HH:mm:ss").withZone(ZoneId.systemDefault())
    private val reader = LatestUiLoader()
    private var pendingAction: ExecutionAction? = null
    private var invalidPendingAction = false
    private var actionInFlight = false
    private var historyCommandInFlight = false
    private var screenRevision = 0L
    private var viewRevision = 0L
    private var stopped = false
    private var notificationStage: MenuStage? = null
    private var invalidNotification = false
    private var notificationAwaitingResume = false

    private data class CommittedAction(val result: ActionResult, val followUpUnconfirmed: Boolean)

    private sealed class ReadResult {
        data class Active(val occurrence: RoutineOccurrence?) : ReadResult()
        data class History(val page: TestAlarmHistoryPage) : ReadResult()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString(PENDING_ACTION)?.let { encoded ->
            pendingAction = runCatching {
                require(encoded.length <= 16_384)
                OccurrenceCodec.decodeAction(encoded).also { StableTestAlarmActions.epoch(it) }
            }.getOrElse { invalidPendingAction = true; null }
        }
        var invalidStage = false
        val restoredStage = savedInstanceState?.getString(NOTIFICATION_STAGE)?.let {
            runCatching { MenuStage.valueOf(it) }.getOrElse { invalidStage = true; null }
        }
        // A recreated Activity is not a fresh notification tap. If its saved state no
        // longer contains either the frozen request or a navigational menu stage, the
        // retained notification Intent must not manufacture a new request with a new
        // clock observation.
        if (savedInstanceState != null && TestAlarmNotification.hasTransport(intent) &&
            pendingAction == null && restoredStage == null && !invalidPendingAction && !invalidStage) {
            invalidPendingAction = true
        }
        readIntent(intent, restoredStage, invalidStage)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingAction?.let { outState.putString(PENDING_ACTION, OccurrenceCodec.encode(it)) }
        notificationStage?.let { outState.putString(NOTIFICATION_STAGE, it.name) }
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep the frozen request on redelivery of the same token. A different delivery
        // may not receive the earlier command's callback.
        if (TestAlarmRuntime.token(intent) != token) {
            pendingAction = null
            invalidPendingAction = false
            actionInFlight = false
            notificationAwaitingResume = false
        }
        setIntent(intent)
        readIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        stopped = false
        TestAlarmRuntime.requestRecovery(this)
        if (notificationAwaitingResume) {
            notificationAwaitingResume = false
            submitPendingAction()
        } else render()
    }

    override fun onStop() {
        stopped = true
        screenRevision++
        historyCommandInFlight = false
        reader.invalidate()
        super.onStop()
    }

    override fun onDestroy() {
        reader.close()
        super.onDestroy()
    }

    private fun readIntent(value: Intent, restoredStage: MenuStage? = null, invalidStage: Boolean = false) {
        screenRevision++
        historyCommandInFlight = false
        token = TestAlarmRuntime.token(value)
        tokenWasSupplied = value.data != null
        currentPage = null
        notificationStage = null
        val pendingBeforeDelivery = pendingAction
        invalidNotification = false
        if (TestAlarmNotification.hasTransport(value)) {
            val route = TestAlarmNotification.decode(value)
            if (route == null || invalidStage) invalidNotification = true
            else if (pendingAction == null && !invalidPendingAction) {
                if (restoredStage != null) notificationStage = restoredStage
                else acceptNotificationChoice(route, incomingDelivery = true)
            }
        }
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
        if (pendingBeforeDelivery == null && pendingAction != null && !invalidNotification) {
            // Android delivers SINGLE_TOP intents before onResume when this screen was
            // stopped. Freeze that explicit tap now, then admit I/O only after resume.
            notificationAwaitingResume = stopped
            if (!stopped) submitPendingAction()
        }
    }

    private fun openHistory() {
        // Explicit screen navigation does not cancel an accepted command, but its late
        // acknowledgement must not finish or repopulate the newly opened history.
        pendingAction = null
        notificationAwaitingResume = false
        invalidPendingAction = false
        actionInFlight = false
        historyOffset = 0
        setIntent(Intent(this, javaClass))
        readIntent(intent)
    }

    private fun render() {
        val currentToken = token
        val requestedOffset = historyOffset
        if (historyCommandInFlight) {
            reader.invalidate()
            show(TestAlarmUiState.Loading("Speicherung wird geprüft …"))
            return
        }
        val pending = pendingAction
        if (invalidNotification || invalidPendingAction || pending != null && !matchesDelivery(pending, currentToken)) {
            reader.invalidate()
            show(TestAlarmUiMapper.invalid())
            return
        }
        if (pending != null) {
            reader.invalidate()
            if (actionInFlight) show(TestAlarmUiState.Loading("Speicherung wird geprüft …"))
            else showUnconfirmedAction()
            return
        }
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
                            val menu = notificationStage
                            if (active != null && menu != null) show(TestAlarmUiState.NotificationActions(active.title, menu))
                            else show(TestAlarmUiMapper.active(
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
        val renderedView = ++viewRevision
        setContentView(TestAlarmScreenView(this, state, colors) { action ->
            // Navigation has no execution authority. A detached view may not apply its
            // action to a different delivery or to a newer history snapshot.
            if (!stopped && !isFinishing && !isDestroyed && (renderedView == viewRevision ||
                action == TestAlarmUiAction.CLOSE || action == TestAlarmUiAction.OPEN_HISTORY)) handleUiAction(action)
        })
    }

    private fun handleUiAction(action: TestAlarmUiAction) {
        when (action) {
            TestAlarmUiAction.CLOSE -> finish()
            TestAlarmUiAction.RETRY -> if (pendingAction != null) submitPendingAction() else render()
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
            is TestAlarmUiAction.NotificationChoice -> {
                val expected = token ?: return
                val stage = notificationStage ?: return
                acceptNotificationChoice(TestAlarmNotification.route(expected, stage, action.choice))
                render()
                if (pendingAction != null) submitPendingAction()
            }
        }
    }

    private fun acceptNotificationChoice(route: NotificationActionRoute, incomingDelivery: Boolean = false) {
        val expected = token ?: return
        if ((stopped && !incomingDelivery) || actionInFlight || pendingAction != null || invalidPendingAction || invalidNotification) return
        val selection = NotificationActionRoutes.selection(route, TestAlarmNotification.allowed)
        val frozen = if (selection is MenuSelection.Request) {
            val type = when (selection.operation) {
                NotificationOperation.DONE -> ActionType.DONE
                NotificationOperation.SKIP -> ActionType.SKIP
                NotificationOperation.SNOOZE -> ActionType.SNOOZE
                NotificationOperation.CLOSE_OUTPUT -> return
            }
            val key = NotificationActionRoutes.requestKey(route)
            FrozenActionBinding(key, StableTestAlarmActions.freeze("test-user:$key", expected,
                type, Instant.now(), selection.snoozeMinutes))
        } else null
        when (val prepared = NotificationActionAdapter.prepare(NotificationActionRoutes.encode(route),
            TestAlarmRuntime.putToken(Intent(), expected), frozen, TestAlarmNotification.allowed)) {
            is PreparedNotification.Navigate -> notificationStage = prepared.stage
            is PreparedNotification.ActionCandidate -> pendingAction = prepared.originalAction
            else -> invalidNotification = true
        }
    }

    private fun handleHistoryRow(action: TestAlarmUiAction.HistoryRow) {
        if (stopped || historyCommandInFlight || pendingAction != null || isFinishing || isDestroyed) return
        val page = currentPage ?: return
        val item = page.items.firstOrNull { it.occurrence.id == action.id }?.occurrence ?: return
        val expected = AlarmDelivery.from(item, page.deliveryEpoch)
        when (action.action) {
            TestHistoryRowAction.ACTIVATE -> confirmActivation(expected)
            TestHistoryRowAction.OPEN -> {
                runCatching { TestAlarmRuntime.refresh(this) }.onFailure { showError(it) }
                startActivity(TestAlarmRuntime.putToken(
                    Intent(this, javaClass),
                    AlarmDelivery.from(item, page.deliveryEpoch),
                ))
            }
            TestHistoryRowAction.REPLAN -> {
                val app = applicationContext
                runHistoryCommand(work = {
                    TestAlarmRuntime.repository(app).use { repo ->
                        check(repo.deliveryEpoch() == expected.epoch &&
                            repo.get(item.id) == item && repo.isDeliveryAllowed(item.id)) {
                            "History delivery is no longer current"
                        }
                    }
                    TestAlarmRuntime.schedule(app, item)
                }, saved = {
                    toast("Testwecker erneut geplant. Vergangene Weckzeiten lösen sofort aus.")
                }, failed = {
                    toast("Planung nicht bestätigt. Aktuellen Testverlauf prüfen; gespeicherte Daten bleiben erhalten.")
                })
            }
        }
    }

    private fun confirmActivation(expected: AlarmDelivery) {
        android.app.AlertDialog.Builder(this).setTitle("Testwecker aktivieren")
            .setMessage("Dieser übernommene Test wird wieder aktiv. Vergangene Weckzeiten und bereits ausgelöste Tests können sofort klingeln. Die übrigen übernommenen Tests bleiben pausiert.")
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Aktivieren") { _, _ ->
                val app = applicationContext
                runHistoryCommand(work = {
                    val restored = TestAlarmRuntime.repository(app).use { repo ->
                        check(repo.activateRestored(expected.occurrenceId, expected)) {
                            "Captured restore confirmation is no longer current"
                        }
                        val item = requireNotNull(repo.get(expected.occurrenceId))
                        item to AlarmDelivery.from(item, repo.deliveryEpoch())
                    }
                    if (restored.first.status == OccurrenceStatus.TRIGGERED) {
                        TestAlarmRuntime.refresh(app)
                    } else TestAlarmRuntime.schedule(app, restored.first)
                    restored
                }, saved = { restored ->
                    if (restored.first.status == OccurrenceStatus.TRIGGERED) {
                        startActivity(TestAlarmRuntime.putToken(Intent(this, javaClass), restored.second))
                    }
                    toast("Aktivierung gespeichert. Tatsächliche Alarmausgabe bitte prüfen.")
                }, failed = {
                    toast("Aktivierung oder Planung nicht bestätigt. Aktuellen Verlauf prüfen; eine gespeicherte Aktivierung bleibt erhalten.")
                })
            }.show()
    }

    private fun <T> runHistoryCommand(work: () -> T, saved: (T) -> Unit, failed: (Throwable) -> Unit) {
        if (stopped || historyCommandInFlight || pendingAction != null || isFinishing || isDestroyed) return
        val request = screenRevision
        historyCommandInFlight = true
        reader.invalidate()
        show(TestAlarmUiState.Loading("Speicherung wird geprüft …"))
        val admitted = reader.submit(work) { result ->
            if (request != screenRevision || isFinishing || isDestroyed) return@submit
            historyCommandInFlight = false
            result.fold(onSuccess = saved, onFailure = {
                android.util.Log.e("TestAlarm", "Verlaufsaktion nicht bestätigt", it)
                failed(it)
            })
            if (request == screenRevision && !isFinishing && !isDestroyed) render()
        }
        if (!admitted) {
            historyCommandInFlight = false
            failed(IllegalStateException("Screen I/O already closed"))
            render()
        }
    }

    private fun act(expected: AlarmDelivery, type: ActionType, snoozeMinutes: Int? = null) {
        // A queued second tap cannot replace the request, its timestamp or its snooze duration.
        if (stopped || isFinishing || isDestroyed || pendingAction != null || invalidPendingAction || token != expected) return
        pendingAction = StableTestAlarmActions.freeze("test-user:${java.util.UUID.randomUUID()}",
            expected, type, Instant.now(), snoozeMinutes)
        submitPendingAction()
    }

    private fun submitPendingAction() {
        val action = pendingAction ?: return
        if (stopped || isFinishing || isDestroyed || invalidNotification || actionInFlight || !matchesDelivery(action, token)) return
        actionInFlight = true
        reader.invalidate()
        show(TestAlarmUiState.Loading("Speicherung wird geprüft …"))
        val app = applicationContext
        val admitted = reader.submit({ commitAction(app, action) }) { answer ->
            if (isFinishing || isDestroyed || pendingAction != action) return@submit
            actionInFlight = false
            // The original request remains available for an explicit journal retry on resume.
            if (stopped) return@submit
            answer.fold(onSuccess = { completed ->
                pendingAction = null
                when {
                    completed.result.duplicate -> {
                        toast("Aktion bereits gespeichert. Aktuellen Testverlauf prüfen; Ausgabe nicht erneut angefordert.")
                        openHistory()
                    }
                    completed.result.outcome != Outcome.APPLIED -> {
                        toast("Testwecker wurde bereits geändert. Bitte aktuellen Verlauf öffnen.")
                        openHistory()
                    }
                    completed.followUpUnconfirmed -> {
                        toast("Aktion gespeichert, anschließende Planung oder Ausgabe nicht bestätigt. Im Testverlauf prüfen.")
                        openHistory()
                    }
                    else -> {
                        toast("${TestAlarmRuntime.label(completed.result.occurrence.status)} · gespeichert")
                        finish()
                    }
                }
            }, onFailure = {
                android.util.Log.e("TestAlarm", "Speicherung der unveränderten Aktion nicht bestätigt", it)
                showUnconfirmedAction()
            })
        }
        if (!admitted) {
            actionInFlight = false
            showUnconfirmedAction()
        }
    }

    private fun commitAction(app: Context, action: ExecutionAction): CommittedAction {
        val result = TestAlarmRuntime.repository(app).use {
            val facade = StableTestAlarmActions(it, java.time.Clock.systemUTC())
            // A new explicit notification tap can recover the complete committed original.
            // A racing unrecorded request may conflict; never overwrite it or retry Android effects.
            val original = if (action.id.startsWith("test-user:r52-ui-v1-")) facade.recover(action.id)?.also { recorded ->
                check(recorded.copy(occurredAt = action.occurredAt) == action) { "Notification request payload conflict" }
            } ?: action else action
            facade.submit(original)
        }
        // A historical journal result never grants another scheduling or output effect.
        if (result.duplicate || result.outcome != Outcome.APPLIED) return CommittedAction(result, false)
        val stillCurrent = TestAlarmRuntime.repository(app).use { repo ->
            repo.deliveryEpoch() == StableTestAlarmActions.epoch(action) &&
                repo.isDeliveryAllowed(action.occurrenceId) && repo.get(action.occurrenceId) == result.occurrence
        }
        if (!stillCurrent) return CommittedAction(result, true)
        var unconfirmed = false
        if (action.type == ActionType.SNOOZE) {
            unconfirmed = runCatching { TestAlarmRuntime.schedule(app, result.occurrence) }.isFailure
        }
        TestAlarmRuntime.reconcileOccurrence(app, result.occurrence.id)
        if (runCatching { TestAlarmRuntime.refresh(app) }.isFailure) unconfirmed = true
        return CommittedAction(result, unconfirmed)
    }

    private fun matchesDelivery(action: ExecutionAction, delivery: AlarmDelivery?): Boolean =
        delivery != null && runCatching {
            AlarmDelivery(action.occurrenceId, action.expectedGeneration, action.expectedRevision,
                StableTestAlarmActions.epoch(action)) == delivery
        }.getOrDefault(false)

    private fun showUnconfirmedAction() {
        show(TestAlarmUiState.Error(
            "Erneut versuchen prüft dieselbe Anfrage mit unveränderter Dauer. Eine bereits gespeicherte Aktion bleibt erhalten.",
            title = "Aktion nicht bestätigt"))
    }

    private fun showError(error: Throwable) {
        android.util.Log.e("TestAlarm", "Aktion fehlgeschlagen", error)
        toast("Aktion nicht abgeschlossen. Berechtigungen prüfen und erneut versuchen.")
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
