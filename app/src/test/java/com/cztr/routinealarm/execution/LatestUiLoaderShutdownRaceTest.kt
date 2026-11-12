package com.cztr.routinealarm.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Android-independent, real-Java-Executor reproducer for the close/load admission race. */
class LatestUiLoaderShutdownRaceTest {
    private fun CountDownLatch.awaitOrFail() {
        assertTrue("Latch timed out", await(3, TimeUnit.SECONDS))
    }

    private class GatedExecutor : AbstractExecutorService() {
        private val real = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun execute(command: Runnable) {
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS)) { "gate timeout" }
            real.execute(command)
        }
        override fun shutdown() = real.shutdown()
        override fun shutdownNow(): MutableList<Runnable> = real.shutdownNow()
        override fun isShutdown(): Boolean = real.isShutdown
        override fun isTerminated(): Boolean = real.isTerminated
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = real.awaitTermination(timeout, unit)
    }

    @Test fun shutdownBetweenLoadGuardAndExecuteIsNotAnUncaughtFailure() {
        repeat(12) { iteration ->
            val worker = GatedExecutor()
            val shown = AtomicInteger()
            val read = AtomicInteger()
            val error = AtomicReference<Throwable?>()
            val finished = CountDownLatch(1)
            val loader = LatestUiLoader(worker, Executor { it.run() })
            val thread = Thread({
                try { loader.load({ read.incrementAndGet() }) { shown.incrementAndGet() } }
                catch (t: Throwable) { error.set(t) }
                finally { finished.countDown() }
            }, "race-repro-$iteration")
            thread.start()
            try {
                worker.entered.awaitOrFail()
                loader.close() // real executor is now shut down before execute() resumes
                worker.release.countDown()
                finished.awaitOrFail()
                assertNull("closed load must not leak shutdown rejection", error.get())
                assertEquals(0, read.get())
                assertEquals(0, shown.get())
                assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS))
            } finally {
                worker.release.countDown()
                loader.close()
                thread.join(3000)
                assertFalse("test thread leaked", thread.isAlive)
                worker.shutdownNow()
            }
        }
    }

    @Test fun unexpectedRejectionWhileOpenRemainsVisible() {
        val worker = Executors.newSingleThreadExecutor()
        val loader = LatestUiLoader(worker, Executor { it.run() })
        worker.shutdown() // external executor failure, loader is still open
        try {
            val error = runCatching { loader.load({ 7 }) {} }.exceptionOrNull()
            assertTrue("unrelated rejection must be visible", error is RejectedExecutionException)
        } finally { loader.close() }
    }

    /** If close wins before executor admission, false means no command effect or reply. */
    @Test fun submitRaceWithCloseRejectsWithoutSchedulingEffects() {
        repeat(12) { iteration ->
            val worker = GatedExecutor()
            val effects = AtomicInteger()
            val replies = AtomicInteger()
            val rejected = AtomicReference<Boolean?>()
            val error = AtomicReference<Throwable?>()
            val finished = CountDownLatch(1)
            val loader = LatestUiLoader(worker, Executor { it.run() })
            val submitter = Thread({
                try {
                    rejected.set(!loader.submit({ effects.incrementAndGet() }) { replies.incrementAndGet() })
                } catch (t: Throwable) { error.set(t) }
                finally { finished.countDown() }
            }, "submit-race-$iteration")
            submitter.start()
            try {
                worker.entered.awaitOrFail()
                loader.close()
                worker.release.countDown()
                finished.awaitOrFail()
                assertNull("submit must not throw on a normal close race", error.get())
                assertTrue("submit after executor shutdown must not be admitted", rejected.get() == true)
                assertEquals(0, effects.get())
                assertEquals(0, replies.get())
                assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS))
            } finally {
                worker.release.countDown()
                loader.close()
                submitter.join(3000)
                assertFalse("submitter thread leaked", submitter.isAlive)
                worker.shutdownNow()
            }
        }
    }

    @Test fun closeNeverCancelsAnAcceptedCommand() {
        val worker = Executors.newSingleThreadExecutor()
        val gateEntered = CountDownLatch(1)
        val unblockWorker = CountDownLatch(1)
        val commandFinished = CountDownLatch(1)
        val effects = AtomicInteger()
        val replies = AtomicInteger()
        val loader = LatestUiLoader(worker, Executor { it.run() })
        worker.execute { gateEntered.countDown(); check(unblockWorker.await(3, TimeUnit.SECONDS)) }
        try {
            gateEntered.awaitOrFail()
            assertTrue(loader.submit({ effects.incrementAndGet(); commandFinished.countDown() }) { replies.incrementAndGet() })
            loader.close()
            unblockWorker.countDown()
            commandFinished.awaitOrFail()
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS))
            assertEquals(1, effects.get())
            assertEquals(0, replies.get())
        } finally {
            unblockWorker.countDown()
            loader.close()
            worker.shutdownNow()
        }
    }

    /** Queued callbacks from an obsolete screen revision must never overwrite new UI. */
    @Test fun queuedUiCallbacksRespectLatestGenerationAndClose() {
        val worker = Executors.newSingleThreadExecutor()
        val callbacks = LinkedBlockingQueue<Runnable>()
        val shown = mutableListOf<String>()
        val loader = LatestUiLoader(worker, Executor { callback -> callbacks.put(callback) })
        fun pending(): Runnable {
            val callback = callbacks.poll(3, TimeUnit.SECONDS)
            assertTrue("Worker failed to publish queued callback", callback != null)
            return callback!!
        }
        try {
            loader.load({ 1 }) { shown.add("initial:${it.getOrNull()}") }
            val initial = pending() // accepted callback not yet delivered to screen
            loader.invalidate() // navigation or schedule edit invalidates old UI
            loader.load({ 2 }) { shown.add("updated:${it.getOrNull()}") }
            val updated = pending()
            initial.run()
            assertTrue("Stale callback must not update the new screen", shown.isEmpty())
            updated.run()
            assertTrue("Unexpected UI result ordering: $shown", shown == listOf("updated:2"))

            loader.load<Int>({ throw IllegalStateException("obsolete read") }) {
                shown.add("obsolete-error:${it.exceptionOrNull()?.message}")
            }
            val staleError = pending()
            loader.load({ 3 }) { shown.add("new:${it.getOrNull()}") }
            val current = pending()
            staleError.run()
            assertTrue("Unexpected UI result ordering: $shown", shown == listOf("updated:2"))
            current.run()
            assertTrue("Unexpected UI result ordering: $shown", shown == listOf("updated:2", "new:3"))

            loader.load<Int>({ throw IllegalStateException("current read failure") }) {
                shown.add("current-error:${it.exceptionOrNull()?.message}")
            }
            pending().run() // current failure remains deliverable
            assertTrue("Unexpected UI result ordering: $shown", shown == listOf("updated:2", "new:3", "current-error:current read failure"))

            loader.load({ 4 }) { shown.add("post-close:${it.getOrNull()}") }
            val pendingAtClose = pending()
            loader.close()
            pendingAtClose.run()
            assertEquals(3, shown.size)
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS))
        } finally {
            loader.close()
            worker.shutdownNow()
        }
    }

}
