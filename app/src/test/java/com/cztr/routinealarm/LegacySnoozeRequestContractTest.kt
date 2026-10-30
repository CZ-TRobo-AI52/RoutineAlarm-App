@file:Suppress("DEPRECATION")
package com.cztr.routinealarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlarmManager

/** Real product entry points on two Android API shadows; not device acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31])
@LooperMode(LooperMode.Mode.PAUSED)
class LegacySnoozeRequestContractTest {
    @Implements(AlarmManager::class)
    class ThrowsAfterRegistration : ShadowAlarmManager() {
        var failNext = true
        @Implementation
        override fun setExactAndAllowWhileIdle(type: Int, triggerAtMillis: Long, operation: PendingIntent) {
            super.setExactAndAllowWhileIdle(type, triggerAtMillis, operation)
            if (failNext) {
                failNext = false
                throw IllegalStateException("Synthetic failure after registration; effect unknown to caller")
            }
        }
    }
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(AlarmManager::class.java)
    @Before fun setup() {
        assertTrue(context.getSharedPreferences("routinealarm_schedule", Context.MODE_PRIVATE).edit().clear().commit())
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }
    @After fun cleanup() {
        shadowOf(manager).scheduledAlarms.toList().forEach { it.operation?.let { p -> manager.cancel(p); p.cancel() } }
    }
    private fun source() = Intent(context, AlarmActivity::class.java)
        .putExtra(AlarmScheduler.EXTRA_RESTORE_GENERATION, ScheduleState.restoreGeneration(context))
        .putExtra(AlarmScheduler.EXTRA_TITLE, "Request")
        .putExtra(AlarmScheduler.EXTRA_SPOKEN_TEXT, "Speech")
        .putExtra(AlarmScheduler.EXTRA_RECURRING, false)
    private fun buttons(view: View): List<Button> = when (view) {
        is Button -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun snooze(activity: AlarmActivity) = buttons(activity.window.decorView).single { it.text == "5 Minuten später" }
    private fun labels(view: View): List<String> =
        (if (view is TextView) listOf(view.text.toString()) else emptyList()) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
            else emptyList()

    @Test fun distinctRequestsNeverOverwriteOneAnother() {
        repeat(64) { AlarmScheduler.scheduleSnooze(context, "Request-$it", "Speech-$it") }
        val alarms = shadowOf(manager).scheduledAlarms
        assertEquals("Every logical request needs its own PendingIntent", 64, alarms.size)
        assertEquals(64, alarms.map { shadowOf(requireNotNull(it.operation)).savedIntent.dataString }.toSet().size)
        assertEquals((0 until 64).map { "Speech-$it" }.toSet(), alarms.map {
            shadowOf(requireNotNull(it.operation)).savedIntent.getStringExtra(AlarmScheduler.EXTRA_SPOKEN_TEXT)
        }.toSet())
    }
    @Test fun oldScreenCannotMintCurrentGenerationAfterRestoreOrStopNewOutput() {
        val controller = Robolectric.buildActivity(AlarmActivity::class.java, source()).setup()
        ScheduleState.disableAfterRestore(context)
        snooze(controller.get()).performClick()
        assertTrue("Rejected old screen must not schedule", shadowOf(manager).scheduledAlarms.isEmpty())
        assertNull("Rejected old screen must not issue global STOP", shadowOf(context).nextStartedService)
        controller.pause().stop().destroy()
    }
    @Test fun recreationRetryKeepsOneHandleDeadlineAndPayload() {
        val original = source()
        val first = Robolectric.buildActivity(AlarmActivity::class.java, original).setup()
        snooze(first.get()).performClick()
        val before = shadowOf(manager).scheduledAlarms.single()
        val originalIntent = Intent(shadowOf(requireNotNull(before.operation)).savedIntent)
        val saved = Bundle()
        first.saveInstanceState(saved).pause().stop().destroy()
        val second = Robolectric.buildActivity(AlarmActivity::class.java, original).create(saved).start().resume().visible()
        buttons(second.get().window.decorView).single { it.text == "5 Min. erneut" }.performClick()
        assertEquals("Recreation is a retry, not a new alarm", 1, shadowOf(manager).scheduledAlarms.size)
        val after = shadowOf(manager).scheduledAlarms.single()
        assertEquals(before.operation, after.operation)
        assertEquals(before.triggerAtTime, after.triggerAtTime)
        assertEquals(originalIntent.dataString, shadowOf(requireNotNull(after.operation)).savedIntent.dataString)
        assertEquals("Speech", shadowOf(requireNotNull(after.operation)).savedIntent.getStringExtra(AlarmScheduler.EXTRA_SPOKEN_TEXT))
        second.pause().stop().destroy()
    }
    @Test fun notificationKeepsOriginalAdmissionWhenOpenedAfterRestore() {
        val original = source()
        val service = Robolectric.buildService(AlarmService::class.java).create()
        service.get().onStartCommand(original, 0, 1)
        val notification = shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(AlarmService.NOTIFICATION_ID)
        val delivery = Intent(shadowOf(requireNotNull(notification.fullScreenIntent)).savedIntent)
        assertEquals(original.getStringExtra(AlarmScheduler.EXTRA_RESTORE_GENERATION), delivery.getStringExtra(AlarmScheduler.EXTRA_RESTORE_GENERATION))
        ScheduleState.disableAfterRestore(context)
        val screen = Robolectric.buildActivity(AlarmActivity::class.java, delivery).setup()
        assertTrue("Revoked admission must be visible", labels(screen.get().window.decorView)
            .any { it.contains("nicht mehr autorisiert") })
        assertTrue("Revoked screen must not offer an active or retry Snooze action",
            buttons(screen.get().window.decorView).none {
                it.text.toString().endsWith("später") || it.text.toString().endsWith("Min. erneut")
            })
        assertTrue(shadowOf(manager).scheduledAlarms.isEmpty())
        assertNull(shadowOf(context).nextStartedService)
        screen.pause().stop().destroy()
        service.destroy()
    }

    @Test fun retryAfterTimePassageKeepsDeadlineIdentityAndPayload() {
        val prepared = requireNotNull(AlarmScheduler.prepareSnooze(context, "Original", "Original speech", 1))
        assertTrue(AlarmScheduler.submitSnooze(context, prepared))
        val before = shadowOf(manager).scheduledAlarms.single()
        val clockBefore = System.currentTimeMillis()
        Thread.sleep(25)
        assertTrue("Retry must happen after observed wall-clock advance", System.currentTimeMillis() > clockBefore)
        val restored = requireNotNull(AlarmScheduler.PreparedSnooze.fromBundle(prepared.toBundle()))
        assertTrue(AlarmScheduler.submitSnooze(context, restored))
        val after = shadowOf(manager).scheduledAlarms.single()
        assertEquals(before.operation, after.operation)
        assertEquals(prepared.triggerAtMillis, after.triggerAtTime)
        val intent = shadowOf(requireNotNull(after.operation)).savedIntent
        assertEquals("Original speech", intent.getStringExtra(AlarmScheduler.EXTRA_SPOKEN_TEXT))
        assertEquals(prepared.restoreGeneration, intent.getStringExtra(AlarmScheduler.EXTRA_RESTORE_GENERATION))
    }

    @Test fun restoreBetweenPreparationAndSubmissionCreatesNoPendingIntent() {
        val prepared = requireNotNull(AlarmScheduler.prepareSnooze(context, "Before restore"))
        ScheduleState.disableAfterRestore(context)
        assertFalse(AlarmScheduler.submitSnooze(context, prepared))
        assertTrue(shadowOf(manager).scheduledAlarms.isEmpty())
        assertNull(PendingIntent.getBroadcast(context, 0, Intent(context, AlarmReceiver::class.java)
            .setData(Uri.parse("routinealarm://snooze/${prepared.requestId}")),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
    }

    @Test fun normalPauseStillAllowsPreparedExplicitOneShot() {
        val prepared = requireNotNull(AlarmScheduler.prepareSnooze(context, "Explicit", minutes = 5))
        ScheduleState.setEnabled(context, false)
        assertTrue(AlarmScheduler.submitSnooze(context, prepared))
        assertTrue(ScheduleState.isDeliveryAllowed(context,
            shadowOf(requireNotNull(shadowOf(manager).scheduledAlarms.single().operation)).savedIntent))
    }

    @Test fun expiredSavedRequestCannotRearmAndInvalidDurationIsRejected() {
        val prepared = requireNotNull(AlarmScheduler.prepareSnooze(context, "Expired"))
        val saved = prepared.toBundle().apply { putLong("at", System.currentTimeMillis() - 1) }
        assertFalse(AlarmScheduler.submitSnooze(context, requireNotNull(AlarmScheduler.PreparedSnooze.fromBundle(saved))))
        assertTrue(shadowOf(manager).scheduledAlarms.isEmpty())
        for (minutes in listOf(0, 6)) {
            assertThrows(IllegalArgumentException::class.java) { AlarmScheduler.prepareSnooze(context, "Invalid", minutes = minutes) }
        }
    }

    @Test fun duplicateTapKeepsOneRegistration() {
        val screen = Robolectric.buildActivity(AlarmActivity::class.java, source()).setup()
        val button = snooze(screen.get())
        button.performClick()
        Thread.sleep(25)
        button.performClick()
        assertEquals(1, shadowOf(manager).scheduledAlarms.size)
        screen.pause().stop().destroy()
    }

    @Test fun permissionFallbackKeepsStableIdentity() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val prepared = requireNotNull(AlarmScheduler.prepareSnooze(context, "Fallback"))
        assertTrue(AlarmScheduler.submitSnooze(context, prepared))
        assertTrue(AlarmScheduler.submitSnooze(context, prepared))
        assertEquals(1, shadowOf(manager).scheduledAlarms.size)
    }

    @Test @Config(shadows = [ThrowsAfterRegistration::class])
    fun unknownEffectRetryRetainsExactRequestAndDoesNotStopPrematurely() {
        val screen = Robolectric.buildActivity(AlarmActivity::class.java, source()).setup()
        val button = snooze(screen.get())
        button.performClick()
        val first = shadowOf(manager).scheduledAlarms.single()
        val original = Intent(shadowOf(requireNotNull(first.operation)).savedIntent)
        assertFalse(screen.get().isFinishing)
        assertNull("Unknown scheduling result must not stop output", shadowOf(context).nextStartedService)
        button.performClick()
        val retry = shadowOf(manager).scheduledAlarms.single()
        assertEquals(first.operation, retry.operation)
        assertEquals(first.triggerAtTime, retry.triggerAtTime)
        assertEquals(original.dataString, shadowOf(requireNotNull(retry.operation)).savedIntent.dataString)
        assertEquals(original.getStringExtra(AlarmScheduler.EXTRA_SPOKEN_TEXT),
            shadowOf(requireNotNull(retry.operation)).savedIntent.getStringExtra(AlarmScheduler.EXTRA_SPOKEN_TEXT))
        assertTrue(screen.get().isFinishing)
        assertEquals(AlarmService.ACTION_STOP, shadowOf(context).nextStartedService?.action)
        screen.pause().stop().destroy()
    }
}
