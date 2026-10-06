package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.archive.ar.ArReader
import io.github.yuroyami.archivekodec.archive.cpio.CpioReader
import io.github.yuroyami.archivekodec.archive.tar.TarReader
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SourceBoundsTest {
    @Test
    fun rejectsInvalidRangesBeforeReading() {
        val source = object : RandomAccessSource {
            override val size = 6L
            override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int =
                error("invalid range must not reach read")
        }
        for ((offset, length) in listOf(-1L to 1, -4294967296L to 1, 7L to 0, 4L to 3, Long.MAX_VALUE to 1, 0L to Int.MAX_VALUE, 0L to -1)) {
            val e = assertFailsWith<ArchiveFormatException> { source.readFully(offset, length) }
            assertEquals(offset, e.offset)
        }
    }

    @Test
    fun byteArraySourceRejectsNegativeOffsetsAndInvalidSlices() {
        val source = ByteArrayRandomAccessSource(byteArrayOf(10, 11, 12))
        val into = ByteArray(3)
        for (offset in listOf(-1L, -4294967296L, Long.MIN_VALUE)) {
            assertFailsWith<ArchiveFormatException> { source.read(offset, into) }
        }
        for ((offset, length) in listOf(-1 to 1, 0 to -1, 2 to 2, Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { source.read(0, into, offset, length) }
        }
        assertEquals(-1, source.read(3, into))
        assertEquals(0, source.read(3, into, 0, 0))
        assertContentEquals(byteArrayOf(11, 12), source.readFully(1, 2))
        assertContentEquals(byteArrayOf(), source.readFully(3, 0))
    }

    @Test
    fun fullReadHandlesShortReadsAndReportsPrematureEof() {
        val source = object : RandomAccessSource {
            override val size = 3L
            override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
                into[intoOffset] = (10 + offset).toByte()
                return 1
            }
        }
        assertContentEquals(byteArrayOf(10, 11, 12), source.readFully(0, 3))
        val short = object : RandomAccessSource {
            override val size = 3L
            override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int = -1
        }
        assertEquals(1L, assertFailsWith<ArchiveFormatException> { short.readFully(1, 2) }.offset)
    }

    @Test
    fun everyReaderRejectsLargeWholeArrayEntriesWithoutNarrowing() {
        val large = 3L * 1024 * 1024 * 1024
        val source = object : RandomAccessSource {
            override val size = large + 100
            override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
                check(offset == 0L && length == 30) { "large payload must never reach read" }
                val header = ByteArray(30)
                putLe(header, 0, 0x04034B50, 4)
                header.copyInto(into, intoOffset)
                return length
            }
        }
        val entry = ArchiveEntry("large", large, false, compressedSize = large, dataOffset = 0)
        for (reader in listOf(ArReader(source), TarReader(source), CpioReader(source), ZipReader(source))) {
            val e = assertFailsWith<ArchiveFormatException> { reader.read(entry, ArchiveLimits.UNBOUNDED) }
            kotlin.test.assertTrue(e.message!!.contains("ByteArray"), e.message)
        }
    }

    @Test
    fun wholeArrayReadRejectsLargeRepresentableSourceRanges() {
        val source = object : RandomAccessSource {
            override val size = Long.MAX_VALUE
            override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int =
                error("unrepresentable read must not reach source")
        }
        assertFailsWith<ArchiveFormatException> { source.readFullyChecked(0, Int.MAX_VALUE.toLong() + 1) }
        assertFailsWith<ArchiveFormatException> { source.readFullyChecked(Long.MAX_VALUE - 1, 2) }
    }
}
