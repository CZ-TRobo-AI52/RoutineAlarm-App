package com.cztr.routinealarm.occurrence

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31])
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class OccurrenceColdOpenConcurrencyTest {
    @Implements(SQLiteDatabase::class)
    class PausedSchema {
        @RealObject lateinit var database: SQLiteDatabase
        @Implementation fun execSQL(sql: String) {
            if (sql.trimStart().startsWith("CREATE TABLE", ignoreCase = true) &&
                paused.compareAndSet(false, true)) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Schema gate was not released" }
            }
            Shadow.directlyOn<Void, SQLiteDatabase>(database, SQLiteDatabase::class.java, "execSQL",
                ClassParameter.from(String::class.java, sql))
        }
        companion object {
            val paused = AtomicBoolean(false)
            var entered = CountDownLatch(1)
            var release = CountDownLatch(1)
        }
    }

    @Test
    @Config(shadows = [PausedSchema::class])
    fun concurrentFirstCatalogReadsFinishWithoutPreopeningTheDatabase() {
        val app = RuntimeEnvironment.getApplication()
        val name = "cold-${UUID.randomUUID()}.db"
        val firstRepo = SqliteOccurrenceRepository(app, Clock.systemUTC(), name)
        val secondRepo = SqliteOccurrenceRepository(app, Clock.systemUTC(), name)
        val executor = Executors.newFixedThreadPool(2) { task -> Thread(task).apply { isDaemon = true } }
        PausedSchema.paused.set(false)
        PausedSchema.entered = CountDownLatch(1)
        PausedSchema.release = CountDownLatch(1)
        val secondThread = AtomicReference<Thread>()
        val first = executor.submit<PersonalRoutineCatalog> { firstRepo.personalRoutineCatalog() }
        var second: java.util.concurrent.Future<PersonalRoutineCatalog>? = null
        try {
            assertTrue("First actual cold schema initialization must be reached",
                PausedSchema.entered.await(3, TimeUnit.SECONDS))
            second = executor.submit<PersonalRoutineCatalog> {
                secondThread.set(Thread.currentThread())
                secondRepo.personalRoutineCatalog()
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (System.nanoTime() < deadline && secondThread.get()?.stackTrace?.none {
                    it.className == "android.database.sqlite.SQLiteOpenHelper"
                } != false) Thread.yield()
            assertFalse("Second real repository read must overlap the cold open", second.isDone)
            assertTrue("Second reader must enter the actual helper boundary",
                secondThread.get()?.stackTrace?.any { it.className == "android.database.sqlite.SQLiteOpenHelper" } == true)
            PausedSchema.release.countDown()
            val a = first.get(3, TimeUnit.SECONDS)
            val b = second.get(3, TimeUnit.SECONDS)
            assertEquals(a, b)
            assertTrue(a.definitions.isEmpty())
        } finally {
            PausedSchema.release.countDown()
            runCatching { first.get(15, TimeUnit.SECONDS) }
            runCatching { second?.get(15, TimeUnit.SECONDS) }
            runCatching { firstRepo.close() }
            runCatching { secondRepo.close() }
            executor.shutdownNow()
        }
    }

    @Test fun closingOneRepositoryDoesNotCloseAnotherRepositoriesLiveTransaction() {
        val app = RuntimeEnvironment.getApplication()
        // Robolectric isolates each method directory. Keep the filename short
        // so native SQLite WAL/SHM sidecars fit the Windows path limit.
        val name = "lease.db"
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = SqliteOccurrenceRepository(app, Clock.systemUTC(), name) {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val peer = SqliteOccurrenceRepository(app, Clock.systemUTC(), name)
        val executor = Executors.newFixedThreadPool(2) { task -> Thread(task).apply { isDaemon = true } }
        val write = executor.submit<Int> { writer.materialize(emptyList()) }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            executor.submit { peer.close(); peer.close() }.get(2, TimeUnit.SECONDS)
            release.countDown()
            assertEquals(0, write.get(3, TimeUnit.SECONDS).toInt())
            assertTrue(writer.personalRoutineCatalog().definitions.isEmpty())
        } finally {
            release.countDown()
            runCatching { write.get(10, TimeUnit.SECONDS) }
            runCatching { writer.close() }
            runCatching { peer.close() }
            executor.shutdownNow()
        }
        SqliteOccurrenceRepository(app, Clock.systemUTC(), name).use {
            assertTrue(it.personalRoutineCatalog().definitions.isEmpty())
        }
    }
}
