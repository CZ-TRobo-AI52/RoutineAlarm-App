package com.cztr.routinealarm.occurrence

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.time.Instant
import java.util.Collections

/** Uses only the repository's transaction connection; no independent writer. */
internal object PersonalPlanPersistence {
    fun read(db: SQLiteDatabase, eventId: String): PersonalPlanAuthority {
        var version = 0L
        var active: String? = null
        val bindings = linkedMapOf<String, String>()
        db.rawQuery("SELECT version, plan_revision, fingerprint FROM personal_plan_commits WHERE event_id = ? ORDER BY version",
            arrayOf(eventId)).use {
            while (it.moveToNext()) {
                check(it.getLong(0) == Math.addExact(version, 1)) { "Personal plan history is incomplete" }
                version = it.getLong(0)
                active = it.getString(1)
                check(bindings.put(active!!, it.getString(2)) == null) { "Duplicated plan revision" }
            }
        }
        val retired = linkedSetOf<String>()
        db.rawQuery("SELECT occurrence_id FROM personal_plan_retirements WHERE event_id = ?",
            arrayOf(eventId)).use { while (it.moveToNext()) retired.add(it.getString(0)) }
        return PersonalPlanAuthority(version, active, Collections.unmodifiableMap(bindings),
            Collections.unmodifiableSet(retired), if (version == 0L) null else eventId)
    }

    fun isRetired(db: SQLiteDatabase, id: String): Boolean = db.rawQuery(
        "SELECT 1 FROM personal_plan_retirements WHERE occurrence_id = ?", arrayOf(id),
    ).use { it.moveToFirst() }

    fun commit(db: SQLiteDatabase, decision: PersonalPlanDecision.Ready, cutoff: Instant, recordedAt: Instant) {
        val next = decision.next
        check(next.version == Math.addExact(decision.expectedVersion, 1))
        db.insertOrThrow("personal_plan_commits", null, ContentValues().apply {
            put("event_id", requireNotNull(next.eventId)); put("version", next.version)
            put("plan_revision", requireNotNull(next.activeRevision))
            put("fingerprint", next.bindings.getValue(next.activeRevision))
            put("cutoff_exact", cutoff.toString()); put("recorded_at_exact", recordedAt.toString())
        })
        decision.retireIds.forEach { id ->
            db.insertOrThrow("personal_plan_retirements", null, ContentValues().apply {
                put("occurrence_id", id); put("event_id", next.eventId); put("plan_version", next.version)
            })
        }
    }

    /** Format 1 cannot represent authority. Refuse incomplete backups until format 2 exists. */
    fun requireLegacyBackupCompatible(db: SQLiteDatabase) {
        db.rawQuery("SELECT 1 FROM personal_plan_commits LIMIT 1", null).use {
            check(!it.moveToFirst()) { "Personal plan authority requires a newer backup format; data was retained" }
        }
    }

    val schema = linkedMapOf(
        "personal_plan_commits" to """CREATE TABLE personal_plan_commits (
            event_id TEXT NOT NULL, version INTEGER NOT NULL CHECK(version > 0),
            plan_revision TEXT NOT NULL, fingerprint TEXT NOT NULL,
            cutoff_exact TEXT NOT NULL, recorded_at_exact TEXT NOT NULL,
            PRIMARY KEY(event_id, version), UNIQUE(event_id, plan_revision)
        )""",
        "personal_plan_retirements" to """CREATE TABLE personal_plan_retirements (
            occurrence_id TEXT PRIMARY KEY NOT NULL REFERENCES occurrences(occurrence_id),
            event_id TEXT NOT NULL, plan_version INTEGER NOT NULL,
            FOREIGN KEY(event_id, plan_version) REFERENCES personal_plan_commits(event_id, version)
        )""",
        "personal_commits_no_update" to """CREATE TRIGGER personal_commits_no_update BEFORE UPDATE ON personal_plan_commits
            BEGIN SELECT RAISE(ABORT, 'Personal plan commits are append-only'); END""",
        "personal_commits_no_delete" to """CREATE TRIGGER personal_commits_no_delete BEFORE DELETE ON personal_plan_commits
            BEGIN SELECT RAISE(ABORT, 'Personal plan commits are append-only'); END""",
        "personal_retirements_no_update" to """CREATE TRIGGER personal_retirements_no_update BEFORE UPDATE ON personal_plan_retirements
            BEGIN SELECT RAISE(ABORT, 'Personal retirements are append-only'); END""",
        "personal_retirements_no_delete" to """CREATE TRIGGER personal_retirements_no_delete BEFORE DELETE ON personal_plan_retirements
            BEGIN SELECT RAISE(ABORT, 'Personal retirements are append-only'); END""",
    )
}
