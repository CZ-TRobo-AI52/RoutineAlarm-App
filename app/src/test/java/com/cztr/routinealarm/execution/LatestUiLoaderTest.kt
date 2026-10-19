package com.cztr.routinealarm.execution

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

class LatestUiLoaderTest {
    @Test fun readsRunOnWorkerAndResultsOnlyOnCallbackExecutor() {
        val worker = ManualExecutor()
        val ui = ManualExecutor()
        val loader = LatestUiLoader(worker, ui)
        var read = false
        var shown: String? = null
        loader.load({ read = true; "stored" }) { shown = it.getOrThrow() }
        assertFalse(read)
        assertNull(shown)
        worker.runNext()
        assertTrue(read)
        assertNull(shown)
        ui.runNext()
        assertEquals("stored", shown)
        loader.close()
    }

    @Test fun newerRequestSkipsAnObsoleteQueuedRead() {
        val worker = ManualExecutor()
        val ui = ManualExecutor()
        val loader = LatestUiLoader(worker, ui)
        var obsoleteReads = 0
        val shown = mutableListOf<String>()
        loader.load({ obsoleteReads++; "old" }) { shown.add(it.getOrThrow()) }
        loader.load({ "new" }) { shown.add(it.getOrThrow()) }
        worker.drain(); ui.drain()
        assertEquals(0, obsoleteReads)
        assertEquals(listOf("new"), shown)
        loader.close()
    }

    @Test fun resultAlreadyQueuedCannotReplaceANewerScreen() {
        val worker = ManualExecutor()
        val ui = ManualExecutor()
        val loader = LatestUiLoader(worker, ui)
        val shown = mutableListOf<Int>()
        loader.load({ 1 }) { shown.add(it.getOrThrow()) }
        worker.runNext()
        loader.load({ 2 }) { shown.add(it.getOrThrow()) }
        worker.runNext(); ui.drain()
        assertEquals(listOf(2), shown)
        loader.close()
    }

    @Test fun invalidateAndDestroyDiscardResultsWithoutInventingEmptyData() {
        val worker = ManualExecutor()
        val ui = ManualExecutor()
        val loader = LatestUiLoader(worker, ui)
        var callbacks = 0
        loader.load({ "before pause" }) { callbacks++ }
        worker.runNext(); loader.invalidate(); ui.drain()
        assertEquals(0, callbacks)
        loader.load({ "after resume" }) { callbacks++ }
        worker.runNext(); ui.drain()
        assertEquals(1, callbacks)
        loader.load({ "before destroy" }) { callbacks++ }
        worker.runNext(); loader.close(); ui.drain()
        loader.load({ error("closed screen read") }) { callbacks++ }
        worker.drain(); ui.drain()
        assertEquals(1, callbacks)
    }

    @Test fun readErrorIsDeliveredAndDoesNotBreakTheNextRequest() {
        val worker = ManualExecutor()
        val ui = ManualExecutor()
        val loader = LatestUiLoader(worker, ui)
        val failure = IOException("storage unavailable")
        var observed: Throwable? = null
        var next: Int? = null
        loader.load<String>({ throw failure }) { observed = it.exceptionOrNull() }
        worker.drain(); ui.drain()
        assertSame(failure, observed)
        loader.load({ 7 }) { next = it.getOrThrow() }
        worker.drain(); ui.drain()
        assertEquals(7, next)
        loader.close()
    }

    private class ManualExecutor : AbstractExecutorService() {
        private val queue = ArrayDeque<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) { check(!stopped); queue.addLast(command) }
        fun runNext() { check(queue.isNotEmpty()) { "No work scheduled" }; queue.removeFirst().run() }
        fun drain() { while (queue.isNotEmpty()) runNext() }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return queue.toMutableList().also { queue.clear() } }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && queue.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }
}
