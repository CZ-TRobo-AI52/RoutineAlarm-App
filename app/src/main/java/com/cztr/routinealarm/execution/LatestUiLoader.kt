package com.cztr.routinealarm.execution

import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Read-only work owned by a screen. A newer request invalidates older results. */
internal class LatestUiLoader(
    private val worker: ExecutorService = Executors.newSingleThreadExecutor(),
    private val callbacks: Executor = Executor { Handler(Looper.getMainLooper()).post(it) },
) : Closeable {
    private val generation = AtomicLong()
    private val closed = AtomicBoolean()

    fun <T> load(read: () -> T, show: (Result<T>) -> Unit) {
        if (closed.get()) return
        val request = generation.incrementAndGet()
        worker.execute {
            if (closed.get() || generation.get() != request) return@execute
            val result = try { Result.success(read()) }
                catch (error: Exception) { Result.failure(error) }
            callbacks.execute {
                if (!closed.get() && generation.get() == request) show(result)
            }
        }
    }

    fun invalidate() { generation.incrementAndGet() }

    override fun close() {
        closed.set(true)
        invalidate()
        // Let an in-flight read release its own cursor/connection normally.
        worker.shutdown()
    }
}
