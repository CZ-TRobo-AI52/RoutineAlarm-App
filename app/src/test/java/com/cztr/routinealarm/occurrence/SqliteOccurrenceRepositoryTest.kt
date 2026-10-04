package com.cztr.routinealarm.occurrence

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.cztr.routinealarm.EventCategory
import com.cztr.routinealarm.EventMode
import com.cztr.routinealarm.ScheduleRegistry
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
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SqliteOccurrenceRepositoryTest {
    private lateinit var context: Context
    private lateinit var name: String
    private val now = Instant.parse("2026-10-01T14:10:00Z")
    private val clock = Clock.fixed(now.plusSeconds(20), ZoneOffset.UTC)
    private val date = LocalDate.parse("2026-10-01")
    private val zone = ZoneId.of("Europe/Berlin")

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        // Robolectric already isolates each method's application directory. Keep the
        // filename short for native SQLite sidecars on Windows (MAX_PATH).
        name = "occ.db"
    }

    @After fun cleanup() { context.deleteDatabase(name) }

    private fun occurrence(id: String = "occ-1") = RoutineOccurrence(
        id = id, eventId = "rinse-$id", scheduleRevision = "plan-v1", nominalDate = date,
        nominalTime = LocalTime.of(16, 10), zoneId = zone.id, offsetSeconds = 7200,
        plannedAt = now, currentTriggerAt = now, status = OccurrenceStatus.SCHEDULED,
        generation = 0, revision = 0, snoozeCount = 0, totalSnoozeMinutes = 0,
        lastUpdatedAt = now.minusSeconds(60), traceId = "trace-$id", title = "Spülen",
        spokenText = "Mungbohnen spülen", category = EventCategory.PERSONAL,
        mode = EventMode.ROUTINE, isTest = false,
    )

    private fun action(id: String = "trigger", type: ActionType = ActionType.TRIGGER,
                       revision: Long = 0, generation: Long = 0, minutes: Int? = null) =
        ExecutionAction(id, "occ-1", type, if (type == ActionType.TRIGGER) Actor.SYSTEM else Actor.USER,
            now.plusSeconds(1), revision, generation, minutes, "repository-test")

    @Test fun fileBackedReopenRetainsSnapshotsJournalAndIdempotentRenamedEvent() {
        val initial = occurrence()
        context.getSharedPreferences("routinealarm_schedule", Context.MODE_PRIVATE).edit()
            .putBoolean("weekly_schedule_enabled", true).commit()
        SqliteOccurrenceRepository(context, clock, name).use { repo ->
            assertEquals(1, repo.materialize(listOf(initial)))
            assertEquals(0, repo.materialize(listOf(initial.copy(title = "Neuer Titel"))))
            assertEquals(Outcome.APPLIED, repo.apply(action()).outcome)
        }
        SqliteOccurrenceRepository(context, clock, name).use { repo ->
            val restored = repo.get("occ-1")!!
            assertEquals("Spülen", restored.title)
            assertEquals(OccurrenceStatus.TRIGGERED, restored.status)
            assertEquals(1L, restored.revision)
            assertEquals(listOf("SCHEDULE", "TRIGGER"), repo.history("occ-1").map { it.actionType })
            assertEquals(clock.instant(), repo.history("occ-1").last().recordedAt)
            assertEquals(restored, repo.listForDate(date, zone).single())
            assertTrue(context.getSharedPreferences("routinealarm_schedule", Context.MODE_PRIVATE)
                .getBoolean("weekly_schedule_enabled", false))
        }
    }

    @Test fun completeWeekSurvivesReopenAndRepeatedExpansionWithoutDuplicateHistory() {
        val monday = LocalDate.parse("2026-09-28")
        val week = OccurrencePlanner(clock).plan(ScheduleRegistry.scheduledEvents, monday, 7, zone, "weekly-v1")
        SqliteOccurrenceRepository(context, clock, name).use { repo ->
            assertEquals(week.size, repo.materialize(week))
        }
        SqliteOccurrenceRepository(context, Clock.offset(clock, java.time.Duration.ofDays(1)), name).use { repo ->
            assertEquals(0, repo.materialize(week))
            assertEquals(week.size, (0..6).sumOf { repo.listForDate(monday.plusDays(it.toLong()), zone).size })
            week.forEach { expected ->
                assertEquals(expected, repo.get(expected.id))
                assertEquals(1, repo.history(expected.id).size)
            }
        }
    }

    @Test fun nanosecondPayloadDifferenceIsNotMistakenForActionReplay() {
        SqliteOccurrenceRepository(context, clock, name).use { repo ->
            repo.materialize(listOf(occurrence()))
            val first = action().copy(occurredAt = now.plusNanos(500))
            repo.apply(first)
            assertTrue(repo.apply(first).duplicate)
            assertThrows(ActionIdConflict::class.java) { repo.apply(first.copy(occurredAt = now.plusNanos(501))) }
        }
    }

    @Test fun sameActionReplaysOriginalSnapshotAfterLaterActionsAndReopen() {
        lateinit var first: ActionResult
        SqliteOccurrenceRepository(context, clock, name).use { repo ->
            repo.materialize(listOf(occurrence()))
            first = repo.apply(action())
            val done = repo.apply(action("done", ActionType.DONE, revision = 1))
            assertEquals(Outcome.APPLIED, done.outcome)
        }
        SqliteOccurrenceRepository(context, clock, name).use { repo ->
            val replay = repo.apply(action())
            assertTrue(replay.duplicate)
            assertEquals(first.copy(duplicate = true), replay)
            assertEquals(OccurrenceStatus.DONE, repo.get("occ-1")!!.status)
            assertEquals(3, repo.history("occ-1").size)
            assertThrows(ActionIdConflict::class.java) { repo.apply(action().copy(source = "changed")) }
            assertEquals(3, repo.history("occ-1").size)
        }
    }

    @Test fun rejectedAndStaleAttemptsAreRetainedWithoutChangingProjection() {
        SqliteOccurrenceRepository(context, clock, name).use { repo ->
            repo.materialize(listOf(occurrence()))
            assertEquals(Outcome.APPLIED, repo.apply(action()).outcome)
            val rejected = repo.apply(action("bad-snooze", ActionType.SNOOZE, 1, minutes = 6))
            assertEquals(Outcome.REJECTED, rejected.outcome)
            assertEquals(Outcome.CONFLICT, repo.apply(action("stale", ActionType.DONE)).outcome)
            assertEquals(1L, repo.get("occ-1")!!.revision)
            assertEquals(listOf(Outcome.APPLIED, Outcome.APPLIED, Outcome.REJECTED, Outcome.CONFLICT),
                repo.history("occ-1").map { it.outcome })
        }
    }

    @Test fun injectedFailureRollsBackProjectionAndJournalTogether() {
        SqliteOccurrenceRepository(context, clock, name).use { it.materialize(listOf(occurrence())) }
        SqliteOccurrenceRepository(context, clock, name, beforeCommit = { error("injected before commit") }).use { repo ->
            assertThrows(IllegalStateException::class.java) { repo.apply(action()) }
        }
        SqliteOccurrenceRepository(context, clock, name).use { repo ->
            assertEquals(OccurrenceStatus.SCHEDULED, repo.get("occ-1")!!.status)
            assertEquals(1, repo.history("occ-1").size)
            assertEquals(Outcome.APPLIED, repo.apply(action()).outcome)
        }
    }

    @Test fun injectedMaterializationFailureLeavesNoRowsOrZoneBinding() {
        SqliteOccurrenceRepository(context, clock, name, beforeCommit = { error("injected") }).use { repo ->
            assertThrows(IllegalStateException::class.java) { repo.materialize(listOf(occurrence())) }
        }
        SqliteOccurrenceRepository(context, clock, name).use { repo ->
            assertNull(repo.get("occ-1"))
            assertTrue(repo.history("occ-1").isEmpty())
            val changedZone = occurrence().copy(zoneId = "UTC", offsetSeconds = 0)
            assertEquals(1, repo.materialize(listOf(changedZone)))
        }
    }

    @Test fun twoConnectionsCompetingForOneRevisionHaveExactlyOneWinner() {
        SqliteOccurrenceRepository(context, clock, name).use { it.materialize(listOf(occurrence())) }
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { index -> executor.submit(Callable {
                SqliteOccurrenceRepository(context, clock, name).use { repo ->
                    // Initialize before synchronizing so both connections exist before the race.
                    repo.get("occ-1")
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    repo.apply(action("competing-$index"))
                }
            }) }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(listOf(Outcome.APPLIED, Outcome.CONFLICT),
                futures.map { it.get(20, TimeUnit.SECONDS).outcome }.sortedBy { it.ordinal })
        } finally { start.countDown(); executor.shutdownNow() }
        SqliteOccurrenceRepository(context, clock, name).use { repo ->
            assertEquals(1L, repo.get("occ-1")!!.revision)
            assertEquals(3, repo.history("occ-1").size)
        }
    }

    @Test fun sameScheduleRevisionCannotBeReboundToAnotherZoneAndBatchIsAtomic() {
        SqliteOccurrenceRepository(context, clock, name).use { repo ->
            repo.materialize(listOf(occurrence()))
            assertThrows(IllegalArgumentException::class.java) {
                repo.materialize(listOf(occurrence("new"), occurrence("other").copy(zoneId = "UTC")))
            }
            assertNull(repo.get("new"))
            assertEquals(1, repo.listForDate(date, zone).size)
            assertEquals(1, repo.materialize(listOf(occurrence("revised").copy(
                scheduleRevision = "plan-v2", zoneId = "UTC"))))
        }
    }

    @Test fun testOccurrencesAreExcludedFromPersonalDailyProjection() {
        SqliteOccurrenceRepository(context, clock, name).use { repo ->
            repo.materialize(listOf(occurrence(), occurrence("test-occ").copy(isTest = true)))
            assertEquals(1, repo.listForDate(date, zone).size)
            assertEquals(2, repo.listForDate(date, zone, includeTests = true).size)
        }
    }

    @Test fun anotherIdCannotAliasTheSameConcreteExecution() {
        SqliteOccurrenceRepository(context, clock, name).use { repo ->
            repo.materialize(listOf(occurrence()))
            assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
                repo.materialize(listOf(occurrence().copy(id = "alias")))
            }
            assertNull(repo.get("alias"))
            assertEquals(1, repo.listForDate(date, zone).size)
        }
    }

    @Test fun malformedActionIdCannotReserveAnotherOccurrencesScheduleJournalKey() {
        SqliteOccurrenceRepository(context, clock, name).use { repo ->
            repo.materialize(listOf(occurrence()))
            for (id in listOf("", " ", "schedule:future")) {
                assertThrows(IllegalArgumentException::class.java) { repo.apply(action(id)) }
            }
            assertEquals(1, repo.history("occ-1").size)
            assertEquals(1, repo.materialize(listOf(occurrence("future"))))
            assertEquals("schedule:future", repo.history("future").single().actionId)
        }
    }

    @Test fun unrepresentableActionTimeIsRejectedBeforeWritingJournal() {
        SqliteOccurrenceRepository(context, clock, name).use { repo ->
            repo.materialize(listOf(occurrence()))
            assertThrows(IllegalArgumentException::class.java) {
                repo.apply(action().copy(occurredAt = Instant.MAX))
            }
            assertEquals(1, repo.history("occ-1").size)
            assertEquals(OccurrenceStatus.SCHEDULED, repo.get("occ-1")!!.status)
        }
    }

    @Test fun appendOnlyJournalCannotBeUpdatedOrDeletedAndForeignKeysAreEnabled() {
        SqliteOccurrenceRepository(context, clock, name).use { it.materialize(listOf(occurrence())) }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            assertThrows(android.database.sqlite.SQLiteException::class.java) {
                db.execSQL("UPDATE execution_journal SET source = 'rewritten'")
            }
            assertThrows(android.database.sqlite.SQLiteException::class.java) {
                db.execSQL("DELETE FROM execution_journal")
            }
        }
        SqliteOccurrenceRepository(context, clock, name).use { repo ->
            assertThrows(IllegalArgumentException::class.java) { repo.apply(action().copy(occurrenceId = "absent")) }
            assertEquals(1, repo.history("occ-1").size)
        }
    }

    @Test fun unknownOlderSchemaAndNewerVersionFailWithoutReset() {
        context.getDatabasePath(name).parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            db.execSQL("CREATE TABLE user_evidence (value TEXT)")
            db.execSQL("INSERT INTO user_evidence VALUES ('keep')")
        }
        assertThrows(IllegalStateException::class.java) {
            SqliteOccurrenceRepository(context, clock, name).use { it.get("occ-1") }
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("SELECT value FROM user_evidence", null).use { c -> assertTrue(c.moveToFirst()); assertEquals("keep", c.getString(0)) }
            db.version = 2
        }
        assertThrows(IllegalStateException::class.java) {
            SqliteOccurrenceRepository(context, clock, name).use { it.get("occ-1") }
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(2, db.version)
            db.rawQuery("SELECT count(*) FROM user_evidence", null).use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        }
    }

    @Test fun corruptDatabaseFailsWithoutDeletingOriginalBytes() {
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val bytes = "This is existing user data, not SQLite".toByteArray()
        file.writeBytes(bytes)
        assertThrows(Exception::class.java) {
            SqliteOccurrenceRepository(context, clock, name).use { it.get("occ-1") }
        }
        assertArrayEquals(bytes, file.readBytes())
    }
}
