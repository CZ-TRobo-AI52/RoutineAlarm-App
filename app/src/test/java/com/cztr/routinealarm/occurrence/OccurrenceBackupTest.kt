package com.cztr.routinealarm.occurrence

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.cztr.routinealarm.EventCategory
import com.cztr.routinealarm.EventMode
import com.cztr.routinealarm.RoutineEvent
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
import java.time.ZoneOffset
import java.time.ZoneId
import java.time.DayOfWeek
import java.security.MessageDigest
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class OccurrenceBackupTest {
    private lateinit var context: Context
    private val now = Instant.parse("2026-10-19T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    @Before fun setup() { context = RuntimeEnvironment.getApplication() }
    @After fun cleanup() { listOf("src.db", "dst.db").forEach(context::deleteDatabase) }

    private fun repo(name: String = "src.db", fail: () -> Unit = {}) =
        SqliteOccurrenceRepository(context, clock, name, fail)

    private fun initial(id: String) = RoutineOccurrence(
        id, id, "test-v1:UTC", LocalDate.parse("2026-10-19"), LocalTime.of(12, 5),
        "UTC", 0, now.plusSeconds(300), now.plusSeconds(300), lastUpdatedAt = now,
        traceId = id, title = "Spülen 😀", spokenText = "Persönlicher Test", category = EventCategory.PERSONAL,
        mode = EventMode.ROUTINE, isTest = true,
    )

    private fun action(id: String, type: ActionType, revision: Long, generation: Long = 0,
        label: String = "$id:${type.name}", minutes: Int? = null) = ExecutionAction(
        label, id, type, if (type == ActionType.TRIGGER) Actor.SYSTEM else Actor.USER,
        now.plusSeconds(301 + revision).plusNanos(123), revision, generation,
        minutes, "backup-test", "exact timestamp and source survive",
    )

    private fun seeded(): ByteArray = repo().use {
        it.materialize(listOf(initial("waiting"), initial("done"), initial("triggered"), initial("snoozed")))
        val receipt = it.beginSchedulingAttempt(it.get("waiting")!!, true)!!
        it.recordSchedulingReceipt(receipt.id, SchedulingOutcome.CALL_RETURNED, SchedulingReason.RETURNED)
        it.beginSchedulingAttempt(it.get("waiting")!!, true) // A crash left an unknown outcome.
        for (id in listOf("done", "triggered", "snoozed")) it.apply(action(id, ActionType.TRIGGER, 0))
        it.apply(action("done", ActionType.DONE, 1))
        it.apply(action("snoozed", ActionType.SNOOZE, 1, minutes = 5))
        it.apply(action("done", ActionType.DONE, 0, label = "conflicting-action"))
        it.exportBackup()
    }

    @Test fun roundTripRetainsJournalReceiptsSnapshotsAndQuarantinesOpenRows() {
        val bytes = seeded()
        repo("dst.db").use {
            assertEquals(4, it.restoreBackup(bytes))
            assertTrue(it.deliveryEpoch().isNotBlank())
            assertEquals(3, it.restorePausedCount())
            assertFalse(it.isDeliveryAllowed("waiting"))
            assertFalse(it.isDeliveryAllowed("triggered"))
            assertFalse(it.isDeliveryAllowed("snoozed"))
            assertThrows(IllegalStateException::class.java) { it.beginSchedulingAttempt(it.get("waiting")!!, true) }
        }
        repo().use { source -> repo("dst.db").use { restored ->
            for (id in listOf("waiting", "done", "triggered", "snoozed")) {
                assertEquals(source.get(id), restored.get(id))
                assertEquals(source.history(id), restored.history(id))
                assertEquals(source.schedulingAttempts(id), restored.schedulingAttempts(id))
            }
            val replay = restored.apply(action("done", ActionType.TRIGGER, 0))
            assertTrue(replay.duplicate)
            assertEquals(OccurrenceStatus.TRIGGERED, replay.occurrence.status)
            assertEquals(OccurrenceStatus.DONE, restored.get("done")!!.status)
            assertEquals(2, restored.schedulingAttempts("waiting").size)
            assertNull(restored.schedulingAttempts("waiting").last().receipt)
            assertEquals(3, restored.restorePausedCount())
        } }
    }

    @Test fun thousandOccurrenceRoundTripRecordsHostTimingAndPreservesEveryRow() {
        repo().use { source -> repo("dst.db").use { target ->
            source.materialize((0 until 1000).map { initial("measurement:$it") })
            val exportStart = System.nanoTime()
            val bytes = source.exportBackup()
            val exportNanos = System.nanoTime() - exportStart
            val restoreStart = System.nanoTime()
            assertEquals(1000, target.restoreBackup(bytes))
            val restoreNanos = System.nanoTime() - restoreStart
            assertEquals(1000, target.restorePausedCount())
            assertArrayEquals(bytes, target.exportBackup())
            println("BACKUP_HOST_MEASUREMENT occurrences=1000 bytes=${bytes.size} export_ns=$exportNanos restore_ns=$restoreNanos")
        } }
    }

    @Test fun explicitActivationIsPerOccurrenceAndSurvivesReopen() {
        val bytes = seeded()
        lateinit var epoch: String
        repo("dst.db").use {
            it.restoreBackup(bytes)
            epoch = it.deliveryEpoch()
            assertTrue(it.activateRestored("waiting"))
            assertFalse(it.activateRestored("waiting"))
            assertFalse(it.activateRestored("unknown"))
        }
        repo("dst.db").use {
            assertEquals(epoch, it.deliveryEpoch())
            assertEquals(2, it.restorePausedCount())
            assertTrue(it.isDeliveryAllowed("waiting"))
            assertFalse(it.isDeliveryAllowed("triggered"))
            assertNotNull(it.beginSchedulingAttempt(it.get("waiting")!!, true))
        }
    }

    @Test fun failedCommitRetainsEmptyStoreAndPreviousEpoch() {
        val bytes = seeded()
        repo("dst.db").use { assertEquals("", it.deliveryEpoch()) }
        repo("dst.db") { error("crash before commit") }.use {
            assertThrows(IllegalStateException::class.java) { it.restoreBackup(bytes) }
        }
        repo("dst.db").use {
            assertEquals("", it.deliveryEpoch())
            assertNull(it.get("waiting"))
            assertEquals(0, it.restorePausedCount())
            assertEquals(4, it.restoreBackup(bytes))
        }
    }

    @Test fun nonemptyAndRepeatedRestoreNeverOverwriteExistingData() {
        val bytes = seeded()
        repo("dst.db").use {
            it.materialize(listOf(initial("local")))
            val before = it.exportBackup()
            assertThrows(IllegalStateException::class.java) { it.restoreBackup(bytes) }
            assertArrayEquals(before, it.exportBackup())
            assertEquals("", it.deliveryEpoch())
            assertNull(it.get("waiting"))
        }
        context.deleteDatabase("dst.db")
        repo("dst.db").use {
            it.restoreBackup(bytes)
            val epoch = it.deliveryEpoch()
            val before = it.exportBackup()
            assertThrows(IllegalStateException::class.java) { it.restoreBackup(bytes) }
            assertArrayEquals(before, it.exportBackup())
            assertEquals(epoch, it.deliveryEpoch())
        }
    }

    @Test fun corruptTruncatedOversizedAndTrailingBytesRejectWithoutMutation() {
        val bytes = seeded()
        val damaged = bytes.copyOf().apply { if (isNotEmpty()) this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        for (invalid in listOf(byteArrayOf(), bytes.copyOf(bytes.size / 2), damaged,
            bytes + 0.toByte(), ByteArray(8 * 1024 * 1024 + 1))) {
            repo("dst.db").use {
                assertThrows(IllegalArgumentException::class.java) { it.restoreBackup(invalid) }
                assertNull(it.get("waiting"))
                assertEquals("", it.deliveryEpoch())
            }
        }
    }

    @Test fun activationFailureKeepsQuarantineAndNormalLocalDataIsAllowed() {
        val bytes = seeded()
        repo().use { assertTrue(it.isDeliveryAllowed("waiting")); assertEquals("", it.deliveryEpoch()) }
        repo("dst.db").use { it.restoreBackup(bytes) }
        repo("dst.db") { error("activation crash") }.use {
            assertThrows(IllegalStateException::class.java) { it.activateRestored("waiting") }
        }
        repo("dst.db").use { assertFalse(it.isDeliveryAllowed("waiting")); assertEquals(3, it.restorePausedCount()) }
    }

    private fun edited(bytes: ByteArray, tableName: String, rowIndex: Int, columnName: String, value: Any?): ByteArray {
        val snapshot = OccurrenceBackup.decode(bytes)
        val tableIndex = OccurrenceBackup.tables.indexOfFirst { it.name == tableName }
        val columnIndex = OccurrenceBackup.tables[tableIndex].columns.indexOfFirst { it.name == columnName }
        return OccurrenceBackup.encode(OccurrenceBackup.Snapshot(snapshot.tables.mapIndexed { ti, rows ->
            rows.mapIndexed { ri, row -> row.mapIndexed { ci, cell ->
                if (ti == tableIndex && ri == rowIndex && ci == columnIndex) value else cell
            } }
        }))
    }

    @Test fun correctlyChecksummedForeignKeysProjectionJournalAndReceiptTamperingAreRejected() {
        val bytes = seeded()
        val mutations = listOf(
            Triple("schedule_revisions", "zone_id", "Europe/Berlin"),
            Triple("occurrences", "revision", 99L),
            Triple("occurrences", "snooze_count", -1L),
            Triple("execution_journal", "sequence", 0L),
            Triple("execution_journal", "action_id", "invented-schedule"),
            Triple("execution_journal", "recorded_at", 77L),
            Triple("scheduling_attempts", "occurrence_id", "missing-parent"),
            Triple("scheduling_attempts", "generation", 99L),
            Triple("scheduling_receipts", "attempt_id", "missing-attempt"),
            Triple("scheduling_receipts", "reason", "EXPIRED"),
        )
        repo("dst.db").use { target ->
            for ((table, column, value) in mutations) {
                assertThrows("$table.$column must reject", IllegalArgumentException::class.java) {
                    target.restoreBackup(edited(bytes, table, 0, column, value))
                }
                assertNull(target.get("waiting")); assertEquals("", target.deliveryEpoch())
            }
        }
    }

    @Test fun duplicateRowsAndSnapshotJsonExtensionsDoNotBypassChecksums() {
        val bytes = seeded()
        val source = OccurrenceBackup.decode(bytes)
        val duplicate = OccurrenceBackup.encode(OccurrenceBackup.Snapshot(source.tables.mapIndexed { index, rows ->
            if (index == 1) rows + listOf(rows.first()) else rows
        }))
        val snapshotColumn = OccurrenceBackup.tables[1].columns.indexOfFirst { it.name == "snapshot" }
        val text = source.tables[1][0][snapshotColumn] as String
        val variants = listOf(
            text.replace("\"version\":1", "\"version\":1,\"version\":1"),
            text.replace("\"version\":1", "\"version\":\"1\""),
            text.dropLast(1) + ",\"unknown\":true}",
            text.replace("\"version\":1", "\"version\":1.0"),
        ).map { edited(bytes, "occurrences", 0, "snapshot", it) }
        repo("dst.db").use { target ->
            for (bad in variants + listOf(duplicate)) {
                assertThrows(IllegalArgumentException::class.java) { target.restoreBackup(bad) }
                assertNull(target.get("waiting"))
            }
        }
    }

    @Test fun unknownFormatAndAbsurdCountsRejectEvenWithRecomputedChecksum() {
        val bytes = seeded()
        for (offset in listOf(8, 12, 16)) {
            val data = bytes.copyOfRange(0, bytes.size - 32)
            ByteBuffer.wrap(data).putInt(offset, Int.MAX_VALUE)
            val invalid = data + MessageDigest.getInstance("SHA-256").digest(data)
            repo("dst.db").use {
                assertThrows(IllegalArgumentException::class.java) { it.restoreBackup(invalid) }
                assertNull(it.get("waiting"))
            }
        }
    }

    @Test fun originalExportIsDeterministicAndRestoreDoesNotExportEpochOrQuarantine() {
        val bytes = seeded()
        repo().use { assertArrayEquals(bytes, it.exportBackup()) }
        repo("dst.db").use {
            it.restoreBackup(bytes)
            assertArrayEquals(bytes, it.exportBackup())
            it.activateRestored("waiting")
            assertArrayEquals(bytes, it.exportBackup())
        }
    }

    @Test fun realPlannerDstGapAndBothOverlapFoldsSurviveRoundTrip() {
        val zone = ZoneId.of("Europe/Berlin")
        val rule = RoutineEvent("dst", setOf(DayOfWeek.SUNDAY), 2, 30, "DST", category = EventCategory.PERSONAL, mode = EventMode.ROUTINE)
        val planner = OccurrencePlanner(clock)
        val gap = planner.plan(listOf(rule), LocalDate.parse("2026-03-29"), 1, zone, "gap").single()
        val early = planner.plan(listOf(rule), LocalDate.parse("2026-10-25"), 1, zone, "early").single()
        val late = planner.plan(listOf(rule), LocalDate.parse("2026-10-25"), 1, zone, "late", laterOverlap = true).single()
        assertEquals("2026-03-29T01:30:00Z", gap.plannedAt.toString())
        assertEquals("2026-10-25T00:30:00Z", early.plannedAt.toString())
        assertEquals("2026-10-25T01:30:00Z", late.plannedAt.toString())
        val bytes = repo().use { it.materialize(listOf(gap, early, late)); it.exportBackup() }
        repo("dst.db").use {
            assertEquals(3, it.restoreBackup(bytes))
            for (expected in listOf(gap, early, late)) assertEquals(expected, it.get(expected.id))
        }
    }

    @Test fun rejectedActionWithBlankSourceIsStillPreservedAsEvidence() {
        val bad = action("waiting", ActionType.TRIGGER, 0).copy(source = "")
        val bytes = repo().use {
            it.materialize(listOf(initial("waiting")))
            assertEquals(Outcome.REJECTED, it.apply(bad).outcome)
            it.exportBackup()
        }
        repo("dst.db").use {
            assertEquals(1, it.restoreBackup(bytes))
            val replay = it.apply(bad)
            assertTrue(replay.duplicate)
            assertEquals(Outcome.REJECTED, replay.outcome)
            assertEquals("invalid_action_identity", replay.reason)
            assertEquals("", it.history("waiting").last().source)
        }
    }
}
