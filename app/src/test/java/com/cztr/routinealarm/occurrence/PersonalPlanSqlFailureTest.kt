package com.cztr.routinealarm.occurrence

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.cztr.routinealarm.*
import java.time.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** SQL statement errors in the real repository. Not abrupt process termination or power loss. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PersonalPlanSqlFailureTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.of("Europe/Berlin")
    private val cutoff = Instant.parse("2026-10-20T12:00:00Z")
    private val clock = Clock.fixed(cutoff, zone)
    private val rule = RoutineEvent("personal", DayOfWeek.entries.toSet(), 21, 10,
        "Messages", category = EventCategory.PERSONAL, mode = EventMode.ANNOUNCEMENT)
    private val scope = OccurrenceScope.PersonalEvent(rule.id)
    private val names = mutableListOf<String>()
    private val databaseNames = linkedMapOf<String, String>()
    private fun database(label: String): String = databaseNames.getOrPut(label) {
        // Robolectric includes the test name in its Windows path; keep the filename short.
        val longPath = context.getDatabasePath("sql-failure-" + label + ".db").path
        if (longPath.length >= 240) println("Fixture path length before shortening: " + longPath.length)
        "fault-" + databaseNames.size + ".db"
    }
    private fun repo(name: String): SqliteOccurrenceRepository {
        names.add(name)
        return SqliteOccurrenceRepository(context, clock, name)
    }
    private fun items(revision: String) =
        OccurrencePlanner(clock).plan(listOf(rule), LocalDate.parse("2026-10-20"), 2, zone, revision)
    private fun replace(repository: SqliteOccurrenceRepository, version: Long, revision: String) =
        repository.replacePersonalPlan(scope, version, items(revision), revision, zone, cutoff)
    @After fun cleanup() { names.distinct().forEach(context::deleteDatabase) }

    /** Install only after normal schema validation; remove before any repository reopen. */
    private fun inject(name: String, timing: String, operation: String, table: String, failureRow: Int = 1, work: () -> Unit) {
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            require(failureRow > 0)
            db.execSQL("CREATE TABLE c6_fault_counter (seen INTEGER NOT NULL)")
            db.execSQL("INSERT INTO c6_fault_counter VALUES (0)")
            db.execSQL("CREATE TRIGGER c6_injected_failure " + timing + " " + operation + " ON " + table +
                " BEGIN UPDATE c6_fault_counter SET seen = seen + 1; " +
                "SELECT CASE WHEN (SELECT seen FROM c6_fault_counter) = " + failureRow +
                " THEN RAISE(ABORT, 'C6 injected SQL failure row " + failureRow + "') END; END")
            try {
                val error = assertThrows(timing + " " + operation + " " + table,
                    SQLiteException::class.java) { work() }
                assertTrue("Must fail at injected SQL statement: " + error,
                    error.message.orEmpty().contains("C6 injected SQL failure row " + failureRow))
            } finally {
                db.execSQL("DROP TRIGGER c6_injected_failure")
                db.execSQL("DROP TABLE c6_fault_counter")
            }
        }
    }

    @Test fun everyReplacementWriteFailurePreservesTheCompletePriorPlan() {
        val tables = listOf("schedule_revisions", "occurrences", "execution_journal",
            "personal_plan_commits", "personal_plan_retirements")
        for (timing in listOf("BEFORE", "AFTER")) for (table in tables)
            for (failureRow in if (table in setOf("occurrences", "execution_journal", "personal_plan_retirements"))
                listOf(1, 2) else listOf(1)) {
            val name = database(timing + "-" + table + "-" + failureRow)
            lateinit var baseline: ByteArray
            repo(name).use { repository ->
                replace(repository, 0, "PRIOR")
                replace(repository, 1, "A")
                repository.beginSchedulingAttempt(items("A").first(), true, scope)
                baseline = repository.exportBackup()
                inject(name, timing, "INSERT", table, failureRow) { replace(repository, 2, "B") }
                assertArrayEquals(baseline, repository.exportBackup())
            }
            repo(name).use { repository ->
                assertArrayEquals(baseline, repository.exportBackup())
                assertEquals(2L, repository.personalPlanAuthority(scope).version)
                assertEquals(items("PRIOR").map { it.id }.toSet(), repository.personalPlanAuthority(scope).retiredIds)
                assertEquals(items("A"), repository.futureOccurrencesPage(scope, cutoff))
                items("B").forEach { assertNull(repository.get(it.id)); assertTrue(repository.history(it.id).isEmpty()) }
                assertTrue(repository.schedulingAttempts(items("A").first().id).single().effectUnknown)
                assertEquals(3L, (replace(repository, 2, "B") as PersonalPlanDecision.Ready).next.version)
                assertEquals(items("PRIOR").plus(items("A")).map { it.id }.toSet(), repository.personalPlanAuthority(scope).retiredIds)
                assertEquals(items("B"), repository.futureOccurrencesPage(scope, cutoff))
            }
        }
    }

    @Test fun everyRestoreWriteFailureRollsBackDataEpochAndQuarantine() {
        val source = repo(database("source")).use { repository ->
            replace(repository, 0, "A")
            val attempt = repository.beginSchedulingAttempt(items("A").first(), true, scope)!!
            repository.recordSchedulingReceipt(attempt.id, SchedulingOutcome.CALL_RETURNED, SchedulingReason.RETURNED)
            replace(repository, 1, "B")
            repository.exportBackup()
        }
        val writes = listOf("schedule_revisions", "occurrences", "execution_journal", "scheduling_attempts",
            "scheduling_receipts", "personal_plan_commits", "personal_plan_retirements", "restored_occurrences")
            .map { it to "INSERT" } + ("store_metadata" to "UPDATE")
        for (timing in listOf("BEFORE", "AFTER")) for ((table, operation) in writes)
            for (failureRow in when (table) {
                "occurrences", "execution_journal", "restored_occurrences" -> listOf(1, 2, 4)
                "schedule_revisions", "personal_plan_commits", "personal_plan_retirements" -> listOf(1, 2)
                else -> listOf(1)
            }) {
            val name = database("restore-" + timing + "-" + table + "-" + failureRow)
            lateinit var empty: ByteArray
            repo(name).use { repository ->
                empty = repository.exportBackup()
                inject(name, timing, operation, table, failureRow) { repository.restoreBackup(source) }
                assertArrayEquals(empty, repository.exportBackup())
                assertEquals("", repository.deliveryEpoch())
                assertEquals(0, repository.restorePausedCount())
            }
            repo(name).use { repository ->
                assertArrayEquals(empty, repository.exportBackup())
                assertEquals(0L, repository.personalPlanAuthority(scope).version)
                assertEquals("", repository.deliveryEpoch())
                assertEquals(0, repository.restorePausedCount())
                assertEquals(4, repository.restoreBackup(source))
                assertArrayEquals(source, repository.exportBackup())
                assertTrue(repository.deliveryEpoch().isNotBlank())
                assertEquals(4, repository.restorePausedCount())
                assertTrue(repository.futureOccurrencesPage(scope, cutoff).isEmpty())
                items("A").plus(items("B")).forEach {
                    assertFalse(repository.isDeliveryAllowed(it.id))
                    assertFalse(repository.activateRestored(it.id))
                }
            }
        }
    }

    @Test fun receiptStatementFailureRetainsUnknownAttemptAndAllowsExactRetry() {
        for (timing in listOf("BEFORE", "AFTER")) {
            val name = database("receipt-" + timing)
            lateinit var attempt: SchedulingAttempt
            lateinit var baseline: ByteArray
            repo(name).use { repository ->
                replace(repository, 0, "A")
                attempt = repository.beginSchedulingAttempt(items("A").first(), true, scope)!!
                baseline = repository.exportBackup()
                inject(name, timing, "INSERT", "scheduling_receipts") {
                    repository.recordSchedulingReceipt(attempt.id, SchedulingOutcome.CALL_RETURNED, SchedulingReason.RETURNED)
                }
                assertArrayEquals(baseline, repository.exportBackup())
            }
            repo(name).use { repository ->
                assertArrayEquals(baseline, repository.exportBackup())
                assertNull(repository.schedulingAttempts(attempt.occurrenceId).single().receipt)
                val receipt = repository.recordSchedulingReceipt(attempt.id,
                    SchedulingOutcome.CALL_RETURNED, SchedulingReason.RETURNED)
                assertEquals(receipt, repository.recordSchedulingReceipt(attempt.id,
                    SchedulingOutcome.CALL_RETURNED, SchedulingReason.RETURNED))
                assertEquals(1, repository.schedulingAttempts(attempt.occurrenceId).size)
                assertTrue(repository.schedulingAttempts(attempt.occurrenceId).single().effectUnknown)
            }
        }
    }
}
