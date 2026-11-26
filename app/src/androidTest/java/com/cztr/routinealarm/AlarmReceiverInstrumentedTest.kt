package com.cztr.routinealarm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmReceiverInstrumentedTest {

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        notificationManager =
            context.getSystemService(NotificationManager::class.java)
        notificationManager.cancelAll()
    }

    @After
    fun tearDown() {
        notificationManager.cancelAll()
    }

    @Test
    fun exactAlarmUnavailable_postsAlarmNotificationOnDevice() {
        val alarmManager =
            context.getSystemService(AlarmManager::class.java)

        assertTrue(
            "Test device must deny SCHEDULE_EXACT_ALARM before this test",
            !alarmManager.canScheduleExactAlarms()
        )

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_TITLE, "Instrumented morning routine")
        }

        AlarmReceiver().onReceive(context, intent)

        val activeNotifications = notificationManager.activeNotifications
        assertEquals(1, activeNotifications.size)

        val notification = activeNotifications.single().notification
        assertEquals(Notification.CATEGORY_ALARM, notification.category)
        assertEquals(
            "Instrumented morning routine",
            notification.extras.getString(Notification.EXTRA_TEXT)
        )
        assertNotNull(notification.fullScreenIntent)

        val channel =
            notificationManager.getNotificationChannel(AlarmService.ROUTINE_CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(
            NotificationManager.IMPORTANCE_HIGH,
            channel!!.importance
        )
    }
}
