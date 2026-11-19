package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.codec.lzw.UnixCompress
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import kotlin.test.Test
import kotlin.test.assertEquals

/** The streaming Unix compress decoder against the whole-array one. */
class CompressStreamingTest {
    @Test
    fun everyWidthAndTableClearDecodesTheSame() {
        var x = 7
        fun next(): Int { x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5); return x }
        // Random bytes fill the table and runs after them make the encoder clear it.
        val mixed = ByteArray(150_000) { if (it < 90_000) next().toByte() else (it / 3000).toByte() }
        for (bits in 10..16) {
            for (input in listOf(ByteArray(0), "a".encodeToByteArray(), StreamParity.text(20_000, bits), mixed)) {
                StreamParity.assertSame(Codecs.COMPRESS, UnixCompress.compress(input, bits), "$bits bits, ${input.size} bytes",
                    ArchiveLimits.UNBOUNDED, listOf(5, 65_536))
            }
        }
        assertEquals(StreamParity.text(20_000, 3).decodeToString(),
            StreamParity.streamOutcome(Codecs.COMPRESS, Codecs.COMPRESS.compress(StreamParity.text(20_000, 3)), 1))
    }

    @Test
    fun truncationsCorruptionsAndLimitsFailTheSameWay() {
        val stream = UnixCompress.compress(StreamParity.text(3000, 9), 12)
        for (cut in 0 until stream.size) StreamParity.assertSame(Codecs.COMPRESS, stream.copyOf(cut), "cut at $cut", steps = listOf(13))
        for (at in stream.indices) {
            val bad = stream.copyOf().also { it[at] = (it[at].toInt() xor 0x41).toByte() }
            StreamParity.assertSame(Codecs.COMPRESS, bad, "byte $at", steps = listOf(13))
        }
        val zeros = Codecs.COMPRESS.compress(ByteArray(500_000))
        StreamParity.assertSame(Codecs.COMPRESS, zeros, "size limit", ArchiveLimits { maxUncompressedBytes = 100_000 }, listOf(4096))
        // Measured against the bytes read so far, the ratio fails as the array decoder's does.
        assertEquals("DecompressionBombException",
            StreamParity.streamOutcome(Codecs.COMPRESS, zeros, 4096, ArchiveLimits { maxCompressionRatio = 50 }))
    }
}
