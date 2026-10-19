package com.cztr.routinealarm

import com.cztr.routinealarm.backup.BackupDocumentIO
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

class BackupDocumentIOTest {
    @Test fun importReadsExactBytesAndClosesDocument() {
        var closed = false
        val input = object : ByteArrayInputStream(byteArrayOf(7, 0, -1, 22)) {
            override fun close() { closed = true; super.close() }
        }
        assertArrayEquals(byteArrayOf(7, 0, -1, 22), BackupDocumentIO.read(input))
        assertTrue(closed)
    }
    @Test fun exportWritesAllBytesBeforeSuccessfulClose() {
        var closed = false
        val output = object : ByteArrayOutputStream() {
            override fun close() { closed = true; super.close() }
        }
        BackupDocumentIO.write(output, byteArrayOf(2, 4, 8))
        assertArrayEquals(byteArrayOf(2, 4, 8), output.toByteArray())
        assertTrue(closed)
    }
    @Test fun oversizeInputIsRejectedWithoutUnboundedReading() {
        var read = 0
        val input = object : InputStream() {
            override fun read(): Int { read++; return 1 }
        }
        assertThrows(IllegalArgumentException::class.java) { BackupDocumentIO.read(input) }
        assertTrue(read <= 8 * 1024 * 1024 + 1)
    }
    @Test fun failedCloseCannotReportSuccessfulExport() {
        val output = object : ByteArrayOutputStream() {
            override fun close() { throw IOException("provider close failed") }
        }
        assertThrows(IOException::class.java) { BackupDocumentIO.write(output, byteArrayOf(2)) }
    }
    @Test fun partialReadFailureCannotYieldAnImportablePrefix() {
        val input = object : InputStream() {
            override fun read(): Int = throw IOException("provider read failed")
        }
        assertThrows(IOException::class.java) { BackupDocumentIO.read(input) }
    }
}
