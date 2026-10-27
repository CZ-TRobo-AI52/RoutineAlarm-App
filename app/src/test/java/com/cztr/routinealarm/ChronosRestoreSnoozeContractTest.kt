@file:Suppress("DEPRECATION")

package com.cztr.routinealarm

import android.app.AlarmManager
import android.app.backup.BackupAgent
import android.content.Context
import android.content.Intent
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Restore contract against real product callbacks. Not an OS transport/device test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31])
@LooperMode(LooperMode.Mode.PAUSED)
class ChronosRestoreSnoozeContractTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(AlarmManager::class.java)

    @Before fun setup() {
        assertTrue(context.getSharedPreferences("routinealarm_schedule", Context.MODE_PRIVATE)
            .edit().clear().commit())
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ScheduleState.setEnabled(context, true)
    }

    @After fun cleanup() {
        shadowOf(manager).scheduledAlarms.toList().forEach {
            it.operation?.let { pending -> manager.cancel(pending); pending.cancel() }
        }
        assertTrue(context.getSharedPreferences("routinealarm_schedule", Context.MODE_PRIVATE)
            .edit().clear().commit())
    }

    private fun oldSnoozeDelivery(): Intent {
        AlarmScheduler.scheduleSnooze(context, "Synthetic restore probe", "Synthetic speech")
        val pending = requireNotNull(shadowOf(manager).scheduledAlarms.single().operation)
        val delivery = Intent(shadowOf(pending).savedIntent)
        assertFalse(delivery.getBooleanExtra(AlarmScheduler.EXTRA_RECURRING, false))
        manager.cancel(pending)
        pending.cancel()
        return delivery
    }

    private fun finishRestore() {
        val name = requireNotNull(context.applicationInfo.backupAgentName)
        val agent = Class.forName(name).getDeclaredConstructor().newInstance() as BackupAgent
        ReflectionHelpers.callInstanceMethod<Void>(agent, "attach",
            ClassParameter.from(Context::class.java, context))
        agent.onCreate()
        agent.onRestoreFinished()
        agent.onDestroy()
        assertFalse(ScheduleState.isEnabled(context))
    }

    @Test fun oldOneShotSnoozeMustNotStartOutputAfterRestore() {
        val delivery = oldSnoozeDelivery()
        finishRestore()
        AlarmReceiver().onReceive(context, delivery)
        assertNull("Pre-restore one-shot delivery must require renewed authority",
            shadowOf(context).nextStartedService)
    }

    @Test fun normalWeeklyPausePreservesAlreadyRequestedOneShotSnooze() {
        val delivery = oldSnoozeDelivery()
        ScheduleState.setEnabled(context, false)
        AlarmReceiver().onReceive(context, delivery)
        val service = requireNotNull(shadowOf(context).nextStartedService)
        assertEquals(AlarmService::class.java.name, service.component?.className)
        assertEquals("Synthetic speech", service.getStringExtra(AlarmScheduler.EXTRA_SPOKEN_TEXT))
    }

    @Test fun recurringDeliveryRemainsRejectedAfterRestore() {
        val event = ScheduleRegistry.enabledEvents.first()
        finishRestore()
        AlarmReceiver().onReceive(context, Intent(context, AlarmReceiver::class.java)
            .putExtra(AlarmScheduler.EXTRA_RECURRING, true)
            .putExtra(AlarmScheduler.EXTRA_EVENT_ID, event.id))
        assertNull(shadowOf(context).nextStartedService)
        assertTrue(shadowOf(manager).scheduledAlarms.isEmpty())
    }

    @Test fun weeklyReenableMustNotReadmitOldSnooze() {
        val delivery = oldSnoozeDelivery()
        finishRestore()
        ScheduleState.setEnabled(context, true)
        AlarmReceiver().onReceive(context, delivery)
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test fun secondRestoreMustInvalidateDeliveryCreatedAfterFirstRestore() {
        finishRestore()
        val delivery = oldSnoozeDelivery()
        finishRestore()
        AlarmReceiver().onReceive(context, delivery)
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test fun preUpgradeUntaggedSnoozeWorksOnlyBeforeFirstRestore() {
        val delivery = Intent(context, AlarmReceiver::class.java)
            .putExtra(AlarmScheduler.EXTRA_RECURRING, false)
            .putExtra(AlarmScheduler.EXTRA_TITLE, "Legacy request")
        AlarmReceiver().onReceive(context, delivery)
        assertNotNull(shadowOf(context).nextStartedService)
        finishRestore()
        AlarmReceiver().onReceive(context, delivery)
        assertNull(shadowOf(context).nextStartedService)
    }
}
