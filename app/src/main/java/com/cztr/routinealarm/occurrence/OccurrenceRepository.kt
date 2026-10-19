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

    override fun materialize(items: List<RoutineOccurrence>): Int = transaction { db ->
        var inserted = 0
        items.forEach { occurrence ->
            requireInitial(occurrence)
            freezeZone(db, occurrence)
            val existing = get(db, occurrence.id)
            if (existing != null) {
                require(sameIdentity(existing, occurrence)) { "Occurrence ID reused with a different schedule identity" }
            } else {
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

    fun recentTests(): List<RoutineOccurrence> = helper.readableDatabase.query(
        "occurrences", arrayOf("snapshot"), "is_test = 1", null, null, null,
        "planned_at DESC, occurrence_id DESC", "20",
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(OccurrenceCodec.decode(cursor.getString(0)))
    } }

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
    fun beginSchedulingAttempt(expected: RoutineOccurrence, futureOnly: Boolean): SchedulingAttempt? = transaction { db ->
        val current = get(db, expected.id)
        check(current == expected && current.isTest && current.status in
            setOf(OccurrenceStatus.SCHEDULED, OccurrenceStatus.SNOOZED)) { "Dieser Testwecker wurde bereits geändert." }
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

    private fun receipt(cursor: Cursor) = SchedulingReceipt(SchedulingOutcome.valueOf(cursor.string("outcome")),
        SchedulingReason.valueOf(cursor.string("reason")), Instant.parse(cursor.string("recorded_at_exact")))

    override fun close() = helper.close()

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

/** Schema 2 adds scheduling observations; the occurrence projection and execution journal remain unchanged. */
private class OccurrenceDatabase(context: Context, name: String) : SQLiteOpenHelper(
    context, name, null, 2,
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2) { "Unsupported occurrence database upgrade $oldVersion -> $newVersion; no data was deleted" }
        // SQLiteOpenHelper wraps onUpgrade and its version change in one transaction.
        validate(db, V1_SCHEMA)
        SCHEDULING_SCHEMA.values.forEach(db::execSQL)
        validate(db, SCHEMA)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported occurrence database downgrade $oldVersion -> $newVersion; no data was deleted")
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        validate(db, SCHEMA)
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
        private val SCHEMA = V1_SCHEMA + SCHEDULING_SCHEMA
    }
}

private fun Cursor.string(name: String): String = getString(getColumnIndexOrThrow(name))
private fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
private fun Cursor.nullableString(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
private fun Cursor.nullableInt(name: String): Int? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getInt(it) }
