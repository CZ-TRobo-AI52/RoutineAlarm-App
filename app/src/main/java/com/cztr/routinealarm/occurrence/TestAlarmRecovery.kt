package com.cztr.routinealarm.occurrence

import java.time.Clock
import android.database.sqlite.SQLiteException
import java.io.IOException

data class RecoveryBatchResult(val processed: Int, val complete: Boolean, val failed: Int)

/** The deployed test-alarm adapter retains its original, explicit scope. */
class TestAlarmRecovery(
    repository: SqliteOccurrenceRepository,
    clock: Clock,
    schedule: (RoutineOccurrence) -> Unit,
    cancel: (AlarmDelivery) -> CancellationObservation,
    store: RecoveryCursorStore,
    cycleId: String,
    onFailure: (Exception) -> Unit = {},
) {
    private val delegate = OccurrenceRecovery(repository, clock, OccurrenceScope.Tests,
        schedule, cancel, store, cycleId, onFailure)
    fun runBatch(maxItems: Int = 64, shouldStop: () -> Boolean = { false }): RecoveryBatchResult =
        delegate.runBatch(maxItems, shouldStop)
}

/** Bounded work over the existing desired state. No loop loads the full future/history list.
 * New recovery requests use a new cycle; retries of one request retain its cycle ID.
 * Callers serialize all batches using the same cursor file and propagate cooperative stop.
 */
class OccurrenceRecovery(
    private val repository: SqliteOccurrenceRepository,
    private val clock: Clock,
    private val scope: OccurrenceScope,
    private val schedule: (RoutineOccurrence) -> Unit,
    private val cancel: (AlarmDelivery) -> CancellationObservation,
    private val store: RecoveryCursorStore,
    private val cycleId: String,
    private val onFailure: (Exception) -> Unit = {},
) {
    fun runBatch(maxItems: Int = 64, shouldStop: () -> Boolean = { false }): RecoveryBatchResult {
        require(maxItems in 1..64)
        require(cycleId.isNotBlank())
        if (shouldStop()) return RecoveryBatchResult(0, false, 0)
        val epoch = repository.deliveryEpoch()
        var cursor = store.load()?.takeIf {
            it.cycleId == cycleId && it.epoch == epoch && it.scopeKey == scope.key
        } ?: RecoveryCursor(cycleId, epoch, RecoveryPhase.FUTURE, repository.schedulingWatermark(),
            scopeKey = scope.key).also(store::save)
        var processed = 0
        var failed = 0
        fun result() = RecoveryBatchResult(processed, cursor.phase == RecoveryPhase.COMPLETE, failed)
        fun checkpoint(next: RecoveryCursor) {
            // A persistence failure must abort the batch, leaving that item retryable.
            // In particular it must not become an individual platform-failure receipt.
            store.save(next)
            cursor = next
        }
        while (processed < maxItems && cursor.phase != RecoveryPhase.COMPLETE) {
            if (shouldStop()) return result()
            if (repository.deliveryEpoch() != cursor.epoch) return RecoveryBatchResult(processed, false, failed)
            when (cursor.phase) {
                RecoveryPhase.FUTURE -> {
                    val page = repository.futureOccurrencesPage(scope, clock.instant(), cursor.futureAfterMillis,
                        cursor.futureAfterId, maxItems - processed)
                    if (page.isEmpty()) {
                        checkpoint(cursor.copy(phase = RecoveryPhase.CLEANUP))
                        continue
                    }
                    for (item in page) {
                        if (shouldStop()) return result()
                        // Re-read before invoking the scheduling adapter, whose own final
                        // check still closes preparation races immediately before Android.
                        val current = repository.get(item.id)
                        val allowed = current == item && scope.accepts(item) && repository.isDeliveryAllowed(item.id) &&
                            item.currentTriggerAt > clock.instant()
                        if (shouldStop()) return result()
                        if (allowed) {
                            try { schedule(item) }
                            catch (error: SQLiteException) { throw error }
                            catch (error: IOException) { throw error }
                            catch (error: Exception) { failed++; onFailure(error) }
                        }
                        processed++
                        checkpoint(cursor.copy(futureAfterMillis = item.currentTriggerAt.toEpochMilli(), futureAfterId = item.id))
                    }
                }
                RecoveryPhase.CLEANUP -> {
                    val page = repository.knownDeliveries(scope, cursor.through, cursor.cleanupAfter)
                        .take(maxItems - processed)
                    if (page.isEmpty()) {
                        checkpoint(cursor.copy(phase = RecoveryPhase.COMPLETE))
                        continue
                    }
                    for (known in page) {
                        if (shouldStop()) return result()
                        val obsolete = OccurrenceReconciliation.isObsolete(repository, known.token, scope)
                        if (shouldStop()) return result()
                        if (obsolete) {
                            try { cancel(known.token) }
                            catch (error: SQLiteException) { throw error }
                            catch (error: IOException) { throw error }
                            catch (error: Exception) { failed++; onFailure(error) }
                        }
                        processed++
                        checkpoint(cursor.copy(cleanupAfter = known.firstSequence))
                    }
                }
                RecoveryPhase.COMPLETE -> Unit
            }
        }
        return result()
    }
}
