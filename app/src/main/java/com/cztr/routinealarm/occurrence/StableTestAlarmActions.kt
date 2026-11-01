package com.cztr.routinealarm.occurrence

import java.time.Clock
import java.time.Instant

/**
 * Core facade for one immutable request from the test-alarm screen. No Android effect is issued.
 * Prepare exactly once before sending; retry the original action, never a new clock observation.
 * After a lost reply, recover reads the committed original from the existing execution journal.
 * A missing original means NOT_RECORDED_OR_NOT_THIS_CONSUMER, never permission to invent one.
 * Results describe local transactions. A historical duplicate cannot authorize scheduling/audio.
 */
class StableTestAlarmActions(private val repository: SqliteOccurrenceRepository, private val clock: Clock) {
    fun prepare(requestId: String, token: AlarmDelivery, type: ActionType, minutes: Int? = null, outputInstanceId: String? = null): ExecutionAction =
        freeze(requestId, token, type, clock.instant(), minutes, outputInstanceId)

    fun submit(action: ExecutionAction): ActionResult = repository.applyTestUserAction(action)

    /** Historical data only, including after restore. submit still checks CURRENT admission. */
    fun recover(requestId: String): ExecutionAction? = repository.recordedAction(requestId)?.takeIf {
        runCatching { epoch(it) }.isSuccess
    }

    internal companion object {
        // Versioned source binds consumer, test-only scope and ORIGINAL epoch in the persisted
        // full action snapshot. No schema change or second request/idempotency store is needed.
        private const val SOURCE = "test-alarm-screen/1;epoch="

        /** Capture the complete request before asynchronous admission, without opening a database. */
        fun freeze(requestId: String, token: AlarmDelivery, type: ActionType, occurredAt: Instant,
            minutes: Int? = null, outputInstanceId: String? = null): ExecutionAction {
            require(token.valid)
            return ExecutionAction(requestId, token.occurrenceId, type, Actor.USER, occurredAt,
                token.revision, token.generation, minutes, SOURCE + token.epoch, outputInstanceId = outputInstanceId).also { epoch(it) }
        }

        fun epoch(action: ExecutionAction): String {
            require(action.id.startsWith("test-user:") && action.id.length in 11..160)
            require(action.source.startsWith(SOURCE) && action.actor == Actor.USER)
            require(action.type in setOf(ActionType.DONE, ActionType.SKIP, ActionType.SNOOZE, ActionType.CLOSE_OUTPUT))
            require(if (action.type == ActionType.SNOOZE) action.snoozeMinutes in 1..5 else action.snoozeMinutes == null)
            require(if (action.type == ActionType.CLOSE_OUTPUT)
                runCatching { java.util.UUID.fromString(action.outputInstanceId) }.isSuccess else action.outputInstanceId == null)
            val epoch = action.source.removePrefix(SOURCE)
            require(AlarmDelivery(action.occurrenceId, action.expectedGeneration, action.expectedRevision, epoch).valid)
            return epoch
        }
    }
}
