package com.cztr.routinealarm

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object ScheduleState {
    private const val PREFS = "routinealarm_schedule"
    private const val KEY_ENABLED = "weekly_schedule_enabled"
    private const val KEY_RESTORE_GENERATION = "restore_generation"
    private const val BEFORE_FIRST_RESTORE = "before-first-restore"
    private class WriteState {
        val observers = mutableSetOf<() -> Unit>()
        val pending = AtomicInteger()
        val sequence = AtomicLong()
        @Volatile var uncertain = false
        @Volatile var requested = false
        @Volatile var error: String? = null
    }
    // Process-local admission fence, not another persistence layer.
    private val states = WeakHashMap<SharedPreferences, WriteState>()
    private fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun state(context: Context): WriteState = synchronized(states) {
        states.getOrPut(preferences(context)) { WriteState() }
    }
    private val transitionLock = Any()
    // Application-owned commands survive render/onStop/onDestroy.
    private val onStorageWorker = ThreadLocal<Boolean>()
    private val writes: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread({ onStorageWorker.set(true); task.run() }, "weekly-preference-writes").apply { isDaemon = true }
    }

    /** Compatibility reads reuse the existing application worker, never SQLite on the caller/UI thread.
     * UI hosts should call the repository through their asynchronous snapshot loader. No cache is authority.
     */
    internal fun <T> readRoutineStorage(block: () -> T): T =
        if (Looper.myLooper() == Looper.getMainLooper()) error("Routine storage reads require a worker") else block()

    internal fun dispatchStorage(block: () -> Unit) {
        writes.execute { runCatching(block).onFailure { android.util.Log.w("RoutineAlarm", "Routine request not confirmed", it) } }
    }
    private data class AdmissionScope(val signature: String, val ticket: Long)
    private val admitted = ThreadLocal<AdmissionScope?>()
    /** One callback-scoped permission, invalidated by every existing repository write. Not saved authority. */
    internal fun requestDeliveryAdmission(context: Context, original: Intent?, accept: () -> Unit, reject: () -> Unit) {
        val intent = original?.let(::Intent)
        if (intent == null) { reject(); return }
        val originalGeneration = if (intent.hasExtra(AlarmScheduler.EXTRA_RESTORE_GENERATION))
            intent.getStringExtra(AlarmScheduler.EXTRA_RESTORE_GENERATION) else BEFORE_FIRST_RESTORE
        // A known stale generation cannot gain authority by waiting for a later read.
        if (originalGeneration != restoreGeneration(context) ||
            (intent.getBooleanExtra(AlarmScheduler.EXTRA_RECURRING, false) && !isEnabled(context))) {
            reject(); return
        }
        if (!intent.getBooleanExtra(AlarmScheduler.EXTRA_RECURRING, false)) {
            if (isDeliveryAllowed(context, intent)) accept() else reject()
            return
        }
        if (isDeliveryAllowedInScope(intent)) { accept(); return }
        val generation = restoreGeneration(context)
        val weeklyRevision = state(context).sequence.get()
        val ticket = com.cztr.routinealarm.occurrence.TestOutputAdmission.ticket()
        dispatchStorage {
            val allowed = ticket != null && runCatching { isDeliveryAllowed(context, intent) }.getOrDefault(false)
            Handler(Looper.getMainLooper()).post {
                var performed = false
                if (allowed && generation == restoreGeneration(context) && weeklyRevision == state(context).sequence.get() && isEnabled(context)) {
                    performed = com.cztr.routinealarm.occurrence.TestOutputAdmission.admit(ticket!!) {
                        val previous = admitted.get()
                        admitted.set(AdmissionScope(intent.toUri(0), ticket))
                        try { accept() } finally { admitted.set(previous) }
                    }
                }
                if (!performed) reject()
            }
        }
    }
    private fun isDeliveryAllowedInScope(intent: Intent): Boolean = admitted.get()?.let {
        it.signature == intent.toUri(0) && com.cztr.routinealarm.occurrence.TestOutputAdmission.ticket() == it.ticket
    } == true

    internal fun restoreGeneration(context: Context): String =
        preferences(context).getString(KEY_RESTORE_GENERATION, BEFORE_FIRST_RESTORE) ?: BEFORE_FIRST_RESTORE

    internal fun isDeliveryAllowed(context: Context, intent: Intent?): Boolean {
        if (intent == null) return false
        val generation = if (intent.hasExtra(AlarmScheduler.EXTRA_RESTORE_GENERATION)) {
            intent.getStringExtra(AlarmScheduler.EXTRA_RESTORE_GENERATION)
        } else BEFORE_FIRST_RESTORE
        if (generation != restoreGeneration(context)) return false
        if (!intent.getBooleanExtra(AlarmScheduler.EXTRA_RECURRING, false)) return true
        val id = intent.getStringExtra(AlarmScheduler.EXTRA_EVENT_ID) ?: return false
        if (!isEnabled(context)) return false
        if (Looper.myLooper() == Looper.getMainLooper()) return isDeliveryAllowedInScope(intent)
        val snapshot = runCatching { ScheduleRegistry.load(context) }.getOrNull() ?: return false
        val event = snapshot.scheduledById(id)?.takeIf { it.enabled } ?: return false
        val definition = snapshot.definitions[id]
        if (definition?.quarantined == true) return false
        if (definition != null || snapshot.restoreEpoch.isNotEmpty() ||
            intent.hasExtra(AlarmScheduler.EXTRA_ROUTINE_VERSION) || intent.hasExtra(AlarmScheduler.EXTRA_ROUTINE_EPOCH)) {
            if (!intent.hasExtra(AlarmScheduler.EXTRA_ROUTINE_VERSION) ||
                intent.getLongExtra(AlarmScheduler.EXTRA_ROUTINE_VERSION, -1) != (definition?.version ?: 0L) ||
                intent.getStringExtra(AlarmScheduler.EXTRA_ROUTINE_EPOCH) != snapshot.restoreEpoch) return false
        }
        return event.enabled
    }

    fun isEnabled(context: Context): Boolean {
        val current = state(context)
        return current.pending.get() == 0 && !current.uncertain &&
            preferences(context).getBoolean(KEY_ENABLED, false)
    }

    internal fun requestedEnabled(context: Context): Boolean =
        state(context).let { if (it.pending.get() > 0) it.requested else isEnabled(context) }

    internal fun writeNotice(context: Context): String? = state(context).let {
        if (it.pending.get() > 0) "Änderung wird gespeichert …" else it.error
    }


    internal fun observe(context: Context, observer: () -> Unit) {
        val current = state(context)
        synchronized(current.observers) { current.observers.add(observer) }
    }

    internal fun unobserve(context: Context, observer: () -> Unit) {
        val current = state(context)
        synchronized(current.observers) { current.observers.remove(observer) }
    }

    private fun publish(context: Context) {
        val current = state(context)
        Handler(Looper.getMainLooper()).post {
            val listeners = synchronized(current.observers) { current.observers.toList() }
            listeners.forEach { it() }
        }
    }

    /** Checked synchronous primitive; product UI uses requestEnabled instead. */
    fun setEnabled(context: Context, enabled: Boolean) = synchronized(transitionLock) {
        val current = state(context)
        current.uncertain = true // commit(false) can still mutate the memory cache.
        val persisted = preferences(context).edit().putBoolean(KEY_ENABLED, enabled).commit()
        current.uncertain = !persisted
        check(persisted) { "Weekly schedule write was not confirmed" }
    }

    internal fun requestEnabled(context: Context, enabled: Boolean, reply: (Result<Int>) -> Unit) {
        val app = context.applicationContext
        val current = state(app)
        val epoch = restoreGeneration(app)
        // Keep sequence assignment and executor admission in the same order.
        synchronized(current) {
            val request = current.sequence.incrementAndGet()
            current.requested = enabled
            current.pending.incrementAndGet()
            writes.execute {
                val result = runCatching {
                    synchronized(transitionLock) {
                        check(epoch == restoreGeneration(app)) { "Request predates restore" }
                        check(!enabled || AlarmScheduler.canScheduleExact(app)) { "Exact alarm permission missing" }
                        setEnabled(app, enabled)
                        if (enabled) {
                            if (current.sequence.get() == request) AlarmScheduler.scheduleAll(app) else 0
                        } else {
                            // Weekly registrations only; no global output stop for an explicit one-shot.
                            AlarmScheduler.cancelAll(app)
                            AlarmService.stopWeeklyOutput(app, epoch)
                            TrainingSessionService.stopWeeklyOutput(app, epoch)
                            0
                        }
                    }
                }
                if (current.sequence.get() == request) {
                    if (result.isFailure) current.uncertain = true
                    current.error = if (result.isFailure)
                        "Wochenplanstatus nicht bestätigt. Bitte Speicherung erneut versuchen." else null
                }
                current.pending.decrementAndGet()
                publish(app)
                Handler(Looper.getMainLooper()).post {
                    if (current.sequence.get() == request) reply(result)
                }
            }
        }
        publish(app)
    }

    /** Restore invalidates queued old-epoch commands as well as deliveries. */
    fun disableAfterRestore(context: Context) = synchronized(transitionLock) {
        val current = state(context)
        current.sequence.incrementAndGet()
        current.uncertain = true
        val persisted = preferences(context).edit().putBoolean(KEY_ENABLED, false)
            .putString(KEY_RESTORE_GENERATION, UUID.randomUUID().toString()).commit()
        current.uncertain = !persisted
        current.error = if (persisted) null else "Wochenplanstatus nicht bestätigt. Restore-Sperre prüfen."
        publish(context.applicationContext)
        check(persisted) { "Could not persist disabled weekly schedule after restore" }
    }
}
