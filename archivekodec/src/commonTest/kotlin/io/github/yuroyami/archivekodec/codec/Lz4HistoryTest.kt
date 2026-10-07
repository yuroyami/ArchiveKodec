package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.checksum.XxHash32
import io.github.yuroyami.archivekodec.codec.lz4.Lz4
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Lz4HistoryTest {
    // lz4 1.10.0: -B4 -BD -BX --content-size; 70,000 A bytes in two linked blocks.
    private val reference = hex("04224d185c407011010000000000e30b0100001f410100ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffe750414141414159405f321b0000000f0100ffffffffffffffffffffffffffffffffff69504141414141072f90270000000048aab257")
    private val seed = "abc".encodeToByteArray()
    private val tail = "12345".encodeToByteArray()
    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun le32(value: Long) = ByteArray(4) { (value ushr (8 * it)).toByte() }

    private fun match(offset: Int, count: Int = 19, literals: ByteArray = byteArrayOf(), last: ByteArray = tail): ByteArray {
        val out = ByteArrayBuilder()
        fun extension(length: Int) {
            if (length < 15) return
            var remainder = length - 15
            while (remainder >= 255) { out.append(255.toByte()); remainder -= 255 }
            out.append(remainder.toByte())
        }
        out.append(((minOf(literals.size, 15) shl 4) or minOf(count - 4, 15)).toByte())
        extension(literals.size)
        out.append(literals)
        out.append(offset.toByte()); out.append((offset ushr 8).toByte())
        extension(count - 4)
        out.append((minOf(last.size, 15) shl 4).toByte())
        extension(last.size)
        out.append(last)
        return out.toByteArray()
    }

    private fun expanded(history: ByteArray, offset: Int, count: Int = 19, literals: ByteArray = byteArrayOf(), last: ByteArray = tail): ByteArray {
        val prefix = history + literals
        val start = prefix.size - offset
        return literals + ByteArray(count) { prefix[start + it % offset] } + last
    }

    private fun frame(
        blocks: List<Pair<Boolean, ByteArray>>, independent: Boolean = false,
        size: ULong? = null, dictionaryId: Long? = null, bd: Int = 0x40,
        blockChecksums: Boolean = false, content: ByteArray? = null,
    ): ByteArray {
        val flags = 0x40 or (if (independent) 0x20 else 0) or
            (if (size != null) 8 else 0) or (if (dictionaryId != null) 1 else 0) or
            (if (blockChecksums) 16 else 0) or (if (content != null) 4 else 0)
        val descriptor = byteArrayOf(flags.toByte(), bd.toByte()) +
            (if (size != null) ByteArray(8) { (size shr (8 * it)).toByte() } else byteArrayOf()) +
            (if (dictionaryId != null) le32(dictionaryId) else byteArrayOf())
        val out = ByteArrayBuilder()
        out.append(hex("04224d18")); out.append(descriptor)
        out.append((XxHash32.hash(descriptor) ushr 8).toByte())
        for ((stored, block) in blocks) {
            out.append(le32(block.size.toLong() or (if (stored) 0x80000000L else 0)))
            out.append(block)
            if (blockChecksums) out.append(le32(XxHash32.hash(block)))
        }
        out.append(le32(0))
        if (content != null) out.append(le32(XxHash32.hash(content)))
        return out.toByteArray()
    }

    @Test
    fun genuineLinkedFrameRestoresThroughBothPublicEntryPoints() {
        val expected = ByteArray(70000) { 65 }
        assertContentEquals(expected, Lz4.frameDecompress(reference))
        assertContentEquals(expected, ArchiveKodec.codec(CodecId.LZ4)!!.decompress(reference))
    }

    @Test
    fun storedHistorySupportsEveryDistanceIncludingTheMaximumOffset() {
        for (distance in listOf(1, 2, 3, 255, 32768, 65535)) {
            val prefix = ByteArray(distance) { ((it * 37 + it / 251) and 255).toByte() }
            val expected = prefix + expanded(prefix, distance, 37)
            assertContentEquals(expected, Lz4.frameDecompress(frame(listOf(true to prefix, false to match(distance, 37)))))
        }
    }

    @Test
    fun matchesCrossFromHistoryThroughCurrentLiteralsIntoOverlappingOutput() {
        val prefix = "abcde".encodeToByteArray()
        val literals = "12".encodeToByteArray()
        val expected = prefix + expanded(prefix, 7, 100, literals)
        assertContentEquals(expected, Lz4.frameDecompress(frame(listOf(true to prefix, false to match(7, 100, literals)))))
    }

    @Test
    fun historySpansMultipleStoredAndCompressedBlocks() {
        val literal = "defg".encodeToByteArray()
        val prefix = seed + literal
        val third = expanded(prefix, 7, 27)
        val expected = prefix + third + expanded(prefix + third, 10, 30)
        val blocks = listOf(true to seed, false to Lz4.blockCompress(literal), false to match(7, 27), false to match(10, 30))
        assertContentEquals(expected, Lz4.frameDecompress(frame(blocks)))
    }

    @Test
    fun theWindowUsesTheTailOfLargeStoredBlocksAfterCompressedOutput() {
        val first = expanded(seed, 3, 100)
        for (maximum in 4..7) {
            val stored = ByteArray(if (maximum == 4) 65536 else 70000) { ((it / 127 + it * 17) and 255).toByte() }
            val prefix = seed + first + stored
            val expected = prefix + expanded(prefix, 65535, 31)
            val blocks = listOf(true to seed, false to match(3, 100), true to stored, false to match(65535, 31))
            assertContentEquals(expected, Lz4.frameDecompress(frame(blocks, bd = maximum shl 4)))
        }
    }

    @Test
    fun emptyBlocksPreserveHistoryUnderAllChecksumCombinations() {
        val expected = seed + expanded(seed, 3, 50)
        val blocks = listOf(true to seed, true to byteArrayOf(), false to byteArrayOf(0), true to byteArrayOf(), false to match(3, 50))
        for (blockChecksums in listOf(false, true)) for (contentChecksum in listOf(false, true)) {
            assertContentEquals(expected, Lz4.frameDecompress(frame(blocks, size = expected.size.toULong(),
                blockChecksums = blockChecksums, content = if (contentChecksum) expected else null)))
        }
    }

    @Test
    fun independentBlocksRejectReferencesIntoEarlierBlocks() {
        val error = assertFailsWith<ArchiveFormatException> {
            Lz4.frameDecompress(frame(listOf(true to seed, false to match(3)), independent = true))
        }
        assertEquals(19L, error.offset)
        assertTrue(error.message!!.contains("available history 0"))
    }

    @Test
    fun standaloneBlockSlicesCannotUseBytesBeforeTheSliceAsHistory() {
        val encoded = match(3)
        val source = seed + encoded + seed
        val error = assertFailsWith<ArchiveFormatException> { Lz4.blockDecompress(source, seed.size, seed.size + encoded.size) }
        assertEquals(4L, error.offset)
    }

    @Test
    fun historyResetsAcrossFramesAndSkippableMetadata() {
        val first = frame(listOf(true to seed, false to match(3)))
        val skipped = hex("5f2a4d1803000000616263")
        for (between in listOf(byteArrayOf(), skipped)) {
            val second = frame(listOf(false to match(1)))
            val error = assertFailsWith<ArchiveFormatException> { Lz4.frameDecompress(first + between + second) }
            assertEquals((first.size + between.size + 12).toLong(), error.offset)
            assertTrue(error.message!!.contains("available history 0"))
        }
        assertContentEquals(ByteArray(140000) { 65 }, Lz4.frameDecompress(reference + skipped + reference))
    }

    @Test
    fun taggedLinkedFramesCanUseTheirOwnHistoryWithoutExternalDictionaryBytes() {
        val expected = seed + expanded(seed, 3, 27)
        assertContentEquals(expected, Lz4.frameDecompress(frame(listOf(true to seed, false to match(3, 27)),
            dictionaryId = 0xFEDCBA98, size = expected.size.toULong(), blockChecksums = true, content = expected)))
        val error = assertFailsWith<ArchiveUnsupportedException> {
            Lz4.frameDecompress(frame(listOf(false to match(1)), dictionaryId = 0xFEDCBA98))
        }
        assertEquals(16L, error.offset)
        assertTrue(error.message!!.contains("dictionary ID 4275878552"))
    }

    @Test
    fun referencesBeyondAvailableFrameHistoryKeepTheirTypedDiagnostics() {
        for (id in listOf(null, 0xFEDCBA98L)) {
            val bytes = frame(listOf(true to seed, false to match(4)), dictionaryId = id)
            if (id == null) {
                val error = assertFailsWith<ArchiveFormatException> { Lz4.frameDecompress(bytes) }
                assertEquals(19L, error.offset)
                assertTrue(error.message!!.contains("available history 3"))
            } else {
                val error = assertFailsWith<ArchiveUnsupportedException> { Lz4.frameDecompress(bytes) }
                assertEquals(23L, error.offset)
                assertTrue(error.message!!.contains("dictionary ID 4275878552"))
            }
        }
    }

    @Test
    fun zeroOffsetsRemainMalformedWithAvailableHistoryAndDictionaryMetadata() {
        for (id in listOf(null, 0xFEDCBA98L)) {
            val error = assertFailsWith<ArchiveFormatException> {
                Lz4.frameDecompress(frame(listOf(true to seed, false to match(0)), dictionaryId = id))
            }
            assertEquals(if (id == null) 19L else 23L, error.offset)
            assertTrue(error.message!!.contains("invalid match offset 0"))
        }
    }

    @Test
    fun outputLimitsCountOnlyNewBytesAndApplyBeforeHistoryBasedGrowth() {
        val expected = seed + expanded(seed, 3, 100)
        val bytes = frame(listOf(true to seed, false to match(3, 100)))
        assertFailsWith<DecompressionBombException> { Lz4.frameDecompress(bytes, ArchiveLimits { maxUncompressedBytes = expected.size - 1L }) }
        assertContentEquals(expected, Lz4.frameDecompress(bytes, ArchiveLimits { maxUncompressedBytes = expected.size.toLong() }))
    }

    @Test
    fun declaredSizeCountsLinkedOutputExactlyWithoutCountingHistoryTwice() {
        val expected = seed + expanded(seed, 3, 100)
        val blocks = listOf(true to seed, false to match(3, 100))
        assertContentEquals(expected, Lz4.frameDecompress(frame(blocks, size = expected.size.toULong())))
        for (size in listOf(expected.size - 1, expected.size + 1)) {
            val error = assertFailsWith<ArchiveFormatException> { Lz4.frameDecompress(frame(blocks, size = size.toULong())) }
            assertEquals(6L, error.offset)
            assertTrue(error.message!!.contains("content size mismatch"))
        }
    }

    @Test
    fun ratioLimitsApplyToMatchesUsingFrameHistory() {
        val expected = seed + expanded(seed, 3, 4000)
        val bytes = frame(listOf(true to seed, false to match(3, 4000)))
        assertFailsWith<DecompressionBombException> { Lz4.frameDecompress(bytes, ArchiveLimits { maxCompressionRatio = 2 }) }
        val ceiling = (expected.size + bytes.size - 1) / bytes.size
        assertContentEquals(expected, Lz4.frameDecompress(bytes, ArchiveLimits { maxCompressionRatio = ceiling }))
    }

    @Test
    fun decodedBlockMaximumAppliesToNewOutputWithoutCountingItsHistory() {
        val prefix = ByteArray(65536) { 65 }
        val blocks = listOf(true to prefix, false to match(1, 65531))
        assertContentEquals(prefix + expanded(prefix, 1, 65531), Lz4.frameDecompress(frame(blocks)))
        val error = assertFailsWith<ArchiveFormatException> {
            Lz4.frameDecompress(frame(listOf(true to prefix, false to match(1, 65532))))
        }
        assertTrue(error.message!!.contains("exceeds maximum 65536"))
    }

    @Test
    fun malformedTerminalSequencesCannotHideBehindValidHistory() {
        for (count in 0..4) {
            val error = assertFailsWith<ArchiveFormatException> {
                Lz4.frameDecompress(frame(listOf(true to seed, false to match(3, last = ByteArray(count)))))
            }
            assertEquals(22L, error.offset)
            assertTrue(error.message!!.contains("final literal"))
        }
    }

    @Test
    fun historySurvivesWindowRolloverAndOutputBufferGrowth() {
        var expected = ByteArray(32768) { ((it * 37 + it / 251) and 255).toByte() }
        val blocks = mutableListOf(true to expected)
        repeat(40) { index ->
            val wanted = listOf(1, 3, 31, 4096, 32767, 65535)[index % 6]
            val distance = minOf(wanted, expected.size)
            val last = ByteArray(5) { (index + it).toByte() }
            val next = expanded(expected, distance, 4091, last = last)
            blocks.add(false to match(distance, 4091, last = last))
            expected += next
        }
        assertContentEquals(expected, Lz4.frameDecompress(frame(blocks, size = expected.size.toULong(), blockChecksums = true, content = expected)))
    }

    @Test
    fun laterLinkedBlockAndContentChecksumFailuresKeepTheirSourceOffsets() {
        for ((at, field) in listOf(321 to "block", 329 to "content")) {
            val changed = reference.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }
            val error = assertFailsWith<ArchiveFormatException> { Lz4.frameDecompress(changed) }
            assertEquals(at.toLong(), error.offset)
            assertTrue(error.message!!.contains("$field checksum mismatch"))
        }
    }
}
