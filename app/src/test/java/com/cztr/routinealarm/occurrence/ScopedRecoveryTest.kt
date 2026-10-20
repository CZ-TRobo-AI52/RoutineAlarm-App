package com.cztr.routinealarm.occurrence

import android.content.Context
import android.database.sqlite.SQLiteException
import com.cztr.routinealarm.PersonalSchedule
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
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Personal fixtures exercise existing storage without enabling an Android weekly adapter. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ScopedRecoveryTest {
    private lateinit var context: Context
    private lateinit var repo: SqliteOccurrenceRepository
    private lateinit var file: File
    private val zone = ZoneId.of("Europe/Berlin")
    private val date = LocalDate.parse("2026-10-19")
    private val clock = MutableClock(Instant.parse("2026-10-19T12:00:00Z"), zone)
    private val event = PersonalSchedule.allEvents.single { it.id == "evening_messages_2110" }
    private val other = PersonalSchedule.allEvents.single { it.id == "evening_finances_2100" }
    private val scope = OccurrenceScope.PersonalEvent(event.id)
    private class MutableClock(var now: Instant, private val zone: ZoneId) : Clock() {
        override fun instant() = now
        override fun getZone() = zone
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
        context.deleteDatabase("scoped-recovery.db")
        context.deleteDatabase("scoped-restored.db")
        repo = SqliteOccurrenceRepository(context, clock, "scoped-recovery.db")
        file = File(context.noBackupFilesDir, "scoped-recovery-test.bin")
        clearCursor()
    }

    @After fun cleanup() {
        repo.close()
        context.deleteDatabase("scoped-recovery.db")
        context.deleteDatabase("scoped-restored.db")
        clearCursor()
    }

    private fun clearCursor() {
        listOf(file, File(file.path + ".bak"), File(file.path + ".new")).forEach { it.delete() }
    }

    private fun planned(rule: RoutineEvent = event, revision: String = "scoped-fixture-v1") =
        OccurrencePlanner(clock).plan(listOf(rule), date, 1, zone, revision).single()

    private fun personal(rule: RoutineEvent = event, revision: String = "scoped-fixture-v1") =
        planned(rule, revision).also { repo.materialize(listOf(it)) }

    private fun worker(selected: OccurrenceScope = scope, cycle: String = "cycle",
        store: RecoveryCursorStore = AtomicRecoveryCursorStore(file),
        schedule: (RoutineOccurrence) -> Unit = {},
        cancel: (AlarmDelivery) -> CancellationObservation = { CancellationObservation.CALL_RETURNED }) =
        OccurrenceRecovery(repo, clock, selected, schedule, cancel, store, cycle)

    private fun apply(item: RoutineOccurrence, type: ActionType, at: Instant,
        minutes: Int? = null): RoutineOccurrence {
        val result = repo.apply(ExecutionAction(UUID.randomUUID().toString(), item.id, type,
            if (type == ActionType.TRIGGER) Actor.SYSTEM else Actor.USER,
            at, item.revision, item.generation, minutes, "scoped-recovery-test"))
        assertEquals(Outcome.APPLIED, result.outcome)
        return result.occurrence
    }

    private fun attempted(item: RoutineOccurrence, selected: OccurrenceScope = scope) =
        requireNotNull(repo.beginSchedulingAttempt(item, false, selected))

    @Test fun scopeIsFilteredBeforePageLimitAndLeavesOtherRulesAndTestsUntouched() {
        // Synthetic revisions create enough earlier foreign rows to catch post-LIMIT filtering.
        val foreign = (1..80).map { planned(other, "foreign-$it") }
        repo.materialize(foreign)
        val tests = (1..3).map { TestAlarmCoordinator(repo, clock).create(1) }
        val selected = personal()
        val before = repo.exportBackup()
        val calls = mutableListOf<String>()

        assertEquals(RecoveryBatchResult(1, true, 0), worker(schedule = { calls += it.id }).runBatch())

        assertEquals(listOf(selected.id), calls)
        assertArrayEquals(before, repo.exportBackup())
        calls.clear()
        val result = TestAlarmRecovery(repo, clock, { calls += it.id },
            { fail("No stale test attempts"); CancellationObservation.CALL_RETURNED },
            AtomicRecoveryCursorStore(file), "test-adapter").runBatch()
        assertTrue(result.complete)
        assertEquals(tests.map { it.id }.sorted(), calls)
    }

    @Test fun oneHundredThirtyPersonalRowsWithEqualTimesContinueAcrossReopen() {
        // Deliberately synthetic plan revisions stress tie-breaking; no active-plan authority is asserted.
        val items = (1..130).map { planned(revision = "tie-$it") }.sortedBy { it.id }
        repo.materialize(items)
        val calls = mutableListOf<String>()
        listOf(64, 64, 2).forEachIndexed { index, expected ->
            val result = worker(schedule = { calls += it.id }).runBatch()
            assertEquals(expected, result.processed)
            assertEquals(index == 2, result.complete)
            assertEquals(0, result.failed)
            repo.close()
            repo = SqliteOccurrenceRepository(context, clock, "scoped-recovery.db")
        }
        assertEquals(items.map { it.id }, calls)
        assertEquals(scope.key, AtomicRecoveryCursorStore(file).load()!!.scopeKey)
        assertEquals(RecoveryBatchResult(0, true, 0),
            worker(schedule = { fail("Completed cycle was replayed") }).runBatch())
    }

    @Test fun sameCycleAndEpochButDifferentScopeRestartsTheCursor() {
        val selected = personal()
        val foreign = personal(other)
        val test = TestAlarmCoordinator(repo, clock).create(1)
        val store = MemoryStore()
        val calls = mutableListOf<String>()
        val scopes = listOf(OccurrenceScope.Tests, scope, OccurrenceScope.PersonalEvent(other.id))
        scopes.forEach { selectedScope ->
            assertEquals(RecoveryBatchResult(1, true, 0),
                worker(selectedScope, store = store, schedule = { calls += it.id }).runBatch())
            assertEquals(selectedScope.key, store.cursor!!.scopeKey)
        }
        assertEquals(listOf(test.id, selected.id, foreign.id), calls)
    }

    /** Independent published-v1 byte layout; never serialized using the new writer. */
    private fun writeCursorBytes(version: Int, scopeKey: String? = null) {
        DataOutputStream(file.outputStream()).use { out ->
            fun text(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                out.writeInt(bytes.size)
                out.write(bytes)
            }
            out.writeInt(0x52353243)
            out.writeInt(version)
            text("cycle")
            text("")
            out.writeInt(RecoveryPhase.COMPLETE.ordinal)
            out.writeLong(0)
            out.writeBoolean(false)
            out.writeLong(0)
            if (scopeKey != null) text(scopeKey)
        }
    }

    @Test fun publishedVersionOneCompleteCursorIsOnlyReusableForTests() {
        val selected = personal()
        TestAlarmCoordinator(repo, clock).create(1)
        writeCursorBytes(1)
        val store = AtomicRecoveryCursorStore(file)
        assertEquals("tests", store.load()!!.scopeKey)
        val originalBytes = file.readBytes()
        assertEquals(RecoveryBatchResult(0, true, 0), worker(OccurrenceScope.Tests,
            schedule = { fail("Legacy test cursor should remain complete") }).runBatch())
        assertArrayEquals(originalBytes, file.readBytes())
        val calls = mutableListOf<String>()

        assertEquals(RecoveryBatchResult(1, true, 0), worker(schedule = { calls += it.id }).runBatch())

        assertEquals(listOf(selected.id), calls)
        assertEquals(scope.key, store.load()!!.scopeKey)
    }

    @Test fun versionTwoPersistsBoundedUnicodeScopeAndRejectsMalformedCursor() {
        val store = AtomicRecoveryCursorStore(file)
        val bounded = OccurrenceScope.PersonalEvent("ü".repeat(128))
        val cursor = RecoveryCursor("cycle", "", RecoveryPhase.FUTURE, 42, 1234, "id", 0, bounded.key)
        store.save(cursor)
        assertEquals(cursor, AtomicRecoveryCursorStore(file).load())
        val valid = file.readBytes()
        file.writeBytes(valid.copyOf(valid.size - 1))
        assertNull(store.load())
        writeCursorBytes(2, "personal:")
        assertNull(store.load())
        writeCursorBytes(2, "unknown-scope")
        assertNull(store.load())
        writeCursorBytes(2, "personal:" + "x".repeat(257))
        assertNull(store.load())
        writeCursorBytes(99, scope.key)
        assertNull(store.load())
        store.save(cursor)
        assertEquals(cursor, store.load())
    }

    @Test fun personalCleanupKeepsOverdueAndTriggeredTokensAndPreservesUnknownReceipts() {
        val done = personal(revision = "done")
        val shifted = personal(revision = "shifted")
        val waiting = personal(revision = "waiting")
        val triggered = personal(revision = "triggered")
        val foreign = personal(other)
        listOf(done, shifted, waiting, triggered).forEach { attempted(it) }
        attempted(foreign, OccurrenceScope.PersonalEvent(other.id))
        clock.now = done.plannedAt.plusSeconds(1)
        apply(apply(done, ActionType.TRIGGER, done.plannedAt), ActionType.DONE, clock.now)
        val snoozed = apply(apply(shifted, ActionType.TRIGGER, shifted.plannedAt),
            ActionType.SNOOZE, clock.now, 5)
        apply(triggered, ActionType.TRIGGER, triggered.plannedAt)
        apply(apply(foreign, ActionType.TRIGGER, foreign.plannedAt), ActionType.DONE, clock.now)
        val before = repo.exportBackup()
        val schedules = mutableListOf<String>()
        val cancellations = mutableListOf<AlarmDelivery>()

        val result = worker(schedule = { schedules += it.id }, cancel = {
            cancellations += it; CancellationObservation.CALL_RETURNED
        }).runBatch()

        assertEquals(RecoveryBatchResult(5, true, 0), result)
        assertEquals(listOf(snoozed.id), schedules)
        assertEquals(listOf(AlarmDelivery.from(done), AlarmDelivery.from(shifted)), cancellations)
        assertArrayEquals(before, repo.exportBackup())
        listOf(done, shifted, waiting, triggered, foreign).forEach {
            assertNull(repo.schedulingAttempts(it.id).single().receipt)
        }
        assertFalse(TestAlarmReconciliation.isObsolete(repo, AlarmDelivery.from(done)))
        assertFalse(OccurrenceReconciliation.isObsolete(repo, AlarmDelivery.from(foreign), scope))
        val repeated = mutableListOf<AlarmDelivery>()
        val summary = OccurrenceReconciliation(repo, scope, {
            repeated += it; CancellationObservation.CALL_RETURNED
        }).reconcile()
        assertEquals(cancellations, repeated)
        assertEquals(ReconciliationResult(4, 2, 0, 2, 0), summary)
        assertArrayEquals(before, repo.exportBackup())
    }

    @Test fun actualRestoreRotatesEpochAndPersonalRowsStayPausedUntilExplicitActivation() {
        val item = personal()
        val attempt = attempted(item)
        repo.recordSchedulingReceipt(attempt.id, SchedulingOutcome.CALL_RETURNED, SchedulingReason.RETURNED)
        val history = repo.history(item.id)
        val attempts = repo.schedulingAttempts(item.id)
        val backup = repo.exportBackup()
        AtomicRecoveryCursorStore(file).save(
            RecoveryCursor("cycle", "", RecoveryPhase.COMPLETE, 0, scopeKey = scope.key))
        repo.close()
        repo = SqliteOccurrenceRepository(context, clock, "scoped-restored.db")
        assertEquals(1, repo.restoreBackup(backup))
        val newEpoch = repo.deliveryEpoch()
        assertNotEquals("", newEpoch)
        val calls = mutableListOf<String>()
        assertTrue(worker(schedule = { calls += it.id },
            cancel = { fail("Paused current token is not obsolete"); CancellationObservation.CALL_RETURNED })
            .runBatch().complete)
        assertTrue(calls.isEmpty())
        assertEquals(newEpoch, AtomicRecoveryCursorStore(file).load()!!.epoch)
        assertTrue(repo.activateRestored(item.id))

        worker(cycle = "explicit-activation", schedule = { calls += it.id }).runBatch()

        assertEquals(listOf(item.id), calls)
        assertFalse(OccurrenceReconciliation.isObsolete(repo, AlarmDelivery.from(item), scope))
        assertEquals(history, repo.history(item.id))
        assertEquals(attempts, repo.schedulingAttempts(item.id))
    }

    @Test fun personalSchedulingCommitFailureLeavesItemRetryableWithoutPlatformEffect() {
        repo.close()
        var failCommit = false
        repo = SqliteOccurrenceRepository(context, clock, "scoped-recovery.db", beforeCommit = {
            if (failCommit) throw SQLiteException("injected commit failure")
        })
        val item = personal()
        val store = MemoryStore()
        var effects = 0
        val runner = worker(store = store, schedule = {
            requireNotNull(repo.beginSchedulingAttempt(it, true, scope))
            effects++
        })
        failCommit = true
        assertThrows(SQLiteException::class.java) { runner.runBatch() }
        assertEquals(0, effects)
        assertNull(store.cursor!!.futureAfterId)
        assertTrue(repo.schedulingAttempts(item.id).isEmpty())
        failCommit = false

        assertEquals(RecoveryBatchResult(1, true, 0), runner.runBatch())

        assertEquals(1, effects)
        assertNull(repo.schedulingAttempts(item.id).single().receipt)
    }

    @Test fun personalEffectBeforeFailedCheckpointIsReplayedWithinTheSameScope() {
        val item = personal()
        val store = MemoryStore()
        var effects = 0
        assertThrows(IOException::class.java) {
            worker(store = store, schedule = { effects++; store.failNext = true }).runBatch()
        }
        assertEquals(1, effects)
        assertNull(store.cursor!!.futureAfterId)
        assertEquals(scope.key, store.cursor!!.scopeKey)

        assertEquals(RecoveryBatchResult(1, true, 0),
            worker(store = store, schedule = { effects++ }).runBatch())

        assertEquals(2, effects)
        assertEquals(listOf("SCHEDULE"), repo.history(item.id).map { it.actionType })
    }
}
