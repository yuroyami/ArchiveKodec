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
}
