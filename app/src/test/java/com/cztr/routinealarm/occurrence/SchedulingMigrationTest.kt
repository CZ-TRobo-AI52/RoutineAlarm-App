package com.cztr.routinealarm.occurrence

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.cztr.routinealarm.EventCategory
import com.cztr.routinealarm.EventMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SchedulingMigrationTest {
    private lateinit var context: Context
    private val name = "mig.db"
    @Before fun setup() { context = RuntimeEnvironment.getApplication() }
    @After fun cleanup() { context.deleteDatabase(name) }

    private fun saved() = RoutineOccurrence(
        id = "legacy-occ", eventId = "old-test", scheduleRevision = "legacy",
        nominalDate = LocalDate.parse("2026-10-01"), nominalTime = LocalTime.NOON,
        zoneId = "UTC", offsetSeconds = 0, plannedAt = Instant.parse("2026-10-01T12:00:00Z"),
        currentTriggerAt = Instant.parse("2026-10-01T12:00:00Z"), lastUpdatedAt = Instant.EPOCH,
        traceId = "old-trace", title = "Keep this title", spokenText = "Keep this spoken text",
        category = EventCategory.PERSONAL, mode = EventMode.ROUTINE, isTest = true,
    )

    private fun legacy(): SQLiteDatabase {
        context.getDatabasePath(name).parentFile!!.mkdirs()
        return SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).apply {
            val fixture = javaClass.classLoader!!.getResourceAsStream("occurrences-v1.sql")!!
                .bufferedReader().use { it.readText() }
            fixture.split("-- statement").drop(1).forEach { execSQL(it.trim()) }
            execSQL("INSERT INTO schedule_revisions VALUES ('legacy', 'UTC', 0)")
            insertOrThrow("occurrences", null, ContentValues().apply {
                put("occurrence_id", "legacy-occ"); put("event_id", "old-test"); put("schedule_revision", "legacy")
                put("nominal_date", "2026-10-01"); put("nominal_time", "12:00"); put("zone_id", "UTC")
                put("offset_seconds", 0); put("planned_at", saved().plannedAt.toEpochMilli())
                put("current_trigger_at", saved().currentTriggerAt.toEpochMilli()); put("status", "SCHEDULED")
                put("generation", 0); put("revision", 0); put("snooze_count", 0); put("total_snooze_minutes", 0)
                put("last_updated_at", 0); put("is_test", 1); put("snapshot", OccurrenceCodec.encode(saved()))
            })
            insertOrThrow("execution_journal", null, ContentValues().apply {
                put("action_id", "schedule:legacy-occ"); put("occurrence_id", "legacy-occ"); put("action_type", "SCHEDULE")
                put("actor", "SYSTEM"); put("occurred_at", 0); put("recorded_at", 0)
                put("occurred_at_exact", Instant.EPOCH.toString()); put("recorded_at_exact", Instant.EPOCH.toString())
                put("expected_revision", 0); put("expected_generation", 0); put("resulting_revision", 0)
                put("resulting_status", "SCHEDULED"); put("generation", 0); put("source", "legacy-fixture")
                put("outcome", "APPLIED"); put("result_snapshot", OccurrenceCodec.encode(saved()))
            })
            version = 1
        }
    }

    @Test fun genuineV1SchemaMigratesAndRetainsExistingData() {
        legacy().close()
        SqliteOccurrenceRepository(context, Clock.systemUTC(), name).use {
            assertEquals(saved(), it.get("legacy-occ"))
            assertEquals("legacy-fixture", it.history("legacy-occ").single().source)
        }
        SqliteOccurrenceRepository(context, Clock.systemUTC(), name).use {
            assertEquals(saved(), it.get("legacy-occ"))
            assertEquals(1, it.history("legacy-occ").size)
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(3, it.version)
            it.rawQuery("SELECT zone_id FROM schedule_revisions WHERE revision_id='legacy'", null).use { row ->
                assertTrue(row.moveToFirst()); assertEquals("UTC", row.getString(0))
            }
            it.rawQuery("SELECT count(*) FROM scheduling_attempts", null).use { row -> row.moveToFirst(); assertEquals(0, row.getInt(0)) }
        }
    }

    @Test fun alteredV1FailsBeforeMigrationAndRetainsOriginalDataAndVersion() {
        legacy().use { it.execSQL("DROP TRIGGER journal_no_delete") }
        assertThrows(IllegalStateException::class.java) {
            SqliteOccurrenceRepository(context, Clock.systemUTC(), name).use { it.get("absent") }
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(1, it.version)
            it.rawQuery("SELECT count(*) FROM schedule_revisions", null).use { row -> row.moveToFirst(); assertEquals(1, row.getInt(0)) }
            it.rawQuery("SELECT count(*) FROM sqlite_master WHERE name='scheduling_attempts'", null).use { row -> row.moveToFirst(); assertEquals(0, row.getInt(0)) }
        }
    }

    @Test fun brokenV1ForeignKeyIsRejectedBeforeAnyMigration() {
        legacy().use { it.execSQL("UPDATE occurrences SET schedule_revision = 'missing'") }
        assertThrows(IllegalStateException::class.java) {
            SqliteOccurrenceRepository(context, Clock.systemUTC(), name).use { it.get("absent") }
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(1, it.version)
            it.rawQuery("SELECT schedule_revision FROM occurrences", null).use { row ->
                assertTrue(row.moveToFirst()); assertEquals("missing", row.getString(0))
            }
        }
    }

    /** Definitions copied from published alpha05 commit 436e437, independent of current schema code. */
    private fun legacyV2(): SQLiteDatabase = legacy().apply {
        listOf(
            """CREATE TABLE scheduling_attempts (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT, attempt_id TEXT NOT NULL UNIQUE,
                occurrence_id TEXT NOT NULL REFERENCES occurrences(occurrence_id),
                revision INTEGER NOT NULL CHECK(revision >= 0), generation INTEGER NOT NULL CHECK(generation >= 0),
                trigger_at INTEGER NOT NULL, started_at_exact TEXT NOT NULL
            )""",
            """CREATE TABLE scheduling_receipts (
                attempt_id TEXT PRIMARY KEY NOT NULL REFERENCES scheduling_attempts(attempt_id),
                outcome TEXT NOT NULL CHECK(outcome IN ('CALL_RETURNED','CALL_FAILED','NOT_CALLED')),
                reason TEXT NOT NULL CHECK(reason IN ('RETURNED','PLATFORM_EXCEPTION','PERMISSION_DENIED','STALE','EXPIRED')),
                recorded_at_exact TEXT NOT NULL
            )""",
            "CREATE INDEX scheduling_occurrence ON scheduling_attempts(occurrence_id, sequence)",
            """CREATE TRIGGER attempts_no_update BEFORE UPDATE ON scheduling_attempts
                BEGIN SELECT RAISE(ABORT, 'Scheduling attempts are append-only'); END""",
            """CREATE TRIGGER attempts_no_delete BEFORE DELETE ON scheduling_attempts
                BEGIN SELECT RAISE(ABORT, 'Scheduling attempts are append-only'); END""",
            """CREATE TRIGGER receipts_no_update BEFORE UPDATE ON scheduling_receipts
                BEGIN SELECT RAISE(ABORT, 'Scheduling receipts are append-only'); END""",
            """CREATE TRIGGER receipts_no_delete BEFORE DELETE ON scheduling_receipts
                BEGIN SELECT RAISE(ABORT, 'Scheduling receipts are append-only'); END""",
        ).forEach(::execSQL)
        execSQL("INSERT INTO scheduling_attempts(attempt_id, occurrence_id, revision, generation, trigger_at, started_at_exact) VALUES ('keep-attempt', 'legacy-occ', 0, 0, ?, '2026-10-01T11:00:00.123456789Z')", arrayOf(saved().plannedAt.toEpochMilli()))
        execSQL("INSERT INTO scheduling_receipts VALUES ('keep-attempt', 'CALL_RETURNED', 'RETURNED', '2026-10-01T11:00:00.123456999Z')")
        version = 2
    }

    @Test fun genuineV2MigrationKeepsHistoryReceiptsAndExistingTokensAllowed() {
        legacyV2().close()
        SqliteOccurrenceRepository(context, Clock.systemUTC(), name).use {
            assertEquals("", it.deliveryEpoch())
            assertTrue(it.isDeliveryAllowed("legacy-occ"))
            assertEquals(0, it.restorePausedCount())
            assertEquals(saved(), it.get("legacy-occ"))
            val attempt = it.schedulingAttempts("legacy-occ").single()
            assertEquals("keep-attempt", attempt.id)
            assertEquals(Instant.parse("2026-10-01T11:00:00.123456789Z"), attempt.startedAt)
            assertEquals(Instant.parse("2026-10-01T11:00:00.123456999Z"), attempt.receipt!!.recordedAt)
            assertEquals("legacy-fixture", it.history("legacy-occ").single().source)
            assertTrue(it.exportBackup().isNotEmpty())
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(3, it.version)
            it.rawQuery("SELECT count(*) FROM restored_occurrences", null).use { row -> row.moveToFirst(); assertEquals(0, row.getInt(0)) }
        }
    }

    @Test fun alteredV2RetainsVersionAndRowsWithoutCreatingRestoreMetadata() {
        legacyV2().use { it.execSQL("DROP TRIGGER receipts_no_update") }
        assertThrows(IllegalStateException::class.java) {
            SqliteOccurrenceRepository(context, Clock.systemUTC(), name).use { it.deliveryEpoch() }
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(2, it.version)
            it.rawQuery("SELECT count(*) FROM scheduling_receipts", null).use { row -> row.moveToFirst(); assertEquals(1, row.getInt(0)) }
            it.rawQuery("SELECT count(*) FROM sqlite_master WHERE name='store_metadata'", null).use { row -> row.moveToFirst(); assertEquals(0, row.getInt(0)) }
        }
    }
}
