package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.archive.ar.ArReader
import io.github.yuroyami.archivekodec.archive.cpio.CpioReader
import io.github.yuroyami.archivekodec.archive.tar.TarReader
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.util.readU32Le
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
    fun directPayloadReadersRejectLargeWholeArrayEntriesWithoutNarrowing() {
        val large = 3L * 1024 * 1024 * 1024
        val readers = listOf(
            ArReader(SparseSource(68 + large, 0L to arHeader(large))),
            TarReader(SparseSource(512 + large, 0L to tarHeader(large))),
            CpioReader(SparseSource(116 + large + 124, 0L to newc("large", large), 116 + large to newc("TRAILER!!!", 0))),
        )
        for (reader in readers) {
            val entry = reader.entries().single()
            assertEquals(large, entry.size)
            val e = assertFailsWith<ArchiveFormatException> { reader.read(entry, ArchiveLimits.UNBOUNDED) }
            kotlin.test.assertTrue(e.message!!.contains("ByteArray"), e.message)
        }
    }

    /** Serves only the listed header ranges; any payload read fails the test. */
    private class SparseSource(override val size: Long, vararg val pieces: Pair<Long, ByteArray>) : RandomAccessSource {
        override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
            val (start, bytes) = pieces.firstOrNull { (start, bytes) -> offset >= start && offset + length <= start + bytes.size }
                ?: error("large payload must never reach read")
            bytes.copyInto(into, intoOffset, (offset - start).toInt(), (offset - start).toInt() + length)
            return length
        }
    }

    private fun arHeader(size: Long): ByteArray =
        ("!<arch>\n" + "large/".padEnd(16) + "0".padEnd(12) + "0".padEnd(6) + "0".padEnd(6) +
            "644".padEnd(8) + size.toString().padEnd(10) + "`\n").encodeToByteArray()

    private fun tarHeader(size: Long): ByteArray = ByteArray(512).also { h ->
        fun put(offset: Int, text: String) = text.encodeToByteArray().copyInto(h, offset)
        put(0, "large"); put(100, "0000644"); put(124, size.toString(8).padStart(11, '0'))
        put(136, "00000000000"); h[156] = '0'.code.toByte(); put(257, "ustar"); put(263, "00")
        put(148, "        ")
        put(148, h.sumOf { it.toInt() and 0xFF }.toString(8).padStart(6, '0') + "\u0000 ")
    }

    private fun newc(name: String, size: Long): ByteArray {
        val fields = longArrayOf(1, 0x81A4, 0, 0, 1, 0, size, 0, 0, 0, 0, name.length + 1L, 0)
        val header = "070701" + fields.joinToString("") { it.toString(16).padStart(8, '0') } + name + "\u0000"
        return header.encodeToByteArray().copyOf((header.length + 3) / 4 * 4)
    }

    @Test
    fun zipDirectoryRejectsLargeWholeArrayPayloadWithoutNarrowing() {
        val large = 3L * 1024 * 1024 * 1024
        val bytes = zipMetadataBase()
        val originalCentral = readU32Le(bytes, bytes.size - 6).toInt()
        for (field in listOf(18, 22, originalCentral + 20, originalCentral + 24)) {
            putLe(bytes, field, large, 4)
        }
        val payloadStart = 35L
        val central = payloadStart + large
        putLe(bytes, bytes.size - 6, central, 4)
        val regions = listOf(0L to bytes.copyOf(35), central to bytes.copyOfRange(originalCentral, bytes.size))
        var armed = false
        var payloadReads = 0
        val source = object : RandomAccessSource {
            override val size = central + bytes.size - originalCentral
            override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
                check(length <= 65557) { "large payload must never reach read" }
                if (armed && offset < central && offset + length > payloadStart) payloadReads++
                into.fill(0, intoOffset, intoOffset + length)
                for ((start, data) in regions) {
                    val from = maxOf(start, offset)
                    val end = minOf(start + data.size, offset + length)
                    if (from < end) data.copyInto(into, intoOffset + (from - offset).toInt(), (from - start).toInt(), (end - start).toInt())
                }
                return length
            }
        }
        val reader = ZipReader(source)
        val entry = reader.entries().single()
        assertEquals(large, entry.compressedSize)
        armed = true
        val error = assertFailsWith<ArchiveFormatException> { reader.read(entry, ArchiveLimits.UNBOUNDED) }
        kotlin.test.assertTrue(error.message!!.contains("ByteArray"), error.message)
        assertEquals("a.txt", error.entryName)
        assertEquals(payloadStart, error.offset)
        assertEquals(0, payloadReads)
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
