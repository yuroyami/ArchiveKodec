package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveClosedException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.archive.ar.ArReader
import io.github.yuroyami.archivekodec.archive.ar.ArWriter
import io.github.yuroyami.archivekodec.archive.cpio.CpioReader
import io.github.yuroyami.archivekodec.archive.cpio.CpioWriter
import io.github.yuroyami.archivekodec.archive.cpio.InitramfsReader
import io.github.yuroyami.archivekodec.archive.tar.TarReader
import io.github.yuroyami.archivekodec.archive.tar.TarWriter
import io.github.yuroyami.archivekodec.archive.zip.ZipMethod
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Built-in readers only read entries they listed, so a stale or foreign position cannot be trusted. */
class IssuedEntryTest {
    private val data = "abc".encodeToByteArray()

    private class CountingSource(bytes: ByteArray) : RandomAccessSource {
        private val backing = ByteArrayRandomAccessSource(bytes)
        var reads = 0
        override val size: Long get() = backing.size
        override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
            reads++
            return backing.read(offset, into, intoOffset, length)
        }
    }

    private val archives: Map<String, ByteArray> = mapOf(
        "ZIP" to ZipWriter.write(listOf(ZipWriter.FileSpec("a.txt", data, ZipMethod.STORED))),
        "TAR" to TarWriter.write(listOf(TarWriter.FileSpec("a.txt", data))),
        "ar" to ArWriter.write(listOf(ArWriter.FileSpec("a.txt", data))),
        "cpio" to CpioWriter.write(listOf(CpioWriter.FileSpec("a.txt", data))),
    )

    private fun open(format: String, source: RandomAccessSource): ArchiveReader = when (format) {
        "ZIP" -> ZipReader(source)
        "TAR" -> TarReader(source)
        "ar" -> ArReader(source)
        else -> CpioReader(source)
    }

    private fun listed(format: String): ArchiveEntry =
        open(format, ByteArrayRandomAccessSource(archives.getValue(format))).use { it.entries().single() }

    @Test
    fun handBuiltEntriesAreRejectedWithTheirNameBeforePayloadReads() {
        for (format in archives.keys) {
            val source = CountingSource(archives.getValue(format))
            open(format, source).use { reader ->
                val listed = reader.entries().single()
                val reads = source.reads
                for (entry in listOf(ArchiveEntry("a.txt", 3L, false), ArchiveEntry("missing", 3L, false))) {
                    val error = assertFailsWith<ArchiveFormatException>(format) { reader.read(entry) }
                    assertEquals(entry.name, error.entryName, format)
                    assertNull(error.offset, format)
                    assertTrue(error.message!!.contains("'${entry.name}'"), error.message)
                }
                assertEquals(reads, source.reads, format)
                assertContentEquals(data, reader.read(listed))
            }
        }
    }

    @Test
    fun entriesFromAnotherReaderAreRejectedWhateverTheirFormat() {
        for (target in archives.keys) {
            open(target, ByteArrayRandomAccessSource(archives.getValue(target))).use { reader ->
                for (origin in archives.keys) {
                    val foreign = listed(origin)
                    val error = assertFailsWith<ArchiveFormatException>("$origin entry in $target") { reader.read(foreign) }
                    assertEquals("a.txt", error.entryName)
                    assertFailsWith<ArchiveFormatException>("$origin copy in $target") { reader.read(foreign.copy()) }
                }
            }
        }
    }

    @Test
    fun zipEntryGivenToTarReaderOverZeroesDoesNotReturnZeroes() {
        val zipEntry = listed("ZIP")
        TarReader(ByteArrayRandomAccessSource(ByteArray(1024))).use { reader ->
            assertEquals(emptyList(), reader.entries())
            assertFailsWith<ArchiveFormatException> { reader.read(zipEntry) }
        }
    }

    @Test
    fun unchangedCopiesAreReadAndChangedCopiesAreRejected() {
        for (format in archives.keys) {
            open(format, ByteArrayRandomAccessSource(archives.getValue(format))).use { reader ->
                val entry = reader.entries().single()
                assertContentEquals(data, reader.read(entry.copy()))
                for (changed in listOf(entry.copy(name = "b.txt"), entry.copy(size = 2), entry.copy(compressedSize = 9))) {
                    val error = assertFailsWith<ArchiveFormatException>(format) { reader.read(changed) }
                    assertEquals("a.txt", error.entryName, format)
                }
            }
        }
    }

    @Test
    fun closedStateIsReportedBeforeOwnership() {
        for (format in archives.keys) {
            val reader = open(format, ByteArrayRandomAccessSource(archives.getValue(format)))
            reader.close()
            assertFailsWith<ArchiveClosedException>(format) { reader.read(ArchiveEntry("a.txt", 3L, false)) }
        }
    }

    @Test
    fun equalMetadataAtDifferentPositionsReadsEachMember() {
        val second = "xyz".encodeToByteArray()
        val tar = TarWriter.write(listOf(TarWriter.FileSpec("same", data), TarWriter.FileSpec("same", second)))
        TarReader(ByteArrayRandomAccessSource(tar)).use { reader ->
            val (first, last) = reader.entries()
            assertEquals(first, last)
            assertContentEquals(data, reader.read(first))
            assertContentEquals(second, reader.read(last))
            assertContentEquals(second, reader.read(last.copy()))
        }
        val cpio = CpioWriter.write(listOf(CpioWriter.FileSpec("same", data), CpioWriter.FileSpec("same", second)))
        InitramfsReader(ByteArrayRandomAccessSource(cpio)).use { reader ->
            val (first, last) = reader.entries()
            assertEquals(first, last)
            assertContentEquals(data, reader.read(first))
            assertContentEquals(second, reader.read(last))
        }
    }
}
