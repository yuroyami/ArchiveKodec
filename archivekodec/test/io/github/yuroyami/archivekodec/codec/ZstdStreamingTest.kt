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

/** The streamed zstd decoder (#154, Stage 2) on zstd 1.5.5 output, read in small pieces. */
@OptIn(ExperimentalEncodingApi::class)
class ZstdStreamingTest {
    // `zstd -19 --check` of 231541 bytes of words, a skippable frame holding "hello", then
    // `zstd -3 --no-check` of 120000 bytes of sample-like data.
    private val multi = Base64.decode(
        "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" +
        "QQoAAEAEEUQAAACACCKIAAQAiCCCCAAAAIhABCkAAAARRBABAIAAIoggAgAAIIIIIgAAACCCCFIAAACkIIIIAAAAiCCCCAAAQAQRiAAAAIAIRp" +
        "ACAAAQQQQRAAAAIIIIIgAAABFEUAIAAABEEEEKAABABBFEAAAAQAQRiBAAAIgggggAAACIIIIUAAAAEUQQAQACACKIIAIAABCBCCIAAAAggghS" +
        "AABARBBBBAAAAIgggggAAEAEEUQAAACAGESQAgAAEEEEEQAAABBBCCIAAAARRCECAAAAIoggBQAAQAQRRAAAAEAEEaQAAACEIIIIAAAAiCCCFA" +
        "AAgAgiCAEIAAAiiCACAAAQQQQRAAAAIIIIUgAAAUQQQQQAAABEIIIIAABABBFEAAAAkAgiSAEAABBBBBEAAAAQQQQRAAAAEYwgAgAAACKIIAUA" +
        "ACCCCEQAAABABJGIAAAARBBBBAAAAIggghQAAIAIIogQAAAAIoggAgAAEEEEEQAAABBBBFIABABEEEEEAAAARBBBBAAAQAQRRAAAQIAIIkgBAA" +
        "CIQAQRAAAAEEEEEQAAgBhEEAEAAAAiiCAFAAAggggiAAAAQAQjiAAAAEQQQQQAAABEEIIUAACACCJIAQAAABFEEAEAABBBBBEAAAAQQQQpCAAA" +
        "QhBBBAAAAEQQQQQAACCCCEIAAAGACCJIAQAAiCCCCAAAABBBBBEAACARRBABAAAAEYggBQAAIIIIIgAAACAGEUQAAABEEEEEAAAARBBBCgAAgA" +
        "giEQEAAAARRBABAACIIAQRAAAAEEEEaQAAACKIIAIAAABEEEEEAAAggggiAAIAgAgiSAEAAIgggggAAACIIAIRAIAAEUQQAQAAABFEkAIAACCC" +
        "CCIAAABQBBFEAAAAIhBBBAAAAEQQQQoAAEAEI4gAAAAAEUQQAQAAiCCCCAAAABBBRFIAAAAiiCACAAAAIghBBAAAIIIIIggAAEAEEaQAAACIII" +
        "IIAAAAiCCCCAABABFEEAEAAAARRJACAAAQQQQiAABAQAQRRAAAACKIIAIAAABEEEEKAABADCKIAAAAgBBEEAEAAIgggggAAACIoARJAAAAIogg" +
        "AgAAACKIIAIAACCCCGIAAABABBGkAAAARBCCCAAAAIgggggEAIAIIogAAAAAEUSQAgAAEEEEEQCAAEAEEUQAAAAiiCACAAAAIohACgAAoAgiiA" +
        "AAAIAIIogAAACIIIIIAAAAiEIEKQAAABGIIAIAAAAiiCACAAAQQUQiAAAAQAQRpAAAAEQQQQQAAACIIIIYAACACCKIAAAAgAhEkAIAABBBBBEA" +
        "AgAggggiAAAAIoggAgAAACKIIAUAQIAIIogAAACACCKIAAAARBCBCIBDZQI0C19qAAEBSw0gIGCpAQQELDWAgIClBhAQsNQAAgKWGkBAwFIDCA" +
        "hYagABAUsNICBgqQEEBCz92wQBAQD4B8A/AP4B8A+AfwD8A+AfAP8A+DcBwD/SEoD//gE=",
    )

    private fun stream(bytes: ByteArray, step: Int, limits: ArchiveLimits = ArchiveLimits.DEFAULT): ByteArray {
        var position = 0
        val out = ByteArrayBuilder(1024)
        val count = Codecs.ZSTD.decompress(ByteSource { into, offset, length ->
            if (position == bytes.size) -1 else minOf(length, step, bytes.size - position).also { n ->
                bytes.copyInto(into, offset, position, position + n); position += n
            }
        }, ByteSink { b, o, n -> out.append(b, o, n) }, limits)
        return out.takeByteArray().also { assertEquals(it.size.toLong(), count) }
    }

    @Test
    fun framesSkippableFramesAndChecksumsStream() {
        for (step in listOf(1, 999, 1 shl 16)) {
            val out = stream(multi, step)
            assertEquals(351541, out.size)
            assertEquals(0x57fc833dL, Crc32.of(out))
        }
        assertContentEquals(Codecs.ZSTD.decompress(multi, ArchiveLimits.UNBOUNDED), stream(multi, 4096))
    }

    @Test
    fun damageAndLimitsFail() {
        // The first frame's content checksum, then a cut.
        val badChecksum = multi.copyOf().also { it[17454] = (it[17454].toInt() xor 1).toByte() }
        assertFailsWith<ArchiveFormatException> { stream(badChecksum, 4096) }
        assertFailsWith<ArchiveFormatException> { stream(multi.copyOf(multi.size - 5), 4096) }
        assertFailsWith<DecompressionBombException> { stream(multi, 4096, ArchiveLimits { maxUncompressedBytes = 300_000 }) }
        assertFailsWith<DecompressionBombException> { stream(multi, 4096, ArchiveLimits { maxDecoderMemory = 1000 }) }
    }
}
