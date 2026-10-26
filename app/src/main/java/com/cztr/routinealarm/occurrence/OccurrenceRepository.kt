package com.cztr.routinealarm.occurrence

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import java.io.Closeable
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

interface OccurrenceRepository : Closeable {
    fun deliveryEpoch(): String = ""
    fun isDeliveryAllowed(id: String): Boolean = true
    fun materialize(items: List<RoutineOccurrence>): Int
    fun get(id: String): RoutineOccurrence?
    fun listForDate(date: LocalDate, zone: ZoneId, includeTests: Boolean = false): List<RoutineOccurrence>
    fun apply(action: ExecutionAction): ActionResult
    fun history(id: String): List<JournalEntry>
}

data class ActionResult(
    val outcome: Outcome,
    val occurrence: RoutineOccurrence,
    val reason: String? = null,
    val duplicate: Boolean = false,
)

data class JournalEntry(
    val actionId: String,
    val occurrenceId: String,
    val actionType: String,
    val actor: Actor,
    val occurredAt: Instant,
    val recordedAt: Instant,
    val expectedRevision: Long,
    val expectedGeneration: Long,
    val resultingRevision: Long,
    val previousStatus: OccurrenceStatus?,
    val resultingStatus: OccurrenceStatus,
    val generation: Long,
    val snoozeMinutes: Int?,
    val source: String,
    val reason: String?,
    val outcome: Outcome,
)

class ActionIdConflict(actionId: String) : IllegalStateException("Action ID reused with different content: $actionId")

/**
 * Local M2.1 persistence. No scheduler, network, SharedPreferences, or device action is invoked here.
 * A successful return follows the SQLite commit. The hook permits testing process failure before it.
 */
class SqliteOccurrenceRepository(
    context: Context,
    private val clock: Clock,
    name: String = "occurrences.db",
    private val beforeCommit: () -> Unit = {},
) : OccurrenceRepository {
    private val helper = OccurrenceDatabase(context.applicationContext, name)

    /** Capture execution data and plan lineage in one snapshot; provider I/O is outside it. */
    fun exportBackup(): ByteArray {
        val snapshot = transaction { db -> OccurrenceBackup.read(db) }
        OccurrenceBackup.validate(snapshot)
        return OccurrenceBackup.encode(snapshot)
    }

    /** A restore never replaces or merges existing user data and invokes no platform effect. */
    fun restoreBackup(bytes: ByteArray): Int {
        val snapshot = OccurrenceBackup.decode(bytes)
        return transaction { db ->
            OccurrenceBackup.tables.forEach { table ->
                db.rawQuery("SELECT 1 FROM ${table.name} LIMIT 1", null).use {
                    check(!it.moveToFirst()) { "Restore requires an empty occurrence store; existing data was retained" }
                }
            }
            db.rawQuery("SELECT 1 FROM restored_occurrences LIMIT 1", null).use { check(!it.moveToFirst()) }
            OccurrenceBackup.insert(db, snapshot)
            check(db.update("store_metadata", ContentValues().apply { put("delivery_epoch", UUID.randomUUID().toString()) },
                "singleton = 1", null) == 1)
            db.execSQL("""INSERT INTO restored_occurrences(occurrence_id)
                SELECT occurrence_id FROM occurrences WHERE status IN ('SCHEDULED','SNOOZED','TRIGGERED')""")
            snapshot.tables[1].size
        }
    }

    override fun deliveryEpoch(): String = helper.readableDatabase.rawQuery(
        "SELECT delivery_epoch FROM store_metadata WHERE singleton = 1", null,
    ).use { check(it.moveToFirst()); it.getString(0) }

    /** Staging API; personal receiver/UI activation needs its own authority validation.
     * A stale writer receives Conflict. An identical retry at the current version is a no-op.
     */
    fun personalPlanAuthority(scope: OccurrenceScope.PersonalEvent): PersonalPlanAuthority =
        readSnapshot { PersonalPlanPersistence.read(it, scope.eventId) }

    fun replacePersonalPlan(scope: OccurrenceScope.PersonalEvent, expectedVersion: Long,
        candidates: List<RoutineOccurrence>, planRevision: String, zone: ZoneId,
        cutoff: Instant): PersonalPlanDecision = transaction { db ->
        val authority = PersonalPlanPersistence.read(db, scope.eventId)
        val existing = db.query("occurrences", arrayOf("snapshot"), "is_test = 0 AND event_id = ?",
            arrayOf(scope.eventId), null, null, "occurrence_id").use { cursor -> buildList {
            while (cursor.moveToNext()) add(OccurrenceCodec.decode(cursor.getString(0)))
        } }
        db.rawQuery("""SELECT 1 FROM restored_occurrences r JOIN occurrences o
            ON r.occurrence_id = o.occurrence_id WHERE o.is_test = 0 AND o.event_id = ? LIMIT 1""",
            arrayOf(scope.eventId)).use {
            check(!it.moveToFirst()) { "Restored personal occurrences require separate explicit activation" }
        }
        val decision = PersonalPlanReplacement.decide(scope, authority, expectedVersion,
            existing, candidates, planRevision, zone, cutoff)
        if (decision is PersonalPlanDecision.Ready && decision.next.version != authority.version) {
            decision.insertions.forEach { occurrence ->
                requireInitial(occurrence)
                freezeZone(db, occurrence)
                db.insertOrThrow("occurrences", null, values(occurrence))
                journal(db, "schedule:" + occurrence.id, null, ActionResult(Outcome.APPLIED, occurrence), null)
            }
            PersonalPlanPersistence.commit(db, decision, cutoff, clock.instant())
        }
        decision
    }

    fun isPersonalPlanRetired(id: String): Boolean =
        PersonalPlanPersistence.isRetired(helper.readableDatabase, id)

    override fun isDeliveryAllowed(id: String): Boolean = deliveryAllowed(helper.readableDatabase, id)

    private fun deliveryAllowed(db: SQLiteDatabase, id: String): Boolean = db.rawQuery(
        """SELECT 1 FROM occurrences o WHERE o.occurrence_id = ? AND NOT EXISTS
            (SELECT 1 FROM restored_occurrences r WHERE r.occurrence_id = o.occurrence_id)
            AND NOT EXISTS (SELECT 1 FROM personal_plan_retirements p
                WHERE p.occurrence_id = o.occurrence_id)""", arrayOf(id),
    ).use { it.moveToFirst() }

    fun restorePausedCount(): Int = helper.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM restored_occurrences", null,
    ).use { it.moveToFirst(); it.getInt(0) }

    /** Test-only activation. Imported personal authority needs its separate admission path. */
    fun activateRestored(id: String): Boolean = transaction { db ->
        if (get(db, id)?.isTest != true) return@transaction false
        db.delete("restored_occurrences", "occurrence_id = ?", arrayOf(id)) == 1
    }

    override fun materialize(items: List<RoutineOccurrence>): Int = transaction { db ->
        var inserted = 0
        items.forEach { occurrence ->
            requireInitial(occurrence)
            freezeZone(db, occurrence)
            val existing = get(db, occurrence.id)
            if (existing != null) {
                require(sameIdentity(existing, occurrence)) { "Occurrence ID reused with a different schedule identity" }
            } else {
                if (!occurrence.isTest) check(PersonalPlanPersistence.read(db, occurrence.eventId).version == 0L) {
                    "New personal occurrences must use the authoritative replacement transaction"
                }
                db.insertOrThrow("occurrences", null, values(occurrence))
                journal(
                    db = db, actionId = "schedule:${occurrence.id}", current = null,
                    result = ActionResult(Outcome.APPLIED, occurrence), action = null,
                )
                inserted++
            }
        }
        inserted
    }

    override fun get(id: String): RoutineOccurrence? = get(helper.readableDatabase, id)

    /** Aggregate persisted millisecond times; only the next visible snapshot is decoded. */
    fun statusSummary(now: Instant): TestAlarmStatusSummary {
        val nowMillis = now.toEpochMilli().toString()
        return readSnapshot { db ->
            val visible = """is_test = 1 AND status IN ('SCHEDULED','SNOOZED','TRIGGERED')
                AND NOT EXISTS (SELECT 1 FROM restored_occurrences r
                    WHERE r.occurrence_id = occurrences.occurrence_id)"""
            val counts = db.rawQuery("""SELECT COUNT(*),
                COALESCE(SUM(CASE WHEN status = 'TRIGGERED' THEN 1 ELSE 0 END), 0),
                COALESCE(SUM(CASE WHEN status IN ('SCHEDULED','SNOOZED') AND current_trigger_at <= ?
                    THEN 1 ELSE 0 END), 0)
                FROM occurrences WHERE $visible""", arrayOf(nowMillis)).use { cursor ->
                check(cursor.moveToFirst())
                Triple(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2))
            }
            val next = db.query("occurrences", arrayOf("snapshot"),
                "$visible AND status IN ('SCHEDULED','SNOOZED') AND current_trigger_at > ?",
                arrayOf(nowMillis), null, null, "current_trigger_at ASC, occurrence_id ASC", "1",
            ).use { cursor -> if (cursor.moveToFirst()) OccurrenceCodec.decode(cursor.getString(0)) else null }
            TestAlarmStatusSummary(counts.first, counts.second, counts.third, next)
        }
    }

    /** One consistent page, including receipt/quarantine state and the token epoch. */
    fun historyPage(offset: Int = 0): TestAlarmHistoryPage {
        require(offset >= 0)
        return readSnapshot { db ->
            val epoch = db.rawQuery("SELECT delivery_epoch FROM store_metadata WHERE singleton = 1", null)
                .use { check(it.moveToFirst()); it.getString(0) }
            val pausedCount = db.rawQuery("SELECT COUNT(*) FROM restored_occurrences", null)
                .use { check(it.moveToFirst()); it.getInt(0) }
            // Select the page before joining, bounding the indexed latest-attempt lookups
            // to 20 occurrences. Keep attempts without receipts visible as unknown outcomes.
            val items = db.rawQuery("""SELECT o.snapshot,
                NOT EXISTS (SELECT 1 FROM restored_occurrences q
                    WHERE q.occurrence_id = o.occurrence_id) AS delivery_allowed,
                a.*, r.outcome, r.reason, r.recorded_at_exact
                FROM (SELECT occurrence_id, planned_at, snapshot FROM occurrences WHERE is_test = 1
                    ORDER BY planned_at DESC, occurrence_id DESC LIMIT 20 OFFSET ?) o
                LEFT JOIN scheduling_attempts a ON a.sequence =
                    (SELECT MAX(latest.sequence) FROM scheduling_attempts latest
                        WHERE latest.occurrence_id = o.occurrence_id)
                LEFT JOIN scheduling_receipts r ON r.attempt_id = a.attempt_id
                ORDER BY o.planned_at DESC, o.occurrence_id DESC""",
                arrayOf(offset.toString())).use { cursor -> buildList {
                while (cursor.moveToNext()) {
                    val attempt = cursor.nullableString("attempt_id")?.let { id -> SchedulingAttempt(
                        id, cursor.string("occurrence_id"), cursor.long("revision"), cursor.long("generation"),
                        Instant.ofEpochMilli(cursor.long("trigger_at")), Instant.parse(cursor.string("started_at_exact")),
                        if (cursor.nullableString("outcome") == null) null else receipt(cursor)) }
                    add(TestAlarmHistoryItem(OccurrenceCodec.decode(cursor.string("snapshot")),
                        attempt, cursor.long("delivery_allowed") == 1L))
                }
            } }
            TestAlarmHistoryPage(items, epoch, pausedCount)
        }
    }

    /** Test alarms only; spans midnight/zone changes without altering personal history. */
    fun openTests(): List<RoutineOccurrence> = helper.readableDatabase.query(
        "occurrences", arrayOf("snapshot"), "is_test = 1 AND status IN (?, ?, ?)",
        arrayOf("SCHEDULED", "SNOOZED", "TRIGGERED"), null, null,
        "current_trigger_at ASC, occurrence_id ASC",
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(OccurrenceCodec.decode(cursor.getString(0)))
    } }

    /** Durable scheduling intent. Recovery never changes status or creates journal actions. */
    fun futureTests(after: Instant): List<RoutineOccurrence> = helper.readableDatabase.query(
        "occurrences", arrayOf("snapshot"),
        "is_test = 1 AND status IN (?, ?) AND current_trigger_at > ?",
        arrayOf("SCHEDULED", "SNOOZED", after.toEpochMilli().toString()), null, null,
        "current_trigger_at ASC, occurrence_id ASC",
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(OccurrenceCodec.decode(cursor.getString(0)))
    } }

    /** Bounded recovery page. Equal trigger times use the stable occurrence ID as tie-breaker.
     * Current state is checked again before the external effect. Changes behind this cursor
     * are handled by their immediate action or a new recovery cycle, never by list offsets.
     */
    fun futureTestsPage(after: Instant, afterTriggerMillis: Long? = null, afterId: String? = null,
        limit: Int = 64): List<RoutineOccurrence> =
        futureOccurrencesPage(OccurrenceScope.Tests, after, afterTriggerMillis, afterId, limit)

    fun futureOccurrencesPage(scope: OccurrenceScope, after: Instant, afterTriggerMillis: Long? = null,
        afterId: String? = null, limit: Int = 64): List<RoutineOccurrence> {
        require(limit in 1..64)
        require((afterTriggerMillis == null) == (afterId == null))
        val args = mutableListOf<String>()
        val scopeSelection = when (scope) {
            OccurrenceScope.Tests -> "is_test = 1"
            is OccurrenceScope.PersonalEvent -> {
                args.add(scope.eventId)
                "is_test = 0 AND event_id = ?"
            }
        }
        args.addAll(listOf("SCHEDULED", "SNOOZED", after.toEpochMilli().toString()))
        var selection = "$scopeSelection AND status IN (?, ?) AND current_trigger_at > ? " +
            "AND NOT EXISTS (SELECT 1 FROM restored_occurrences r WHERE r.occurrence_id = occurrences.occurrence_id) " +
            "AND NOT EXISTS (SELECT 1 FROM personal_plan_retirements p WHERE p.occurrence_id = occurrences.occurrence_id)"
        if (afterTriggerMillis != null) {
            selection += " AND (current_trigger_at > ? OR (current_trigger_at = ? AND occurrence_id > ?))"
            args.add(afterTriggerMillis.toString()); args.add(afterTriggerMillis.toString()); args.add(afterId!!)
        }
        return helper.readableDatabase.query("occurrences", arrayOf("snapshot"), selection,
            args.toTypedArray(), null, null, "current_trigger_at ASC, occurrence_id ASC", limit.toString()
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) add(OccurrenceCodec.decode(cursor.getString(0)))
        } }
    }

    fun recentTests(offset: Int = 0): List<RoutineOccurrence> {
        require(offset >= 0)
        return helper.readableDatabase.query(
        "occurrences", arrayOf("snapshot"), "is_test = 1", null, null, null,
        "planned_at DESC, occurrence_id DESC", "$offset,20",
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(OccurrenceCodec.decode(cursor.getString(0)))
    } }
    }

    override fun listForDate(date: LocalDate, zone: ZoneId, includeTests: Boolean): List<RoutineOccurrence> {
        val filter = "nominal_date = ? AND zone_id = ?" + if (includeTests) "" else " AND is_test = 0"
        return helper.readableDatabase.query(
            "occurrences", arrayOf("snapshot"), filter, arrayOf(date.toString(), zone.id),
            null, null, "planned_at ASC, occurrence_id ASC",
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) add(OccurrenceCodec.decode(cursor.getString(0)))
        } }
    }

    override fun apply(action: ExecutionAction): ActionResult = transaction { db ->
        // Structural errors cannot enter the journal: otherwise an external action
        // could reserve the internal SCHEDULE key of a future occurrence.
        require(action.id.isNotBlank() && !action.id.startsWith("schedule:")) {
            "Action IDs must be nonblank and outside the reserved schedule namespace"
        }
        try {
            action.occurredAt.toEpochMilli()
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException("Action time must fit UTC epoch milliseconds", error)
        }
        // Check before reading current state: a replay returns the historical decision even after later actions.
        db.query("execution_journal", null, "action_id = ?", arrayOf(action.id), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) {
                val savedAction = cursor.nullableString("action_snapshot")
                if (savedAction == null || OccurrenceCodec.decodeAction(savedAction) != action) {
                    throw ActionIdConflict(action.id)
                }
                return@transaction ActionResult(
                    Outcome.valueOf(cursor.string("outcome")),
                    OccurrenceCodec.decode(cursor.string("result_snapshot")),
                    cursor.nullableString("result_reason"), duplicate = true,
                )
            }
        }
        val current = requireNotNull(get(db, action.occurrenceId)) { "Unknown occurrence: ${action.occurrenceId}" }
        // Recheck persisted quarantine and retirement in the same transaction as the write.
        // Historical replay above remains readable without granting a new execution effect.
        check(deliveryAllowed(db, current.id)) { "This occurrence is paused after restore or retired by a newer plan" }
        val transition = OccurrenceTransitions.apply(current, action)
        val result = ActionResult(transition.outcome, transition.occurrence, transition.reason)
        if (result.outcome == Outcome.APPLIED) {
            val updated = db.update(
                "occurrences", values(result.occurrence),
                "occurrence_id = ? AND revision = ? AND generation = ?",
                arrayOf(current.id, current.revision.toString(), current.generation.toString()),
            )
            check(updated == 1) { "Concurrent occurrence projection changed inside a transaction" }
        }
        journal(db, action.id, current, result, action)
        result
    }

    override fun history(id: String): List<JournalEntry> = helper.readableDatabase.query(
        "execution_journal", null, "occurrence_id = ?", arrayOf(id), null, null, "sequence ASC",
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(JournalEntry(
            actionId = cursor.string("action_id"), occurrenceId = cursor.string("occurrence_id"),
            actionType = cursor.string("action_type"), actor = Actor.valueOf(cursor.string("actor")),
            occurredAt = Instant.parse(cursor.string("occurred_at_exact")),
            recordedAt = Instant.parse(cursor.string("recorded_at_exact")),
            expectedRevision = cursor.long("expected_revision"), expectedGeneration = cursor.long("expected_generation"),
            resultingRevision = cursor.long("resulting_revision"),
            previousStatus = cursor.nullableString("previous_status")?.let(OccurrenceStatus::valueOf),
            resultingStatus = OccurrenceStatus.valueOf(cursor.string("resulting_status")),
            generation = cursor.long("generation"), snoozeMinutes = cursor.nullableInt("snooze_minutes"),
            source = cursor.string("source"), reason = cursor.nullableString("reason"),
            outcome = Outcome.valueOf(cursor.string("outcome")),
        ))
    } }

    /** A successful return means the attempt commit completed before any platform effect. */
    fun beginSchedulingAttempt(expected: RoutineOccurrence, futureOnly: Boolean): SchedulingAttempt? =
        beginSchedulingAttempt(expected, futureOnly, OccurrenceScope.Tests)

    fun beginSchedulingAttempt(expected: RoutineOccurrence, futureOnly: Boolean,
        scope: OccurrenceScope): SchedulingAttempt? = transaction { db ->
        check(deliveryAllowed(db, expected.id)) { "Dieses Ereignis ist für die Zustellung gesperrt." }
        val current = get(db, expected.id)
        check(current == expected && scope.accepts(current) && current.status in
            setOf(OccurrenceStatus.SCHEDULED, OccurrenceStatus.SNOOZED)) { "Dieses Ereignis wurde geändert oder gehört nicht zum ausgewählten Bereich." }
        val triggerMillis = expected.currentTriggerAt.toEpochMilli()
        val now = clock.instant()
        val nowMillis = now.toEpochMilli()
        if (futureOnly && triggerMillis <= nowMillis) return@transaction null
        val attempt = SchedulingAttempt(UUID.randomUUID().toString(), expected.id, expected.revision,
            expected.generation, Instant.ofEpochMilli(triggerMillis), now)
        db.insertOrThrow("scheduling_attempts", null, ContentValues().apply {
            put("attempt_id", attempt.id); put("occurrence_id", attempt.occurrenceId)
            put("revision", attempt.revision); put("generation", attempt.generation)
            put("trigger_at", triggerMillis); put("started_at_exact", now.toString())
        })
        attempt
    }

    /** Idempotent receipt replay cannot rewrite an existing observation. */
    fun recordSchedulingReceipt(attemptId: String, outcome: SchedulingOutcome,
        reason: SchedulingReason): SchedulingReceipt = transaction { db ->
        require(when (outcome) {
            SchedulingOutcome.CALL_RETURNED -> reason == SchedulingReason.RETURNED
            SchedulingOutcome.CALL_FAILED -> reason in setOf(SchedulingReason.PLATFORM_EXCEPTION, SchedulingReason.PERMISSION_DENIED)
            SchedulingOutcome.NOT_CALLED -> reason in setOf(SchedulingReason.PERMISSION_DENIED, SchedulingReason.STALE, SchedulingReason.EXPIRED)
        }) { "Inconsistent scheduling receipt" }
        db.query("scheduling_receipts", null, "attempt_id = ?", arrayOf(attemptId), null, null, null).use {
            if (it.moveToFirst()) {
                val previous = receipt(it)
                check(previous.outcome == outcome && previous.reason == reason) { "Scheduling receipt cannot be rewritten" }
                return@transaction previous
            }
        }
        val value = SchedulingReceipt(outcome, reason, clock.instant())
        value.recordedAt.toEpochMilli()
        db.insertOrThrow("scheduling_receipts", null, ContentValues().apply {
            put("attempt_id", attemptId); put("outcome", outcome.name); put("reason", reason.name)
            put("recorded_at_exact", value.recordedAt.toString())
        })
        value
    }

    fun schedulingAttempts(occurrenceId: String, latestOnly: Boolean = false): List<SchedulingAttempt> =
        helper.readableDatabase.rawQuery("""SELECT a.*, r.outcome, r.reason, r.recorded_at_exact
            FROM scheduling_attempts a LEFT JOIN scheduling_receipts r ON a.attempt_id = r.attempt_id
            WHERE a.occurrence_id = ? ORDER BY a.sequence ${if (latestOnly) "DESC LIMIT 1" else "ASC"}""",
            arrayOf(occurrenceId)).use { cursor -> buildList {
            while (cursor.moveToNext()) add(SchedulingAttempt(
                cursor.string("attempt_id"), cursor.string("occurrence_id"), cursor.long("revision"), cursor.long("generation"),
                Instant.ofEpochMilli(cursor.long("trigger_at")), Instant.parse(cursor.string("started_at_exact")),
                if (cursor.nullableString("outcome") == null) null else receipt(cursor)))
        } }

    fun schedulingWatermark(): Long = helper.readableDatabase.rawQuery(
        "SELECT COALESCE(MAX(sequence), 0) FROM scheduling_attempts", null,
    ).use { check(it.moveToFirst()); it.getLong(0) }

    /** Fixed high-watermark, stable identity cursor, bounded Kotlin list. The first
     * attempt defines the cursor regardless of receipt; any possible effect qualifies.
     * Existing sequence/occurrence indexes avoid regrouping the entire history per page.
     * Attempts lack epoch provenance; only current local identities are reconstructed.
     */
    fun knownTestDeliveries(through: Long, after: Long, occurrenceId: String? = null): List<KnownTestDelivery> =
        knownDeliveries(OccurrenceScope.Tests, through, after, occurrenceId)

    fun knownDeliveries(scope: OccurrenceScope, through: Long, after: Long,
        occurrenceId: String? = null): List<KnownOccurrenceDelivery> {
        require(through >= 0 && after >= 0)
        val epoch = deliveryEpoch()
        val args = mutableListOf<String>()
        val scopeSelection = when (scope) {
            OccurrenceScope.Tests -> "o.is_test = 1"
            is OccurrenceScope.PersonalEvent -> {
                args.add(scope.eventId)
                "o.is_test = 0 AND o.event_id = ?"
            }
        }
        args.addAll(listOf(after.toString(), through.toString()))
        if (occurrenceId != null) args.add(occurrenceId)
        args.add(through.toString())
        return helper.readableDatabase.rawQuery("""SELECT a.sequence AS first_sequence,
            a.occurrence_id, a.generation, a.revision
            FROM scheduling_attempts a JOIN occurrences o ON a.occurrence_id = o.occurrence_id
            WHERE $scopeSelection AND a.sequence > ? AND a.sequence <= ?
            ${if (occurrenceId == null) "" else "AND a.occurrence_id = ?"}
            AND NOT EXISTS (SELECT 1 FROM scheduling_attempts earlier
                WHERE earlier.occurrence_id = a.occurrence_id AND earlier.generation = a.generation
                AND earlier.revision = a.revision AND earlier.sequence < a.sequence)
            AND EXISTS (SELECT 1 FROM scheduling_attempts possible
                LEFT JOIN scheduling_receipts r ON possible.attempt_id = r.attempt_id
                WHERE possible.occurrence_id = a.occurrence_id AND possible.generation = a.generation
                AND possible.revision = a.revision AND possible.sequence <= ?
                AND (r.outcome IS NULL OR r.outcome != 'NOT_CALLED'))
            ORDER BY a.sequence ASC LIMIT 64""", args.toTypedArray()).use { cursor -> buildList {
                while (cursor.moveToNext()) add(KnownOccurrenceDelivery(cursor.long("first_sequence"),
                    AlarmDelivery(cursor.string("occurrence_id"), cursor.long("generation"), cursor.long("revision"), epoch)))
            } }
    }

    private fun receipt(cursor: Cursor) = SchedulingReceipt(SchedulingOutcome.valueOf(cursor.string("outcome")),
        SchedulingReason.valueOf(cursor.string("reason")), Instant.parse(cursor.string("recorded_at_exact")))

    override fun close() = helper.close()

    /** Keep related SELECTs on one connection and snapshot; never invoke the mutation hook. */
    private fun <T> readSnapshot(block: (SQLiteDatabase) -> T): T {
        val db = helper.readableDatabase
        db.beginTransactionNonExclusive()
        try {
            val result = block(db)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val result = block(db)
            beforeCommit()
            db.setTransactionSuccessful()
            // endTransaction performs the commit and may throw. Never return success before it.
            return result
        } finally {
            db.endTransaction()
        }
    }

    private fun get(db: SQLiteDatabase, id: String): RoutineOccurrence? = db.query(
        "occurrences", arrayOf("snapshot"), "occurrence_id = ?", arrayOf(id), null, null, null,
    ).use { if (it.moveToFirst()) OccurrenceCodec.decode(it.getString(0)) else null }

    private fun freezeZone(db: SQLiteDatabase, occurrence: RoutineOccurrence) {
        val existing = db.query("schedule_revisions", arrayOf("zone_id"), "revision_id = ?",
            arrayOf(occurrence.scheduleRevision), null, null, null).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        if (existing == null) {
            db.insertOrThrow("schedule_revisions", null, ContentValues().apply {
                put("revision_id", occurrence.scheduleRevision)
                put("zone_id", occurrence.zoneId)
                put("created_at", clock.instant().toEpochMilli())
            })
        } else {
            require(existing == occurrence.zoneId) { "A zone change needs an explicit new schedule revision" }
        }
    }

    private fun requireInitial(occurrence: RoutineOccurrence) {
        require(listOf(occurrence.id, occurrence.eventId, occurrence.scheduleRevision, occurrence.traceId).all { it.isNotBlank() }) {
            "Occurrence, rule, revision and trace IDs are required"
        }
        require(occurrence.status == OccurrenceStatus.SCHEDULED && occurrence.revision == 0L &&
            occurrence.generation == 0L && occurrence.snoozeCount == 0 && occurrence.totalSnoozeMinutes == 0 &&
            occurrence.currentTriggerAt == occurrence.plannedAt) { "Materialization requires an initial scheduled occurrence" }
    }

    private fun sameIdentity(left: RoutineOccurrence, right: RoutineOccurrence): Boolean =
        left.id == right.id && left.eventId == right.eventId && left.scheduleRevision == right.scheduleRevision &&
            left.nominalDate == right.nominalDate && left.nominalTime == right.nominalTime &&
            left.zoneId == right.zoneId && left.offsetSeconds == right.offsetSeconds &&
            left.plannedAt == right.plannedAt && left.isTest == right.isTest

    private fun values(occurrence: RoutineOccurrence) = ContentValues().apply {
        put("occurrence_id", occurrence.id)
        put("event_id", occurrence.eventId)
        put("schedule_revision", occurrence.scheduleRevision)
        put("nominal_date", occurrence.nominalDate.toString())
        put("nominal_time", occurrence.nominalTime.toString())
        put("zone_id", occurrence.zoneId)
        put("offset_seconds", occurrence.offsetSeconds)
        put("planned_at", occurrence.plannedAt.toEpochMilli())
        put("current_trigger_at", occurrence.currentTriggerAt.toEpochMilli())
        put("status", occurrence.status.name)
        put("generation", occurrence.generation)
        put("revision", occurrence.revision)
        put("snooze_count", occurrence.snoozeCount)
        put("total_snooze_minutes", occurrence.totalSnoozeMinutes)
        put("last_updated_at", occurrence.lastUpdatedAt.toEpochMilli())
        put("is_test", if (occurrence.isTest) 1 else 0)
        put("snapshot", OccurrenceCodec.encode(occurrence))
    }

    private fun journal(db: SQLiteDatabase, actionId: String, current: RoutineOccurrence?,
                        result: ActionResult, action: ExecutionAction?) {
        val occurrence = result.occurrence
        val occurredAt = action?.occurredAt ?: occurrence.lastUpdatedAt
        val recordedAt = clock.instant()
        db.insertOrThrow("execution_journal", null, ContentValues().apply {
            put("action_id", actionId)
            put("occurrence_id", occurrence.id)
            put("action_type", action?.type?.name ?: "SCHEDULE")
            put("actor", action?.actor?.name ?: Actor.SYSTEM.name)
            put("occurred_at", occurredAt.toEpochMilli())
            put("recorded_at", recordedAt.toEpochMilli())
            put("occurred_at_exact", occurredAt.toString())
            put("recorded_at_exact", recordedAt.toString())
            put("expected_revision", action?.expectedRevision ?: 0L)
            put("expected_generation", action?.expectedGeneration ?: 0L)
            put("resulting_revision", occurrence.revision)
            put("previous_status", current?.status?.name)
            put("resulting_status", occurrence.status.name)
            put("generation", occurrence.generation)
            put("snooze_minutes", action?.snoozeMinutes)
            put("source", action?.source ?: "occurrence-planner")
            put("reason", result.reason ?: action?.reason)
            put("outcome", result.outcome.name)
            put("action_snapshot", action?.let(OccurrenceCodec::encode))
            put("result_snapshot", OccurrenceCodec.encode(occurrence))
            put("result_reason", result.reason)
        })
    }
}

/** Schema 5 adds personal authority and monotonic retirement without changing execution evidence. */
private class OccurrenceDatabase(context: Context, name: String) : SQLiteOpenHelper(
    context, name, null, 5,
    DatabaseErrorHandler { throw SQLiteException("Occurrence database is corrupt; original file retained") },
) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.rawQuery("PRAGMA busy_timeout = 10000", null).use { it.moveToFirst() }
    }

    override fun onCreate(db: SQLiteDatabase) {
        val existing = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'", null)
            .use { it.count }
        check(existing == 0) { "Unknown unversioned database; refusing to replace user data" }
        SCHEMA.values.forEach(db::execSQL)
        initializeMetadata(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..4 && newVersion == 5) { "Unsupported occurrence database upgrade $oldVersion -> $newVersion; no data was deleted" }
        // SQLiteOpenHelper wraps onUpgrade and its version change in one transaction.
        validate(db, when (oldVersion) { 1 -> V1_SCHEMA; 2 -> V2_SCHEMA; 3 -> V3_SCHEMA; else -> V4_SCHEMA })
        if (oldVersion == 1) SCHEDULING_SCHEMA.values.forEach(db::execSQL)
        if (oldVersion < 3) {
            RESTORE_SCHEMA.values.forEach(db::execSQL)
            initializeMetadata(db)
        }
        if (oldVersion < 4) RECONCILIATION_SCHEMA.values.forEach(db::execSQL)
        PersonalPlanPersistence.schema.values.forEach(db::execSQL)
        validate(db, SCHEMA)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported occurrence database downgrade $oldVersion -> $newVersion; no data was deleted")
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        validate(db, SCHEMA)
        db.rawQuery("SELECT singleton, delivery_epoch FROM store_metadata", null).use {
            check(it.moveToFirst() && it.getInt(0) == 1)
            val epoch = it.getString(1)
            check(epoch.isEmpty() || runCatching { UUID.fromString(epoch).toString() == epoch }.getOrDefault(false))
            check(!it.moveToNext()) { "Invalid store metadata" }
        }
    }

    private fun initializeMetadata(db: SQLiteDatabase) {
        // Existing alpha05 tokens have no epoch. Only an explicit import rotates it.
        db.execSQL("INSERT INTO store_metadata(singleton, delivery_epoch) VALUES (1, '')")
    }

    private fun validate(db: SQLiteDatabase, expected: Map<String, String>) {
        val found = mutableMapOf<String, String>()
        db.rawQuery("SELECT name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name != 'android_metadata'", null).use {
            while (it.moveToNext()) found[it.getString(0)] = normalize(it.getString(1))
        }
        check(found == expected.mapValues { normalize(it.value) }) { "Unsupported or damaged occurrence database schema; no reset performed" }
        db.rawQuery("PRAGMA quick_check", null).use {
            check(it.moveToFirst() && it.getString(0) == "ok" && !it.moveToNext()) { "Occurrence database integrity check failed" }
        }
        db.rawQuery("PRAGMA foreign_key_check", null).use {
            check(!it.moveToFirst()) { "Occurrence database has broken foreign keys" }
        }
    }

    private fun normalize(sql: String): String = sql.trim().replace(Regex("\\s+"), " ")

    companion object {
        private val V1_SCHEMA = linkedMapOf(
            "schedule_revisions" to """CREATE TABLE schedule_revisions (
                revision_id TEXT PRIMARY KEY NOT NULL, zone_id TEXT NOT NULL, created_at INTEGER NOT NULL,
                UNIQUE(revision_id, zone_id)
            )""",
            "occurrences" to """CREATE TABLE occurrences (
                occurrence_id TEXT PRIMARY KEY NOT NULL, event_id TEXT NOT NULL, schedule_revision TEXT NOT NULL,
                nominal_date TEXT NOT NULL, nominal_time TEXT NOT NULL, zone_id TEXT NOT NULL,
                offset_seconds INTEGER NOT NULL, planned_at INTEGER NOT NULL,
                current_trigger_at INTEGER NOT NULL,
                status TEXT NOT NULL CHECK(status IN ('SCHEDULED','TRIGGERED','SNOOZED','DONE','SKIPPED','MISSED')),
                generation INTEGER NOT NULL CHECK(generation >= 0), revision INTEGER NOT NULL CHECK(revision >= 0),
                snooze_count INTEGER NOT NULL CHECK(snooze_count >= 0),
                total_snooze_minutes INTEGER NOT NULL CHECK(total_snooze_minutes >= 0),
                last_updated_at INTEGER NOT NULL, is_test INTEGER NOT NULL CHECK(is_test IN (0,1)), snapshot TEXT NOT NULL,
                UNIQUE(event_id, schedule_revision, nominal_date, zone_id, planned_at, is_test),
                FOREIGN KEY(schedule_revision, zone_id) REFERENCES schedule_revisions(revision_id, zone_id)
            )""",
            "execution_journal" to """CREATE TABLE execution_journal (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT, action_id TEXT NOT NULL UNIQUE,
                occurrence_id TEXT NOT NULL REFERENCES occurrences(occurrence_id),
                action_type TEXT NOT NULL CHECK(action_type IN ('SCHEDULE','TRIGGER','DONE','SKIP','SNOOZE','MISS')),
                actor TEXT NOT NULL CHECK(actor IN ('USER','SYSTEM')),
                occurred_at INTEGER NOT NULL, recorded_at INTEGER NOT NULL,
                occurred_at_exact TEXT NOT NULL, recorded_at_exact TEXT NOT NULL,
                expected_revision INTEGER NOT NULL, expected_generation INTEGER NOT NULL,
                resulting_revision INTEGER NOT NULL, previous_status TEXT, resulting_status TEXT NOT NULL,
                generation INTEGER NOT NULL, snooze_minutes INTEGER, source TEXT NOT NULL, reason TEXT,
                outcome TEXT NOT NULL CHECK(outcome IN ('APPLIED','REJECTED','CONFLICT')),
                action_snapshot TEXT, result_snapshot TEXT NOT NULL, result_reason TEXT
            )""",
            "occurrences_day" to "CREATE INDEX occurrences_day ON occurrences(nominal_date, zone_id, is_test, planned_at)",
            "occurrences_due" to "CREATE INDEX occurrences_due ON occurrences(status, current_trigger_at)",
            "journal_occurrence" to "CREATE INDEX journal_occurrence ON execution_journal(occurrence_id, sequence)",
            "journal_no_update" to """CREATE TRIGGER journal_no_update BEFORE UPDATE ON execution_journal
                BEGIN SELECT RAISE(ABORT, 'Execution journal is append-only'); END""",
            "journal_no_delete" to """CREATE TRIGGER journal_no_delete BEFORE DELETE ON execution_journal
                BEGIN SELECT RAISE(ABORT, 'Execution journal is append-only'); END""",
        )
        private val SCHEDULING_SCHEMA = linkedMapOf(
            "scheduling_attempts" to """CREATE TABLE scheduling_attempts (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT, attempt_id TEXT NOT NULL UNIQUE,
                occurrence_id TEXT NOT NULL REFERENCES occurrences(occurrence_id),
                revision INTEGER NOT NULL CHECK(revision >= 0), generation INTEGER NOT NULL CHECK(generation >= 0),
                trigger_at INTEGER NOT NULL, started_at_exact TEXT NOT NULL
            )""",
            "scheduling_receipts" to """CREATE TABLE scheduling_receipts (
                attempt_id TEXT PRIMARY KEY NOT NULL REFERENCES scheduling_attempts(attempt_id),
                outcome TEXT NOT NULL CHECK(outcome IN ('CALL_RETURNED','CALL_FAILED','NOT_CALLED')),
                reason TEXT NOT NULL CHECK(reason IN ('RETURNED','PLATFORM_EXCEPTION','PERMISSION_DENIED','STALE','EXPIRED')),
                recorded_at_exact TEXT NOT NULL
            )""",
            "scheduling_occurrence" to "CREATE INDEX scheduling_occurrence ON scheduling_attempts(occurrence_id, sequence)",
            "attempts_no_update" to """CREATE TRIGGER attempts_no_update BEFORE UPDATE ON scheduling_attempts
                BEGIN SELECT RAISE(ABORT, 'Scheduling attempts are append-only'); END""",
            "attempts_no_delete" to """CREATE TRIGGER attempts_no_delete BEFORE DELETE ON scheduling_attempts
                BEGIN SELECT RAISE(ABORT, 'Scheduling attempts are append-only'); END""",
            "receipts_no_update" to """CREATE TRIGGER receipts_no_update BEFORE UPDATE ON scheduling_receipts
                BEGIN SELECT RAISE(ABORT, 'Scheduling receipts are append-only'); END""",
            "receipts_no_delete" to """CREATE TRIGGER receipts_no_delete BEFORE DELETE ON scheduling_receipts
                BEGIN SELECT RAISE(ABORT, 'Scheduling receipts are append-only'); END""",
        )
        private val RESTORE_SCHEMA = linkedMapOf(
            "store_metadata" to """CREATE TABLE store_metadata (
                singleton INTEGER PRIMARY KEY CHECK(singleton = 1), delivery_epoch TEXT NOT NULL
            )""",
            "restored_occurrences" to """CREATE TABLE restored_occurrences (
                occurrence_id TEXT PRIMARY KEY NOT NULL REFERENCES occurrences(occurrence_id)
            )""",
        )
        private val V2_SCHEMA = V1_SCHEMA + SCHEDULING_SCHEMA
        private val V3_SCHEMA = V2_SCHEMA + RESTORE_SCHEMA
        private val RECONCILIATION_SCHEMA = linkedMapOf(
            "scheduling_identity" to "CREATE INDEX scheduling_identity ON scheduling_attempts(occurrence_id, generation, revision, sequence)",
        )
        private val V4_SCHEMA = V3_SCHEMA + RECONCILIATION_SCHEMA
        private val SCHEMA = V4_SCHEMA + PersonalPlanPersistence.schema
    }
}

private fun Cursor.string(name: String): String = getString(getColumnIndexOrThrow(name))
private fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
private fun Cursor.nullableString(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
private fun Cursor.nullableInt(name: String): Int? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getInt(it) }
