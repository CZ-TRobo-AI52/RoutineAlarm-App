package com.cztr.routinealarm.occurrence

import android.content.Intent
import com.cztr.routinealarm.execution.TestAlarmRuntime
import com.cztr.routinealarm.execution.TestAlarmService
import com.cztr.routinealarm.support.awaitTestOutput
import com.cztr.routinealarm.support.awaitTestOutputStopped
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** Native SQLite, original immutable request and actual service-selection boundary.
 * Local suppression is proved; physical speaker shutdown remains UNKNOWN_EFFECT.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28,31])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TestAlarmDurableOutputTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var repo: SqliteOccurrenceRepository
    private lateinit var fired: RoutineOccurrence
    private lateinit var token: AlarmDelivery
    private val instance get() = UUID.randomUUID().toString()
    @Before fun setup() {
        app.deleteDatabase("occurrences.db"); app.deleteDatabase("restored-output.db")
        repo = TestAlarmRuntime.repository(app)
        val created = TestAlarmCoordinator(repo, Clock.offset(Clock.systemUTC(), Duration.ofMinutes(-2))).create(1)
        fired = TestAlarmRuntime.coordinator(repo).trigger(AlarmDelivery.from(created, repo.deliveryEpoch()))!!
        token = AlarmDelivery.from(fired, repo.deliveryEpoch())
    }
    @After fun cleanup() { repo.close(); app.deleteDatabase("occurrences.db"); app.deleteDatabase("restored-output.db") }
    private fun close(id: String, output: String, expected: AlarmDelivery = token): ExecutionAction =
        StableTestAlarmActions(repo, Clock.systemUTC()).prepare("test-user:$id", expected,
            ActionType.CLOSE_OUTPUT, outputInstanceId = output)
    private fun submit(action: ExecutionAction) = StableTestAlarmActions(repo, Clock.systemUTC()).submit(action)

    @Test fun committedSuppressionSurvivesRepositoryReopenWithoutCompletingOccurrence() {
        val handle=instance; assertTrue(repo.bindTestOutput(token,handle))
        val action=close("durable-close",handle); val answer=submit(action)
        assertEquals(Outcome.APPLIED,answer.outcome); assertEquals("UNKNOWN_EFFECT",answer.reason)
        assertEquals(OccurrenceStatus.TRIGGERED,answer.occurrence.status)
        assertEquals(token.generation,answer.occurrence.outputSilencedGeneration)
        repo.close(); repo=TestAlarmRuntime.repository(app)
        assertEquals(answer.occurrence,repo.get(fired.id))
        assertEquals(action,StableTestAlarmActions(repo,Clock.systemUTC()).recover(action.id))
        assertTrue(submit(action).duplicate)
        assertFalse(repo.bindTestOutput(AlarmDelivery.from(answer.occurrence,repo.deliveryEpoch()),instance))
        assertEquals(listOf("SCHEDULE","TRIGGER","CLOSE_OUTPUT"),repo.history(fired.id).map { it.actionType })
    }
    @Test fun oldInstanceCannotCloseReplacementEvenWithSameDelivery() {
        val old=instance; val fresh=instance
        assertTrue(repo.bindTestOutput(token,old)); val stale=close("old-instance",old)
        assertTrue(repo.bindTestOutput(token,fresh))
        assertThrows(IllegalStateException::class.java) { submit(stale) }
        assertEquals(fired,repo.get(fired.id)); assertEquals(2,repo.history(fired.id).size)
        assertEquals(fresh,repo.outputInstance(fired.id))
        assertEquals(Outcome.APPLIED,submit(close("fresh-instance",fresh)).outcome)
    }
    @Test fun outputBindingAddsNoDomainActionAndCannotBeExportedAsAuthority() {
        val before=repo.exportBackup(); val handle=instance
        assertTrue(repo.bindTestOutput(token,handle))
        assertEquals(fired,repo.get(fired.id)); assertEquals(2,repo.history(fired.id).size)
        assertArrayEquals(before,repo.exportBackup())
        SqliteOccurrenceRepository(app,Clock.systemUTC(),"restored-output.db").use { restored ->
            assertEquals(1,restored.restoreBackup(repo.exportBackup()))
            assertNull(restored.outputInstance(fired.id)); assertFalse(restored.isDeliveryAllowed(fired.id))
        }
    }
    @Test fun closeRequestCannotReuseItsIdWithChangedInstance() {
        val handle=instance; assertTrue(repo.bindTestOutput(token,handle));val action=close("immutable",handle)
        submit(action)
        assertThrows(ActionIdConflict::class.java) { submit(action.copy(outputInstanceId=instance)) }
        assertNull(StableTestAlarmActions(repo,Clock.systemUTC()).recover("test-user:absent"))
        assertEquals(3,repo.history(fired.id).size)
    }
    @Test fun snoozeAndNewGenerationRemainEligibleAfterExplicitClose() {
        val handle=instance;assertTrue(repo.bindTestOutput(token,handle));val result=submit(close("close-before-snooze",handle))
        val now=Clock.offset(Clock.systemUTC(),Duration.ofSeconds(1))
        val api=StableTestAlarmActions(repo,now)
        val snoozed=api.submit(api.prepare("test-user:new-snooze",AlarmDelivery.from(result.occurrence,repo.deliveryEpoch()),ActionType.SNOOZE,1)).occurrence
        assertEquals(token.generation+1,snoozed.generation)
        val retriggered=TestAlarmCoordinator(repo,Clock.offset(Clock.systemUTC(),Duration.ofMinutes(2)))
            .trigger(AlarmDelivery.from(snoozed,repo.deliveryEpoch()))!!
        assertTrue(repo.bindTestOutput(AlarmDelivery.from(retriggered,repo.deliveryEpoch()),instance))
        assertNotEquals(retriggered.generation,retriggered.outputSilencedGeneration)
    }
    @Test fun restoreKeepsSilenceButRejectsOldRequestAndDoesNotGrantPhysicalEffect() {
        val handle=instance;assertTrue(repo.bindTestOutput(token,handle));val action=close("restore-close",handle);submit(action)
        val bytes=repo.exportBackup()
        SqliteOccurrenceRepository(app,Clock.systemUTC(),"restored-output.db").use { restored ->
            assertEquals(1,restored.restoreBackup(bytes))
            assertEquals(action,StableTestAlarmActions(restored,Clock.systemUTC()).recover(action.id))
            assertThrows(IllegalStateException::class.java) { StableTestAlarmActions(restored,Clock.systemUTC()).submit(action) }
            val row=restored.get(fired.id)!!;assertFalse(restored.isDeliveryAllowed(row.id))
            assertTrue(restored.activateRestored(row.id,AlarmDelivery.from(row,restored.deliveryEpoch())))
            assertFalse(restored.bindTestOutput(AlarmDelivery.from(row,restored.deliveryEpoch()),instance))
        }
    }
    @Test fun serviceRestartDoesNotSelectPersistentlySilencedFiring() {
        val handle=instance;assertTrue(repo.bindTestOutput(token,handle));submit(close("restart-suppression",handle))
        val service=Robolectric.buildService(TestAlarmService::class.java).create()
        try {
            service.get().onStartCommand(Intent().setAction(TestAlarmService.REFRESH),0,1)
            awaitTestOutput(service)
            assertTrue(shadowOf(service.get()).isStoppedBySelf)
            assertNull(shadowOf(app.getSystemService(android.app.NotificationManager::class.java))
                .getNotification(TestAlarmService.NOTIFICATION))
            assertEquals(OccurrenceStatus.TRIGGERED,repo.get(fired.id)!!.status)
            assertFalse(repo.history(fired.id).any { it.actionType=="DONE" || it.actionType=="SKIP" })
        } finally { service.destroy();awaitTestOutputStopped(service) }
    }
    @Test fun scheduledBackupCannotForgeUserCloseConsent() {
        app.deleteDatabase("forged-output.db")
        try {
            SqliteOccurrenceRepository(app,Clock.systemUTC(),"forged-output.db").use { source ->
                val created=TestAlarmCoordinator(source,Clock.systemUTC()).create(1)
                val original=OccurrenceBackup.decode(source.exportBackup())
                val rows=original.tables.map { table -> table.map { it.toMutableList() }.toMutableList() }.toMutableList()
                val forged=OccurrenceCodec.encode(created.copy(outputSilencedGeneration=0))
                rows[1][0][OccurrenceBackup.tables[1].columns.indexOfFirst { it.name=="snapshot" }]=forged
                rows[2][0][OccurrenceBackup.tables[2].columns.indexOfFirst { it.name=="result_snapshot" }]=forged
                assertThrows(IllegalArgumentException::class.java) {
                    OccurrenceBackup.decode(OccurrenceBackup.encode(original.copy(tables=rows)))
                }
            }
        } finally { app.deleteDatabase("forged-output.db") }
    }

    @Test fun genuineV5JournalUpgradesWithoutLosingRequestsOrEpoch() {
        val before=repo.history(fired.id);val epoch=repo.deliveryEpoch();repo.close()
        android.database.sqlite.SQLiteDatabase.openDatabase(app.getDatabasePath("occurrences.db").path,
            null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
            useHistoricalOutputJournalFixture(db,5);db.version=5
        }
        repo=TestAlarmRuntime.repository(app)
        assertEquals(before,repo.history(fired.id));assertEquals(epoch,repo.deliveryEpoch())
        assertEquals(fired,repo.get(fired.id))
        val handle=instance;assertTrue(repo.bindTestOutput(token,handle))
        assertEquals("UNKNOWN_EFFECT",submit(close("v5-migration-close",handle)).reason)
        assertEquals(3,repo.history(fired.id).size)
        android.database.sqlite.SQLiteDatabase.openDatabase(app.getDatabasePath("occurrences.db").path,
            null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { assertEquals(7,it.version) }
    }

    @Test fun failedCommitDoesNotRecordConsentOrSilence() {
        val handle=instance;assertTrue(repo.bindTestOutput(token,handle));val action=close("failed-commit",handle)
        SqliteOccurrenceRepository(app,Clock.systemUTC(),beforeCommit={ error("injected precommit failure") }).use { failing ->
            assertThrows(IllegalStateException::class.java) { StableTestAlarmActions(failing,Clock.systemUTC()).submit(action) }
        }
        assertEquals(fired,repo.get(fired.id));assertEquals(2,repo.history(fired.id).size)
        assertNull(StableTestAlarmActions(repo,Clock.systemUTC()).recover(action.id))
    }
}

/** A genuine historical journal CHECK, with every existing row retained; never relabel v6 as v4/v5. */
internal fun useHistoricalOutputJournalFixture(db: android.database.sqlite.SQLiteDatabase, version: Int) {
    db.rawQuery("SELECT COUNT(*) FROM personal_routine_definitions", null).use { it.moveToFirst(); check(it.getInt(0) == 0) }
    db.execSQL("DROP TRIGGER routine_definitions_no_update"); db.execSQL("DROP TRIGGER routine_definitions_no_delete")
    db.execSQL("DROP TABLE personal_routine_definitions")
    val field=Class.forName("com.cztr.routinealarm.occurrence.OccurrenceDatabase")
        .getDeclaredField("V${version}_SCHEMA").apply { isAccessible=true }
    @Suppress("UNCHECKED_CAST") val schema=field.get(null) as Map<String,String>
    db.execSQL("DROP TRIGGER journal_no_update");db.execSQL("DROP TRIGGER journal_no_delete")
    db.execSQL("DROP INDEX journal_occurrence")
    db.execSQL("ALTER TABLE execution_journal RENAME TO journal_current_fixture")
    db.execSQL(schema.getValue("execution_journal"))
    db.execSQL("INSERT INTO execution_journal SELECT * FROM journal_current_fixture")
    db.execSQL("DROP TABLE journal_current_fixture")
    listOf("journal_occurrence","journal_no_update","journal_no_delete").forEach { db.execSQL(schema.getValue(it)) }
}
