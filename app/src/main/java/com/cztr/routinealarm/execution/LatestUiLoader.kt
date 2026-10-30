package com.cztr.routinealarm.execution

import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Screen I/O: latest-only reads and accepted commands share the same serial executor. */
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

    /** Once admitted, a command finishes even when navigation invalidates reads or closes the screen.
     * The caller retains its immutable request and handles a lost reply through the existing journal.
     */
    fun <T> submit(command: () -> T, reply: (Result<T>) -> Unit): Boolean {
        if (closed.get()) return false
        return try {
            worker.execute {
                val result = try { Result.success(command()) }
                    catch (error: Exception) { Result.failure(error) }
                callbacks.execute { if (!closed.get()) reply(result) }
            }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    override fun close() {
        closed.set(true)
        invalidate()
        // Release in-flight reads normally and finish every already accepted command.
        worker.shutdown()
    }
}
