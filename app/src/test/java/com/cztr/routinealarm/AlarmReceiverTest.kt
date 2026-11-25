package com.cztr.routinealarm

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
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
import org.robolectric.shadows.ShadowApplication
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlarmReceiverTest {

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.cancelAll()
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
    }

    @Test
    fun exactAlarmUnavailable_postsAlarmNotification() {
        AlarmReceiver().onReceive(
            context,
            Intent(context, AlarmReceiver::class.java).apply {
                putExtra(AlarmScheduler.EXTRA_TITLE, "Morning routine")
            }
        )

        val notification = shadowOf(notificationManager).activeNotifications.single().notification

        assertEquals(Notification.CATEGORY_ALARM, notification.category)
        assertEquals(
            "Morning routine",
            notification.extras.getString(Notification.EXTRA_TEXT)
        )
        assertNotNull(notification.fullScreenIntent)
        assertNotNull(notificationManager.getNotificationChannel(AlarmService.CHANNEL_ID))
        assertTrue(shadowOf(context.applicationContext).getAllStartedServices().isEmpty())
    }

    @Test
    fun foregroundServiceRejection_postsAlarmNotification() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        val rejectingContext = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName {
                throw ForegroundServiceStartNotAllowedException("blocked by test")
            }
        }

        AlarmReceiver().onReceive(
            rejectingContext,
            Intent(rejectingContext, AlarmReceiver::class.java).apply {
                putExtra(AlarmScheduler.EXTRA_TITLE, "FGS rejection")
            }
        )

        val notification = shadowOf(notificationManager).activeNotifications.single().notification

        assertEquals(Notification.CATEGORY_ALARM, notification.category)
        assertEquals(
            "FGS rejection",
            notification.extras.getString(Notification.EXTRA_TEXT)
        )
        assertNotNull(notification.fullScreenIntent)
    }
}
