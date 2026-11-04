package com.cztr.routinealarm.support

import com.cztr.routinealarm.ScheduleState
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import android.os.Looper
import org.robolectric.Shadows.shadowOf

/** Fixture barrier only; production callbacks never wait for this future. */
internal fun <T> onRoutineStorage(block: () -> T): T {
    val result = CompletableFuture<T>()
    ScheduleState.dispatchStorage { try { result.complete(block()) } catch (t: Throwable) { result.completeExceptionally(t) } }
    return result.get(10, TimeUnit.SECONDS)
}
internal fun drainRoutineAdmissions() {
    repeat(3) { onRoutineStorage { Unit }; shadowOf(Looper.getMainLooper()).idle() }
}
