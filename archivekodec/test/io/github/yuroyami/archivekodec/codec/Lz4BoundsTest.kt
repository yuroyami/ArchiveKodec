package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.checksum.XxHash32
import io.github.yuroyami.archivekodec.codec.lz4.Lz4
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class Lz4BoundsTest {
    private fun le32(v: Long) = ByteArray(4) { (v ushr (it * 8)).toByte() }
    private fun frame(bd: Int = 0x70, blocks: List<Pair<Boolean, ByteArray>>): ByteArray {
        val descriptor = byteArrayOf(0x60, bd.toByte())
        var bytes = byteArrayOf(4, 0x22, 0x4d, 0x18) + descriptor +
            (XxHash32.hash(descriptor).toInt() ushr 8).toByte()
        for ((stored, block) in blocks) bytes += le32(block.size.toLong() or (if (stored) 0x80000000L else 0)) + block
        return bytes + le32(0)
    }

    @Test
    fun hostileStoredBlockLengthsAreRejectedWithoutGrowth() {
        for (raw in listOf(0xFFFFFFFFL, 0xFD00003CL, 0x00400001L, 0x80400001L)) {
            val bytes = frame(blocks = emptyList()).copyOfRange(0, 7) + le32(raw) + le32(0)
            assertFailsWith<ArchiveFormatException> { Lz4.frameDecompress(bytes) }
        }
        val truncated = frame(blocks = emptyList()).copyOfRange(0, 7) + le32(0x80000008L) + le32(0)
        assertFailsWith<ArchiveFormatException> { Lz4.frameDecompress(truncated) }
    }

    @Test
    fun tokensCannotConsumeBytesPastTheirSlice() {
        val examples = listOf(
            byteArrayOf(0x30, 65, 66, 67) to 1, // literal data outside the slice
            byteArrayOf(0xF0.toByte(), 0, 65) to 1, // literal-length extension
            byteArrayOf(0x10, 65, 1, 0) to 3, // incomplete offset
            byteArrayOf(0x1F, 65, 1, 0, 0) to 4, // match-length extension
        )
        for ((bytes, end) in examples) {
            assertFailsWith<ArchiveFormatException> { Lz4.blockDecompress(bytes, 0, end) }
        }
        assertFailsWith<ArchiveFormatException> {
            Lz4.frameDecompress(frame(blocks = listOf(false to byteArrayOf(0x30))))
        }
        for ((start, end) in listOf(-1 to 1, 0 to 5, 2 to 1)) {
            assertFailsWith<ArchiveFormatException> { Lz4.blockDecompress(byteArrayOf(0), start, end) }
        }
    }

    @Test
    fun configuredBudgetAppliesWithinCompressedAndStoredBlocks() {
        val data = ByteArray(128 * 1024)
        val compressed = Lz4.frameCompress(data)
        assertFailsWith<DecompressionBombException> {
            Lz4.frameDecompress(compressed, ArchiveLimits { maxUncompressedBytes = 1024 })
        }
        val tiny = "ABCD".encodeToByteArray()
        for (stored in listOf(false, true)) {
            val block = if (stored) tiny else Lz4.blockCompress(tiny)
            val bytes = frame(blocks = listOf(stored to block, stored to block))
            assertFailsWith<DecompressionBombException> {
                Lz4.frameDecompress(bytes, ArchiveLimits { maxUncompressedBytes = 7 })
            }
            assertContentEquals(tiny + tiny, Lz4.frameDecompress(bytes, ArchiveLimits { maxUncompressedBytes = 8 }))
        }
        assertContentEquals(byteArrayOf(), Lz4.frameDecompress(frame(blocks = listOf(true to byteArrayOf())), ArchiveLimits { maxUncompressedBytes = 0 }))
    }

    @Test
    fun descriptorMaximumBoundsBothStoredAndDecodedBlocks() {
        val data = ByteArray(64 * 1024 + 1)
        for (stored in listOf(false, true)) {
            val block = if (stored) data else Lz4.blockCompress(data)
            assertFailsWith<ArchiveFormatException> {
                Lz4.frameDecompress(frame(0x40, listOf(stored to block)))
            }
        }
        for (bd in listOf(0, 0x30, 0x41, 0xC0)) {
            assertFailsWith<ArchiveFormatException> { Lz4.frameDecompress(frame(bd, emptyList())) }
        }
        for (bd in listOf(0x40, 0x50, 0x60, 0x70)) {
            assertContentEquals(byteArrayOf(65), Lz4.frameDecompress(frame(bd, listOf(true to byteArrayOf(65)))))
        }
    }

    @Test
    fun truncatedFrameFieldsReportFormatErrors() {
        val bytes = frame(blocks = listOf(true to byteArrayOf(65)))
        for (end in bytes.indices) {
            assertFailsWith<ArchiveFormatException> { Lz4.frameDecompress(bytes.copyOfRange(0, end)) }
        }
    }
}
