package com.cztr.routinealarm.occurrence

import com.cztr.routinealarm.EventCategory
import com.cztr.routinealarm.EventMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

enum class OccurrenceStatus { SCHEDULED, TRIGGERED, SNOOZED, DONE, SKIPPED, MISSED }
enum class ActionType { TRIGGER, DONE, SKIP, SNOOZE, MISS }
enum class Actor { USER, SYSTEM }
enum class Outcome { APPLIED, REJECTED, CONFLICT }

/** A concrete execution of a rule. plannedAt and id never change after materialization. */
data class RoutineOccurrence(
    val id: String,
    val eventId: String,
    val scheduleRevision: String,
    val nominalDate: LocalDate,
    val nominalTime: LocalTime,
    val zoneId: String,
    val offsetSeconds: Int,
    val plannedAt: Instant,
    val currentTriggerAt: Instant,
    val status: OccurrenceStatus = OccurrenceStatus.SCHEDULED,
    val generation: Long = 0,
    val revision: Long = 0,
    val snoozeCount: Int = 0,
    val totalSnoozeMinutes: Int = 0,
    val lastUpdatedAt: Instant,
    val traceId: String,
    val title: String,
    val spokenText: String,
    val category: EventCategory,
    val mode: EventMode,
    val isTest: Boolean = false
)

/** occurredAt records the observation; repository Clock supplies the independent recordedAt. */
data class ExecutionAction(
    val id: String,
    val occurrenceId: String,
    val type: ActionType,
    val actor: Actor,
    val occurredAt: Instant,
    val expectedRevision: Long,
    val expectedGeneration: Long,
    val snoozeMinutes: Int? = null,
    val source: String = "local",
    val reason: String? = null
)

data class Transition(val outcome: Outcome, val occurrence: RoutineOccurrence, val reason: String? = null)

/** No platform calls: the repository commits this result and its journal entry together. */
object OccurrenceTransitions {
    fun apply(current: RoutineOccurrence, action: ExecutionAction): Transition {
        fun reject(reason: String) = Transition(Outcome.REJECTED, current, reason)
        if (action.occurrenceId != current.id) return reject("occurrence_mismatch")
        if (action.id.isBlank() || action.id.startsWith("schedule:") || action.source.isBlank()) {
            return reject("invalid_action_identity")
        }
        if (action.expectedRevision != current.revision || action.expectedGeneration != current.generation) {
            return Transition(Outcome.CONFLICT, current, "stale_revision_or_generation")
        }
        if (current.status in setOf(OccurrenceStatus.DONE, OccurrenceStatus.SKIPPED, OccurrenceStatus.MISSED)) {
            return reject("terminal_occurrence")
        }
        val observedAt = try {
            Instant.ofEpochMilli(action.occurredAt.toEpochMilli())
        } catch (_: ArithmeticException) {
            return reject("time_out_of_range")
        }
        // A backward wall-clock jump needs explicit reconciliation in M2.2. Until then,
        // reject backdated follow-ups instead of creating an alarm in an earlier clock context.
        if (current.status != OccurrenceStatus.SCHEDULED && observedAt < current.lastUpdatedAt) {
            return reject("clock_reconciliation_required")
        }
        if (action.type != ActionType.SNOOZE && action.snoozeMinutes != null) return reject("unexpected_snooze_minutes")
        val newStatus = when (action.type) {
            ActionType.TRIGGER -> {
                if (action.actor != Actor.SYSTEM) return reject("system_trigger_required")
                if (current.status !in setOf(OccurrenceStatus.SCHEDULED, OccurrenceStatus.SNOOZED)) {
                    return reject("not_waiting_for_trigger")
                }
                if (observedAt < current.currentTriggerAt) return reject("trigger_too_early")
                OccurrenceStatus.TRIGGERED
            }
            ActionType.DONE, ActionType.SKIP, ActionType.SNOOZE -> {
                if (action.actor != Actor.USER) return reject("explicit_user_action_required")
                if (current.status !in setOf(OccurrenceStatus.TRIGGERED, OccurrenceStatus.SNOOZED)) {
                    return reject("not_triggered")
                }
                when (action.type) {
                    ActionType.DONE -> OccurrenceStatus.DONE
                    ActionType.SKIP -> OccurrenceStatus.SKIPPED
                    else -> {
                        if (current.status != OccurrenceStatus.TRIGGERED) return reject("snooze_requires_trigger")
                        if (action.snoozeMinutes !in 1..5) return reject("snooze_must_be_1_to_5_minutes")
                        OccurrenceStatus.SNOOZED
                    }
                }
            }
            // Missing receiver evidence does not prove a missed personal action. M2.2 must
            // supply an explicit due policy and delivery evidence before enabling this path.
            ActionType.MISS -> return reject("miss_policy_not_configured")
        }
        return try {
            val snooze = action.type == ActionType.SNOOZE
            val invalidatesDelivery = snooze || action.type == ActionType.DONE || action.type == ActionType.SKIP
            val result = current.copy(
                status = newStatus,
                revision = Math.addExact(current.revision, 1),
                generation = if (invalidatesDelivery) Math.addExact(current.generation, 1) else current.generation,
                currentTriggerAt = if (snooze) observedAt.plusSeconds(action.snoozeMinutes!! * 60L)
                    else current.currentTriggerAt,
                snoozeCount = if (snooze) Math.addExact(current.snoozeCount, 1) else current.snoozeCount,
                totalSnoozeMinutes = if (snooze) Math.addExact(current.totalSnoozeMinutes, action.snoozeMinutes!!)
                    else current.totalSnoozeMinutes,
                lastUpdatedAt = observedAt
            )
            Transition(Outcome.APPLIED, result)
        } catch (_: ArithmeticException) {
            reject("counter_or_time_overflow")
        } catch (_: java.time.DateTimeException) {
            reject("counter_or_time_overflow")
        }
    }
}
