package io.github.yuroyami.archivekodec.util

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveSizeException
import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.RandomAccessSource
import io.github.yuroyami.archivekodec.archive.zip.ZipMethod
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.codec.deflate.Deflate
import io.github.yuroyami.archivekodec.codec.deflate.Inflate
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.wrapper.Gzip
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class DecoderBufferTest {
    @Test
    fun transferringExactAndTrimmedBuffersDoesNotAliasLaterWritesOrSnapshots() {
        for (size in listOf(0, 7, 16, 17, 32)) {
            val expected = ByteArray(size) { it.toByte() }
            val out = ByteArrayBuilder(16)
            out.append(expected)
            val snapshot = out.toByteArray()
            val transferred = out.takeByteArray()
            assertContentEquals(expected, transferred)
            assertEquals(0, out.size)
            transferred.fill(99)
            assertContentEquals(expected, snapshot)
            out.append(expected)
            assertContentEquals(expected, out.takeByteArray())
            assertContentEquals(ByteArray(size) { 99 }, transferred)
        }
    }

    @Test
    fun frameGrowthBudgetsCanChangeWithoutEagerReservations() {
        val out = ByteArrayBuilder(16, 1000)
        out.limitGrowth(1000)
        assertEquals(16L, out.checksum { bytes, _ -> bytes.size.toLong() })
        out.append(ByteArray(17) { 65 })
        assertEquals(32L, out.checksum { bytes, _ -> bytes.size.toLong() })
        out.limitGrowth(20)
        out.copyBack(1, 3)
        assertFailsWith<ArchiveSizeException> { out.copyBack(1, 1) }
        out.limitGrowth(37)
        out.copyBack(17, 17)
        assertEquals(37L, out.checksum { bytes, _ -> bytes.size.toLong() })
        assertContentEquals(ByteArray(37) { 65 }, out.takeByteArray())
    }

    @Test
    fun nearbyHintsAvoidAGrowthAtTheLastByteWithoutReservingDistantSizes() {
        assertEquals(129, byteArrayCapacity(64, 64, 1, 1000, 129))
        assertEquals(128, byteArrayCapacity(64, 64, 1, 1000, 131))
        assertEquals(128, byteArrayCapacity(64, 64, 1, 128, 129))
        assertEquals(8192, byteArrayCapacity(4096, 4096, 1, Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(16_777_217, byteArrayCapacity(8_388_608, 8_388_608, 1, Int.MAX_VALUE, 16_777_217))
        val out = ByteArrayBuilder(64, 1000)
        out.limitGrowth(129, 129)
        out.append(ByteArray(65))
        assertEquals(129L, out.checksum { bytes, _ -> bytes.size.toLong() })
    }

    @Test
    fun overlappingMatchesRepeatTheirPatternAcrossCopyAndGrowthBoundaries() {
        for (distance in listOf(2, 3, 7, 15, 63, 64, 65, 32768, 65535)) {
            val prefix = ByteArray(distance + 3) { (it * 31 + it / 7).toByte() }
            for (length in listOf(4, 63, 64, 65, 127, 129, distance + 1, distance * 2 + 5)) {
                val out = ByteArrayBuilder(16)
                out.append(prefix)
                out.copyBack(distance, length)
                val repeated = ByteArray(length) { prefix[prefix.size - distance + it % distance] }
                assertContentEquals(prefix + repeated, out.takeByteArray(), "$distance/$length")
            }
        }
    }

    @Test
    fun inflateHintsDoNotConstrainOutputOrReplaceItsLimits() {
        val data = ByteArray(8193) { (it % 23).toByte() }
        val encoded = Deflate.encode(data)
        val source = byteArrayOf(7, 8) + encoded + byteArrayOf(9, 10)
        for (hint in listOf(null, 0L, 1L, 8192L, 8193L, 8194L, 0xFFFFFFFFL, Long.MAX_VALUE)) {
            val out = Inflate.Output(ArchiveLimits.DEFAULT, encoded.size, sizeHint = hint)
            assertEquals(source.size - 2, Inflate.inflateInto(source, 2, source.size - 2, out))
            val decoded = out.takeByteArray()
            assertContentEquals(data, decoded)
            out.copyStored(byteArrayOf(99), 0, 1)
            assertContentEquals(data, decoded)
            assertContentEquals(byteArrayOf(99), out.takeByteArray())
            for (limits in listOf(ArchiveLimits { maxUncompressedBytes = 8192 }, ArchiveLimits { maxCompressionRatio = 1 })) {
                val bounded = Inflate.Output(limits, encoded.size, sizeHint = hint)
                assertFailsWith<DecompressionBombException> { Inflate.inflateInto(encoded, 0, encoded.size, bounded) }
            }
        }
    }

    @Test
    fun authoritativeInflateSizeRemainsSeparateFromTheHint() {
        val data = ByteArray(8193) { 65 }
        val encoded = Deflate.encode(data)
        assertContentEquals(data, Inflate.inflateExact(encoded, ArchiveLimits.DEFAULT, data.size.toLong()))
        for (size in listOf(8192L, 8194L)) {
            assertFailsWith<ArchiveFormatException> { Inflate.inflateExact(encoded, ArchiveLimits.DEFAULT, size) }
        }
        val out = Inflate.Output(ArchiveLimits.DEFAULT, encoded.size, expectedSize = 8, sizeHint = Long.MAX_VALUE)
        assertFailsWith<ArchiveFormatException> { Inflate.inflateInto(encoded, 0, encoded.size, out) }
    }

    @Test
    fun gzipLastSizeIsOnlyAHintAcrossMembersAndPadding() {
        val large = ByteArray(8193) { (it % 31).toByte() }
        val small = byteArrayOf(42)
        for ((first, second) in listOf(large to small, small to large, large to byteArrayOf())) {
            for (padding in listOf(0, 1, 4, 17)) {
                val input = Gzip.compress(first) + ByteArray(padding) + Gzip.compress(second) + ByteArray(padding)
                assertContentEquals(first + second, Gzip.decompress(input))
            }
        }
        val corrupt = Gzip.compress(small).also { it.fill(0xFF.toByte(), it.size - 4) }
        assertFailsWith<ArchiveFormatException> { Gzip.decompress(corrupt, ArchiveLimits.UNBOUNDED) }
    }

    @Test
    fun storedZipReturnsItsReadBufferAndNeverAliasesTheSource() {
        val data = ByteArray(8193) { it.toByte() }
        val zip = ZipWriter.write(listOf(ZipWriter.FileSpec("data", data, ZipMethod.STORED)))
        val backing = ByteArrayRandomAccessSource(zip)
        var payloadBuffer: ByteArray? = null
        val source = object : RandomAccessSource {
            override val size = backing.size
            override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
                if (offset == 34L && into.size == data.size) payloadBuffer = into
                return backing.read(offset, into, intoOffset, minOf(length, 137))
            }
        }
        val reader = ZipReader(source)
        val entry = reader.entries().single()
        val result = reader.read(entry)
        assertSame(payloadBuffer, result)
        assertContentEquals(data, result)
        result.fill(0)
        assertContentEquals(data, reader.read(entry))
        reader.close()
    }
}
