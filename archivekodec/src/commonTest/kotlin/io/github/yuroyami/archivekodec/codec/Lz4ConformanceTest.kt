package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.checksum.XxHash32
import io.github.yuroyami.archivekodec.codec.lz4.Lz4
import io.github.yuroyami.archivekodec.hex
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Lz4ConformanceTest {
    private val hello = "hello".encodeToByteArray()
    // liblz4 1.10.0 accepts the complete block and rejects the match-ending prefix.
    private val complete = hex("8061626364656667680800503132333435")
    private val cut = hex("8061626364656667680800")
    private val expanded = "abcdefghabcd12345".encodeToByteArray()

    private fun frame(
        size: ULong? = null, blocks: List<Pair<Boolean, ByteArray>> = emptyList(),
        blockChecksum: Boolean = false, contentChecksum: ByteArray? = null,
    ): ByteArray {
        val flags = 0x60 or (if (size != null) 8 else 0) or
            (if (blockChecksum) 16 else 0) or (if (contentChecksum != null) 4 else 0)
        val descriptor = byteArrayOf(flags.toByte(), 0x40) +
            (if (size != null) ByteArray(8) { (size shr (it * 8)).toByte() } else byteArrayOf())
        var bytes = hex("04224d18") + descriptor + (XxHash32.hash(descriptor).toInt() ushr 8).toByte()
        for ((stored, block) in blocks) {
            bytes += le32(block.size.toLong() or (if (stored) 0x80000000L else 0L)) + block
            if (blockChecksum) bytes += le32(XxHash32.hash(block))
        }
        bytes += le32(0)
        if (contentChecksum != null) bytes += le32(XxHash32.hash(contentChecksum))
        return bytes
    }

    private fun sizeMismatch(bytes: ByteArray) {
        val error = assertFailsWith<ArchiveFormatException> { Lz4.frameDecompress(bytes) }
        assertEquals(6L, error.offset)
        assertTrue(error.message!!.contains("content size mismatch"), error.toString())
    }

    @Test
    fun originalFramesRequireExactDeclaredSize() {
        assertContentEquals(hello, Lz4.frameDecompress(hex("04224d186840050000000000000061060000005068656c6c6f00000000")))
        sizeMismatch(hex("04224d186840640000000000000023060000005068656c6c6f00000000"))
        sizeMismatch(hex("04224d1868400200000000000000a0060000005068656c6c6f00000000"))
    }

    @Test
    fun zeroSizeAndAbsentSizeHaveDifferentMeanings() {
        for (blocks in listOf(emptyList(), listOf(true to byteArrayOf()), listOf(false to byteArrayOf(0)))) {
            assertContentEquals(byteArrayOf(), Lz4.frameDecompress(frame(0uL, blocks)))
        }
        for (stored in listOf(false, true)) {
            val block = if (stored) hello else Lz4.blockCompress(hello)
            assertContentEquals(hello, Lz4.frameDecompress(frame(blocks = listOf(stored to block))))
            sizeMismatch(frame(0uL, listOf(stored to block)))
        }
    }

    @Test
    fun declaredSizeCountsEveryStoredAndCompressedBlock() {
        for (firstStored in listOf(false, true)) for (secondStored in listOf(false, true)) {
            val blocks = listOf(firstStored to (if (firstStored) hello else Lz4.blockCompress(hello)),
                secondStored to (if (secondStored) hello else Lz4.blockCompress(hello)))
            for (checksummed in listOf(false, true)) {
                val checksum = if (checksummed) hello + hello else null
                assertContentEquals(hello + hello, Lz4.frameDecompress(frame(10uL, blocks, checksummed, checksum)))
                sizeMismatch(frame(9uL, blocks, checksummed, checksum))
                sizeMismatch(frame(11uL, blocks, checksummed, checksum))
            }
        }
    }

    @Test
    fun overrunIsRejectedBeforeLaterBlocksAreRead() {
        for (stored in listOf(false, true)) {
            val first = if (stored) hello else Lz4.blockCompress(hello)
            sizeMismatch(frame(2uL, listOf(stored to first, false to byteArrayOf(0x30))))
        }
    }

    @Test
    fun declaredSizeAppliesOutputAndRatioLimitsBeforeDecode() {
        val headerOnly = frame(100uL).copyOf(15)
        assertFailsWith<DecompressionBombException> {
            Lz4.frameDecompress(headerOnly, ArchiveLimits { maxUncompressedBytes = 99 })
        }
        assertFailsWith<DecompressionBombException> {
            Lz4.frameDecompress(headerOnly, ArchiveLimits { maxCompressionRatio = 1 })
        }
    }

    @Test
    fun unsignedSizeCannotWrapIntoAnAcceptedNegativeValue() {
        for (size in listOf(Long.MAX_VALUE.toULong() + 1uL, ULong.MAX_VALUE)) {
            val headerOnly = frame(size).copyOf(15)
            for (limits in listOf(ArchiveLimits.DEFAULT, ArchiveLimits.UNBOUNDED)) {
                val error = assertFailsWith<DecompressionBombException> { Lz4.frameDecompress(headerOnly, limits) }
                assertEquals(6L, error.offset)
            }
        }
    }

    @Test
    fun wholeArrayCapacityIsCheckedBeforeReadingBlocks() {
        for (size in listOf(Int.MAX_VALUE.toULong() + 1uL, UInt.MAX_VALUE.toULong())) {
            val error = assertFailsWith<ArchiveFormatException> {
                Lz4.frameDecompress(frame(size).copyOf(15), ArchiveLimits.UNBOUNDED)
            }
            assertEquals(6L, error.offset)
            assertTrue(error.message!!.contains("ByteArray limit"), error.toString())
        }
    }

    @Test
    fun descriptorChecksumIsVerifiedBeforeTrustingDeclaredSize() {
        val bytes = frame(ULong.MAX_VALUE)
        bytes[14] = (bytes[14].toInt() xor 1).toByte()
        val error = assertFailsWith<ArchiveFormatException> {
            Lz4.frameDecompress(bytes, ArchiveLimits { maxUncompressedBytes = 0 })
        }
        assertEquals(14L, error.offset)
        assertTrue(error.message!!.contains("header checksum mismatch"))
    }

    @Test
    fun blocksRequireACompleteFinalLiteralSequence() {
        val malformed = listOf(byteArrayOf(), cut) + (0..4).map { count ->
            cut + (count shl 4).toByte() + ByteArray(count) { (49 + it).toByte() }
        }
        for (block in malformed) {
            val error = assertFailsWith<ArchiveFormatException> { Lz4.blockDecompress(block, 0, block.size) }
            assertTrue(error.message!!.contains("final literal"), error.toString())
            val expectedOffset = if (block.size <= cut.size) block.size else cut.size
            assertEquals(expectedOffset.toLong(), error.offset)
        }
    }

    @Test
    fun literalOnlyBlocksIncludingZeroTokenAreValid() {
        assertContentEquals(byteArrayOf(), Lz4.blockDecompress(byteArrayOf(0), 0, 1))
        for (length in 0..14) {
            val input = ByteArray(length) { (it + 65).toByte() }
            val block = Lz4.blockCompress(input)
            assertContentEquals(input, Lz4.blockDecompress(block, 0, block.size))
        }
    }

    @Test
    fun completeReferenceBlockHonorsItsSlice() {
        assertContentEquals(expanded, Lz4.blockDecompress(complete, 0, complete.size))
        val source = byteArrayOf(1, 2, 3, 4) + complete + byteArrayOf(0x30)
        assertContentEquals(expanded, Lz4.blockDecompress(source, 4, 4 + complete.size))
        val malformed = byteArrayOf(1, 2, 3, 4) + cut + complete
        val error = assertFailsWith<ArchiveFormatException> { Lz4.blockDecompress(malformed, 4, 4 + cut.size) }
        assertEquals((4 + cut.size).toLong(), error.offset)
    }

    @Test
    fun frameBlocksRequireFinalLiteralsEvenWithValidChecksums() {
        for (block in listOf(cut, cut + byteArrayOf(0), cut + byteArrayOf(0x40, 49, 50, 51, 52))) {
            for (checksummed in listOf(false, true)) {
                val prefix = "abcdefghabcd".encodeToByteArray()
                val bytes = frame(blocks = listOf(false to block), blockChecksum = checksummed,
                    contentChecksum = if (checksummed) prefix else null)
                val error = assertFailsWith<ArchiveFormatException> { Lz4.frameDecompress(bytes) }
                assertTrue(error.message!!.contains("final literal"), error.toString())
                assertEquals((11 + cut.size).toLong(), error.offset)
            }
        }
    }

    private fun le32(value: Long) = ByteArray(4) { (value ushr (8 * it)).toByte() }
}
