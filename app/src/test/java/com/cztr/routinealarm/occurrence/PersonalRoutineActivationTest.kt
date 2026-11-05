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
@Config(sdk = [28, 31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PersonalRoutineActivationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val clock = Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val event = RoutineEvent("user:activation", setOf(DayOfWeek.THURSDAY), 20, 15,
        "My routine", "Speech", EventCategory.PERSONAL, EventMode.ROUTINE, false)
    private fun repo() = SqliteOccurrenceRepository(context, clock)
    @After fun cleanup() { context.deleteDatabase("occurrences.db") }
    private fun method(r: SqliteOccurrenceRepository, name: String) =
        r.javaClass.methods.firstOrNull { it.name == name }.also { assertNotNull("Missing functional repository API: $name", it) }!!
    private fun snapshot(r: SqliteOccurrenceRepository, id: String) =
        method(r, "personalRoutineEditorSnapshot").invoke(r, id) as PersonalRoutineRecord?
    private fun save(r: SqliteOccurrenceRepository) = PersonalRoutineSaveRequest("save", event, 0, 0, r.deliveryEpoch())
    private fun activate(r: SqliteOccurrenceRepository, record: PersonalRoutineRecord, id: String = "activate",
        enabled: Boolean = true, version: Long = record.version, plan: Long = record.planVersion,
        epoch: String = record.restoreEpoch): PersonalRoutineSaveResult {
        val m = method(r, "setPersonalRoutineEnabled")
        val type = Class.forName("com.cztr.routinealarm.occurrence.PersonalRoutineActivationRequest")
        val request = type.constructors.single().newInstance(id, record.event, enabled, version, plan, epoch)
        return m.invoke(r, request) as PersonalRoutineSaveResult
    }
    @Test fun defaultAndUnknownEditorSnapshotHaveAtomicFences() { repo().use { r ->
        val record = snapshot(r, PersonalSchedule.allEvents.first().id)!!
        assertEquals(0L, record.version); assertEquals(0L, record.planVersion)
        assertEquals(r.deliveryEpoch(), record.restoreEpoch); assertFalse(record.quarantined)
        assertNull(snapshot(r, "missing"))
    } }
    @Test fun receiptStaysHistoricalAfterEditAndReadDoesNotSave() { repo().use { r ->
        val request = save(r); r.savePersonalRoutine(request)
        r.savePersonalRoutine(request.copy(stableRequestId = "edit", event = event.copy(title = "New"), expectedVersion = 1, expectedPlanVersion = 1))
        val receipt = method(r, "personalRoutineSaveReceipt").invoke(r, request) as PersonalRoutineRecord
        assertEquals(1L, receipt.version); assertEquals("My routine", receipt.event.title)
        assertEquals(2L, r.readPersonalRoutine(event.id)!!.version)
        assertNull(method(r, "personalRoutineSaveReceipt").invoke(r, request.copy(stableRequestId = "absent")))
    } }
    @Test fun explicitActivationPersistsAcrossReopenAndColdProvider() {
        repo().use { r -> r.savePersonalRoutine(save(r)); val active = activate(r, snapshot(r, event.id)!!) as PersonalRoutineSaveResult.Saved
            assertTrue(active.record.event.enabled); assertEquals(2L, active.record.version) }
        repo().use { r -> assertTrue(r.readPersonalRoutine(event.id)!!.event.enabled) }
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try { assertTrue(worker.submit<Boolean> { ScheduleRegistry.load(context).scheduledById(event.id)!!.enabled }.get(10, java.util.concurrent.TimeUnit.SECONDS)) }
        finally { worker.shutdownNow() }
    }
    @Test fun activationCasDuplicateAndEditedSavePausesAgain() { repo().use { r ->
        r.savePersonalRoutine(save(r)); val before = snapshot(r, event.id)!!
        val first = activate(r, before) as PersonalRoutineSaveResult.Saved
        val retry = activate(r, before) as PersonalRoutineSaveResult.Saved
        assertTrue(retry.duplicate); assertEquals(first.record, retry.record)
        assertEquals("DEFINITION_VERSION", (activate(r, before, "stale") as PersonalRoutineSaveResult.Conflict).reason)
        assertEquals("PLAN_VERSION", (activate(r, first.record, "stale-plan", plan = 1) as PersonalRoutineSaveResult.Conflict).reason)
        val edited = r.savePersonalRoutine(save(r).copy(stableRequestId = "edit", expectedVersion = 2, expectedPlanVersion = 2)) as PersonalRoutineSaveResult.Saved
        assertFalse(edited.record.event.enabled)
    } }
    @Test fun activeBackupRestoresQuarantinedAndOldReceiptKeepsOriginalEpoch() {
        lateinit var original: PersonalRoutineSaveRequest
        val backup = repo().use { r -> original = save(r); r.savePersonalRoutine(original); activate(r, snapshot(r, event.id)!!); r.exportBackup() }
        context.deleteDatabase("occurrences.db")
        repo().use { r -> r.restoreBackup(backup); val record = snapshot(r, event.id)!!
            assertTrue(record.quarantined); assertFalse(record.event.enabled)
            assertEquals("RESTORE_QUARANTINE", (activate(r, record, "restored") as PersonalRoutineSaveResult.Conflict).reason)
            val receipt = method(r, "personalRoutineSaveReceipt").invoke(r, original) as PersonalRoutineRecord
            assertEquals(original.expectedRestoreEpoch, receipt.restoreEpoch); assertTrue(receipt.quarantined)
        }
    }
}
