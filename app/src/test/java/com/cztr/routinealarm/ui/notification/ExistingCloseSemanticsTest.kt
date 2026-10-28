package com.cztr.routinealarm.ui.notification

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.cztr.routinealarm.execution.TestAlarmActivity
import com.cztr.routinealarm.execution.TestAlarmRuntime
import com.cztr.routinealarm.execution.TestAlarmService
import com.cztr.routinealarm.occurrence.AlarmDelivery
import com.cztr.routinealarm.occurrence.OccurrenceStatus
import com.cztr.routinealarm.occurrence.TestAlarmCoordinator
import com.cztr.routinealarm.support.awaitUi
import com.cztr.routinealarm.support.awaitUiReadersStopped
import org.junit.Assert.*
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

/** Existing behavior only: screen-close is not an acknowledged output-close API. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ExistingCloseSemanticsTest {
    private fun buttons(view: View): List<Button> = when (view) {
        is Button -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun closeScreenLeavesTriggeredOccurrenceAndExistingNotificationUnchanged() {
        val context: Context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("occurrences.db")
        val repo = TestAlarmRuntime.repository(context)
        val created = TestAlarmCoordinator(repo,
            Clock.offset(Clock.systemDefaultZone(), Duration.ofMinutes(-2))).create(1)
        val fired = TestAlarmRuntime.coordinator(repo).trigger(
            AlarmDelivery.from(created, repo.deliveryEpoch()))!!
        val delivery = AlarmDelivery.from(fired, repo.deliveryEpoch())
        val historyBefore = repo.history(fired.id)
        val service = Robolectric.buildService(TestAlarmService::class.java).create()
        val intent = TestAlarmRuntime.putToken(Intent(context, TestAlarmActivity::class.java), delivery)
        val activity = Robolectric.buildActivity(TestAlarmActivity::class.java, intent)
        try {
            service.get().onStartCommand(TestAlarmRuntime.putToken(
                Intent(context, TestAlarmService::class.java), delivery), 0, 1)
            val notifications = shadowOf(context.getSystemService(NotificationManager::class.java))
            fun notificationToken(): AlarmDelivery? = notifications.getNotification(TestAlarmService.NOTIFICATION)
                ?.contentIntent?.let { TestAlarmRuntime.token(shadowOf(it).savedIntent) }
            assertEquals(delivery, notificationToken())
            activity.setup()
            awaitUi(activity.get())
            buttons(activity.get().window.decorView).single { it.text == "Schließen" }.performClick()
            assertTrue(activity.get().isFinishing)
            assertEquals(fired, repo.get(fired.id))
            assertEquals(OccurrenceStatus.TRIGGERED, repo.get(fired.id)!!.status)
            assertEquals(historyBefore, repo.history(fired.id))
            assertEquals(delivery, notificationToken())
            // No physical TTS, durable output-close receipt or user completion is inferred.
        } finally {
            activity.pause().stop().destroy()
            awaitUiReadersStopped(activity.get())
            service.destroy()
            repo.close()
            context.deleteDatabase("occurrences.db")
        }
    }
}
