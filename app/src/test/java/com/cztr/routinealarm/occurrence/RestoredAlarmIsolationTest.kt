package com.cztr.routinealarm.occurrence

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Removing epoch/quarantine checks must fail these tests even when revisions still match. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RestoredAlarmIsolationTest {
    private lateinit var context: Context
    private lateinit var repo: SqliteOccurrenceRepository
    private val now = Instant.parse("2026-10-19T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        repo = SqliteOccurrenceRepository(context, clock, "isolation.db")
    }
    @After fun cleanup() { repo.close(); context.deleteDatabase("isolation.db") }

    private fun protected(epoch: String, allowed: Boolean) = object : OccurrenceRepository by repo {
        override fun deliveryEpoch() = epoch
        override fun isDeliveryAllowed(id: String) = allowed
    }
    private fun create() = TestAlarmCoordinator(repo, clock).create(1)
    private fun due(store: OccurrenceRepository = repo) =
        TestAlarmCoordinator(store, Clock.fixed(now.plusSeconds(61), ZoneOffset.UTC))

    @Test fun oldEpochCannotTriggerAnOtherwiseMatchingRestoredProjection() {
        val planned = create()
        assertNull(due(protected("new-restore-epoch", true)).trigger(AlarmDelivery.from(planned)))
        assertEquals(OccurrenceStatus.SCHEDULED, repo.get(planned.id)!!.status)
        assertEquals(1, repo.history(planned.id).size)
    }

    @Test fun quarantineBlocksTriggerEvenWithMatchingEpoch() {
        val planned = create()
        assertNull(due(protected("", false)).trigger(AlarmDelivery.from(planned)))
        assertEquals(1, repo.history(planned.id).size)
    }

    @Test fun historicalTriggeredProjectionCannotPlayOrAcceptUserActionBeforeActivation() {
        val fired = due().trigger(AlarmDelivery.from(create()))!!
        val coordinator = due(protected("", false))
        assertNull(coordinator.active(AlarmDelivery.from(fired)))
        assertNull(coordinator.act(AlarmDelivery.from(fired), ActionType.DONE))
        assertEquals(OccurrenceStatus.TRIGGERED, repo.get(fired.id)!!.status)
    }

    @Test fun oldActivityEpochCannotCompleteMatchingRestoredAlarm() {
        val fired = due().trigger(AlarmDelivery.from(create()))!!
        val coordinator = due(protected("another-epoch", true))
        assertNull(coordinator.active(AlarmDelivery.from(fired)))
        assertNull(coordinator.act(AlarmDelivery.from(fired), ActionType.DONE))
        assertEquals(2, repo.history(fired.id).size)
    }
}
