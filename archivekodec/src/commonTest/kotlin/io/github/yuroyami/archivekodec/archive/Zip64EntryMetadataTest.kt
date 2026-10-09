package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.archive.zip.ZipFormat
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.util.readU32Le
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Zip64EntryMetadataTest {
    private fun entries(bytes: ByteArray) = ZipReader(ByteArrayRandomAccessSource(bytes)).entries()
    private fun invalid(bytes: ByteArray): ArchiveFormatException {
        val error = assertFailsWith<ArchiveFormatException> { entries(bytes) }
        assertEquals("a.txt", error.entryName)
        assertTrue(error.message!!.contains("ZIP64") || error.message!!.contains("extra field"), error.toString())
        return error
    }

    @Test
    fun everyConditionalFieldCombinationResolvesInSpecificationOrder() {
        val unrelated = byteArrayOf(0x34, 0x12, 1, 0, 5)
        for (mask in 1..15) {
            val bytes = zipEntryMetadata(mask, unrelated + zip64Extra(mask) + unrelated)
            val reader = ZipReader(ByteArrayRandomAccessSource(bytes))
            val entry = reader.entries().single()
            assertEquals(3L, entry.size)
            assertEquals(3L, entry.compressedSize)
            assertEquals(0L, entry.dataOffset)
            assertEquals(0, entry.zipMethod)
            assertEquals("ABC", reader.read(entry).decodeToString())
        }
    }

    @Test
    fun missingZip64BlocksAreRejectedWithEntryContext() {
        for (mask in 1..15) {
            invalid(zipEntryMetadata(mask, byteArrayOf()))
            invalid(zipEntryMetadata(mask, byteArrayOf(0x34, 0x12, 1, 0, 5)))
        }
    }

    @Test
    fun truncatedExtraHeadersAndBodiesAreRejected() {
        for (length in 1..3) invalid(zipEntryMetadata(1, ByteArray(length) { 1 }))
        for (mask in 1..15) {
            val full = zip64Extra(mask)
            for (length in 4 until full.size) invalid(zipEntryMetadata(mask, full.copyOf(length)))
        }
    }

    @Test
    fun completeBlocksMustHaveAllRequiredValues() {
        for (mask in 1..15) {
            val full = zip64Extra(mask)
            for (length in 0 until full.size - 4) {
                val short = full.copyOf(length + 4)
                putLe(short, 2, length.toLong(), 2)
                invalid(zipEntryMetadata(mask, short))
            }
        }
    }

    @Test
    fun duplicateRequiredZip64BlocksAreRejected() {
        for (mask in 1..15) {
            val field = zip64Extra(mask)
            invalid(zipEntryMetadata(mask, field + field))
        }
    }

    @Test
    fun unsignedSizesAndOffsetsAboveSignedLongRangeAreRejectedAtTheField() {
        for (bit in listOf(1, 2, 4)) for (value in listOf(Long.MIN_VALUE, -1L)) {
            val extra = when (bit) {
                1 -> zip64Extra(bit, output = value)
                2 -> zip64Extra(bit, compressed = value)
                else -> zip64Extra(bit, offset = value)
            }
            val bytes = zipEntryMetadata(bit, extra)
            val central = readU32Le(bytes, bytes.size - 6).toInt()
            val error = invalid(bytes)
            assertEquals((central + 51 + 4).toLong(), error.offset)
            assertTrue(error.message!!.contains("Long"), error.toString())
        }
    }

    @Test
    fun aResolvedMaximum32BitValueIsNotAnUnresolvedSentinel() {
        for (value in listOf(0xFFFFFFFFL, Long.MAX_VALUE)) {
            val entry = entries(zipEntryMetadata(1, zip64Extra(1, output = value))).single()
            assertEquals(value, entry.size)
        }
    }

    @Test
    fun sentinelDirectorySizesAndOffsetsStillRequireALocator() {
        for (field in listOf(12, 16)) {
            val bytes = zipMetadataBase()
            putLe(bytes, bytes.size - 22 + field, 0xFFFFFFFF, 4)
            val error = assertFailsWith<ArchiveFormatException> { entries(bytes) }
            assertTrue(error.message!!.contains("missing ZIP64 locator"), error.toString())
        }
    }

    @Test
    fun resolvedOffsetsAndCompressedSizesRetainAll32BitsOnSparseSources() {
        val marker = 0xFFFFFFFFL
        val compact = zipEntryMetadata(6, zip64Extra(6, compressed = marker, offset = marker))
        val central = readU32Le(compact, compact.size - 6).toInt()
        val records = compact.copyOfRange(central, compact.size - 22)
        val directoryOffset = marker + 35 + marker
        val z64 = ByteArray(56)
        putLe(z64, 0, ZipFormat.Z64_EOCD, 4); putLe(z64, 4, 44, 8)
        putLe(z64, 12, 45, 2); putLe(z64, 14, 45, 2)
        putLe(z64, 24, 1, 8); putLe(z64, 32, 1, 8)
        putLe(z64, 40, records.size.toLong(), 8); putLe(z64, 48, directoryOffset, 8)
        val locator = ByteArray(20)
        putLe(locator, 0, ZipFormat.Z64_LOC, 4)
        putLe(locator, 8, directoryOffset + records.size, 8); putLe(locator, 16, 1, 4)
        val end = ByteArray(22)
        putLe(end, 0, ZipFormat.EOCD, 4)
        putLe(end, 8, 65535, 2); putLe(end, 10, 65535, 2)
        putLe(end, 12, marker, 4); putLe(end, 16, marker, 4)
        val tail = records + z64 + locator + end
        val regions = listOf(marker to compact.copyOf(35), directoryOffset to tail)
        val reads = mutableListOf<Pair<Long, Int>>()
        val source = object : RandomAccessSource {
            override val size = directoryOffset + tail.size
            override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
                if (offset >= size) return -1
                val count = minOf(length.toLong(), size - offset).toInt()
                reads.add(offset to count)
                into.fill(0, intoOffset, intoOffset + count)
                for ((start, bytes) in regions) {
                    val left = maxOf(offset, start)
                    val right = minOf(offset + count, start + bytes.size)
                    if (left < right) bytes.copyInto(into, intoOffset + (left - offset).toInt(), (left - start).toInt(), (right - start).toInt())
                }
                return count
            }
        }
        val entry = ZipReader(source).entries().single()
        assertEquals(marker, entry.compressedSize)
        assertEquals(marker, entry.dataOffset)
        assertEquals(3L, entry.size)
        assertTrue(reads.contains(marker to 30))
        assertTrue(reads.all { it.second <= 65557 }, reads.toString())
    }

}
