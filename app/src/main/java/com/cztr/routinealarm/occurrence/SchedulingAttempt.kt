package com.cztr.routinealarm.occurrence

import java.time.Clock
import java.time.Instant

/** These observations concern an API invocation, never audible delivery. */
enum class SchedulingOutcome { CALL_RETURNED, CALL_FAILED, NOT_CALLED }
enum class SchedulingReason { RETURNED, PLATFORM_EXCEPTION, PERMISSION_DENIED, STALE, EXPIRED }

/** The adapter can abort preparation before invoking AlarmManager. */
class SchedulingNotCalled(val reason: SchedulingReason) : RuntimeException() {
    init { require(reason in setOf(SchedulingReason.STALE, SchedulingReason.EXPIRED)) }
}

data class SchedulingReceipt(val outcome: SchedulingOutcome, val reason: SchedulingReason, val recordedAt: Instant)
data class SchedulingAttempt(
    val id: String, val occurrenceId: String, val revision: Long, val generation: Long,
    val triggerAt: Instant, val startedAt: Instant, val receipt: SchedulingReceipt? = null,
) {
    val effectUnknown: Boolean get() = receipt?.outcome != SchedulingOutcome.NOT_CALLED
}

/** No transaction spans the external call. The durable attempt exposes both crash windows. */
class TestAlarmScheduling(
    private val repository: SqliteOccurrenceRepository,
    private val clock: Clock,
    private val canSchedule: () -> Boolean,
    private val platformSchedule: (RoutineOccurrence) -> Unit,
    private val afterAttempt: (SchedulingAttempt) -> Unit = {},
    private val afterPlatformReturn: () -> Unit = {},
) {
    fun schedule(occurrence: RoutineOccurrence, futureOnly: Boolean): Boolean {
        val attempt = repository.beginSchedulingAttempt(occurrence, futureOnly) ?: return false
        afterAttempt(attempt)
        if (repository.get(occurrence.id) != occurrence) {
            repository.recordSchedulingReceipt(attempt.id, SchedulingOutcome.NOT_CALLED, SchedulingReason.STALE)
            return false
        }
        if (futureOnly && occurrence.currentTriggerAt.toEpochMilli() <= clock.millis()) {
            repository.recordSchedulingReceipt(attempt.id, SchedulingOutcome.NOT_CALLED, SchedulingReason.EXPIRED)
            return false
        }
        if (!canSchedule()) {
            repository.recordSchedulingReceipt(attempt.id, SchedulingOutcome.NOT_CALLED, SchedulingReason.PERMISSION_DENIED)
            throw SecurityException("Bitte genaue Alarme erlauben.")
        }
        try {
            // Permission queries can consume time or race a user action.
            requireCurrentBeforeCall(repository, occurrence, clock, futureOnly)
            platformSchedule(occurrence)
        } catch (notCalled: SchedulingNotCalled) {
            repository.recordSchedulingReceipt(attempt.id, SchedulingOutcome.NOT_CALLED, notCalled.reason)
            return false
        } catch (error: Exception) {
            // A thrown platform call can still have had an effect. Store only a bounded category.
            try {
                repository.recordSchedulingReceipt(attempt.id, SchedulingOutcome.CALL_FAILED,
                    if (error is SecurityException) SchedulingReason.PERMISSION_DENIED else SchedulingReason.PLATFORM_EXCEPTION)
            } catch (receiptError: Exception) { error.addSuppressed(receiptError) }
            throw error
        }
        afterPlatformReturn()
        repository.recordSchedulingReceipt(attempt.id, SchedulingOutcome.CALL_RETURNED, SchedulingReason.RETURNED)
        return true
    }

    companion object {
        /** Also called by the Android adapter after PendingIntent preparation. */
        fun requireCurrentBeforeCall(repository: SqliteOccurrenceRepository, occurrence: RoutineOccurrence,
            clock: Clock, futureOnly: Boolean) {
            if (!repository.isDeliveryAllowed(occurrence.id) || repository.get(occurrence.id) != occurrence)
                throw SchedulingNotCalled(SchedulingReason.STALE)
            if (futureOnly && occurrence.currentTriggerAt.toEpochMilli() <= clock.millis())
                throw SchedulingNotCalled(SchedulingReason.EXPIRED)
        }
    }
}
