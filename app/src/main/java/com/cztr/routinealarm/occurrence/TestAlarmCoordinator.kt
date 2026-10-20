package com.cztr.routinealarm.occurrence

import com.cztr.routinealarm.EventCategory
import com.cztr.routinealarm.EventMode
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** A delivery is scoped to one persisted revision, never to its display title. */
data class AlarmDelivery(val occurrenceId: String, val generation: Long, val revision: Long,
    val epoch: String = "") {
    val valid: Boolean get() = occurrenceId.isNotBlank() && generation >= 0 && revision >= 0 &&
        epoch.length <= 64 && epoch.all { it.isLetterOrDigit() || it == '-' }
    companion object {
        fun from(item: RoutineOccurrence, epoch: String = "") = AlarmDelivery(item.id, item.generation, item.revision, epoch)
    }
}

/** M2.2a: existing journal, limited to explicit test alarms until weekly recovery is ready. */
class TestAlarmCoordinator(private val repository: OccurrenceRepository, private val clock: Clock) {
    private val deliveryGate = OccurrenceDeliveryGate(repository) { it.isTest }

    fun create(minutes: Int): RoutineOccurrence {
        require(minutes in 1..60)
        val now = Instant.ofEpochMilli(clock.millis())
        val planned = now.plusSeconds(minutes * 60L).atZone(clock.zone)
        val id = "test:${UUID.randomUUID()}"
        val occurrence = RoutineOccurrence(
            id = id, eventId = id, scheduleRevision = "test-v1:${clock.zone.id}",
            nominalDate = planned.toLocalDate(), nominalTime = planned.toLocalTime(),
            zoneId = clock.zone.id, offsetSeconds = planned.offset.totalSeconds,
            plannedAt = planned.toInstant(), currentTriggerAt = planned.toInstant(),
            lastUpdatedAt = now, traceId = id, title = "RoutineAlarm Test",
            spokenText = "RoutineAlarm Test. Der Wecker funktioniert.",
            category = EventCategory.PERSONAL, mode = EventMode.ROUTINE, isTest = true,
        )
        repository.materialize(listOf(occurrence))
        return occurrence
    }

    fun trigger(delivery: AlarmDelivery): RoutineOccurrence? {
        val current = deliveryGate.triggerCandidate(delivery, clock.instant()) ?: return null
        // A redelivery reads current state, not an old ActionResult snapshot. Do not
        // reuse an action ID with a new observation timestamp on retry.
        if (current.status == OccurrenceStatus.TRIGGERED) return current
        repository.apply(ExecutionAction(
            id = "test-trigger:${UUID.randomUUID()}", occurrenceId = current.id,
            type = ActionType.TRIGGER, actor = Actor.SYSTEM, occurredAt = clock.instant(),
            expectedRevision = delivery.revision, expectedGeneration = delivery.generation,
            source = "test-alarm-receiver",
        ))
        return deliveryGate.triggeredResult(delivery)
    }

    fun active(token: AlarmDelivery): RoutineOccurrence? = deliveryGate.active(token)

    fun act(token: AlarmDelivery, type: ActionType, minutes: Int? = null): ActionResult? {
        require(type in setOf(ActionType.DONE, ActionType.SKIP, ActionType.SNOOZE))
        if (deliveryGate.actionTarget(token) == null) return null
        return repository.apply(ExecutionAction(
            id = "test-user:${UUID.randomUUID()}", occurrenceId = token.occurrenceId,
            type = type, actor = Actor.USER, occurredAt = clock.instant(),
            expectedRevision = token.revision, expectedGeneration = token.generation,
            snoozeMinutes = minutes, source = "test-alarm-screen",
        ))
    }
}
