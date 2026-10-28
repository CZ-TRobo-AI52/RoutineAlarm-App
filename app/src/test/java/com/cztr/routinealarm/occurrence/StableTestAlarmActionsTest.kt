package com.cztr.routinealarm.occurrence

import android.content.Context
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class StableTestAlarmActionsTest {
    private lateinit var context: Context
    private lateinit var repo: SqliteOccurrenceRepository
    private lateinit var fired: RoutineOccurrence
    private val now = Instant.parse("2026-10-27T12:00:00.123456789Z")
    private fun clock() = Clock.fixed(now, ZoneOffset.UTC)
    private fun api() = StableTestAlarmActions(repo, clock())
    private fun request(type: ActionType = ActionType.DONE, minutes: Int? = null, id: String = "test-user:one") =
        api().prepare(id, AlarmDelivery.from(fired, repo.deliveryEpoch()), type, minutes)
    private fun reopen() { repo.close(); repo = SqliteOccurrenceRepository(context, clock(), "stable.db") }
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        repo = SqliteOccurrenceRepository(context, clock(), "stable.db")
        val coordinator = TestAlarmCoordinator(repo, Clock.fixed(now.minusSeconds(61), ZoneOffset.UTC))
        val row = coordinator.create(1)
        fired = TestAlarmCoordinator(repo, clock()).trigger(AlarmDelivery.from(row, repo.deliveryEpoch()))!!
    }
    @After fun cleanup() { repo.close(); context.deleteDatabase("stable.db") }

    @Test fun lostAcknowledgementReopensOriginalPayloadWithoutSecondTransition() {
        val action = request(); val first = api().submit(action)!!
        reopen()
        val recovered = api().recover(action.id)
        assertEquals(action, recovered)
        assertEquals(first.copy(duplicate = true), api().submit(recovered!!))
        assertEquals(3, repo.history(fired.id).size)
        assertEquals(2L, repo.get(fired.id)!!.revision)
    }
    @Test fun historicalSnoozeReplayCannotReplaceCurrentDone() {
        val action = request(ActionType.SNOOZE, 3); val snoozed = api().submit(action)!!
        val done = api().prepare("test-user:done", AlarmDelivery.from(snoozed.occurrence, repo.deliveryEpoch()), ActionType.DONE)
        assertEquals(Outcome.APPLIED, api().submit(done)!!.outcome)
        reopen()
        assertEquals(snoozed.copy(duplicate = true), api().submit(action))
        assertEquals(OccurrenceStatus.DONE, repo.get(fired.id)!!.status)
        assertEquals(4, repo.history(fired.id).size)
    }
    @Test fun changedNanosecondIsIdentityConflict() {
        val a = request(); api().submit(a)
        assertThrows(ActionIdConflict::class.java) { api().submit(a.copy(occurredAt = a.occurredAt.plusNanos(1))) }
        assertEquals(3, repo.history(fired.id).size)
    }
    @Test fun changedTypeIsIdentityConflict() {
        val a = request(); api().submit(a)
        assertThrows(ActionIdConflict::class.java) { api().submit(a.copy(type = ActionType.SKIP)) }
    }
    @Test fun changedMinutesAreIdentityConflict() {
        val a = request(ActionType.SNOOZE, 1); api().submit(a)
        assertThrows(ActionIdConflict::class.java) { api().submit(a.copy(snoozeMinutes = 5)) }
    }
    @Test fun allSnoozeDurationsKeepFrozenTimeOnRetry() {
        for (minutes in 1..5) {
            val coordinator = TestAlarmCoordinator(repo, Clock.fixed(now.minusSeconds(61), ZoneOffset.UTC))
            val row = coordinator.create(1)
            val active = TestAlarmCoordinator(repo, clock()).trigger(AlarmDelivery.from(row, repo.deliveryEpoch()))!!
            val a = api().prepare("test-user:snooze-$minutes", AlarmDelivery.from(active, repo.deliveryEpoch()), ActionType.SNOOZE, minutes)
            val first = api().submit(a)!!
            val replay = StableTestAlarmActions(repo, Clock.fixed(now.plusSeconds(100), ZoneOffset.UTC)).submit(a)!!
            assertTrue(replay.duplicate); assertEquals(first.occurrence, replay.occurrence)
            assertEquals(Instant.ofEpochMilli(now.toEpochMilli()).plusSeconds(minutes * 60L), replay.occurrence.currentTriggerAt)
            assertEquals(3, repo.history(row.id).size)
        }
    }
    @Test fun competingActionsHaveOneWinnerAndDurableConflict() {
        val a = request(); val b = request(ActionType.SKIP, id = "test-user:two")
        assertEquals(Outcome.APPLIED, api().submit(a)!!.outcome)
        val loser = api().submit(b)!!; assertEquals(Outcome.CONFLICT, loser.outcome)
        reopen(); assertEquals(loser.copy(duplicate = true), api().submit(b))
        assertEquals(4, repo.history(fired.id).size)
    }
    @Test fun beforeCommitFailureLeavesNoBindingAndOriginalRetryAppliesOnce() {
        val a = request(); repo.close()
        repo = SqliteOccurrenceRepository(context, clock(), "stable.db", beforeCommit = { error("injected") })
        assertThrows(IllegalStateException::class.java) { api().submit(a) }
        reopen(); assertNull(api().recover(a.id)); assertEquals(fired, repo.get(fired.id))
        assertEquals(Outcome.APPLIED, api().submit(a)!!.outcome)
        assertTrue(api().submit(a)!!.duplicate)
    }
    @Test fun unknownRequestDoesNotInventOriginalAction() {
        assertNull(api().recover("test-user:missing")); assertEquals(2, repo.history(fired.id).size)
    }
    @Test fun foreignConsumerCannotEnterJournal() {
        assertThrows(IllegalArgumentException::class.java) { api().submit(request().copy(source = "training")) }
        assertEquals(2, repo.history(fired.id).size)
    }
    @Test fun systemActorCannotEnterUserAdapter() {
        assertThrows(IllegalArgumentException::class.java) { api().submit(request().copy(actor = Actor.SYSTEM)) }
        assertEquals(2, repo.history(fired.id).size)
    }
    @Test fun staleEpochCannotWrite() {
        assertThrows(IllegalStateException::class.java) { api().submit(request().copy(source = "test-alarm-screen/1;epoch=wrong")) }
        assertEquals(2, repo.history(fired.id).size)
    }
    @Test fun restoredOriginalIsReadableButCannotAuthorizeNewApplication() {
        val a = request(ActionType.SNOOZE, 3); api().submit(a); val bytes = repo.exportBackup()
        repo.close(); context.deleteDatabase("stable.db")
        repo = SqliteOccurrenceRepository(context, clock(), "stable.db")
        repo.restoreBackup(bytes); reopen()
        assertEquals(a, api().recover(a.id))
        assertThrows(IllegalStateException::class.java) { api().submit(a) }
        assertEquals(1, repo.restorePausedCount()); assertEquals(3, repo.history(fired.id).size)
    }
    @Test fun personalScopeCannotEnterAdapter() {
        val personal = fired.copy(id = "personal", isTest = false, status = OccurrenceStatus.SCHEDULED, revision = 0); repo.materialize(listOf(personal))
        val a = api().prepare("test-user:personal", AlarmDelivery.from(personal, repo.deliveryEpoch()), ActionType.DONE)
        assertThrows(IllegalStateException::class.java) { api().submit(a) }
        assertEquals(1, repo.history(personal.id).size)
    }
    @Test fun oldActorPayloadCannotBeMistakenForAdapterRequest() {
        assertNull(api().recover(repo.history(fired.id).last().actionId))
    }
    @Test fun invalidRequestShapeFailsBeforeJournal() {
        for (minutes in listOf(0, 6)) assertThrows(IllegalArgumentException::class.java) { request(ActionType.SNOOZE, minutes) }
        assertThrows(IllegalArgumentException::class.java) { request(ActionType.DONE, 1) }
        assertThrows(IllegalArgumentException::class.java) { request(id = "schedule:forged") }
        assertEquals(2, repo.history(fired.id).size)
    }

    private fun race(first: ExecutionAction, second: ExecutionAction): List<Pair<ExecutionAction, ActionResult?>> {
        val ready = java.util.concurrent.CountDownLatch(2)
        val start = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        return try {
            val futures = listOf(first, second).map { action -> pool.submit(java.util.concurrent.Callable {
                SqliteOccurrenceRepository(context, clock(), "stable.db").use { connection ->
                    connection.get(action.occurrenceId)
                    ready.countDown()
                    check(start.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    val result = try { StableTestAlarmActions(connection, clock()).submit(action) }
                        catch (_: ActionIdConflict) { null }
                    action to result
                }
            }) }
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS))
            start.countDown()
            futures.map { it.get(20, java.util.concurrent.TimeUnit.SECONDS) }
        } finally { start.countDown(); pool.shutdownNow() }
    }

    @Test fun concurrentSameRequestIdCannotBindTwoPayloads() {
        val first = request(ActionType.SNOOZE, 1, "test-user:concurrent")
        val second = first.copy(snoozeMinutes = 5, occurredAt = first.occurredAt.plusNanos(1))
        val results = race(first, second)
        val winner = results.single { it.second != null }
        val loser = results.single { it.second == null }.first
        assertEquals(Outcome.APPLIED, winner.second!!.outcome)
        assertFalse(winner.second!!.duplicate)
        reopen()
        assertEquals(winner.first, api().recover(first.id))
        assertEquals(winner.second!!.copy(duplicate = true), api().submit(winner.first))
        assertThrows(ActionIdConflict::class.java) { api().submit(loser) }
        assertEquals(3, repo.history(fired.id).size)
        assertEquals(2L, repo.get(fired.id)!!.revision)
        assertEquals(1L, repo.get(fired.id)!!.generation)
        assertEquals(Instant.ofEpochMilli(winner.first.occurredAt.toEpochMilli()).plusSeconds(winner.first.snoozeMinutes!! * 60L), repo.get(fired.id)!!.currentTriggerAt)
    }

    @Test fun concurrentIdenticalRequestCommitsOnceAndReplaysOnce() {
        val action = request(ActionType.SNOOZE, 3, "test-user:identical")
        val results = race(action, action).map { it.second!! }
        assertTrue(results.all { it.outcome == Outcome.APPLIED })
        assertEquals(1, results.count { it.duplicate })
        assertEquals(results[0].occurrence, results[1].occurrence)
        reopen()
        assertEquals(action, api().recover(action.id))
        assertTrue(api().submit(action).duplicate)
        assertEquals(3, repo.history(fired.id).size)
        assertEquals(2L, repo.get(fired.id)!!.revision)
        assertEquals(1L, repo.get(fired.id)!!.generation)
    }
}
