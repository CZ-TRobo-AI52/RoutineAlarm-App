package com.cztr.routinealarm.occurrence

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.cztr.routinealarm.*
import java.time.DayOfWeek
import java.util.Collections
import org.json.JSONObject

/** Definition data only. Saving never grants activation or invokes a device effect. */
data class PersonalRoutineRecord(val event: RoutineEvent, val version: Long, val planVersion: Long,
    val restoreEpoch: String, val quarantined: Boolean)
data class PersonalRoutineCatalog(val definitions: List<PersonalRoutineRecord>, val restoreEpoch: String)
data class PersonalRoutineSaveRequest(val stableRequestId: String, val event: RoutineEvent,
    val expectedVersion: Long, val expectedPlanVersion: Long, val expectedRestoreEpoch: String)
sealed class PersonalRoutineSaveResult {
    data class Saved(val record: PersonalRoutineRecord, val duplicate: Boolean = false) : PersonalRoutineSaveResult()
    data class Conflict(val actual: PersonalRoutineRecord?, val reason: String) : PersonalRoutineSaveResult()
}

/** Uses its caller's existing transaction and database; append-only definition revisions. */
internal object PersonalRoutinePersistence {
    fun encode(event: RoutineEvent): String = JSONObject().apply {
        put("id", event.id); put("days", event.daysOfWeek.sortedBy { it.value }.joinToString(",") { it.value.toString() })
        put("hour", event.hour); put("minute", event.minute); put("title", event.title)
        put("spokenText", event.spokenText); put("category", event.category.name); put("mode", event.mode.name)
        put("enabled", event.enabled)
    }.toString()
    fun decode(raw: String): RoutineEvent = JSONObject(raw).let { j ->
        val event = RoutineEvent(j.getString("id"), Collections.unmodifiableSet(j.getString("days")
            .split(',').map { DayOfWeek.of(it.toInt()) }.toSet()), j.getInt("hour"), j.getInt("minute"),
            j.getString("title"), j.getString("spokenText"), EventCategory.valueOf(j.getString("category")),
            EventMode.valueOf(j.getString("mode")), j.getBoolean("enabled"))
        require(encode(event) == raw) { "Noncanonical routine definition" }; event
    }
    fun payload(request: PersonalRoutineSaveRequest): String = JSONObject().apply {
        put("event", encode(request.event)); put("version", request.expectedVersion)
        put("plan", request.expectedPlanVersion); put("epoch", request.expectedRestoreEpoch)
    }.toString()
    fun epoch(db: SQLiteDatabase): String = db.rawQuery(
        "SELECT delivery_epoch FROM store_metadata WHERE singleton = 1", null).use { check(it.moveToFirst()); it.getString(0) }
    fun read(db: SQLiteDatabase, eventId: String): PersonalRoutineRecord? = db.rawQuery(
        "SELECT snapshot, version, plan_version, restore_epoch FROM personal_routine_definitions WHERE event_id = ? ORDER BY version DESC LIMIT 1",
        arrayOf(eventId)).use { c -> if (!c.moveToFirst()) null else PersonalRoutineRecord(decode(c.getString(0)),
            c.getLong(1), c.getLong(2), epoch(db), c.getString(3) != epoch(db)) }
    fun list(db: SQLiteDatabase): List<PersonalRoutineRecord> = db.rawQuery(
        "SELECT DISTINCT event_id FROM personal_routine_definitions ORDER BY event_id", null).use { c ->
        buildList { while (c.moveToNext()) add(requireNotNull(read(db, c.getString(0)))) }
    }
    fun duplicate(db: SQLiteDatabase, request: PersonalRoutineSaveRequest): PersonalRoutineRecord? = db.rawQuery(
        "SELECT snapshot, version, plan_version, restore_epoch, request_payload FROM personal_routine_definitions WHERE request_id = ?",
        arrayOf(request.stableRequestId)).use { c ->
        if (!c.moveToFirst()) null else {
            require(c.getString(4) == payload(request)) { "Stable request ID reused with different content" }
            PersonalRoutineRecord(decode(c.getString(0)), c.getLong(1), c.getLong(2), epoch(db), c.getString(3) != epoch(db))
        }
    }
    fun insert(db: SQLiteDatabase, request: PersonalRoutineSaveRequest, record: PersonalRoutineRecord) {
        db.insertOrThrow("personal_routine_definitions", null, ContentValues().apply {
            put("event_id", record.event.id); put("version", record.version); put("plan_version", record.planVersion)
            put("snapshot", encode(record.event)); put("request_id", request.stableRequestId)
            put("request_payload", payload(request)); put("restore_epoch", record.restoreEpoch)
        })
    }
    val schema = linkedMapOf(
        "personal_routine_definitions" to """CREATE TABLE personal_routine_definitions (
            event_id TEXT NOT NULL, version INTEGER NOT NULL CHECK(version > 0),
            plan_version INTEGER NOT NULL CHECK(plan_version > 0), snapshot TEXT NOT NULL,
            request_id TEXT NOT NULL UNIQUE, request_payload TEXT NOT NULL, restore_epoch TEXT NOT NULL,
            PRIMARY KEY(event_id, version),
            FOREIGN KEY(event_id, plan_version) REFERENCES personal_plan_commits(event_id, version)
        )""",
        "routine_definitions_no_update" to """CREATE TRIGGER routine_definitions_no_update BEFORE UPDATE ON personal_routine_definitions
            BEGIN SELECT RAISE(ABORT, 'Routine definitions are append-only'); END""",
        "routine_definitions_no_delete" to """CREATE TRIGGER routine_definitions_no_delete BEFORE DELETE ON personal_routine_definitions
            BEGIN SELECT RAISE(ABORT, 'Routine definitions are append-only'); END""",
    )
}
