package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The streamed xz decoder (#154, Stage 2) on xz 5.4.5 output, read a few bytes at a time. */
@OptIn(ExperimentalEncodingApi::class)
class XzStreamingTest {
    // `xz --block-size=50000 --check=crc32` of 231541 bytes of words (five blocks), eight zero
    // bytes of padding, `xz -1` of 120000 bytes of sample-like data, then four more zero bytes.
    private val multi = Base64.decode(
        "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",
    )
    // `xz --delta=dist=2 --lzma2=preset=1 --check=sha256` of the same 120000 bytes.
    private val delta = Base64.decode(
        "/Td6WFoAAArh+wyhAgEDAQEhARAp4hu/4dS/AGFdAAACD6S6bn4RvHb/773DEye3Qf5JfM30W+7FqpprXaHqgkXGVaSOIgGzxrk1bP7DSQnQqV" +
        "Q9roAn7zIRbjk72u36k88uWcxjeuA6VUtxHUPGnumhEt++CjGRMQuACQK9qAAAAAAA+q7Q/3T6rrkzPYZt1Wr68VP7DOobQfYfRPuSiDWZ0q4A" +
        "AZUBwKkHALNkO1y26d8cAgAAAAAKWVo=",
    )
    // `xz --arm --lzma2=preset=0` of 4096 bytes dense in ARM BL instructions.
    private val arm = Base64.decode(
        "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",
    )

    private fun stream(bytes: ByteArray, step: Int, limits: ArchiveLimits = ArchiveLimits.DEFAULT): ByteArray {
        var position = 0
        val out = ByteArrayBuilder(1024)
        val count = Codecs.XZ.decompress(ByteSource { into, offset, length ->
            if (position == bytes.size) -1 else minOf(length, step, bytes.size - position).also { n ->
                bytes.copyInto(into, offset, position, position + n); position += n
            }
        }, ByteSink { b, o, n -> out.append(b, o, n) }, limits)
        val result = out.takeByteArray()
        assertEquals(result.size.toLong(), count)
        return result
    }

    @Test
    fun blocksStreamsPaddingDeltaAndBranchFiltersStream() {
        for ((bytes, size, crc) in listOf(Triple(multi, 351541, 0x57fc833dL), Triple(delta, 120000, 0x84840de6L), Triple(arm, 4096, 0x87b4f1a9L))) {
            for (step in listOf(1, 333, 1 shl 16)) {
                if (step == 1 && bytes.size > 10_000) continue
                val out = stream(bytes, step)
                assertEquals(size, out.size)
                assertEquals(crc, Crc32.of(out))
            }
            assertContentEquals(Codecs.XZ.decompress(bytes, ArchiveLimits.UNBOUNDED), stream(bytes, 4096))
        }
    }

    @Test
    fun damageAndLimitsFail() {
        assertFailsWith<ArchiveFormatException> { stream(multi.copyOf(multi.size - 13), 4096) }
        val badCheck = delta.copyOf().also { it[it.size - 40] = (it[it.size - 40].toInt() xor 1).toByte() }
        assertFailsWith<io.github.yuroyami.archivekodec.ArchiveException> { stream(badCheck, 4096) }
        assertFailsWith<ArchiveFormatException> { stream(multi + byteArrayOf(0, 0, 0), 4096) }
        assertFailsWith<DecompressionBombException> { stream(multi, 4096, ArchiveLimits { maxUncompressedBytes = 100_000 }) }
        assertFailsWith<DecompressionBombException> { stream(multi, 4096, ArchiveLimits { maxDecoderMemory = 1000 }) }
    }
}
