package com.cztr.routinealarm.backup

import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import com.cztr.routinealarm.occurrence.OccurrenceBackup

internal object BackupDocumentIO {
    private const val MAX_BYTES = OccurrenceBackup.MAX_BYTES

    fun read(input: InputStream): ByteArray = input.use {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            // Read at most one byte beyond the limit, even from an unbounded provider.
            val count = it.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - bytes.size()))
            if (count < 0) break
            if (count == 0) {
                val single = it.read()
                if (single < 0) break
                bytes.write(single)
            } else bytes.write(buffer, 0, count)
            require(bytes.size() <= MAX_BYTES) { "Backup document is too large" }
        }
        bytes.toByteArray()
    }

    fun write(output: OutputStream, bytes: ByteArray) = output.use {
        require(bytes.size <= MAX_BYTES) { "Backup document is too large" }
        it.write(bytes)
        it.flush()
        // use propagates close failures; callers must not show success before it returns.
    }
}
