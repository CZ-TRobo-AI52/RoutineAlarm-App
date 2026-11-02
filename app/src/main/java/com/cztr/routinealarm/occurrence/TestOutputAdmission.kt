package com.cztr.routinealarm.occurrence

/** Process-local admission fence, never persisted authority. No SQLite runs under its monitor.
 * A write invalidates queued output before it opens SQLite and again after commit/rollback.
 * Platform calls admitted before a write may finish; snapshots queued across it may not play.
 */
internal object TestOutputAdmission {
    private val session = java.util.concurrent.atomic.AtomicReference<String?>()
    private val monitor = Any()
    private var revision = 0L
    private var writers = 0
    private var published: Pair<String, AlarmDelivery>? = null
    private data class StopPermit(val key: String, val instance: String, val delivery: AlarmDelivery,
        val ticket: Long, var consumed: Boolean = false)
    private var closePermit: StopPermit? = null

    fun registerSession(id: String) = synchronized(monitor) {
        session.set(id); published = null; closePermit = null; revision++
    }
    fun currentSession(id: String): Boolean = session.get() == id
    fun releaseSession(id: String) = synchronized(monitor) {
        if (session.compareAndSet(id, null)) { published = null; closePermit = null; revision++ }
    }
    fun publishOutput(id: String, delivery: AlarmDelivery): Boolean = synchronized(monitor) {
        if (!currentSession(id)) false else {
            if (closePermit?.delivery != delivery) closePermit = null
            published = id to delivery
            true
        }
    }
    fun retireOutput(id: String) = synchronized(monitor) {
        if (published?.first == id) published = null
    }

    /** Ephemeral, one-shot permission after a NEW committed original request. Never saved/restored.
     * A returned stop API call still cannot prove physical silence: durable reason is UNKNOWN_EFFECT.
     */
    fun issueOutputClose(action: ExecutionAction, result: ActionResult, ticket: Long): String? = synchronized(monitor) {
        val instance = action.outputInstanceId ?: return@synchronized null
        val original = AlarmDelivery(action.occurrenceId, action.expectedGeneration,
            action.expectedRevision, StableTestAlarmActions.epoch(action))
        if (action.type != ActionType.CLOSE_OUTPUT || result.duplicate || result.outcome != Outcome.APPLIED ||
            result.reason != "UNKNOWN_EFFECT" || result.occurrence.id != action.occurrenceId ||
            result.occurrence.status != OccurrenceStatus.TRIGGERED ||
            result.occurrence.generation != action.expectedGeneration ||
            result.occurrence.outputSilencedGeneration != action.expectedGeneration ||
            writers != 0 || revision != ticket || !currentSession(instance) ||
            published != instance to original || closePermit != null) return@synchronized null
        val key = java.util.UUID.randomUUID().toString()
        closePermit = StopPermit(key, instance, original, ticket)
        key
    }
    fun revokeOutputClose(key: String) = synchronized(monitor) {
        closePermit?.takeIf { it.key == key }?.consumed = true
    }
    fun consumeOutputClose(key: String?, id: String, current: AlarmDelivery?, transport: AlarmDelivery?,
        stop: () -> Unit): Boolean = synchronized(monitor) {
        val permit = closePermit ?: return@synchronized false
        if (key != permit.key || permit.consumed) return@synchronized false
        permit.consumed = true
        if (permit.instance != id || !currentSession(id) || current != permit.delivery ||
            transport != permit.delivery || published != id to permit.delivery ||
            writers != 0 || revision != permit.ticket) return@synchronized false
        stop()
        true
    }

    fun ticket(): Long? = synchronized(monitor) { if (writers == 0) revision else null }

    fun admit(ticket: Long, output: () -> Unit): Boolean = synchronized(monitor) {
        if (writers != 0 || revision != ticket) false else { output(); true }
    }

    fun <T> writing(block: () -> T): T {
        synchronized(monitor) { writers++; revision++ }
        try { return block() } finally { synchronized(monitor) { writers--; revision++ } }
    }
}
