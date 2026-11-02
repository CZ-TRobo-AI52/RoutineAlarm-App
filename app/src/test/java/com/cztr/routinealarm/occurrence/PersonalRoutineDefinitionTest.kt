package com.cztr.routinealarm.occurrence

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
class PersonalRoutineDefinitionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val clock = Clock.fixed(Instant.parse("2026-11-02T12:00:00Z"), ZoneId.of("Europe/Berlin"))
    private fun repo() = SqliteOccurrenceRepository(context, clock, "definitions.db")
    @After fun cleanup() { context.deleteDatabase("definitions.db") }
    @Test fun creationIsPausedAndSurvivesRepositoryReopen() {
        val event = RoutineEvent("user:123", setOf(DayOfWeek.MONDAY), 8, 15, "My routine",
            "Speak this", EventCategory.PERSONAL, EventMode.ROUTINE, true)
        // Reflection keeps the baseline compilable and proves the missing durable API at runtime.
        val requestType = Class.forName("com.cztr.routinealarm.occurrence.PersonalRoutineSaveRequest")
        repo().use { r ->
            val request = requestType.constructors.single().newInstance("save-1", event, 0L, 0L, r.deliveryEpoch())
            r.javaClass.getMethod("savePersonalRoutine", requestType).invoke(r, request)
        }
        repo().use { r ->
            val record = r.javaClass.getMethod("readPersonalRoutine", String::class.java).invoke(r, event.id)
            assertNotNull(record)
            val saved = record.javaClass.getMethod("getEvent").invoke(record) as RoutineEvent
            assertEquals(event.copy(enabled = false), saved)
            assertEquals(1L, record.javaClass.getMethod("getVersion").invoke(record))
            assertTrue(r.futureOccurrencesPage(OccurrenceScope.PersonalEvent(event.id), clock.instant()).isEmpty())
        }
    }

    private val event get() = RoutineEvent("user:123", setOf(DayOfWeek.MONDAY), 20, 15, "Title",
        "Speech", EventCategory.PERSONAL, EventMode.ROUTINE, true)
    private fun request(r: SqliteOccurrenceRepository, id: String = "save-1", value: RoutineEvent = event,
        version: Long = 0, plan: Long = 0) = PersonalRoutineSaveRequest(id, value, version, plan, r.deliveryEpoch())
    private fun saved(result: PersonalRoutineSaveResult) = (result as PersonalRoutineSaveResult.Saved).record

    @Test fun editKeepsStableIdAndAllFieldsAcrossReopen() {
        repo().use { r ->
            val first = saved(r.savePersonalRoutine(request(r)))
            val edited = event.copy(title = "Changed", spokenText = "New speech", hour = 7, minute = 2,
                daysOfWeek = setOf(DayOfWeek.TUESDAY, DayOfWeek.SUNDAY), category = EventCategory.WORK,
                mode = EventMode.ANNOUNCEMENT)
            val second = saved(r.savePersonalRoutine(request(r, "edit", edited, first.version, first.planVersion)))
            assertEquals(edited.copy(enabled = false), second.event)
            assertEquals(2L, second.version); assertEquals(2L, second.planVersion)
        }
        repo().use { r ->
            val record = r.listPersonalRoutines().single()
            assertEquals("user:123", record.event.id); assertEquals("Changed", record.event.title)
            assertEquals(setOf(DayOfWeek.TUESDAY, DayOfWeek.SUNDAY), record.event.daysOfWeek)
            assertFalse(record.event.enabled); assertFalse(record.quarantined)
        }
    }

    @Test fun exactRetryReturnsOriginalButChangedRequestIdPayloadIsRejected() {
        repo().use { r ->
            val original = request(r)
            val first = saved(r.savePersonalRoutine(original))
            val duplicate = r.savePersonalRoutine(original) as PersonalRoutineSaveResult.Saved
            assertTrue(duplicate.duplicate); assertEquals(first, duplicate.record)
            assertEquals(1L, r.personalPlanAuthority(OccurrenceScope.PersonalEvent(event.id)).version)
            assertThrows(IllegalArgumentException::class.java) {
                r.savePersonalRoutine(original.copy(event = event.copy(title = "Wrong")))
            }
        }
    }

    @Test fun staleDefinitionAndPlanWritersDoNotOverwrite() {
        repo().use { r ->
            r.savePersonalRoutine(request(r))
            assertEquals("DEFINITION_VERSION", (r.savePersonalRoutine(request(r, "stale")) as PersonalRoutineSaveResult.Conflict).reason)
            assertEquals("PLAN_VERSION", (r.savePersonalRoutine(request(r, "stale-plan", version = 1)) as PersonalRoutineSaveResult.Conflict).reason)
            assertEquals(1L, r.readPersonalRoutine(event.id)!!.version)
        }
    }

    @Test fun failedCommitRollsBackDefinitionPlanAndRetryAuthority() {
        SqliteOccurrenceRepository(context, clock, "definitions.db") { error("power loss") }.use { r ->
            assertThrows(IllegalStateException::class.java) { r.savePersonalRoutine(request(r)) }
        }
        repo().use { r ->
            assertNull(r.readPersonalRoutine(event.id))
            assertEquals(0L, r.personalPlanAuthority(OccurrenceScope.PersonalEvent(event.id)).version)
            assertFalse((r.savePersonalRoutine(request(r)) as PersonalRoutineSaveResult.Saved).duplicate)
        }
    }

    @Test fun pauseSaveRetiresOnlyFutureUntouchedPlanWithoutRewritingHistory() {
        val zone = clock.zone; val scope = OccurrenceScope.PersonalEvent(event.id)
        val items = OccurrencePlanner(clock).plan(listOf(event.copy(daysOfWeek = DayOfWeek.entries.toSet())),
            LocalDate.parse("2026-11-01"), 2, zone, "old")
        val future = items.single { it.plannedAt > clock.instant() }
        val due = items.single { it.plannedAt <= clock.instant() }
        repo().use { r ->
            r.materialize(items)
            r.replacePersonalPlan(scope, 0, listOf(future), "old", zone, clock.instant())
            val dueHistory = r.history(due.id); val futureHistory = r.history(future.id)
            r.savePersonalRoutine(request(r, plan = 1))
            assertTrue(future.id in r.personalPlanAuthority(scope).retiredIds)
            assertFalse(due.id in r.personalPlanAuthority(scope).retiredIds)
            assertEquals(due, r.get(due.id)); assertEquals(future, r.get(future.id))
            assertEquals(dueHistory, r.history(due.id)); assertEquals(futureHistory, r.history(future.id))
            assertFalse(r.isDeliveryAllowed(future.id))
        }
    }

    @Test fun restorePreservesDefinitionsButQuarantinesAndRejectsOldEpoch() {
        lateinit var original: PersonalRoutineSaveRequest
        val bytes = repo().use { r -> original = request(r); r.savePersonalRoutine(original); r.exportBackup() }
        assertEquals("R52BKUP3", bytes.take(8).toByteArray().toString(Charsets.US_ASCII))
        context.deleteDatabase("definitions.db")
        repo().use { r ->
            r.restoreBackup(bytes)
            val restored = r.listPersonalRoutines().single()
            assertTrue(restored.quarantined); assertFalse(restored.event.enabled)
            assertEquals("RESTORE_EPOCH", (r.savePersonalRoutine(original) as PersonalRoutineSaveResult.Conflict).reason)
            val fresh = request(r, "after-restore", version = 1, plan = 1)
            assertEquals("RESTORE_QUARANTINE", (r.savePersonalRoutine(fresh) as PersonalRoutineSaveResult.Conflict).reason)
            assertArrayEquals(bytes, r.exportBackup())
        }
    }

    @Test fun providerMergesPausedOverridesAndRejectsTrainingCollision() {
        val builtIn = PersonalSchedule.allEvents.first()
        val override = builtIn.copy(title = "Edited", enabled = false)
        val snapshot = ScheduleRegistry.personalEvents(listOf(override, event.copy(enabled = false)))
        assertEquals("Edited", snapshot.single { it.id == builtIn.id }.title)
        assertFalse(snapshot.single { it.id == event.id }.enabled)
        assertThrows(IllegalArgumentException::class.java) {
            ScheduleRegistry.personalEvents(listOf(TrainingSchedule.allEvents.first().copy(enabled = false)))
        }
        assertThrows(IllegalArgumentException::class.java) { ScheduleRegistry.personalEvents(listOf(event)) }
    }

    @Test fun migrationFromSixPreservesJournalAndAllowsNewDefinition() {
        repo().use { r -> r.materialize(OccurrencePlanner(clock).plan(listOf(event), LocalDate.parse("2026-11-02"), 1, clock.zone, "old")) }
        android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("definitions.db").path, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TRIGGER routine_definitions_no_update"); db.execSQL("DROP TRIGGER routine_definitions_no_delete")
            db.execSQL("DROP TABLE personal_routine_definitions"); db.version = 6
        }
        repo().use { r ->
            val old = r.listForDate(LocalDate.parse("2026-11-02"), clock.zone).single()
            assertEquals(1, r.history(old.id).size); assertEquals("", r.deliveryEpoch())
            r.savePersonalRoutine(request(r)); assertFalse(r.isDeliveryAllowed(old.id))
        }
    }
}
