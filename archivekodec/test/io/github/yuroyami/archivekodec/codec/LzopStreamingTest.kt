package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.security.ArchiveLimits
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

/** The streaming lzop decoder against the whole-array one. */
class LzopStreamingTest {
    // lzop 1.04: `printf 'lzop block %.0s' $(seq 1 200) | lzop --crc32`, from a file named r.txt:
    // CRC-32 header and data checks, a stored name and a compressed block.
    private val reference = Base64.decode("iUxaTwANChoKEEAgoBABAQUDABEIAACBpGr/HzIAAAAABXIudHh0EfOukAAACJgAAAAtKJnMwwhsem9wIGJsb2Nr" +
        "ICAAAAAAAAAAAGMoAA5ibG9jayBsem9wIGJsb2NrIBEAAAAAAAA=")

    @Test
    fun blocksStoredAndCompressedDecodeTheSame() {
        assertEquals("lzop block ".repeat(200), StreamParity.arrayOutcome(Codecs.LZOP, reference))
        StreamParity.assertSame(Codecs.LZOP, reference, "reference")
        var x = 5
        // Past the 256 KiB block size, with a random stretch that LZO1X stores.
        val mixed = ByteArray(700_000) { if (it in 300_000 until 400_000) { x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5); x.toByte() } else (it / 999).toByte() }
        for (input in listOf(ByteArray(0), "a".encodeToByteArray(), StreamParity.text(50_000, 2), mixed)) {
            StreamParity.assertSame(Codecs.LZOP, Codecs.LZOP.compress(input), "${input.size} bytes", ArchiveLimits.UNBOUNDED, listOf(7, 65_536))
        }
        StreamParity.assertSame(Codecs.LZOP, reference + byteArrayOf(0), "trailing byte")
    }

    @Test
    fun truncationsCorruptionsAndLimitsFailTheSameWay() {
        for (stream in listOf(reference, Codecs.LZOP.compress(StreamParity.text(3000, 4)))) {
            for (cut in stream.indices) StreamParity.assertSame(Codecs.LZOP, stream.copyOf(cut), "cut at $cut", steps = listOf(13))
            for (at in stream.indices) {
                val bad = stream.copyOf().also { it[at] = (it[at].toInt() xor 0x41).toByte() }
                StreamParity.assertSame(Codecs.LZOP, bad, "byte $at", steps = listOf(13))
            }
        }
        val zeros = Codecs.LZOP.compress(ByteArray(2_000_000))
        StreamParity.assertSame(Codecs.LZOP, zeros, "size limit", ArchiveLimits { maxUncompressedBytes = 300_000 }, listOf(4096))
        assertEquals("DecompressionBombException",
            StreamParity.streamOutcome(Codecs.LZOP, zeros, 4096, ArchiveLimits { maxCompressionRatio = 20 }))
    }
}
