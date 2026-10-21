package com.cztr.routinealarm.occurrence

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

/** Deterministic host interruption boundaries; not an OS process-kill or device proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PersonalPlanInterruptionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val name = "plan-interruption.db"
    private val zone = ZoneId.of("Europe/Berlin")
    private val cutoff = Instant.parse("2026-10-20T12:00:00Z")
    private val clock = Clock.fixed(cutoff, zone)
    private val rule = RoutineEvent("personal", DayOfWeek.entries.toSet(), 21, 10,
        "Messages", category = EventCategory.PERSONAL, mode = EventMode.ANNOUNCEMENT)
    private val scope = OccurrenceScope.PersonalEvent(rule.id)
    private fun item(revision: String) =
        OccurrencePlanner(clock).plan(listOf(rule), LocalDate.parse("2026-10-20"), 1, zone, revision).single()
    private fun repo(db: String = name) = SqliteOccurrenceRepository(context, clock, db)
    private fun replace(repo: SqliteOccurrenceRepository, version: Long, revision: String) =
        repo.replacePersonalPlan(scope, version, listOf(item(revision)), revision, zone, cutoff)
    private class LostAcknowledgement : Error("simulated lost acknowledgement")
    @After fun cleanup() { context.deleteDatabase(name); context.deleteDatabase("restore-interruption.db") }

    @Test fun replacementAfterAttemptCommitStopsOldPlatformCallAndSurvivesReopen() {
        val old = item("A")
        var calls = 0
        repo().use { repository ->
            replace(repository, 0, "A")
            val scheduling = OccurrenceScheduling(repository, clock, scope, { true }, { calls++ },
                afterAttempt = { replace(repository, 1, "B") })
            assertFalse(scheduling.schedule(old, true))
        }
        repo().use { repository ->
            assertEquals(0, calls)
            assertEquals(2L, repository.personalPlanAuthority(scope).version)
            assertEquals(listOf(item("B")), repository.futureOccurrencesPage(scope, cutoff))
            val attempt = repository.schedulingAttempts(old.id).single()
            assertEquals(SchedulingOutcome.NOT_CALLED, attempt.receipt!!.outcome)
            assertEquals(SchedulingReason.STALE, attempt.receipt!!.reason)
            assertFalse(repository.isDeliveryAllowed(old.id))
            assertEquals(1, repository.history(old.id).size)
        }
    }

    @Test fun replacementAfterPlatformEffectPreservesMissingReceiptAndRetirement() {
        val old = item("A")
        var calls = 0
        repo().use { repository ->
            replace(repository, 0, "A")
            val scheduling = OccurrenceScheduling(repository, clock, scope, { true },
                { calls++; replace(repository, 1, "B") },
                afterPlatformReturn = { throw LostAcknowledgement() })
            assertThrows(LostAcknowledgement::class.java) { scheduling.schedule(old, true) }
        }
        repo().use { repository ->
            assertEquals(1, calls)
            val attempt = repository.schedulingAttempts(old.id).single()
            assertNull(attempt.receipt)
            assertTrue(attempt.effectUnknown)
            val token = repository.knownDeliveries(scope, repository.schedulingWatermark(), 0).single().token
            assertNull(OccurrenceDeliveryGate(repository, scope::accepts).actionTarget(token))
            assertTrue(OccurrenceReconciliation.isObsolete(repository, token, scope))
            var cancellations = 0
            val failed = OccurrenceReconciliation(repository, scope, {
                assertEquals(token, it); cancellations++; throw IllegalStateException("adapter interrupted")
            }).reconcile()
            assertEquals(1, failed.failed)
            assertNull(repository.schedulingAttempts(old.id).single().receipt)
            val retried = OccurrenceReconciliation(repository, scope, {
                assertEquals(token, it); cancellations++; CancellationObservation.CALL_RETURNED
            }).reconcile()
            assertEquals(1, retried.callReturned)
            assertEquals(2, cancellations)
            // A cancellation return does not resolve the historical scheduling observation.
            assertTrue(repository.schedulingAttempts(old.id).single().effectUnknown)
            repository.recordSchedulingReceipt(attempt.id, SchedulingOutcome.CALL_RETURNED, SchedulingReason.RETURNED)
            assertFalse(repository.isDeliveryAllowed(old.id))
            assertNull(OccurrenceDeliveryGate(repository, scope::accepts).actionTarget(token))
            assertEquals(listOf(item("B")), repository.futureOccurrencesPage(scope, cutoff))
        }
        repo().use { repository ->
            assertEquals(2L, repository.personalPlanAuthority(scope).version)
            assertTrue(repository.schedulingAttempts(old.id).single().effectUnknown)
            assertEquals(SchedulingOutcome.CALL_RETURNED,
                repository.schedulingAttempts(old.id).single().receipt!!.outcome)
            assertFalse(repository.isDeliveryAllowed(old.id))
        }
    }

    @Test fun lostReplacementAcknowledgementReconcilesVersionWithoutDuplicateCommit() {
        repo().use { repository ->
            replace(repository, 0, "A")
            assertThrows(LostAcknowledgement::class.java) {
                replace(repository, 1, "B")
                throw LostAcknowledgement()
            }
        }
        repo().use { repository ->
            val saved = repository.exportBackup()
            assertEquals(PersonalPlanDecision.Conflict(2), replace(repository, 1, "B"))
            val retry = replace(repository, 2, "B") as PersonalPlanDecision.Ready
            assertEquals(2L, retry.next.version)
            assertTrue(retry.insertions.isEmpty())
            assertTrue(retry.retireIds.isEmpty())
            assertArrayEquals(saved, repository.exportBackup())
            assertEquals(1, repository.history(item("A").id).size)
            assertEquals(1, repository.history(item("B").id).size)
        }
    }

    @Test fun lostRestoreAcknowledgementCannotRotateEpochOrActivateOnRetry() {
        val bytes = repo().use { replace(it, 0, "A"); replace(it, 1, "B"); it.exportBackup() }
        var committedEpoch = ""
        repo("restore-interruption.db").use { repository ->
            assertThrows(LostAcknowledgement::class.java) {
                assertEquals(2, repository.restoreBackup(bytes))
                committedEpoch = repository.deliveryEpoch()
                throw LostAcknowledgement()
            }
        }
        repo("restore-interruption.db").use { repository ->
            assertTrue(committedEpoch.isNotBlank())
            assertEquals(committedEpoch, repository.deliveryEpoch())
            assertEquals(2, repository.restorePausedCount())
            assertThrows(IllegalStateException::class.java) { repository.restoreBackup(bytes) }
            assertEquals(committedEpoch, repository.deliveryEpoch())
            assertEquals(2, repository.restorePausedCount())
            assertEquals(2L, repository.personalPlanAuthority(scope).version)
            assertFalse(repository.activateRestored(item("B").id))
            assertFalse(repository.isDeliveryAllowed(item("B").id))
            assertTrue(repository.futureOccurrencesPage(scope, cutoff).isEmpty())
            assertArrayEquals(bytes, repository.exportBackup())
        }
    }
}
