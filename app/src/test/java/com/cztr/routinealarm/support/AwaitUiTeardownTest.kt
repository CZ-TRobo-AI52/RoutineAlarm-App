package com.cztr.routinealarm.support

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.cztr.routinealarm.execution.LatestUiLoader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31])
@LooperMode(LooperMode.Mode.PAUSED)
class AwaitUiTeardownTest {
    class ReaderActivity : Activity() {
        internal val reader = LatestUiLoader()
        override fun onDestroy() { reader.close(); super.onDestroy() }
    }

    @Test fun teardownWaitsForWorkerAndQueuedReplyWithoutDrainingRepostedFrames() {
        val controller = Robolectric.buildActivity(ReaderActivity::class.java).create().start().resume()
        val activity = controller.get()
        val completed = CountDownLatch(1)
        var lateReplies = 0
        assertTrue(activity.reader.submit({ completed.countDown(); 1 }) { lateReplies++ })
        assertTrue(completed.await(10, TimeUnit.SECONDS))
        controller.pause().stop().destroy()
        val handler = Handler(Looper.getMainLooper())
        var frames = 0
        val frame = object : Runnable {
            override fun run() { frames++; if (frames < 1000) handler.post(this) }
        }
        handler.post(frame)
        try {
            awaitUiReadersStopped(activity)
            assertEquals("Closed reader must suppress its already queued reply", 0, lateReplies)
            assertTrue("Already queued UI work must run", frames > 0)
            assertTrue("Teardown must stop at its queue barrier instead of draining future frames", frames < 1000)
        } finally { handler.removeCallbacks(frame) }
    }
}
