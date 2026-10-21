package com.cztr.routinealarm.occurrence

import android.database.sqlite.SQLiteDatabase
import com.cztr.routinealarm.*
import java.time.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PersonalPlanPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val name = "plan.db"
    private val zone = ZoneId.of("Europe/Berlin")
    private val cutoff = Instant.parse("2026-10-20T12:00:00Z")
    private val clock = Clock.fixed(cutoff, zone)
    private val rule = RoutineEvent("personal", DayOfWeek.entries.toSet(), 21, 10,
        "Messages", category = EventCategory.PERSONAL, mode = EventMode.ANNOUNCEMENT)
    private val scope = OccurrenceScope.PersonalEvent(rule.id)
    private fun item(revision: String, event: RoutineEvent = rule) =
        OccurrencePlanner(clock).plan(listOf(event), LocalDate.parse("2026-10-20"), 1, zone, revision).single()
    private fun repo(hook: () -> Unit = {}) = SqliteOccurrenceRepository(context, clock, name, hook)
    private fun replace(repo: SqliteOccurrenceRepository, version: Long, revision: String,
        items: List<RoutineOccurrence> = listOf(item(revision))) =
        repo.replacePersonalPlan(scope, version, items, revision, zone, cutoff)
    @After fun cleanup() { context.deleteDatabase(name); context.deleteDatabase("copy.db") }

    @Test fun replacementSurvivesReopenAndRetainsJournalAndUnknownEffects() {
        val old = item("A"); val fresh = item("B")
        repo().use {
            replace(it, 0, "A")
            it.beginSchedulingAttempt(old, true, scope)
            val history = it.history(old.id)
            val result = replace(it, 1, "B") as PersonalPlanDecision.Ready
            assertEquals(setOf(old.id), result.retireIds)
            assertEquals(history, it.history(old.id))
            assertEquals(old, it.get(old.id))
        }
        repo().use {
            assertEquals(2L, it.personalPlanAuthority(scope).version)
            assertEquals("B", it.personalPlanAuthority(scope).activeRevision)
            assertEquals(setOf(old.id), it.personalPlanAuthority(scope).retiredIds)
            assertFalse(it.isDeliveryAllowed(old.id))
            assertTrue(it.isDeliveryAllowed(fresh.id))
            assertEquals(listOf(fresh), it.futureOccurrencesPage(scope, cutoff))
            assertNull(it.schedulingAttempts(old.id).single().receipt)
            val known = it.knownDeliveries(scope, it.schedulingWatermark(), 0).single()
            assertTrue(OccurrenceReconciliation.isObsolete(it, known.token, scope))
            assertFalse(OccurrenceReconciliation.isObsolete(it, known.token, OccurrenceScope.Tests))
            assertNull(OccurrenceDeliveryGate(it, scope::accepts).actionTarget(known.token))
            assertThrows(IllegalStateException::class.java) { it.beginSchedulingAttempt(old, true, scope) }
        }
    }

    @Test fun staleWriterAndIdenticalRetryCannotCreateExtraRows() {
        repo().use {
            replace(it, 0, "A")
            assertEquals(PersonalPlanDecision.Conflict(1), replace(it, 0, "B"))
            assertNull(it.get(item("B").id))
            val retry = replace(it, 1, "A") as PersonalPlanDecision.Ready
            assertEquals(1L, retry.next.version)
            assertTrue(retry.insertions.isEmpty())
            assertEquals(1, it.history(item("A").id).size)
            assertThrows(IllegalArgumentException::class.java) {
                replace(it, 1, "A", listOf(item("A").copy(title = "Changed")))
            }
        }
    }

    @Test fun commitFailureRollsBackAuthorityOccurrencesJournalAndRevocation() {
        repo().use { replace(it, 0, "A") }
        repo { error("simulated commit interruption") }.use {
            assertThrows(IllegalStateException::class.java) { replace(it, 1, "B") }
        }
        repo().use {
            assertEquals(1L, it.personalPlanAuthority(scope).version)
            assertTrue(it.isDeliveryAllowed(item("A").id))
            assertNull(it.get(item("B").id))
            assertTrue(it.history(item("B").id).isEmpty())
        }
    }

    @Test fun unrelatedEventAndTestsKeepTheirPermission() {
        val other = item("OTHER", rule.copy(id = "other"))
        val test = item("TEST", rule.copy(id = "test")).copy(isTest = true)
        repo().use {
            it.materialize(listOf(other, test))
            replace(it, 0, "A")
            replace(it, 1, "OFF", emptyList())
            assertTrue(it.isDeliveryAllowed(other.id))
            assertTrue(it.isDeliveryAllowed(test.id))
            assertEquals(0L, it.personalPlanAuthority(OccurrenceScope.PersonalEvent("other")).version)
            assertThrows(IllegalArgumentException::class.java) { replace(it, 2, "A") }
        }
    }

    @Test fun pendingBackupFormatCannotSilentlyLosePlanAuthority() {
        repo().use {
            val legacy = it.exportBackup()
            replace(it, 0, "A")
            assertThrows(IllegalStateException::class.java) { it.exportBackup() }
            assertThrows(IllegalStateException::class.java) { it.restoreBackup(legacy) }
            assertEquals(1L, it.personalPlanAuthority(scope).version)
        }
    }

    @Test fun restoredPersonalPlanRemainsPausedUntilSeparateExplicitActivation() {
        val bytes = repo().use { it.materialize(listOf(item("A"))); it.exportBackup() }
        SqliteOccurrenceRepository(context, clock, "copy.db").use {
            it.restoreBackup(bytes)
            assertThrows(IllegalStateException::class.java) { replace(it, 0, "B") }
            assertEquals(0L, it.personalPlanAuthority(scope).version)
            assertEquals(1, it.restorePausedCount())
            assertNull(it.get(item("B").id))
        }
    }

    @Test fun retiredOccurrenceCannotBeMutatedOrResurrectedByMaterialization() {
        val old = item("A")
        repo().use {
            replace(it, 0, "A")
            replace(it, 1, "OFF", emptyList())
            assertEquals(0, it.materialize(listOf(old)))
            assertFalse(it.isDeliveryAllowed(old.id))
            val action = ExecutionAction("late", old.id, ActionType.TRIGGER, Actor.SYSTEM,
                old.plannedAt, 0, 0, source = "late-receiver")
            assertThrows(IllegalStateException::class.java) { it.apply(action) }
            assertEquals(old, it.get(old.id))
            assertEquals(1, it.history(old.id).size)
        }
    }

    @Test fun materializationCannotBypassAnExistingPersonalAuthority() {
        repo().use {
            replace(it, 0, "A")
            assertThrows(IllegalStateException::class.java) { it.materialize(listOf(item("ROGUE"))) }
            assertNull(it.get(item("ROGUE").id))
            assertTrue(it.history(item("ROGUE").id).isEmpty())
            assertEquals(1L, it.personalPlanAuthority(scope).version)
        }
    }

    @Test fun v4MigrationPreservesExistingJournalAndEpoch() {
        repo().use { it.materialize(listOf(item("A"))) }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TRIGGER personal_retirements_no_update")
            it.execSQL("DROP TRIGGER personal_retirements_no_delete")
            it.execSQL("DROP TABLE personal_plan_retirements")
            it.execSQL("DROP TRIGGER personal_commits_no_update")
            it.execSQL("DROP TRIGGER personal_commits_no_delete")
            it.execSQL("DROP TABLE personal_plan_commits")
            it.version = 4
        }
        repo().use {
            assertEquals(item("A"), it.get(item("A").id))
            assertEquals(1, it.history(item("A").id).size)
            assertEquals("", it.deliveryEpoch())
            assertEquals(0L, it.personalPlanAuthority(scope).version)
            replace(it, 0, "B")
            assertFalse(it.isDeliveryAllowed(item("A").id))
        }
    }
}
