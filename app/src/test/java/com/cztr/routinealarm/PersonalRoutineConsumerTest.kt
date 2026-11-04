package com.cztr.routinealarm

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import com.cztr.routinealarm.occurrence.*
import java.time.Clock
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PersonalRoutineConsumerTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val manager get() = app.getSystemService(AlarmManager::class.java)
    private val event get() = PersonalSchedule.allEvents.first { it.enabled }
    private fun repo() = SqliteOccurrenceRepository(app, Clock.systemUTC())
    @Before fun prepare() {
        app.deleteDatabase("occurrences.db")
        app.getSharedPreferences("routinealarm_schedule", Context.MODE_PRIVATE).edit().clear().commit()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ScheduleState.setEnabled(app, true)
    }
    @After fun cleanup() { app.deleteDatabase("occurrences.db") }
    private fun pauseSaved() = repo().use { r ->
        r.savePersonalRoutine(PersonalRoutineSaveRequest("pause", event.copy(title = "Persisted edit"), 0, 0, r.deliveryEpoch()))
    }
    private fun pendingIds() = shadowOf(manager).scheduledAlarms.mapNotNull { alarm ->
        alarm.operation?.let { shadowOf(it).savedIntent.getStringExtra(AlarmScheduler.EXTRA_EVENT_ID) }
    }
    private fun delivery() = Intent(app, AlarmReceiver::class.java)
        .putExtra(AlarmScheduler.EXTRA_EVENT_ID, event.id)
        .putExtra(AlarmScheduler.EXTRA_RECURRING, true)
        .putExtra(AlarmScheduler.EXTRA_RESTORE_GENERATION, ScheduleState.restoreGeneration(app))

    @Test fun schedulerDoesNotResurrectPausedSavedDefinitionAfterReopen() {
        pauseSaved()
        com.cztr.routinealarm.support.onRoutineStorage { AlarmScheduler.scheduleAll(app) }
        assertFalse("Static default resurrected a stored paused edit", event.id in pendingIds())
        assertFalse(com.cztr.routinealarm.support.onRoutineStorage { AlarmScheduler.scheduleEvent(app, event, ZonedDateTime.now()) })
    }
    @Test fun receiverAdmissionReadsPersistedOverrideAndRejectsOldDefault() {
        pauseSaved()
        assertFalse("A stale static delivery bypassed the stored edit", com.cztr.routinealarm.support.onRoutineStorage { ScheduleState.isDeliveryAllowed(app, delivery()) })
        run { AlarmReceiver().onReceive(app, delivery()); com.cztr.routinealarm.support.drainRoutineAdmissions() }
        assertNull(shadowOf(app).nextStartedService)
    }
    @Test fun bootRecoveryCancelsOldRegistrationInsteadOfRearmingDefault() {
        assertTrue(com.cztr.routinealarm.support.onRoutineStorage { AlarmScheduler.scheduleEvent(app, event) })
        assertTrue(event.id in pendingIds())
        pauseSaved()
        run { BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED)); com.cztr.routinealarm.support.drainRoutineAdmissions() }
        assertFalse("Boot planned static default after saved pause", event.id in pendingIds())
    }

    @Test fun boundCurrentDeliveryRejectsWrongDefinitionVersionAndStoreEpoch() {
        assertTrue(com.cztr.routinealarm.support.onRoutineStorage { AlarmScheduler.scheduleEvent(app, event) })
        val token = shadowOf(manager).scheduledAlarms.first { alarm ->
            alarm.operation?.let { shadowOf(it).savedIntent.getStringExtra(AlarmScheduler.EXTRA_EVENT_ID) } == event.id
        }.operation!!
        val current = android.content.Intent(shadowOf(token).savedIntent)
        assertTrue(com.cztr.routinealarm.support.onRoutineStorage { ScheduleState.isDeliveryAllowed(app, current) })
        assertFalse(com.cztr.routinealarm.support.onRoutineStorage { ScheduleState.isDeliveryAllowed(app, Intent(current).putExtra(AlarmScheduler.EXTRA_ROUTINE_VERSION, 99L)) })
        assertFalse(com.cztr.routinealarm.support.onRoutineStorage { ScheduleState.isDeliveryAllowed(app, Intent(current).putExtra(AlarmScheduler.EXTRA_ROUTINE_EPOCH, "wrong")) })
        pauseSaved()
        assertFalse(com.cztr.routinealarm.support.onRoutineStorage { ScheduleState.isDeliveryAllowed(app, current) })
    }

    @Test fun freshDatabaseSnapshotIncludesCustomPausedRoutineWithoutSchedulingIt() {
        val custom = event.copy(id = "user:new", title = "Created routine")
        repo().use { r -> r.savePersonalRoutine(PersonalRoutineSaveRequest("new", custom, 0, 0, r.deliveryEpoch())) }
        val snapshot = com.cztr.routinealarm.support.onRoutineStorage { ScheduleRegistry.load(app) }
        assertEquals(custom.copy(enabled = false), snapshot.scheduledById(custom.id))
        assertEquals(1L, snapshot.definitions.getValue(custom.id).version)
        com.cztr.routinealarm.support.onRoutineStorage { AlarmScheduler.scheduleAll(app) }
        assertFalse(custom.id in pendingIds())
        assertFalse(com.cztr.routinealarm.support.onRoutineStorage { ScheduleState.isDeliveryAllowed(app, delivery().putExtra(AlarmScheduler.EXTRA_EVENT_ID, custom.id)) })
    }

    @Test fun cleanupAfterEditRetainsUnrelatedRegistration() {
        val other = PersonalSchedule.allEvents.first { it.enabled && it.id != event.id }
        assertTrue(com.cztr.routinealarm.support.onRoutineStorage { AlarmScheduler.scheduleEvent(app, event) })
        assertTrue(com.cztr.routinealarm.support.onRoutineStorage { AlarmScheduler.scheduleEvent(app, other) })
        pauseSaved()
        com.cztr.routinealarm.support.onRoutineStorage { AlarmScheduler.cancelIneligibleEvents(app) }
        assertFalse(event.id in pendingIds()); assertTrue(other.id in pendingIds())
    }

    @Test fun sqliteCatalogOpensOnExistingWorkerRatherThanCallingMainThread() {
        val caller = Thread.currentThread()
        val seen = java.util.Collections.synchronizedList(mutableListOf<Thread>())
        val context = object : android.content.ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): java.io.File {
                if (name == "occurrences.db") seen.add(Thread.currentThread())
                return super.getDatabasePath(name)
            }
        }
        com.cztr.routinealarm.support.onRoutineStorage { ScheduleRegistry.load(context) }
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.all { it !== caller && it.name == "weekly-preference-writes" })
    }

    @Test fun unreadableStoreNeverFallsBackToEnabledStaticDefault() {
        val path = app.getDatabasePath("occurrences.db")
        path.parentFile!!.mkdirs(); path.writeText("unreadable original evidence")
        val original = path.readBytes()
        assertEquals(0, com.cztr.routinealarm.support.onRoutineStorage { AlarmScheduler.scheduleAll(app) })
        assertFalse(com.cztr.routinealarm.support.onRoutineStorage { ScheduleState.isDeliveryAllowed(app, delivery()) })
        assertArrayEquals(original, path.readBytes())
        assertTrue(pendingIds().isEmpty())
    }

    @Test fun explicitWeeklyEnableKeepsPersistedEditPaused() {
        pauseSaved(); ScheduleState.setEnabled(app, false)
        var result: Result<Int>? = null
        ScheduleState.requestEnabled(app, true) { result = it }
        com.cztr.routinealarm.support.onRoutineStorage { Unit }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(ScheduleState.isEnabled(app)); assertTrue(result?.isSuccess == true)
        assertFalse(event.id in pendingIds())
        assertFalse(repo().use { it.readPersonalRoutine(event.id)!!.event.enabled })
    }
}
