package com.cztr.routinealarm.occurrence

import android.content.Context
import android.database.sqlite.SQLiteException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TestAlarmRecoveryChunkTest {
    private lateinit var context: Context
    private lateinit var repo: SqliteOccurrenceRepository
    private lateinit var file: File
    private val clock = MutableClock(Instant.parse("2030-01-01T00:00:00Z"))
    private class MutableClock(var now: Instant) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    }
    private class MemoryStore : RecoveryCursorStore {
        var cursor: RecoveryCursor? = null
        var failNext = false
        override fun load() = cursor
        override fun save(cursor: RecoveryCursor) {
            if (failNext) { failNext = false; throw IOException("checkpoint failed") }
            this.cursor = cursor
        }
    }
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("occurrences.db")
        context.deleteDatabase("recovery-source.db")
        repo = SqliteOccurrenceRepository(context, clock)
        file = File(context.noBackupFilesDir, "recovery-chunk-test.bin")
        clearCursor()
    }
    @After fun cleanup() {
        repo.close()
        context.deleteDatabase("occurrences.db")
        context.deleteDatabase("recovery-source.db")
        clearCursor()
    }
    private fun clearCursor() {
        listOf(file, File(file.path + ".bak"), File(file.path + ".new")).forEach { it.delete() }
    }
    private fun create(minutes: Int = 10) = TestAlarmCoordinator(repo, clock).create(minutes)
    private fun terminal(): RoutineOccurrence {
        val past = Clock.fixed(clock.instant().minusSeconds(120), clock.zone)
        val item = TestAlarmCoordinator(repo, past).create(1)
        repo.beginSchedulingAttempt(item, false)
        val coordinator = TestAlarmCoordinator(repo, clock)
        val triggered = coordinator.trigger(AlarmDelivery.from(item, repo.deliveryEpoch()))!!
        coordinator.act(AlarmDelivery.from(triggered, repo.deliveryEpoch()), ActionType.DONE)!!
        return item
    }
    private fun worker(cycle: String = "cycle-a", store: RecoveryCursorStore = AtomicRecoveryCursorStore(file),
        schedule: (RoutineOccurrence) -> Unit = {},
        cancel: (AlarmDelivery) -> CancellationObservation = { CancellationObservation.CALL_RETURNED },
        onFailure: (Exception) -> Unit = {}) = TestAlarmRecovery(repo, clock, schedule, cancel, store, cycle, onFailure)

    @Test fun oneHundredThirtyFutureTestsContinueAcrossReopenWithoutRepeatingTheFirstPage() {
        val items = (1..130).map { create() }.sortedBy { it.id }
        val calls = mutableListOf<String>()
        val expected = listOf(64, 64, 2)
        expected.forEachIndexed { index, count ->
            val result = worker(schedule = { calls.add(it.id) }).runBatch()
            assertEquals(count, result.processed)
            assertEquals(index == 2, result.complete)
            assertEquals(0, result.failed)
            repo.close(); repo = SqliteOccurrenceRepository(context, clock)
        }
        assertEquals(items.map { it.id }, calls)
        assertEquals(RecoveryBatchResult(0, true, 0), worker(schedule = { fail("finished cycle repeated") }).runBatch())
        items.forEach { assertEquals(listOf("SCHEDULE"), repo.history(it.id).map { entry -> entry.actionType }) }
    }

    @Test fun futurePlanningPrecedesHistoricalCleanupAndSharesOneHardItemBudget() {
        val old = (1..3).map { terminal() }
        val future = (1..2).map { create() }.sortedBy { it.id }
        val calls = mutableListOf<String>()
        val runner = worker(schedule = { calls.add("future:${it.id}") }, cancel = {
            calls.add("cancel:${it.occurrenceId}"); CancellationObservation.CALL_RETURNED
        })
        assertEquals(RecoveryBatchResult(3, false, 0), runner.runBatch(3))
        assertEquals(future.map { "future:${it.id}" } + "cancel:${old.first().id}", calls)
        assertEquals(RecoveryBatchResult(2, true, 0), runner.runBatch(3))
        assertEquals(5, calls.size)
    }

    @Test fun cooperativeStopCheckpointsOnlyProcessedItemsAndResumeFinishes() {
        val items = (1..3).map { create() }.sortedBy { it.id }
        val calls = mutableListOf<String>()
        var stop = true
        val runner = worker(schedule = { calls.add(it.id); stop = true })
        assertEquals(RecoveryBatchResult(0, false, 0), runner.runBatch(shouldStop = { stop }))
        assertFalse(file.exists())
        stop = false
        assertEquals(RecoveryBatchResult(1, false, 0), runner.runBatch(shouldStop = { stop }))
        assertEquals(listOf(items.first().id), calls)
        val next = worker(schedule = { calls.add(it.id) }).runBatch()
        assertEquals(RecoveryBatchResult(2, true, 0), next)
        assertEquals(items.map { it.id }, calls)
    }

    @Test fun individualFailuresAdvanceButAreReportedWithoutSuppressingRemainingWork() {
        val old = (1..2).map { terminal() }
        val future = (1..3).map { create() }.sortedBy { it.id }
        val before = (old + future).associate { it.id to repo.history(it.id) }
        val called = mutableListOf<String>()
        val errors = mutableListOf<Exception>()
        val result = worker(schedule = {
            called.add(it.id)
            if (it.id == future.first().id) throw SecurityException("permission changed")
        }, cancel = {
            called.add(it.occurrenceId)
            if (it.occurrenceId == old.first().id) throw IllegalStateException("unknown platform effect")
            CancellationObservation.CALL_RETURNED
        }, onFailure = { errors.add(it) }).runBatch()
        assertEquals(RecoveryBatchResult(5, true, 2), result)
        assertEquals(future.map { it.id } + old.map { it.id }, called)
        assertEquals(2, errors.size)
        before.forEach { (id, history) -> assertEquals(history, repo.history(id)) }
        old.forEach { assertNull(repo.schedulingAttempts(it.id).single().receipt) }
    }

    @Test fun crashAfterEffectBeforeCheckpointReplaysThatItemWithoutAdvancingCursor() {
        val item = create()
        val store = MemoryStore()
        var calls = 0
        val runner = worker(store = store, schedule = { calls++; store.failNext = true })
        assertThrows(IOException::class.java) { runner.runBatch() }
        assertEquals(1, calls)
        assertEquals(RecoveryBatchResult(1, true, 0), worker(store = store, schedule = { calls++ }).runBatch())
        assertEquals(2, calls)
        assertEquals(listOf("SCHEDULE"), repo.history(item.id).map { it.actionType })
    }

    @Test fun newCycleReassertsFutureAlarmsEvenWhenPreviousCycleCompleted() {
        create()
        var calls = 0
        assertTrue(worker(schedule = { calls++ }).runBatch().complete)
        assertEquals(0, worker(schedule = { calls++ }).runBatch().processed)
        assertEquals(1, worker(cycle = "cycle-after-boot", schedule = { calls++ }).runBatch().processed)
        assertEquals(2, calls)
    }

    @Test fun changedRestoreEpochResetsCompletedCursorAndPausedImportsRemainExcluded() {
        assertTrue(worker().runBatch().complete)
        val imported = SqliteOccurrenceRepository(context, clock, "recovery-source.db").use { source ->
            val item = TestAlarmCoordinator(source, clock).create(10)
            repo.restoreBackup(source.exportBackup())
            item
        }
        val calls = mutableListOf<String>()
        assertTrue(worker(schedule = { calls.add(it.id) }).runBatch().complete)
        assertTrue(calls.isEmpty())
        assertEquals(repo.deliveryEpoch(), AtomicRecoveryCursorStore(file).load()!!.epoch)
        assertFalse(repo.isDeliveryAllowed(imported.id))
        assertTrue(repo.activateRestored(imported.id))
        assertEquals(1, worker(cycle = "explicitly-activated", schedule = { calls.add(it.id) }).runBatch().processed)
        assertEquals(listOf(imported.id), calls)
    }

    @Test fun fixedCleanupWatermarkDefersAttemptsCreatedByAnExternalCallback() {
        val first = terminal()
        lateinit var late: RoutineOccurrence
        val calls = mutableListOf<String>()
        val result = worker(cancel = {
            calls.add(it.occurrenceId)
            late = terminal()
            CancellationObservation.CALL_RETURNED
        }).runBatch()
        assertEquals(RecoveryBatchResult(1, true, 0), result)
        assertEquals(listOf(first.id), calls)
        calls.clear()
        worker(cycle = "new-cycle", cancel = { calls.add(it.occurrenceId); CancellationObservation.TOKEN_NOT_FOUND }).runBatch()
        assertEquals(listOf(first.id, late.id), calls)
    }

    @Test fun overdueTriggeredAndTerminalItemsAreNeverAutomaticallyScheduled() {
        val overdue = TestAlarmCoordinator(repo, Clock.offset(clock, java.time.Duration.ofMinutes(-2))).create(1)
        val other = TestAlarmCoordinator(repo, Clock.offset(clock, java.time.Duration.ofMinutes(-2))).create(1)
        TestAlarmCoordinator(repo, clock).trigger(AlarmDelivery.from(other))!!
        terminal()
        val next = create()
        val calls = mutableListOf<String>()
        worker(schedule = { calls.add(it.id) }).runBatch()
        assertEquals(listOf(next.id), calls)
        assertEquals(OccurrenceStatus.SCHEDULED, repo.get(overdue.id)!!.status)
        assertEquals(OccurrenceStatus.TRIGGERED, repo.get(other.id)!!.status)
    }

    @Test fun atomicCursorRoundTripsAndCorruptOrOversizeCursorCanRestartSafely() {
        val store = AtomicRecoveryCursorStore(file)
        val cursor = RecoveryCursor("cycle", "", RecoveryPhase.FUTURE, 42, 1234, "test:ä", 0)
        store.save(cursor)
        assertEquals(cursor, AtomicRecoveryCursorStore(file).load())
        val updated = cursor.copy(cycleId = "next-cycle")
        store.save(updated)
        assertEquals(ShadowLog.getLogsForTag("AtomicFile").joinToString { it.msg }, updated, store.load())
        store.save(cursor)
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size - 1))
        assertNull(store.load())
        store.save(cursor)
        RandomAccessFile(file, "rw").use { it.setLength(AtomicRecoveryCursorStore.MAX_BYTES.toLong() + 1) }
        assertNull(store.load())
        store.save(cursor)
        assertEquals(cursor, store.load())
    }

    @Test fun cursorReadIoFailureIsNotTreatedAsMissingProgress() {
        assertTrue(file.mkdir())
        assertThrows(IOException::class.java) { AtomicRecoveryCursorStore(file).load() }
    }

    @Test fun databaseFailureInsideSchedulingDoesNotConsumeThatRecoveryItem() {
        repo.close()
        var failWrites = false
        repo = SqliteOccurrenceRepository(context, clock, beforeCommit = {
            if (failWrites) throw SQLiteException("injected database commit failure")
        })
        val item = create()
        val store = MemoryStore()
        var effects = 0
        val runner = worker(store = store, schedule = {
            TestAlarmScheduling(repo, clock, { true }, { effects++ }).schedule(it, true)
        })
        failWrites = true
        assertThrows(SQLiteException::class.java) { runner.runBatch() }
        assertEquals(0, effects)
        assertTrue(repo.schedulingAttempts(item.id).isEmpty())
        failWrites = false
        assertEquals(RecoveryBatchResult(1, true, 0), runner.runBatch())
        assertEquals(1, effects)
    }

    @Test fun batchLimitCannotBeDisabledByZeroNegativeOrOversizedInput() {
        val runner = worker()
        listOf(0, -1, 65, Int.MAX_VALUE).forEach { limit ->
            assertThrows(IllegalArgumentException::class.java) { runner.runBatch(limit) }
        }
    }
}
