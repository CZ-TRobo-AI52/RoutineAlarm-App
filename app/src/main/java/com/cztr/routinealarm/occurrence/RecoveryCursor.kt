package com.cztr.routinealarm.occurrence

import java.io.File
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException

enum class RecoveryPhase { FUTURE, CLEANUP, COMPLETE }

data class RecoveryCursor(
    val cycleId: String,
    val epoch: String,
    val phase: RecoveryPhase,
    val through: Long,
    val futureAfterMillis: Long? = null,
    val futureAfterId: String? = null,
    val cleanupAfter: Long = 0,
)

interface RecoveryCursorStore {
    fun load(): RecoveryCursor?
    fun save(cursor: RecoveryCursor)
}

/** Progress only, never alarm authority. The Android adapter supplies a noBackupFilesDir file.
 * A malformed checkpoint safely starts a fresh pass; durable alarm guards still apply.
 * One serialized recovery worker owns this file, including across job stop/restart.
 */
class AtomicRecoveryCursorStore(private val file: File) : RecoveryCursorStore {
    private val atomic = AtomicFile(file)

    override fun load(): RecoveryCursor? = try {
        atomic.openRead().use { stream ->
            require(stream.channel.size() <= MAX_BYTES)
            val input = DataInputStream(stream)
            require(input.readInt() == MAGIC && input.readInt() == VERSION)
            val cycleId = input.text(256)
            val epoch = input.text(64)
            val phase = RecoveryPhase.entries.getOrNull(input.readInt()) ?: error("Unknown recovery phase")
            val through = input.readLong()
            val hasFuture = input.readBoolean()
            val futureMillis = if (hasFuture) input.readLong() else null
            val futureId = if (hasFuture) input.text(MAX_ID_BYTES) else null
            val cleanupAfter = input.readLong()
            require(input.read() == -1)
            RecoveryCursor(cycleId, epoch, phase, through, futureMillis, futureId, cleanupAfter).also(::validate)
        }
    } catch (error: FileNotFoundException) {
        if (!file.exists() && !File(file.path + ".bak").exists()) null else throw error
    } catch (_: EOFException) { null }
      catch (_: IllegalArgumentException) { null }
      catch (_: IllegalStateException) { null }

    override fun save(cursor: RecoveryCursor) {
        validate(cursor)
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(MAGIC); output.writeInt(VERSION)
                output.text(cursor.cycleId); output.text(cursor.epoch)
                output.writeInt(cursor.phase.ordinal); output.writeLong(cursor.through)
                output.writeBoolean(cursor.futureAfterMillis != null)
                if (cursor.futureAfterMillis != null) {
                    output.writeLong(cursor.futureAfterMillis); output.text(cursor.futureAfterId!!)
                }
                output.writeLong(cursor.cleanupAfter)
            }
            buffer.toByteArray()
        }
        require(bytes.size <= MAX_BYTES)
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create recovery checkpoint directory")
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }

    private fun validate(cursor: RecoveryCursor) {
        require(cursor.cycleId.isNotBlank() && cursor.cycleId.toByteArray(Charsets.UTF_8).size <= 256)
        require(cursor.epoch.length <= 64 && cursor.epoch.all { it.isLetterOrDigit() || it == '-' })
        require(cursor.through >= 0 && cursor.cleanupAfter in 0..cursor.through)
        require((cursor.futureAfterMillis == null) == (cursor.futureAfterId == null))
        cursor.futureAfterId?.let { require(it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= MAX_ID_BYTES) }
    }

    private fun DataInputStream.text(limit: Int): String {
        val size = readInt()
        require(size in 0..limit)
        val bytes = ByteArray(size)
        readFully(bytes)
        val value = bytes.toString(Charsets.UTF_8)
        require(value.toByteArray(Charsets.UTF_8).contentEquals(bytes))
        return value
    }

    private fun DataOutputStream.text(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size); write(bytes)
    }

    companion object {
        const val MAX_BYTES = 512 * 1024
        private const val MAX_ID_BYTES = 256 * 1024
        private const val MAGIC = 0x52353243
        private const val VERSION = 1
    }
}
