package com.cztr.routinealarm.support

import android.os.Looper
import com.cztr.routinealarm.execution.TestAlarmService
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Test-only serial barriers: await actual repository work and its main-thread publication.
 * No result/notification/action is fabricated and no safety assertion is changed.
 */
fun awaitTestOutput(service: ServiceController<TestAlarmService>) {
    val field = TestAlarmService::class.java.getDeclaredField("reader").apply { isAccessible = true }
    val executor = field.get(service.get()) as ExecutorService
    repeat(4) {
        executor.submit {}.get(10, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }
}

fun awaitTestOutputStopped(service: ServiceController<TestAlarmService>) {
    val field = TestAlarmService::class.java.getDeclaredField("reader").apply { isAccessible = true }
    check((field.get(service.get()) as ExecutorService).awaitTermination(10, TimeUnit.SECONDS))
}
