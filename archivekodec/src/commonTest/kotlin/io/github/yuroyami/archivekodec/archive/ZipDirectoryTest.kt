package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.archive.zip.ZipFormat
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.codec.CodecId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ZipDirectoryTest {
    private class Directory(val prefix: ByteArray, val records: ByteArray) {
        fun encoded(count: Long, zip64: Boolean = false, sentinels: Boolean = true): ByteArray {
            val end = ByteArray(22)
            putLe(end, 0, ZipFormat.EOCD, 4)
            putLe(end, 8, if (zip64 && sentinels) 65535 else count, 2)
            putLe(end, 10, if (zip64 && sentinels) 65535 else count, 2)
            putLe(end, 12, if (zip64 && sentinels) 0xFFFFFFFF else records.size.toLong(), 4)
            putLe(end, 16, if (zip64 && sentinels) 0xFFFFFFFF else prefix.size.toLong(), 4)
            if (!zip64) return prefix + records + end
            val z64 = ByteArray(56)
            putLe(z64, 0, ZipFormat.Z64_EOCD, 4)
            putLe(z64, 4, 44, 8)
            putLe(z64, 12, 45, 2); putLe(z64, 14, 45, 2)
            putLe(z64, 24, count, 8); putLe(z64, 32, count, 8)
            putLe(z64, 40, records.size.toLong(), 8); putLe(z64, 48, prefix.size.toLong(), 8)
            val locator = ByteArray(20)
            putLe(locator, 0, ZipFormat.Z64_LOC, 4)
            putLe(locator, 8, (prefix.size + records.size).toLong(), 8)
            putLe(locator, 16, 1, 4)
            return prefix + records + z64 + locator + end
        }
    }

    private fun directory(count: Int = 1): Directory {
        val bytes = ZipWriter.write((0 until count).map { ZipWriter.FileSpec("entry$it", byteArrayOf(it.toByte()), CodecId.STORE) })
        val offset = ZipFormat.u32(bytes, bytes.size - 6).toInt()
        return Directory(bytes.copyOfRange(0, offset), bytes.copyOfRange(offset, bytes.size - 22))
    }

    private fun entries(bytes: ByteArray) = ZipReader(ByteArrayRandomAccessSource(bytes)).entries()

    @Test
    fun emptyAndCompleteClassicAndZip64DirectoriesRemainReadable() {
        for (count in listOf(0, 1, 2)) for (zip64 in listOf(false, true)) {
            val fixture = directory(count)
            val bytes = fixture.encoded(count.toLong(), zip64)
            val reader = ZipReader(ByteArrayRandomAccessSource(bytes))
            assertEquals((0 until count).map { "entry$it" }, reader.entries().map { it.name })
            for ((i, entry) in reader.entries().withIndex()) assertContentEquals(byteArrayOf(i.toByte()), reader.read(entry))
        }
    }

    @Test
    fun bothClassicCountMismatchDirectionsAreRejected() {
        val fixture = directory(2)
        for (count in listOf(0L, 1L, 3L)) {
            val error = assertFailsWith<ArchiveFormatException> { entries(fixture.encoded(count)) }
            assertTrue(error.message!!.contains("count"), error.toString())
            assertEquals(fixture.prefix.size.toLong(), error.offset)
        }
    }

    @Test
    fun zip64CountsMustMatchExactlyRatherThanModulo() {
        val fixture = directory()
        for (count in listOf(0L, 2L, 65537L)) {
            assertFailsWith<ArchiveFormatException> { entries(fixture.encoded(count, zip64 = true)) }
        }
    }

    @Test
    fun aPresentZip64LocatorIsAuthoritativeWithoutClassicSentinels() {
        val fixture = directory()
        val valid = fixture.encoded(1, zip64 = true, sentinels = false)
        putLe(valid, valid.size - 22 + 8, 0, 2); putLe(valid, valid.size - 22 + 10, 0, 2)
        assertEquals("entry0", entries(valid).single().name)
        val invalid = valid.copyOf()
        putLe(invalid, invalid.size - 98 + 32, 0, 8)
        assertFailsWith<ArchiveFormatException> { entries(invalid) }
    }

    @Test
    fun truncatedFixedRecordsNeverReturnPartialOrEmptyLists() {
        val fixture = directory()
        for (length in 1 until 46) for (count in listOf(0L, 1L)) {
            val bytes = Directory(fixture.prefix, fixture.records.copyOf(length)).encoded(count)
            assertFailsWith<ArchiveFormatException> { entries(bytes) }
        }
    }

    @Test
    fun truncatedNamesExtrasAndCommentsAreRejectedBeforeDecoding() {
        val fixture = directory()
        val extra = byteArrayOf(0x34, 0x12, 3, 0, 1, 2, 3)
        val comment = byteArrayOf(4, 5, 6)
        val full = fixture.records + extra + comment
        putLe(full, 30, extra.size.toLong(), 2); putLe(full, 32, comment.size.toLong(), 2)
        assertEquals("entry0", entries(Directory(fixture.prefix, full).encoded(1)).single().name)
        for (length in 46 until full.size) {
            val error = assertFailsWith<ArchiveFormatException> {
                entries(Directory(fixture.prefix, full.copyOf(length)).encoded(1))
            }
            assertEquals(fixture.prefix.size.toLong(), error.offset)
        }
    }

    @Test
    fun unexplainedTrailingBytesAndMalformedSignaturesAreRejected() {
        val fixture = directory()
        val signature = byteArrayOf(0x50, 0x4b, 5, 5, 2, 0, 7, 8)
        for (trailer in listOf(byteArrayOf(0), byteArrayOf(1, 2, 3, 4, 5, 6), signature.copyOf(7), signature + byteArrayOf(9), signature + signature)) {
            assertFailsWith<ArchiveFormatException> { entries(Directory(fixture.prefix, fixture.records + trailer).encoded(1)) }
        }
    }

    @Test
    fun oneCompleteDigitalSignatureRecordIsAllowed() {
        for (count in listOf(0, 1)) for (length in listOf(0, 4, 65535)) {
            val fixture = directory(count)
            val signature = ByteArray(6 + length)
            putLe(signature, 0, 0x05054b50, 4); putLe(signature, 4, length.toLong(), 2)
            assertEquals(count, entries(Directory(fixture.prefix, fixture.records + signature).encoded(count.toLong())).size)
        }
    }

    @Test
    fun directoryCannotOverlapClassicOrZip64EndRecords() {
        val fixture = directory()
        for (zip64 in listOf(false, true)) {
            for (extra in listOf(1L, 22L)) {
                val bytes = fixture.encoded(1, zip64)
                putLe(bytes, if (zip64) bytes.size - 98 + 40 else bytes.size - 22 + 12, fixture.records.size + extra, if (zip64) 8 else 4)
                val error = assertFailsWith<ArchiveFormatException> { entries(bytes) }
                assertEquals(fixture.prefix.size.toLong(), error.offset)
            }
        }
    }

    @Test
    fun zip64EndRecordLengthCannotRunIntoItsLocator() {
        val fixture = directory()
        for (length in listOf(43L, 45L, Long.MAX_VALUE, -1L)) {
            val bytes = fixture.encoded(1, zip64 = true)
            putLe(bytes, bytes.size - 98 + 4, length, 8)
            val error = assertFailsWith<ArchiveFormatException> { entries(bytes) }
            assertEquals((bytes.size - 98 + 4).toLong(), error.offset)
        }
    }
}
