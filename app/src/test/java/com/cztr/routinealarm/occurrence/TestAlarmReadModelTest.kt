package com.cztr.routinealarm.occurrence

import android.content.Context
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
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TestAlarmReadModelTest {
    private lateinit var context: Context
    private val now = Instant.parse("2026-10-19T23:59:59.999Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Before fun setup() { context = RuntimeEnvironment.getApplication() }
    @After fun cleanup() { listOf("read.db", "copy.db").forEach(context::deleteDatabase) }

    private fun repo(name: String = "read.db", beforeCommit: () -> Unit = {}) =
        SqliteOccurrenceRepository(context, clock, name, beforeCommit)

    private fun occurrence(id: String, at: Instant = now.plusSeconds(60), isTest: Boolean = true) =
        RoutineOccurrence(id, "event:$id", "read-v1", at.atOffset(ZoneOffset.UTC).toLocalDate(),
            at.atOffset(ZoneOffset.UTC).toLocalTime(), "Z", 0, at, at,
            lastUpdatedAt = now.minusSeconds(600), traceId = "trace:$id", title = id,
            spokenText = "Test $id", category = EventCategory.PERSONAL, mode = EventMode.ROUTINE,
            isTest = isTest)

    private fun apply(repo: SqliteOccurrenceRepository, id: String, type: ActionType,
        at: Instant, minutes: Int? = null) {
        val current = repo.get(id)!!
        assertEquals(Outcome.APPLIED, repo.apply(ExecutionAction("$id:${type.name}", id, type,
            if (type == ActionType.TRIGGER) Actor.SYSTEM else Actor.USER, at,
            current.revision, current.generation, minutes, "read-model-test")).outcome)
    }

    @Test fun emptyStoreHasZeroCountsAndEmptyHistory() {
        repo().use {
            assertEquals(TestAlarmStatusSummary(0, 0, 0, null), it.statusSummary(now))
            assertEquals(TestAlarmHistoryPage(emptyList(), "", 0), it.historyPage())
        }
    }

    @Test fun statusCountsOnlyOpenTestsAndSelectsCurrentSnoozeTime() {
        repo().use {
            it.materialize(listOf(occurrence("past", now.minusSeconds(60)), occurrence("due", now),
                occurrence("triggered", now.minusSeconds(30)), occurrence("snoozed", now.minusSeconds(50)),
                occurrence("future-a"), occurrence("future-b"), occurrence("done", now.minusSeconds(20)),
                occurrence("skipped", now.minusSeconds(20)), occurrence("weekly", now.minusSeconds(20), false)))
            for (id in listOf("triggered", "snoozed", "done", "skipped"))
                apply(it, id, ActionType.TRIGGER, it.get(id)!!.plannedAt)
            apply(it, "snoozed", ActionType.SNOOZE, now.minusSeconds(50), 1)
            apply(it, "done", ActionType.DONE, now.minusSeconds(19))
            apply(it, "skipped", ActionType.SKIP, now.minusSeconds(19))

            val status = it.statusSummary(now)
            assertEquals(6, status.openCount)
            assertEquals(1, status.triggeredCount)
            assertEquals(2, status.overdueCount)
            assertEquals("snoozed", status.nextTest!!.id)
            assertEquals(now.plusSeconds(10), status.nextTest.currentTriggerAt)
            assertEquals(OccurrenceStatus.SNOOZED, status.nextTest.status)
        }
    }

    @Test fun sameTriggerTimeUsesStableAscendingIdentityForNextTest() {
        repo().use {
            it.materialize(listOf(occurrence("z"), occurrence("b"), occurrence("a")))
            assertEquals("a", it.statusSummary(now).nextTest!!.id)
        }
        repo().use { assertEquals("a", it.statusSummary(now).nextTest!!.id) }
    }

    @Test fun midnightAndExactDueBoundaryDoNotHideTestsFromAnotherNominalDate() {
        repo().use {
            it.materialize(listOf(occurrence("yesterday", now.minusSeconds(1)),
                occurrence("boundary", now), occurrence("tomorrow", now.plusMillis(1))))
            val beforeMidnight = it.statusSummary(now)
            assertEquals(3, beforeMidnight.openCount)
            assertEquals(2, beforeMidnight.overdueCount)
            assertEquals("tomorrow", beforeMidnight.nextTest!!.id)
            val atMidnight = it.statusSummary(now.plusMillis(1))
            assertEquals(3, atMidnight.overdueCount)
            assertNull(atMidnight.nextTest)
        }
    }

    @Test fun restoredRowsStayVisibleInHistoryButExcludedFromStatusUntilExplicitActivation() {
        val bytes = repo().use {
            it.materialize(listOf(occurrence("allowed"), occurrence("paused"),
                occurrence("weekly", isTest = false)))
            it.exportBackup()
        }
        repo("copy.db").use {
            it.restoreBackup(bytes)
            assertEquals(TestAlarmStatusSummary(0, 0, 0, null), it.statusSummary(now))
            assertTrue(it.activateRestored("allowed"))

            val summary = it.statusSummary(now)
            assertEquals(1, summary.openCount)
            assertEquals("allowed", summary.nextTest!!.id)
            val history = it.historyPage()
            assertEquals(listOf("paused", "allowed"), history.items.map { row -> row.occurrence.id })
            assertEquals(listOf(false, true), history.items.map { row -> row.deliveryAllowed })
            assertTrue(history.deliveryEpoch.isNotBlank())
            assertEquals(it.deliveryEpoch(), history.deliveryEpoch)
            // Retain the existing global quarantine count, including the real weekly occurrence.
            assertEquals(2, history.pausedCount)
        }
    }

    @Test fun historyReturnsTwentyRowsThenRemainingRowsInStableDescendingOrder() {
        repo().use {
            it.materialize((1..23).map { n -> occurrence("item-${n.toString().padStart(2, '0')}") } +
                occurrence("weekly", now.plusSeconds(120), false))
            val first = it.historyPage()
            assertEquals(20, first.items.size)
            assertEquals("item-23", first.items.first().occurrence.id)
            assertEquals("item-04", first.items.last().occurrence.id)
            assertEquals(listOf("item-03", "item-02", "item-01"),
                it.historyPage(20).items.map { row -> row.occurrence.id })
            assertTrue(it.historyPage(23).items.isEmpty())
            assertTrue(it.historyPage(Int.MAX_VALUE).items.isEmpty())
            assertThrows(IllegalArgumentException::class.java) { it.historyPage(-1) }
        }
    }

    @Test fun historyUsesPlannedTimeAndLatestAttemptEvenWhenReceiptIsMissing() {
        repo().use {
            it.materialize(listOf(occurrence("z-earlier", now.plusSeconds(30)), occurrence("a-later"),
                occurrence("failed", now.plusSeconds(45))))
            val old = it.beginSchedulingAttempt(it.get("a-later")!!, true)!!
            it.recordSchedulingReceipt(old.id, SchedulingOutcome.CALL_RETURNED, SchedulingReason.RETURNED)
            val latest = it.beginSchedulingAttempt(it.get("a-later")!!, true)!!
            val failed = it.beginSchedulingAttempt(it.get("failed")!!, true)!!
            val receipt = it.recordSchedulingReceipt(failed.id, SchedulingOutcome.CALL_FAILED,
                SchedulingReason.PLATFORM_EXCEPTION)

            val items = it.historyPage().items
            assertEquals(listOf("a-later", "failed", "z-earlier"), items.map { row -> row.occurrence.id })
            assertEquals(latest, items[0].latestSchedulingAttempt)
            assertNull(items[0].latestSchedulingAttempt!!.receipt)
            assertEquals(failed.copy(receipt = receipt), items[1].latestSchedulingAttempt)
            assertNull(items[2].latestSchedulingAttempt)
            assertTrue(items.all { row -> row.deliveryAllowed })
        }
    }

    @Test fun readingDoesNotInvokeMutationHookOrAlterAnyPersistedRows() {
        val bytes = repo().use {
            it.materialize(listOf(occurrence("waiting"), occurrence("paused")))
            val attempt = it.beginSchedulingAttempt(it.get("waiting")!!, true)!!
            it.recordSchedulingReceipt(attempt.id, SchedulingOutcome.CALL_RETURNED, SchedulingReason.RETURNED)
            it.exportBackup()
        }
        repo("copy.db").use { it.restoreBackup(bytes); it.activateRestored("waiting") }
        val before = rows("copy.db")
        repo("copy.db", beforeCommit = { error("Read model invoked mutation hook") }).use {
            repeat(3) { _ ->
                assertEquals(1, it.statusSummary(now).openCount)
                assertEquals(2, it.historyPage().items.size)
            }
        }
        assertEquals(before, rows("copy.db"))
    }

    private fun rows(name: String): Map<String, List<List<String?>>> = SQLiteDatabase.openDatabase(
        context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY,
    ).use { db ->
        listOf("occurrences", "execution_journal", "schedule_revisions", "scheduling_attempts",
            "scheduling_receipts", "store_metadata", "restored_occurrences", "sqlite_sequence")
            .associateWith { table -> db.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { index ->
                    if (cursor.isNull(index)) null else cursor.getString(index)
                }) }
            } }
    }
}
