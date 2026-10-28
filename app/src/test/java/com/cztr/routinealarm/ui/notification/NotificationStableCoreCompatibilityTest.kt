package com.cztr.routinealarm.ui.notification

import android.content.Context
import android.content.Intent
import com.cztr.routinealarm.execution.TestAlarmReceiver
import com.cztr.routinealarm.execution.TestAlarmRuntime
import com.cztr.routinealarm.occurrence.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Isolated source-bound characterization, not a wired Android notification consumer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NotificationStableCoreCompatibilityTest {
    private val context: android.app.Application get() = RuntimeEnvironment.getApplication()
    private val now = Instant.parse("2026-10-27T12:00:00.123456789Z")
    private val clock get() = Clock.fixed(now, ZoneOffset.UTC)
    private lateinit var repo: SqliteOccurrenceRepository
    private lateinit var token: AlarmDelivery
    private fun core(at: Instant = now) = StableTestAlarmActions(repo, Clock.fixed(at, ZoneOffset.UTC))
    @Before fun setup() {
        context.deleteDatabase("c2-compatibility.db")
        repo = SqliteOccurrenceRepository(context, clock, "c2-compatibility.db")
        val planned = TestAlarmCoordinator(repo, Clock.fixed(now.minusSeconds(61), ZoneOffset.UTC)).create(1)
        val fired = TestAlarmCoordinator(repo, clock).trigger(AlarmDelivery.from(planned, repo.deliveryEpoch()))!!
        token = AlarmDelivery.from(fired, repo.deliveryEpoch())
    }
    @After fun cleanup() { repo.close(); context.deleteDatabase("c2-compatibility.db") }
    private fun intent() = TestAlarmRuntime.putToken(Intent(context, TestAlarmReceiver::class.java), token)
    private fun route(snooze: Boolean = false) = NotificationActionRoute(
        intent().dataString!!, "TEST", "compatibility-fixture-plan",
        if (snooze) MenuStage.SNOOZE_LONG else MenuStage.ROOT,
        if (snooze) MenuChoice.THREE else MenuChoice.DONE)
    private fun adapt(route: NotificationActionRoute, action: ExecutionAction?) =
        NotificationActionAdapter.prepare(NotificationActionRoutes.encode(route), intent(),
            action?.let { FrozenActionBinding(NotificationActionRoutes.requestKey(route), it) },
            NotificationOperation.entries.toSet())
    private fun reopen() { repo.close(); repo = SqliteOccurrenceRepository(context, clock, "c2-compatibility.db") }

    @Test fun prepareSubmitRecoverPreservesDistinctKeyDomainsAndOriginalPayload() {
        val route = route()
        val key = NotificationActionRoutes.requestKey(route)
        assertThrows(IllegalArgumentException::class.java) { core().prepare(key, token, ActionType.DONE) }
        // Explicit test-consumer mapping only; no new product request authority is introduced.
        val original = core().prepare("test-user:" + key, token, ActionType.DONE)
        val candidate = adapt(route, original) as PreparedNotification.ActionCandidate
        assertSame(original, candidate.originalAction)
        assertEquals(now, candidate.originalAction.occurredAt)
        assertEquals(key, candidate.requestKey)
        val applied = core().submit(candidate.originalAction)
        assertEquals(Outcome.APPLIED, applied.outcome)
        assertFalse(applied.duplicate)
        assertEquals(JournalFeedback.LOCAL_STATE_ONLY, NotificationActionAdapter.feedback(applied))
        val history = repo.history(token.occurrenceId)
        reopen()
        val recovered = core(now.plusSeconds(100)).recover(original.id)!!
        assertEquals(original, recovered)
        val retried = adapt(route, recovered) as PreparedNotification.ActionCandidate
        val duplicate = core(now.plusSeconds(100)).submit(retried.originalAction)
        assertTrue(duplicate.duplicate)
        assertEquals(history, repo.history(token.occurrenceId))
        assertEquals(JournalFeedback.HISTORICAL_ONLY, NotificationActionAdapter.feedback(duplicate))
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test fun missingCommittedPayloadCannotBeRecreatedByAdapter() {
        val route = route()
        val missing = core().recover("test-user:" + NotificationActionRoutes.requestKey(route))
        assertNull(missing)
        assertEquals(PreparedNotification.Rejected(PreparationError.MISSING_FROZEN_ACTION), adapt(route, missing))
        assertEquals(2, repo.history(token.occurrenceId).size)
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test fun restoredHistoricalPayloadIsReadableButCurrentCoreRejectsItsEpoch() {
        val route = route(true)
        val original = core().prepare("test-user:" + NotificationActionRoutes.requestKey(route), token, ActionType.SNOOZE, 3)
        val candidate = adapt(route, original) as PreparedNotification.ActionCandidate
        assertEquals(Outcome.APPLIED, core().submit(candidate.originalAction).outcome)
        val backup = repo.exportBackup()
        repo.close(); context.deleteDatabase("c2-compatibility.db")
        repo = SqliteOccurrenceRepository(context, clock, "c2-compatibility.db")
        repo.restoreBackup(backup)
        assertEquals(original, core().recover(original.id))
        assertNotEquals(token.epoch, repo.deliveryEpoch())
        val historical = adapt(route, original) as PreparedNotification.ActionCandidate
        val history = repo.history(token.occurrenceId)
        assertThrows(IllegalStateException::class.java) { core().submit(historical.originalAction) }
        assertEquals(history, repo.history(token.occurrenceId))
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test fun changedReplayTimeReachesExistingCoreConflictWithoutNewIdentity() {
        val route = route()
        val original = core().prepare("test-user:" + NotificationActionRoutes.requestKey(route), token, ActionType.DONE)
        core().submit((adapt(route, original) as PreparedNotification.ActionCandidate).originalAction)
        val changed = original.copy(occurredAt = original.occurredAt.plusNanos(1))
        val candidate = adapt(route, changed) as PreparedNotification.ActionCandidate
        assertSame(changed, candidate.originalAction)
        assertThrows(ActionIdConflict::class.java) { core().submit(candidate.originalAction) }
        assertEquals(original, core().recover(original.id))
        assertEquals(3, repo.history(token.occurrenceId).size)
    }
}
