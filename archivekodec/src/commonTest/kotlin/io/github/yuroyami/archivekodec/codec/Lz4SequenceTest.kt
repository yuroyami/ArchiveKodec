package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.checksum.XxHash32
import io.github.yuroyami.archivekodec.codec.lz4.Lz4
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Lz4SequenceTest {
    // lz4 1.10.0: -B4 -BI -c; sized variants add -BX --content-size.
    private val first = hex("04224d186440a70600008066697273742d00000000e9193015")
    private val second = hex("04224d186440a7060000807365636f6e6400000000dd5dd6ef")
    private val firstSized = hex("04224d187c400600000000000000790600008066697273742de919301500000000e9193015")
    private val secondSized = hex("04224d187c40060000000000000079060000807365636f6e64dd5dd6ef00000000dd5dd6ef")
    private val compressed = hex("04224d187c40e8030000000000003d21000000d768656c6c6f20776f726c642c200d00170a0c000f1900ffffffaf506f726c640a507ea66d000000008e799139")
    private val expanded = "hello world, hello world\n".repeat(40).encodeToByteArray()
    private val expected = "first-second".encodeToByteArray()
    private val tagged = hex("04224d18614098badcfed10600008066697273742d00000000")
    private val dictionary = hex("04224d186d40540000000000000098badcfee2140000000f3600234120616e6452000c710050616761696e00000000d2bb8d00")
    private val missingHistory = hex("04224d186440a7140000000f3600234120616e6452000c710050616761696e00000000d2bb8d00")

    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun le32(value: Long) = ByteArray(4) { (value ushr (8 * it)).toByte() }
    private fun skip(id: Int = 0, payload: ByteArray = byteArrayOf()) = le32(0x184D2A50L + id) + le32(payload.size.toLong()) + payload
    private fun header(size: ULong): ByteArray {
        val descriptor = byteArrayOf(0x68, 0x40) + ByteArray(8) { (size shr (it * 8)).toByte() }
        return hex("04224d18") + descriptor + (XxHash32.hash(descriptor) ushr 8).toByte()
    }
    private fun frame(data: ByteArray, stored: Boolean, size: ULong? = null): ByteArray {
        val prefix = if (size != null) header(size) else hex("04224d18604082")
        val block = if (stored) data else Lz4.blockCompress(data)
        return prefix + le32(block.size.toLong() or (if (stored) 0x80000000L else 0)) + block + le32(0)
    }

    @Test
    fun referenceConcatenationRestoresEveryFrameThroughBothEntryPoints() {
        assertContentEquals(expected, Lz4.frameDecompress(first + second))
        assertContentEquals(expected, ArchiveKodec.codec(CodecId.LZ4)!!.decompress(first + second))
    }

    @Test
    fun allSkippableMagicsWorkBeforeBetweenAndAfterFrames() {
        for (id in 0..15) {
            val metadata = skip(id, "abcd".encodeToByteArray())
            assertContentEquals(expected, Lz4.frameDecompress(metadata + first + metadata + second + metadata), "magic $id")
        }
    }

    @Test
    fun skippableOnlySequencesAndZeroLengthPayloadsProduceNoOutput() {
        for (id in 0..15) {
            assertContentEquals(byteArrayOf(), Lz4.frameDecompress(skip(id)))
            assertContentEquals(byteArrayOf(), Lz4.frameDecompress(skip(id, first) + skip(15 - id)))
        }
        assertFailsWith<ArchiveFormatException> { Lz4.frameDecompress(byteArrayOf()) }
    }

    @Test
    fun skippedPayloadIsNeverParsedAsCompressedData() {
        val payload = first + hex("02214c18ffffffff") + skip(15) + byteArrayOf(4, 0x22)
        assertContentEquals(expected, Lz4.frameDecompress(skip(0, payload) + first + skip(15, payload) + second))
    }

    @Test
    fun everyTruncatedSkippablePrefixIsRejected() {
        val metadata = skip(15, first)
        for (end in metadata.indices) {
            for (prefix in listOf(byteArrayOf(), first)) {
                // A complete prefix by itself is valid; a nonempty partial next frame is not.
                if (end == 0 && prefix.isNotEmpty()) continue
                assertFailsWith<ArchiveFormatException>("prefix ${prefix.size}, length $end") {
                    Lz4.frameDecompress(prefix + metadata.copyOf(end))
                }
            }
        }
    }

    @Test
    fun unsignedSkippableSizesAreBoundedBeforeConversionOrAllocation() {
        for (length in listOf(1L, Int.MAX_VALUE.toLong(), 0x80000000L, 0xFFFFFFFFL)) {
            for (prefix in listOf(byteArrayOf(), first)) {
                val error = assertFailsWith<ArchiveFormatException> { Lz4.frameDecompress(prefix + le32(0x184D2A50) + le32(length)) }
                assertEquals((prefix.size + 8).toLong(), error.offset)
                assertTrue(error.message!!.contains("skippable"), error.toString())
            }
        }
    }

    @Test
    fun unrecognizedAndPartialTrailingMagicsAreRejectedAtTheirStart() {
        for (trailing in listOf(byteArrayOf(0), byteArrayOf(4, 0x22), byteArrayOf(4, 0x22, 0x4d),
            le32(0), le32(0x184D2A4F), le32(0x184D2A60), hex("28b52ffd"), "garbage".encodeToByteArray())) {
            for (prefix in listOf(first, first + skip(0))) {
                val error = assertFailsWith<ArchiveFormatException> { Lz4.frameDecompress(prefix + trailing) }
                assertEquals(prefix.size.toLong(), error.offset)
            }
        }
    }

    @Test
    fun aTruncatedSecondFrameCannotReturnTheFirstFramesOutput() {
        for (end in 1 until second.size) {
            val error = assertFailsWith<ArchiveFormatException>("length $end") { Lz4.frameDecompress(first + second.copyOf(end)) }
            assertTrue(error.offset!! >= first.size, error.toString())
        }
    }

    @Test
    fun legacyMagicHasAnExplicitUnsupportedCategoryAtAnyBoundary() {
        for (prefix in listOf(byteArrayOf(), skip(15), first, first + skip(0))) {
            val error = assertFailsWith<ArchiveUnsupportedException> { Lz4.frameDecompress(prefix + hex("02214c18")) }
            assertEquals(prefix.size.toLong(), error.offset)
            assertTrue(error.message!!.contains("legacy"), error.toString())
        }
    }

    @Test
    fun contentSizesAndBothChecksumKindsAreLocalToEachFrame() {
        val empty = hex("04224d187440bd00000000055dcc02")
        assertContentEquals(expected, Lz4.frameDecompress(firstSized + empty + skip(3) + secondSized + empty))
        assertContentEquals(expanded + expected + expanded, Lz4.frameDecompress(compressed + firstSized + secondSized + compressed))
    }

    @Test
    fun laterDescriptorErrorsRetainAbsoluteSourceOffsets() {
        val prefix = first + skip(10, byteArrayOf(1, 2, 3))
        for ((field, mask) in listOf(4 to 0x80, 4 to 2, 5 to 1)) {
            val changed = second.copyOf().also { it[field] = (it[field].toInt() xor mask).toByte() }
            val error = assertFailsWith<ArchiveFormatException> { Lz4.frameDecompress(prefix + changed) }
            assertEquals((prefix.size + field).toLong(), error.offset)
        }
    }

    @Test
    fun laterHeaderChecksumFailureRetainsItsSourceOffset() {
        val changed = second.copyOf().also { it[6] = 0 }
        val error = assertFailsWith<ArchiveFormatException> { Lz4.frameDecompress(first + changed) }
        assertEquals((first.size + 6).toLong(), error.offset)
        assertTrue(error.message!!.contains("header checksum"))
    }

    @Test
    fun laterBlockChecksumIsCheckedBeforeCopyingUnderTheRemainingLimit() {
        val changed = secondSized.copyOf().also { it[23] = (it[23].toInt() xor 1).toByte() }
        // Remove the size declaration so the block checksum is the first failing guard.
        val noSize = hex("04224d187440bd") + changed.copyOfRange(15, changed.size)
        val error = assertFailsWith<ArchiveFormatException> {
            Lz4.frameDecompress(first + noSize, ArchiveLimits(maxUncompressedBytes = 6))
        }
        assertEquals((first.size + 17).toLong(), error.offset)
        assertTrue(error.message!!.contains("block checksum"))
    }

    @Test
    fun laterContentChecksumIsCheckedAgainstOnlyThatFramesOutput() {
        val changed = secondSized.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val error = assertFailsWith<ArchiveFormatException> { Lz4.frameDecompress(first + changed) }
        assertEquals((first.size + changed.size - 4).toLong(), error.offset)
        assertTrue(error.message!!.contains("content checksum"))
    }

    @Test
    fun aLaterDeclaredSizeMismatchReportsThatFramesSizeField() {
        for (stored in listOf(false, true)) for (size in listOf(5uL, 7uL)) {
            val error = assertFailsWith<ArchiveFormatException> {
                Lz4.frameDecompress(first + frame("second".encodeToByteArray(), stored, size))
            }
            assertEquals((first.size + 6).toLong(), error.offset)
            assertTrue(error.message!!.contains("content size mismatch"))
        }
    }

    @Test
    fun combinedOutputLimitCoversStoredAndCompressedFrames() {
        for (stored in listOf(false, true)) {
            val flow = frame("first-".encodeToByteArray(), stored) + skip(0) + frame("second".encodeToByteArray(), stored)
            assertFailsWith<DecompressionBombException> { Lz4.frameDecompress(flow, ArchiveLimits(maxUncompressedBytes = 11)) }
            assertContentEquals(expected, Lz4.frameDecompress(flow, ArchiveLimits(maxUncompressedBytes = 12)))
        }
        assertFailsWith<DecompressionBombException> { Lz4.frameDecompress(firstSized + secondSized, ArchiveLimits(maxUncompressedBytes = 11)) }
    }

    @Test
    fun laterDeclaredSizesAreCheckedAgainstTheRemainingBudgetBeforeBlocks() {
        for (size in listOf(7uL, ULong.MAX_VALUE)) {
            val error = assertFailsWith<DecompressionBombException> {
                Lz4.frameDecompress(first + header(size), ArchiveLimits(maxUncompressedBytes = 12))
            }
            assertEquals((first.size + 6).toLong(), error.offset)
        }
        val error = assertFailsWith<ArchiveFormatException> {
            Lz4.frameDecompress(first + header(Int.MAX_VALUE.toULong()), ArchiveLimits.UNBOUNDED)
        }
        assertEquals((first.size + 6).toLong(), error.offset)
        assertTrue(error.message!!.contains("ByteArray limit"))
    }

    @Test
    fun skippableMetadataAndAdjacentFramesCannotDiluteCompressionRatio() {
        val padding = skip(0, ByteArray(4096))
        val large = frame(ByteArray(4096), true)
        // With and without Content Size, enforce the same frame-local expansion limit.
        val noSize = hex("04224d187440bd") + compressed.copyOfRange(15, compressed.size)
        for (highRatio in listOf(compressed, noSize)) {
            for (flow in listOf(padding + highRatio, highRatio + padding, large + highRatio, highRatio + large)) {
                assertFailsWith<DecompressionBombException> { Lz4.frameDecompress(flow, ArchiveLimits(maxCompressionRatio = 2)) }
            }
        }
    }

    @Test
    fun theRatioBoundaryUsesTheCurrentFramesCompleteEncodedLength() {
        val ceiling = (expanded.size + compressed.size - 1) / compressed.size
        for (prefix in listOf(byteArrayOf(), first, skip(0, ByteArray(4096)))) {
            assertFailsWith<DecompressionBombException> {
                Lz4.frameDecompress(prefix + compressed, ArchiveLimits(maxCompressionRatio = ceiling - 1))
            }
            val outputPrefix = if (prefix.contentEquals(first)) "first-".encodeToByteArray() else byteArrayOf()
            assertContentEquals(outputPrefix + expanded, Lz4.frameDecompress(prefix + compressed, ArchiveLimits(maxCompressionRatio = ceiling)))
        }
    }

    @Test
    fun zeroOutputBudgetStillAllowsEmptyFramesAndSkippableData() {
        val empty = hex("04224d1860408200000000")
        val limits = ArchiveLimits(maxUncompressedBytes = 0, maxCompressionRatio = 0)
        assertContentEquals(byteArrayOf(), Lz4.frameDecompress(skip(0, first) + empty + skip(15) + empty, limits))
        assertFailsWith<DecompressionBombException> { Lz4.frameDecompress(empty + first, limits) }
    }

    @Test
    fun dictionaryMetadataAndBlockHistoryDoNotCrossFrameBoundaries() {
        val error = assertFailsWith<ArchiveFormatException> { Lz4.frameDecompress(tagged + missingHistory) }
        assertEquals((tagged.size + 12).toLong(), error.offset)
        assertTrue(error.message!!.contains("may be required"))
        val unsupported = assertFailsWith<ArchiveUnsupportedException> { Lz4.frameDecompress(first + dictionary) }
        assertEquals((first.size + 24).toLong(), unsupported.offset)
        assertTrue(unsupported.message!!.contains("dictionary ID 4275878552"))
    }

    @Test
    fun longSequencesAreIterativeAndKeepIndependentEmptyChecksums() {
        val part = skip(15) + hex("04224d187440bd00000000055dcc02")
        val many = ByteArray(part.size * 4096)
        repeat(4096) { part.copyInto(many, it * part.size) }
        assertContentEquals(expected, Lz4.frameDecompress(first + many + second))
    }
}
