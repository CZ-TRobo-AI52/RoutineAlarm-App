package com.cztr.routinealarm.occurrence

import com.cztr.routinealarm.EventCategory
import com.cztr.routinealarm.EventMode
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** A delivery is scoped to one persisted revision, never to its display title. */
data class AlarmDelivery(val occurrenceId: String, val generation: Long, val revision: Long) {
    val valid: Boolean get() = occurrenceId.isNotBlank() && generation >= 0 && revision >= 0
    companion object {
        fun from(item: RoutineOccurrence) = AlarmDelivery(item.id, item.generation, item.revision)
    }
}

/** M2.2a: existing journal, limited to explicit test alarms until weekly recovery is ready. */
class TestAlarmCoordinator(private val repository: OccurrenceRepository, private val clock: Clock) {
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
        if (!delivery.valid) return null
        val current = repository.get(delivery.occurrenceId)?.takeIf { it.isTest } ?: return null
        if (current.generation != delivery.generation) return null
        // A redelivery reads current state, not an old ActionResult snapshot. Do not
        // reuse an action ID with a new observation timestamp on retry.
        if (current.status == OccurrenceStatus.TRIGGERED) {
            return current.takeIf { delivery.revision < Long.MAX_VALUE && it.revision == delivery.revision + 1 }
        }
        if (current.revision != delivery.revision ||
            current.status !in setOf(OccurrenceStatus.SCHEDULED, OccurrenceStatus.SNOOZED) ||
            clock.instant() < current.currentTriggerAt) return null
        repository.apply(ExecutionAction(
            id = "test-trigger:${UUID.randomUUID()}", occurrenceId = current.id,
            type = ActionType.TRIGGER, actor = Actor.SYSTEM, occurredAt = clock.instant(),
            expectedRevision = delivery.revision, expectedGeneration = delivery.generation,
            source = "test-alarm-receiver",
        ))
        return repository.get(current.id)?.takeIf {
            it.isTest && it.status == OccurrenceStatus.TRIGGERED && it.generation == delivery.generation &&
                delivery.revision < Long.MAX_VALUE && it.revision == delivery.revision + 1
        }
    }

    fun active(token: AlarmDelivery): RoutineOccurrence? =
        if (!token.valid) null else repository.get(token.occurrenceId)?.takeIf {
            it.isTest && it.status == OccurrenceStatus.TRIGGERED &&
                it.generation == token.generation && it.revision == token.revision
        }

    fun act(token: AlarmDelivery, type: ActionType, minutes: Int? = null): ActionResult? {
        require(type in setOf(ActionType.DONE, ActionType.SKIP, ActionType.SNOOZE))
        if (!token.valid || repository.get(token.occurrenceId)?.isTest != true) return null
        return repository.apply(ExecutionAction(
            id = "test-user:${UUID.randomUUID()}", occurrenceId = token.occurrenceId,
            type = type, actor = Actor.USER, occurredAt = clock.instant(),
            expectedRevision = token.revision, expectedGeneration = token.generation,
            snoozeMinutes = minutes, source = "test-alarm-screen",
        ))
    }
}
