package com.cztr.routinealarm.occurrence

/** Process-local admission fence, never persisted authority. No SQLite runs under its monitor.
 * A write invalidates queued output before it opens SQLite and again after commit/rollback.
 * Platform calls admitted before a write may finish; snapshots queued across it may not play.
 */
internal object TestOutputAdmission {
    private val session = java.util.concurrent.atomic.AtomicReference<String?>()
    fun registerSession(id: String) { session.set(id) }
    fun currentSession(id: String): Boolean = session.get() == id
    fun releaseSession(id: String) { session.compareAndSet(id, null) }
    private val monitor = Any()
    private var revision = 0L
    private var writers = 0

    fun ticket(): Long? = synchronized(monitor) { if (writers == 0) revision else null }

    fun admit(ticket: Long, output: () -> Unit): Boolean = synchronized(monitor) {
        if (writers != 0 || revision != ticket) false else { output(); true }
    }

    fun <T> writing(block: () -> T): T {
        synchronized(monitor) { writers++; revision++ }
        try { return block() } finally { synchronized(monitor) { writers--; revision++ } }
    }
}
