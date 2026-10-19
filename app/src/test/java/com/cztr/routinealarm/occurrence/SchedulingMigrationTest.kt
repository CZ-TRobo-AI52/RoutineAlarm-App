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
            assertEquals(2, it.version)
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
}
