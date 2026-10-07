package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.checksum.XxHash32
import io.github.yuroyami.archivekodec.codec.lz4.Lz4
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class CodecRatioTest {
    private val codecs = Codecs.implemented.map { Codecs.byId(it)!! }

    @Test
    fun allCodecsHonorZeroRatioWithoutRejectingEmptyOutput() {
        val limits = ArchiveLimits(maxCompressionRatio = 0)
        for (codec in codecs) {
            assertContentEquals(byteArrayOf(), codec.decompress(codec.compress(byteArrayOf()), limits))
            assertFailsWith<DecompressionBombException> { codec.decompress(codec.compress(byteArrayOf(65)), limits) }
        }
    }

    @Test
    fun allCodecsCompareTheCompleteInputAndOutputRatioExactly() {
        val data = ByteArray(16_384)
        for (codec in codecs) {
            val input = if (codec.id == CodecId.SNAPPY) snappyZeros(data.size) else codec.compress(data)
            val ceiling = (data.size + input.size - 1) / input.size
            assertContentEquals(data, codec.decompress(input, ArchiveLimits(maxCompressionRatio = ceiling)))
            assertFailsWith<DecompressionBombException> {
                codec.decompress(input, ArchiveLimits(maxCompressionRatio = ceiling - 1))
            }
        }
    }

    @Test
    fun lz4ChecksStoredBlocksAndTheTotalAcrossMixedBlocks() {
        val stored = frame(listOf(true to byteArrayOf(65)))
        assertFailsWith<DecompressionBombException> { Lz4.frameDecompress(stored, ArchiveLimits(maxCompressionRatio = 0)) }
        val zeros = ByteArray(4096)
        val mixed = frame(listOf(true to byteArrayOf(65), false to Lz4.blockCompress(zeros)))
        assertContentEquals(byteArrayOf(65) + zeros, Lz4.frameDecompress(mixed, ArchiveLimits(maxCompressionRatio = 1000)))
        assertFailsWith<DecompressionBombException> { Lz4.frameDecompress(mixed, ArchiveLimits(maxCompressionRatio = 1)) }
    }

    private fun snappyZeros(size: Int): ByteArray {
        val bytes = ArrayList<Byte>()
        var length = size
        while (length >= 128) { bytes.add(((length and 127) or 128).toByte()); length = length ushr 7 }
        bytes.add(length.toByte())
        bytes.add(0); bytes.add(0) // one literal zero
        var remaining = size - 1
        while (remaining > 0) {
            val count = minOf(64, remaining)
            bytes.add((((count - 1) shl 2) or 2).toByte()); bytes.add(1); bytes.add(0)
            remaining -= count
        }
        return bytes.toByteArray()
    }

    private fun frame(blocks: List<Pair<Boolean, ByteArray>>): ByteArray {
        val descriptor = byteArrayOf(0x60, 0x70)
        var bytes = byteArrayOf(4, 0x22, 0x4d, 0x18) + descriptor +
            (XxHash32.hash(descriptor).toInt() ushr 8).toByte()
        for ((stored, block) in blocks) {
            val size = block.size.toLong() or (if (stored) 0x80000000L else 0L)
            bytes += ByteArray(4) { (size ushr (8 * it)).toByte() } + block
        }
        return bytes + ByteArray(4)
    }
}
