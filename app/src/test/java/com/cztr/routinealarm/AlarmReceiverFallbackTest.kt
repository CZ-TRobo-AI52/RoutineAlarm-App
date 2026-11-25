package com.cztr.routinealarm

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlarmReceiverFallbackTest {

    @Test
    fun rejectedForegroundServiceStartPostsAlarmFallbackNotification() {
        val app = RuntimeEnvironment.getApplication()
        val receiver = AlarmReceiver()
        val context = RejectingForegroundServiceContext(app)

        receiver.onReceive(
            context,
            Intent().putExtra(AlarmScheduler.EXTRA_TITLE, "Testalarm"),
        )

        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNotNull(manager.getNotificationChannel(AlarmDeliveryFallback.CHANNEL_ID))
        assertNotNull(manager.activeNotifications.firstOrNull {
            it.id == AlarmDeliveryFallback.NOTIFICATION_ID
        })
    }

    private class RejectingForegroundServiceContext(base: Context) : ContextWrapper(base) {
        override fun startForegroundService(service: Intent): ComponentName {
            throw ForegroundServiceStartNotAllowedException("test rejection")
        }
    }
}
