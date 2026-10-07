package io.github.yuroyami.archivekodec

import io.github.yuroyami.archivekodec.archive.zip.ZipMethod
import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.archive.zip64WithComment
import io.github.yuroyami.archivekodec.archive.zip.ZipFormat
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Interop both directions against the JDK's ZIP, plus the ZIP64 (entry-count) path. */
class ZipInteropTest {

    private val dataA = ByteArray(40) { 'A'.code.toByte() }
    private val dataB = "the quick brown fox jumps over the lazy dog ".repeat(20).encodeToByteArray()

    @Test
    fun ourZipReadByJdk() {
        val files = listOf(
            ZipWriter.FileSpec("a.txt", dataA, ZipMethod.STORED),
            ZipWriter.FileSpec("b.txt", dataB, ZipMethod.DEFLATED),
            ZipWriter.FileSpec("dir/", byteArrayOf(), ZipMethod.STORED),
            ZipWriter.FileSpec("dir/é雪.txt", ByteArray(256) { it.toByte() }, ZipMethod.DEFLATED),
        )
        val bytes = ZipWriter.write(files)
        withJdkZip(bytes) { assertJdkEntries(it, files) }
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            for (file in files) {
                val entry = zis.nextEntry!!
                assertEquals(file.name, entry.name)
                assertContentEquals(file.data, zis.readBytes())
                zis.closeEntry()
            }
            assertNull(zis.nextEntry)
        }
    }

    @Test
    fun damagedCentralDirectoryIsRejectedEvenWhenLocalEntriesRemainReadable() {
        val file = ZipWriter.FileSpec("a.txt", dataA, ZipMethod.STORED)
        val bytes = ZipWriter.write(listOf(file))
        val centralStart = ZipFormat.u32(bytes, bytes.size - 6).toInt()
        bytes.fill(0x7F.toByte(), centralStart, bytes.size - 22)
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            assertEquals(file.name, zis.nextEntry!!.name)
            assertContentEquals(file.data, zis.readBytes())
            zis.closeEntry()
            assertNull(zis.nextEntry)
        }
        assertFailsWith<ZipException> { withJdkZip(bytes) { error("Damaged central directory was accepted") } }
    }

    @Test
    fun jdkZipReadByUs() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.putNextEntry(ZipEntry("x.txt")); zos.write(dataB); zos.closeEntry()
            zos.putNextEntry(ZipEntry("y.txt")); zos.write(dataA); zos.closeEntry()
        }
        ZipReader(ByteArrayRandomAccessSource(bos.toByteArray())).use { reader ->
            val byName = reader.entries().associateBy { it.name }
            assertContentEquals(dataB, reader.read(byName.getValue("x.txt")))
            assertContentEquals(dataA, reader.read(byName.getValue("y.txt")))
        }
    }

    @Test
    fun zip64LongCommentsAgreeWithJdkCentralDirectoryReader() {
        for (length in listOf(0, 65515, 65516, 65535)) {
            val bytes = zip64WithComment(length)
            withJdkZip(bytes) { jdk ->
                ZipReader(ByteArrayRandomAccessSource(bytes)).use { reader ->
                    val entry = reader.entries().single()
                    assertEquals(jdk.size(), reader.entries().size)
                    jdk.getInputStream(jdk.getEntry(entry.name)).use { stream ->
                        assertContentEquals(stream.readBytes(), reader.read(entry))
                    }
                }
            }
        }
    }

    @Test
    fun zip64EntryCountRoundTrip() {
        // > 65535 entries forces the ZIP64 end-of-central-directory on write + read.
        val files = (0 until 70_000).map {
            ZipWriter.FileSpec("f$it", byteArrayOf((it and 0xFF).toByte()), ZipMethod.STORED)
        }
        val bytes = ZipWriter.write(files)
        withJdkZip(bytes) { assertJdkEntries(it, files) }
        ZipReader(ByteArrayRandomAccessSource(bytes)).use { reader ->
            val entries = reader.entries()
            assertEquals(70_000, entries.size)
            assertContentEquals(byteArrayOf(0), reader.read(entries.first { it.name == "f0" }))
            assertContentEquals(byteArrayOf((69_999 and 0xFF).toByte()), reader.read(entries.first { it.name == "f69999" }))
        }
    }

    private fun assertJdkEntries(zip: ZipFile, files: List<ZipWriter.FileSpec>) {
        assertEquals(files.size, zip.size())
        val entries = zip.entries()
        for (file in files) {
            assertTrue(entries.hasMoreElements(), "Missing ${file.name}")
            val entry = entries.nextElement()
            assertEquals(file.name, entry.name)
            assertEquals(file.name.endsWith('/'), entry.isDirectory)
            val method = if (file.method == ZipMethod.STORED) ZipEntry.STORED else ZipEntry.DEFLATED
            assertEquals(method, entry.method, file.name)
            assertEquals(file.data.size.toLong(), entry.size, file.name)
            assertEquals(CRC32().also { it.update(file.data) }.value, entry.crc, file.name)
            if (method == ZipEntry.STORED) {
                assertEquals(entry.size, entry.compressedSize, file.name)
            } else {
                assertTrue(entry.compressedSize > 0, file.name)
            }
            zip.getInputStream(entry).use { stream ->
                // One extra byte detects excess data without an 8 KiB buffer for each tiny ZIP64 member.
                assertContentEquals(file.data, stream.readNBytes(file.data.size + 1), file.name)
            }
        }
        assertTrue(!entries.hasMoreElements(), "Unexpected central-directory entry")
    }

    private fun withJdkZip(bytes: ByteArray, block: (ZipFile) -> Unit) {
        val path = Files.createTempFile("archivekodec-interop", ".zip")
        try {
            Files.write(path, bytes)
            ZipFile(path.toFile()).use(block)
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
