package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.zip.ZipFormat
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.util.readU32Le
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ZipEndMetadataTest {
    private fun entries(bytes: ByteArray) = ZipReader(ByteArrayRandomAccessSource(bytes)).entries()
    private fun unsupported(bytes: ByteArray, offset: Int) {
        val error = assertFailsWith<ArchiveUnsupportedException> { entries(bytes) }
        assertTrue(error.message!!.contains("multi-disk"), error.toString())
        assertEquals(offset.toLong(), error.offset)
    }

    @Test
    fun validCommentLengthsRemainReadableForClassicAndZip64() {
        for (length in listOf(0, 1, 22, 65515, 65516, 65535)) {
            for (original in listOf(zipMetadataBase(), zip64WithComment(0))) {
                val bytes = zipWithComment(original, ByteArray(length) { 99 })
                val reader = ZipReader(ByteArrayRandomAccessSource(bytes))
                assertEquals("a.txt", reader.entries().single().name)
                assertEquals("ABC", reader.read(reader.entries().single()).decodeToString())
            }
        }
    }

    @Test
    fun falseEndRecordsInsideCommentsAreSkipped() {
        for (original in listOf(zipMetadataBase(), zip64WithComment(0))) {
            for (length in listOf(1L, 7L, 65535L)) {
                val comment = ByteArray(66)
                for (at in listOf(0, 44)) {
                    putLe(comment, at, ZipFormat.EOCD, 4)
                    putLe(comment, at + 20, length, 2)
                }
                assertEquals("a.txt", entries(zipWithComment(original, comment)).single().name)
            }
        }
    }

    @Test
    fun commentLengthMustEndAtEof() {
        val valid = zipWithComment(zipMetadataBase(), byteArrayOf(1, 2, 3))
        for (length in listOf(0L, 2L, 4L, 65535L)) {
            val bytes = valid.copyOf()
            putLe(bytes, bytes.size - 3 - 2, length, 2)
            assertFailsWith<ArchiveFormatException> { entries(bytes) }
        }
        assertFailsWith<ArchiveFormatException> { entries(zipMetadataBase() + byteArrayOf(1)) }
    }

    @Test
    fun classicDiskNumbersAreUnsupported() {
        for (field in listOf(4, 6)) for (disk in listOf(1L, 65534L, 65535L)) {
            val bytes = zipMetadataBase()
            val offset = bytes.size - 22 + field
            putLe(bytes, offset, disk, 2)
            unsupported(bytes, offset)
        }
    }

    @Test
    fun zip64ClassicDiskSentinelsResolveToDiskZero() {
        for (fields in listOf(listOf(4), listOf(6), listOf(4, 6))) {
            val bytes = zip64WithComment(0)
            for (field in fields) putLe(bytes, bytes.size - 22 + field, 65535, 2)
            assertEquals("a.txt", entries(bytes).single().name)
        }
    }

    @Test
    fun nonSentinelClassicDisksAreUnsupportedEvenWithZip64() {
        for (field in listOf(4, 6)) {
            val bytes = zip64WithComment(0)
            val offset = bytes.size - 22 + field
            putLe(bytes, offset, 1, 2)
            unsupported(bytes, offset)
        }
    }

    @Test
    fun zip64EndRecordDiskNumbersAreUnsupported() {
        for (field in listOf(16, 20)) for (disk in listOf(1L, 0xFFFFFFFFL)) {
            val bytes = zip64WithComment(0)
            val offset = bytes.size - 98 + field
            putLe(bytes, offset, disk, 4)
            unsupported(bytes, offset)
        }
    }

    @Test
    fun locatorMustDescribeOneDiskAndDiskZero() {
        for (field in listOf(4, 16)) for (value in listOf(2L, 0xFFFFFFFFL)) {
            val bytes = zip64WithComment(0)
            val offset = bytes.size - 42 + field
            putLe(bytes, offset, value, 4)
            unsupported(bytes, offset)
        }
        val zero = zip64WithComment(0)
        putLe(zero, zero.size - 42 + 16, 0, 4)
        assertFailsWith<ArchiveFormatException> { entries(zero) }
    }

    @Test
    fun authoritativePerDiskAndTotalCountsMustAgree() {
        for (zip64 in listOf(false, true)) for (count in listOf(0L, 2L)) {
            val bytes = if (zip64) zip64WithComment(0) else zipMetadataBase()
            val offset = if (zip64) bytes.size - 98 + 24 else bytes.size - 22 + 8
            putLe(bytes, offset, count, if (zip64) 8 else 2)
            val error = assertFailsWith<ArchiveFormatException> { entries(bytes) }
            assertTrue(error.message!!.contains("count"), error.toString())
            assertEquals(offset.toLong(), error.offset)
        }
    }

    @Test
    fun entryDiskNumbersAreResolvedBeforeDecidingSupport() {
        val ordinary = zipMetadataBase()
        val central = readU32Le(ordinary, ordinary.size - 6).toInt()
        putLe(ordinary, central + 34, 1, 2)
        val normal = assertFailsWith<ArchiveUnsupportedException> { entries(ordinary) }
        assertEquals("a.txt", normal.entryName)
        assertEquals((central + 34).toLong(), normal.offset)
        for (disk in listOf(1L, 0xFFFFFFFFL)) {
            val extended = zipEntryMetadata(8, zip64Extra(8, disk = disk))
            val error = assertFailsWith<ArchiveUnsupportedException> { entries(extended) }
            assertEquals("a.txt", error.entryName)
            assertEquals((central + 51 + 4).toLong(), error.offset)
        }
        assertEquals("a.txt", entries(zipEntryMetadata(8)).single().name)
    }
}
