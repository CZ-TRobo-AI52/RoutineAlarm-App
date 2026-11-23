package com.cztr.routinealarm

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlarmReceiverTest {

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        notificationManager =
            context.getSystemService(NotificationManager::class.java)

        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        notificationManager.cancelAll()
        shadowOf(context).clearStartedServices()
    }

    @After
    fun tearDown() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        notificationManager.cancelAll()
        shadowOf(context).clearStartedServices()
    }

    @Test
    fun exactAlarmUnavailable_postsAlarmNotification() {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_TITLE, "Morning routine")
        }

        AlarmReceiver().onReceive(context, intent)

        val activeNotifications = shadowOf(notificationManager).activeNotifications
        assertEquals(1, activeNotifications.size)

        val notification = activeNotifications.single().notification
        assertEquals(Notification.CATEGORY_ALARM, notification.category)
        assertEquals(
            "Morning routine",
            notification.extras.getString(Notification.EXTRA_TEXT)
        )
        assertNotNull(notification.fullScreenIntent)
        assertNotNull(
            notificationManager.getNotificationChannel(AlarmService.CHANNEL_ID)
        )
        assertTrue(shadowOf(context).getAllStartedServices().isEmpty())
    }

    @Test
    fun exactAlarmUnavailable_keepsAlarmNotificationHighPriority() {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_TITLE, "Take supplements")
        }

        AlarmReceiver().onReceive(context, intent)

        val notification =
            shadowOf(notificationManager).activeNotifications.single().notification

        assertEquals(Notification.PRIORITY_MAX, notification.priority)
        assertEquals(
            Notification.VISIBILITY_PUBLIC,
            notification.visibility
        )
    }
}
