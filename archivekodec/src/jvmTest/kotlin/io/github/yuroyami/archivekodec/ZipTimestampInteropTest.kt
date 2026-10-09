package io.github.yuroyami.archivekodec

import io.github.yuroyami.archivekodec.archive.zip.ZipMethod
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import java.nio.file.Files
import java.time.LocalDateTime
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZipTimestampInteropTest {
    @Test
    fun defaultDatesAreValidAndReproducibleInBothHeaders() {
        val expected = LocalDateTime.of(1980, 1, 1, 0, 0)
        val files = listOf(
            ZipWriter.FileSpec("stored.txt", byteArrayOf(65, 66, 67), ZipMethod.STORED),
            ZipWriter.FileSpec("deflated.txt", ByteArray(256) { it.toByte() }, ZipMethod.DEFLATED),
        )
        val bytes = ZipWriter.write(files)
        assertContentEquals(bytes, ZipWriter.write(files))
        ZipInputStream(bytes.inputStream()).use { input ->
            for (file in files) {
                val entry = input.nextEntry
                assertEquals(file.name, entry.name)
                assertEquals(expected, entry.timeLocal)
                assertContentEquals(file.data, input.readAllBytes())
            }
            assertNull(input.nextEntry)
        }
        val path = Files.createTempFile("archivekodec-default-date-", ".zip")
        try {
            Files.write(path, bytes)
            ZipFile(path.toFile()).use { zip ->
                for (file in files) {
                    val entry = zip.getEntry(file.name)
                    assertEquals(expected, entry.timeLocal)
                    zip.getInputStream(entry).use { assertContentEquals(file.data, it.readAllBytes()) }
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun setTimesModesAndCommentsAreReadByTheJdk() {
        val files = listOf(
            ZipWriter.FileSpec("run.sh", "#!/bin/sh\n".encodeToByteArray(), ZipMethod.STORED) {
                lastModifiedEpochSeconds = 981_184_091 // 2001-02-03 07:08:11 UTC
                unixMode = 0x81ED
                comment = "entry note"
            },
        )
        val bytes = ZipWriter.write(files, "archive note")
        val path = Files.createTempFile("archivekodec-metadata-", ".zip")
        try {
            Files.write(path, bytes)
            ZipFile(path.toFile()).use { zip ->
                assertEquals("archive note", zip.comment)
                val entry = zip.getEntry("run.sh")
                assertEquals("entry note", entry.comment)
                // The JDK prefers the extended timestamp, which keeps the odd second.
                assertEquals(java.nio.file.attribute.FileTime.from(java.time.Instant.ofEpochSecond(981_184_091)), entry.lastModifiedTime)
            }
            // The local header's DOS fields are UTC, with the odd second rounded down to 07:08:10.
            val time = (bytes[10].toInt() and 0xFF) or (bytes[11].toInt() and 0xFF shl 8)
            val date = (bytes[12].toInt() and 0xFF) or (bytes[13].toInt() and 0xFF shl 8)
            assertEquals((7 shl 11) or (8 shl 5) or 5, time)
            assertEquals(((2001 - 1980) shl 9) or (2 shl 5) or 3, date)
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
