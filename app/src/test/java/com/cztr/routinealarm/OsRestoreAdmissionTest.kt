@file:Suppress("DEPRECATION")

package com.cztr.routinealarm

import android.app.AlarmManager
import android.app.backup.BackupAgent
import android.app.backup.BackupAgentHelper
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import com.cztr.routinealarm.execution.TestAlarmRuntime
import java.io.File
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
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Real app callback/receivers/preferences/SQLite; invokes restore completion, not an OS transport. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class OsRestoreAdmissionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(AlarmManager::class.java)
    private val prefs get() = context.getSharedPreferences("routinealarm_schedule", Context.MODE_PRIVATE)

    @Before fun setup() {
        assertTrue(prefs.edit().clear().commit())
        context.deleteDatabase("occurrences.db")
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After fun cleanup() {
        shadowOf(manager).scheduledAlarms.toList().forEach {
            it.operation?.let { pending -> manager.cancel(pending); pending.cancel() }
        }
        context.getSystemService(JobScheduler::class.java).cancelAll()
        context.deleteDatabase("occurrences.db")
        assertTrue(prefs.edit().clear().commit())
    }

    private fun agent(): BackupAgent {
        // With no app agent, the platform's default restore completion has no app-specific hook.
        val name = context.applicationInfo.backupAgentName
        val instance = if (name == null) BackupAgentHelper() else
            Class.forName(name).getDeclaredConstructor().newInstance() as BackupAgent
        ReflectionHelpers.callInstanceMethod<Void>(instance, "attach", ClassParameter.from(Context::class.java, context))
        instance.onCreate()
        return instance
    }

    private fun restoredEnabledPreference() {
        // Models a legacy eligible payload already delivered by Android before onRestoreFinished.
        assertTrue(prefs.edit().putBoolean("weekly_schedule_enabled", true).commit())
        assertTrue(ScheduleState.isEnabled(context))
    }

    private fun recurringIntent(): Intent = Intent(context, AlarmReceiver::class.java)
        .putExtra(AlarmScheduler.EXTRA_RECURRING, true)
        .putExtra(AlarmScheduler.EXTRA_EVENT_ID, PersonalSchedule.allEvents.first { it.enabled }.id)

    @Test fun restoredEnabledPreferenceIsRevokedAndSynchronouslyWrittenToDisk() {
        restoredEnabledPreference()
        agent().onRestoreFinished()
        assertFalse(ScheduleState.isEnabled(context))
        val disk = File(context.applicationInfo.dataDir, "shared_prefs/routinealarm_schedule.xml").readText()
        assertTrue(disk.contains("name=\"weekly_schedule_enabled\" value=\"false\""))
        agent().onRestoreFinished()
        assertFalse(ScheduleState.isEnabled(context))
    }

    @Test fun restoredWeeklyPreferenceCannotRearmAtBootOrDispatchStaleRecurringIntent() {
        restoredEnabledPreference()
        agent().onRestoreFinished()
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        AlarmReceiver().onReceive(context, recurringIntent())
        assertTrue(shadowOf(manager).scheduledAlarms.isEmpty())
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test fun ordinaryAgentCreationAndSameDeviceUpgradeKeepExistingEnablement() {
        restoredEnabledPreference()
        agent().onDestroy() // Backup/agent initialization is not a restore-completion event.
        BootReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertTrue(ScheduleState.isEnabled(context))
        assertTrue(shadowOf(manager).scheduledAlarms.isNotEmpty())
    }

    @Test fun explicitUserEnableAfterRestoreAllowsCurrentPersonalRoutineAgain() {
        restoredEnabledPreference()
        agent().onRestoreFinished()
        assertFalse(ScheduleState.isEnabled(context))
        ScheduleState.setEnabled(context, true)
        AlarmReceiver().onReceive(context, recurringIntent())
        assertNotNull(shadowOf(context).nextStartedService)
        assertTrue(shadowOf(manager).scheduledAlarms.isNotEmpty())
    }

    @Test fun restoreHookPreservesUnrelatedPreferencesAndLocalTestAlarmHistory() {
        restoredEnabledPreference()
        val other = context.getSharedPreferences("unrelated-settings", Context.MODE_PRIVATE)
        assertTrue(other.edit().putString("voice", "chosen-voice").commit())
        TestAlarmRuntime.repository(context).use { repository ->
            val row = TestAlarmRuntime.coordinator(repository).create(5)
            val before = repository.exportBackup()
            val epoch = repository.deliveryEpoch()
            agent().onRestoreFinished()
            assertEquals("chosen-voice", other.getString("voice", null))
            assertEquals(row, repository.get(row.id))
            assertEquals(epoch, repository.deliveryEpoch())
            assertArrayEquals(before, repository.exportBackup())
        }
    }

    @Test fun emptyRestoreRemainsDisabled() {
        agent().onRestoreFinished()
        assertFalse(ScheduleState.isEnabled(context))
    }
}
