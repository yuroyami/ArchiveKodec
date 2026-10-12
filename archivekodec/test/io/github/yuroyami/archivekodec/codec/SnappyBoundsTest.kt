package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.codec.snappy.Snappy
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SnappyBoundsTest {
    private fun varint(value: Long): ByteArray {
        var v = value
        val bytes = ArrayList<Byte>()
        do {
            var b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) b = b or 0x80
            bytes.add(b.toByte())
        } while (v != 0L)
        return bytes.toByteArray()
    }

    @Test
    fun truncatedAndIllegalStreamsReportFormatErrors() {
        val examples = listOf(
            byteArrayOf(), byteArrayOf(0x80.toByte()),
            byteArrayOf(3, 0, 65), byteArrayOf(4, 1, 0),
            ByteArray(11) { if (it < 10) 0x80.toByte() else 0 },
            byteArrayOf(-1, -1, -1, -1, 0x10), // preamble above u32
            byteArrayOf(1, 4, 1, 2, 3), byteArrayOf(8, 0, 65, 1),
            byteArrayOf(1, 0xF0.toByte()), byteArrayOf(1, 0xF4.toByte(), 0),
            byteArrayOf(1, 0xF8.toByte(), 0, 0), byteArrayOf(1, 0xFC.toByte(), 0, 0, 0),
            byteArrayOf(1, 0xFC.toByte(), -1, -1, -1, -1), // 2^32 literal length
            byteArrayOf(5, 0, 65, 1, 2), // offset before the produced prefix
            byteArrayOf(5, 0, 65, 14, 1), // incomplete copy-2 offset
            byteArrayOf(5, 0, 65, 15, 1, 0, 0), // incomplete copy-4 offset
            byteArrayOf(5, 0, 65, 15, -1, -1, -1, -1), // unsigned copy offset
            byteArrayOf(0, 0, 65), // output exceeds preamble
            byteArrayOf(1, 0, 65, 0, 66), // trailing tag exceeds completed output
        )
        for (bytes in examples) {
            val error = assertFailsWith<ArchiveFormatException> { Snappy.decompress(bytes) }
            assertTrue(error.offset != null)
        }
    }

    @Test
    fun everyCopyFormSupportsOverlappingMatches() {
        val copies = listOf(byteArrayOf(1, 1), byteArrayOf(14, 1, 0), byteArrayOf(15, 1, 0, 0, 0))
        for (copy in copies) {
            assertContentEquals(ByteArray(5) { 65 }, Snappy.decompress(byteArrayOf(5, 0, 65) + copy))
        }
        assertEquals("xababab", Snappy.decompress(byteArrayOf(7, 8, 120, 97, 98, 14, 2, 0)).decodeToString())
    }

    @Test
    fun oneByteCopyOffsetsRetainAllElevenBitsAtEveryLength() {
        // All 128 streams were independently decoded by libsnappy 1.2.2.
        val offsets = listOf(1, 255, 256, 511, 512, 767, 768, 1023, 1024, 1279, 1280, 1535, 1536, 1791, 1792, 2047)
        for (offset in offsets) {
            val prefix = ByteArray(offset) { ((it * 13 xor (it ushr 8)) + 17).toByte() }
            val literal = when {
                offset <= 60 -> byteArrayOf(((offset - 1) shl 2).toByte())
                offset <= 256 -> byteArrayOf(0xF0.toByte(), (offset - 1).toByte())
                else -> byteArrayOf(0xF4.toByte(), (offset - 1).toByte(), ((offset - 1) ushr 8).toByte())
            }
            for (length in 4..11) {
                val tag = ((offset ushr 8) shl 5) or ((length - 4) shl 2) or 1
                val bytes = varint((offset + length).toLong()) + literal + prefix + byteArrayOf(tag.toByte(), offset.toByte())
                val expected = prefix + ByteArray(length) { prefix[it % offset] }
                assertContentEquals(expected, Snappy.decompress(bytes), "offset $offset, length $length")
            }
        }
    }

    @Test
    fun fourByteCopyOffsetsAreNotRestrictedToTheEncoderWindow() {
        val prefix = ByteArray(65_536) { (it % 251).toByte() }
        val bytes = varint(65_537) + byteArrayOf(0xF4.toByte(), -1, -1) + prefix + byteArrayOf(3, 0, 0, 1, 0)
        assertContentEquals(prefix + prefix[0], Snappy.decompress(bytes))
    }

    @Test
    fun outputAndRatioLimitsApplyBeforeMaterialization() {
        for (size in listOf(2_147_483_648L, 2_147_483_649L, 4_294_967_295L)) {
            assertFailsWith<DecompressionBombException> { Snappy.decompress(varint(size)) }
            assertFailsWith<DecompressionBombException> { Snappy.decompress(varint(size), ArchiveLimits.UNBOUNDED) }
        }
        val repeated = byteArrayOf(65, 0, 65, 0xFE.toByte(), 1, 0) // one literal + copy-2 of 64 bytes
        assertFailsWith<DecompressionBombException> { Snappy.decompress(repeated, ArchiveLimits { maxUncompressedBytes = 64 }) }
        assertFailsWith<DecompressionBombException> { Snappy.decompress(repeated, ArchiveLimits { maxCompressionRatio = 10 }) }
        assertContentEquals(ByteArray(65) { 65 }, Snappy.decompress(repeated, ArchiveLimits {
            maxUncompressedBytes = 65
            maxCompressionRatio = 11
        }))
    }

    @Test
    fun aLargeDeclarationWithoutTagsCannotTriggerAllocation() {
        for (size in listOf(1L shl 30, Int.MAX_VALUE.toLong())) {
            val error = assertFailsWith<ArchiveFormatException> { Snappy.decompress(varint(size), ArchiveLimits.UNBOUNDED) }
            assertTrue(error.message!!.contains("decoded 0 bytes"))
        }
        assertContentEquals(byteArrayOf(), Snappy.decompress(byteArrayOf(0)))
    }
}
