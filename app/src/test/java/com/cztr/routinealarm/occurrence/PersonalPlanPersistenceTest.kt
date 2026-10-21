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

    @Test fun backupPreservesAuthorityRetirementAndUnknownEffectsWithoutActivation() {
        repo().use {
            val legacy = it.exportBackup()
            replace(it, 0, "A")
            it.beginSchedulingAttempt(item("A"), true, scope)
            replace(it, 1, "B")
            val bytes = it.exportBackup()
            assertEquals("R52BKUP2", bytes.take(8).toByteArray().toString(Charsets.US_ASCII))
            SqliteOccurrenceRepository(context, clock, "copy.db").use { restored ->
                assertEquals(2, restored.restoreBackup(bytes))
                assertEquals(it.personalPlanAuthority(scope), restored.personalPlanAuthority(scope))
                assertEquals(it.history(item("A").id), restored.history(item("A").id))
                assertEquals(it.schedulingAttempts(item("A").id), restored.schedulingAttempts(item("A").id))
                assertFalse(restored.isDeliveryAllowed(item("A").id))
                assertFalse(restored.isDeliveryAllowed(item("B").id))
                assertFalse(restored.activateRestored(item("B").id))
                assertFalse(restored.activateRestored(item("A").id))
                assertEquals(2, restored.restorePausedCount())
                assertNotEquals(it.deliveryEpoch(), restored.deliveryEpoch())
                assertArrayEquals(bytes, restored.exportBackup())
            }
            assertThrows(IllegalStateException::class.java) { it.restoreBackup(legacy) }
            assertEquals(2L, it.personalPlanAuthority(scope).version)
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

    @Test fun legacyBackupImportsWithoutInventingPersonalAuthority() {
        val legacy = repo().use {
            it.materialize(listOf(item("A")))
            OccurrenceBackup.encode(OccurrenceBackup.decode(it.exportBackup()).copy(formatVersion = 1))
        }
        assertEquals("R52BKUP1", legacy.take(8).toByteArray().toString(Charsets.US_ASCII))
        SqliteOccurrenceRepository(context, clock, "copy.db").use {
            assertEquals(1, it.restoreBackup(legacy))
            assertEquals(0L, it.personalPlanAuthority(scope).version)
            assertFalse(it.isDeliveryAllowed(item("A").id))
        }
    }

    @Test fun backupOfEmptyPlanAndLegacyAdoptionPreservesOpaqueBinding() {
        val due = item("A").copy(id = "already-due", nominalDate = LocalDate.parse("2026-10-19"),
            plannedAt = item("A").plannedAt.minusSeconds(86400),
            currentTriggerAt = item("A").plannedAt.minusSeconds(86400))
        val bytes = repo().use {
            it.materialize(listOf(due))
            replace(it, 0, "A") // Binding includes only the new candidate, not the due legacy row.
            replace(it, 1, "OFF", emptyList())
            assertThrows(IllegalArgumentException::class.java) {
                OccurrenceBackup.encode(OccurrenceBackup.decode(it.exportBackup()).copy(formatVersion = 1))
            }
            it.exportBackup()
        }
        SqliteOccurrenceRepository(context, clock, "copy.db").use {
            it.restoreBackup(bytes)
            assertEquals("OFF", it.personalPlanAuthority(scope).activeRevision)
            assertEquals(due, it.get(due.id))
            assertArrayEquals(bytes, it.exportBackup())
        }
    }

    @Test fun invalidPlanLineageRejectsBeforeAnyDestinationMutation() {
        val saved = repo().use { replace(it, 0, "A"); replace(it, 1, "B");
            OccurrenceBackup.decode(it.exportBackup()) }
        val mutations = listOf(
            Triple(5, "version", 0L), Triple(5, "version", 2L),
            Triple(5, "fingerprint", "invalid"), Triple(5, "plan_revision", ""),
            Triple(5, "cutoff_exact", "not-a-date"),
            Triple(6, "event_id", "other"), Triple(6, "plan_version", 99L),
            Triple(6, "occurrence_id", "missing"),
        )
        SqliteOccurrenceRepository(context, clock, "copy.db").use { target ->
            for ((table, column, bad) in mutations) {
                val columnIndex = OccurrenceBackup.tables[table].columns.indexOfFirst { it.name == column }
                val changed = saved.copy(tables = saved.tables.mapIndexed { ti, rows ->
                    rows.mapIndexed { ri, row -> row.mapIndexed { ci, cell ->
                        if (ti == table && ri == 0 && ci == columnIndex) bad else cell
                    } }
                })
                assertThrows("$table.$column", IllegalArgumentException::class.java) {
                    target.restoreBackup(OccurrenceBackup.encode(changed))
                }
                assertNull(target.get(item("A").id))
                assertEquals(0L, target.personalPlanAuthority(scope).version)
                assertEquals("", target.deliveryEpoch())
                assertEquals(0, target.restorePausedCount())
            }
        }
    }

    @Test fun restoreCommitFailureRollsBackLineageQuarantineAndEpoch() {
        val bytes = repo().use { replace(it, 0, "A"); replace(it, 1, "B"); it.exportBackup() }
        SqliteOccurrenceRepository(context, clock, "copy.db") { error("before restore commit") }.use {
            assertThrows(IllegalStateException::class.java) { it.restoreBackup(bytes) }
        }
        SqliteOccurrenceRepository(context, clock, "copy.db").use {
            assertEquals(0L, it.personalPlanAuthority(scope).version)
            assertNull(it.get(item("A").id)); assertEquals("", it.deliveryEpoch())
            assertEquals(0, it.restorePausedCount())
            assertEquals(2, it.restoreBackup(bytes))
            assertEquals(2L, it.personalPlanAuthority(scope).version)
        }
    }

    @Test fun duplicateAndOrphanRetirementsAreRejected() {
        val saved = repo().use { replace(it, 0, "A"); replace(it, 1, "B");
            OccurrenceBackup.decode(it.exportBackup()) }
        val malformed = listOf(
            saved.copy(tables = saved.tables.mapIndexed { i, rows -> if (i == 6) rows + rows else rows }),
            saved.copy(tables = saved.tables.mapIndexed { i, rows -> if (i == 5) emptyList() else rows }),
        )
        SqliteOccurrenceRepository(context, clock, "copy.db").use { target ->
            malformed.forEach { invalid ->
                assertThrows(IllegalArgumentException::class.java) { target.restoreBackup(OccurrenceBackup.encode(invalid)) }
                assertNull(target.get(item("A").id))
            }
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
