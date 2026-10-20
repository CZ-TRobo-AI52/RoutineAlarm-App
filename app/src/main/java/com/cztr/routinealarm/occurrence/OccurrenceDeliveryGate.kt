package com.cztr.routinealarm.occurrence

import java.time.Instant

/** Read-only delivery admission; every caller must explicitly select its occurrence scope. */
internal class OccurrenceDeliveryGate(
    private val repository: OccurrenceRepository,
    private val accepts: (RoutineOccurrence) -> Boolean,
) {
    /** CAS and terminal-state decisions belong to apply(), including their journal records. */
    fun actionTarget(token: AlarmDelivery): RoutineOccurrence? {
        if (!token.valid || token.epoch != repository.deliveryEpoch() ||
            !repository.isDeliveryAllowed(token.occurrenceId)) return null
        return repository.get(token.occurrenceId)?.takeIf(accepts)
    }

    fun triggerCandidate(token: AlarmDelivery, now: Instant): RoutineOccurrence? {
        val current = actionTarget(token) ?: return null
        if (current.status == OccurrenceStatus.TRIGGERED) {
            return current.takeIf { triggeredFrom(it, token) }
        }
        return current.takeIf {
            it.generation == token.generation && it.revision == token.revision &&
                it.status in setOf(OccurrenceStatus.SCHEDULED, OccurrenceStatus.SNOOZED) &&
                now >= it.currentTriggerAt
        }
    }

    /** Read current state again after the transaction; never return an old action snapshot. */
    fun triggeredResult(token: AlarmDelivery): RoutineOccurrence? =
        actionTarget(token)?.takeIf { triggeredFrom(it, token) }

    fun active(token: AlarmDelivery): RoutineOccurrence? =
        actionTarget(token)?.takeIf {
            it.status == OccurrenceStatus.TRIGGERED &&
                it.generation == token.generation && it.revision == token.revision
        }

    private fun triggeredFrom(current: RoutineOccurrence, token: AlarmDelivery): Boolean =
        current.status == OccurrenceStatus.TRIGGERED && current.generation == token.generation &&
            token.revision < Long.MAX_VALUE && current.revision == token.revision + 1
}
