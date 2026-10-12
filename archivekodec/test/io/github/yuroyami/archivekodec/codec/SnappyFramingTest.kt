package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.ArchiveFormat
import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.Magic
import io.github.yuroyami.archivekodec.codec.snappy.Snappy
import io.github.yuroyami.archivekodec.hex
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SnappyFramingTest {
    private val identifier = hex("ff060000734e61507059")
    // python-snappy 0.7.3 StreamCompressor (cramjam 2.13.0).
    private val stored = hex("ff060000734e6150705901160000e12aac4068656c6c6f20417263686976654b6f646563")
    private val compressed = hex("ff060000734e6150705900230000845f0ef19802346672616d656420736e6170707920fe0e00fe0e00fe0e00fe0e00190e")
    private val storedOutput = "hello ArchiveKodec".encodeToByteArray()
    private val compressedOutput = "framed snappy ".repeat(20).encodeToByteArray()

    @Test fun decodesIndependentStoredCompressedAndMultipleChunks() {
        assertContentEquals(storedOutput, Snappy.frameDecompress(stored))
        assertContentEquals(compressedOutput, Snappy.frameDecompress(compressed))
        assertContentEquals(storedOutput + compressedOutput, Snappy.frameDecompress(stored + compressed.copyOfRange(10, compressed.size)))
    }

    @Test fun keepsRawAndFramedRoutesExplicit() {
        val raw = hex("124468656c6c6f20417263686976654b6f646563")
        assertContentEquals(storedOutput, Codecs.SNAPPY.decompress(raw))
        assertContentEquals(storedOutput, Codecs.SNAPPY_FRAMED.decompress(stored))
        assertEquals(CodecId.SNAPPY_FRAMED, Codecs.SNAPPY_FRAMED.id)
        assertEquals(ArchiveFormat.UNKNOWN, Magic.sniff(raw))
        assertEquals(ArchiveFormat.SNAPPY, Magic.sniff(stored))
        assertEquals(ArchiveFormat.SNAPPY, Magic.sniff(ByteArrayRandomAccessSource(compressed)))
        assertFailsWith<ArchiveFormatException> { Snappy.frameDecompress(raw) }
        assertFailsWith<ArchiveFormatException> { Snappy.decompress(stored) }
    }

    @Test fun snifferRequiresTheCompleteIdentifier() {
        for (length in 0 until identifier.size) {
            assertEquals(ArchiveFormat.UNKNOWN, Magic.sniff(identifier.copyOf(length)))
        }
        for (position in identifier.indices) {
            val bad = identifier.copyOf()
            bad[position] = (bad[position].toInt() xor 0x80).toByte()
            assertEquals(ArchiveFormat.UNKNOWN, Magic.sniff(bad))
            assertFailsWith<ArchiveFormatException> { Snappy.frameDecompress(bad) }
        }
    }

    @Test fun encoderHandlesChunkBoundariesAndUsesStoredFallback() {
        for (size in listOf(0, 1, 65535, 65536, 65537, 131072, 131079)) {
            for (input in listOf(ByteArray(size) { 65 }, Random(size).nextBytes(size))) {
                val encoded = Snappy.frameCompress(input)
                assertContentEquals(identifier, encoded.copyOf(10))
                assertContentEquals(input, Snappy.frameDecompress(encoded), "size $size")
                var position = 10
                var chunks = 0
                while (position < encoded.size) {
                    val type = encoded[position].toInt() and 0xff
                    val length = (encoded[position + 1].toInt() and 0xff) or
                        ((encoded[position + 2].toInt() and 0xff) shl 8) or
                        ((encoded[position + 3].toInt() and 0xff) shl 16)
                    assertTrue(type == 0 || type == 1)
                    assertTrue(length in 4..65540)
                    position += 4 + length
                    chunks++
                }
                assertEquals(encoded.size, position)
                assertEquals((size + 65535) / 65536, chunks)
            }
        }
        assertEquals(1, Snappy.frameCompress(Random(7).nextBytes(65536))[10].toInt())
        assertEquals(0, Snappy.frameCompress(ByteArray(65536))[10].toInt())
    }

    @Test fun acceptsConcatenationRepeatIdentifiersAndUninterpretedSkippableChunks() {
        val skipped = chunk(0x80, hex("010203")) + chunk(0xfd, byteArrayOf()) + chunk(0xfe, hex("ff00ff"))
        assertContentEquals(storedOutput + compressedOutput, Snappy.frameDecompress(stored + skipped + identifier + compressed))
        assertContentEquals(byteArrayOf(), Snappy.frameDecompress(identifier + skipped + identifier))
        assertFailsWith<ArchiveFormatException> { Snappy.frameDecompress(skipped + identifier) }
        assertFailsWith<ArchiveFormatException> { Snappy.frameDecompress(identifier + chunk(0xff, "sNaPpX".encodeToByteArray())) }
        assertFailsWith<ArchiveFormatException> { Snappy.frameDecompress(identifier + chunk(0xff, "sNaPpY!".encodeToByteArray())) }
    }

    @Test fun rejectsCorruptChecksumsInBothChunkTypes() {
        for (frame in listOf(stored, compressed)) {
            for (byte in 14..17) {
                val corrupted = frame.copyOf()
                corrupted[byte] = (corrupted[byte].toInt() xor 0x80).toByte()
                val error = assertFailsWith<ArchiveFormatException> { Snappy.frameDecompress(corrupted) }
                assertEquals(14L, error.offset)
                assertTrue(error.message!!.contains("CRC-32C"))
            }
        }
    }

    @Test fun rejectsTruncatedHeadersAndPayloads() {
        for (frame in listOf(stored, compressed)) {
            for (length in 0 until frame.size) {
                if (length == 10) continue // A stream identifier alone is a valid empty stream.
                assertFailsWith<ArchiveFormatException>("prefix $length") { Snappy.frameDecompress(frame.copyOf(length)) }
            }
        }
        assertFailsWith<ArchiveFormatException> { Snappy.frameDecompress(identifier + hex("80ffffff")) }
        for (type in 0..1) for (length in 0..3) {
            assertFailsWith<ArchiveFormatException> { Snappy.frameDecompress(identifier + chunk(type, ByteArray(length))) }
        }
    }

    @Test fun rejectsUnskippableChunkTypes() {
        for (type in 2..0x7f) {
            val error = assertFailsWith<ArchiveUnsupportedException> { Snappy.frameDecompress(identifier + chunk(type, byteArrayOf())) }
            assertEquals(10L, error.offset)
        }
    }

    @Test fun rejectsOversizedStoredAndDeclaredCompressedChunksBeforeDecoding() {
        val oversized = listOf(
            chunk(1, ByteArray(4 + 65537)),
            chunk(0, ByteArray(4) + hex("818004")), // 65537, without allocating or decoding a payload
            chunk(0, ByteArray(4) + hex("ffffffff0f")), // maximum raw u32 length
        )
        for (bytes in oversized) {
            val error = assertFailsWith<ArchiveFormatException> { Snappy.frameDecompress(identifier + bytes, ArchiveLimits.UNBOUNDED) }
            assertTrue(error.message!!.contains("65536"))
        }
    }

    @Test fun chunkCeilingAppliesToDecodedBytesRatherThanCompressedBytes() {
        // Spec permits up to 2^24 - 5 compressed bytes, provided output is <= 65536.
        // Independent python-snappy controls validate this raw stream and the CRC
        // taken from StreamCompressor's normal encoding of 65536 'a' bytes.
        val raw = hex("808004") + ByteArray(2 * 65536) { if (it % 2 == 0) 0 else 97 }
        val frame = identifier + chunk(0, hex("0388017d") + raw)
        assertContentEquals(ByteArray(65536) { 97 }, Snappy.frameDecompress(frame))
    }

    @Test fun enforcesCombinedOutputAndWholeStreamRatioLimits() {
        val mixed = stored + compressed
        val expected = storedOutput + compressedOutput
        assertContentEquals(expected, Snappy.frameDecompress(mixed, ArchiveLimits { maxUncompressedBytes = expected.size.toLong() }))
        assertFailsWith<DecompressionBombException> {
            Snappy.frameDecompress(mixed, ArchiveLimits { maxUncompressedBytes = expected.size - 1L })
        }
        val ratio = (expected.size + mixed.size - 1) / mixed.size
        assertContentEquals(expected, Snappy.frameDecompress(mixed, ArchiveLimits { maxCompressionRatio = ratio }))
        assertFailsWith<DecompressionBombException> {
            Snappy.frameDecompress(mixed, ArchiveLimits { maxCompressionRatio = ratio - 1 })
        }
        val zero = ArchiveLimits { maxUncompressedBytes = 0; maxCompressionRatio = 0 }
        assertContentEquals(byteArrayOf(), Snappy.frameDecompress(identifier + chunk(1, hex("d8ea82a2")) + chunk(0, hex("d8ea82a200")), zero))
        assertFailsWith<DecompressionBombException> { Snappy.frameDecompress(stored, zero) }
    }

    @Test fun malformedRawChunksReportAbsoluteFrameOffsets() {
        // One-byte output preamble followed by a literal tag with no literal byte.
        val frame = identifier + chunk(0, ByteArray(4) + hex("0100")) + identifier
        val error = assertFailsWith<ArchiveFormatException> { Snappy.frameDecompress(frame) }
        assertEquals(20L, error.offset)
    }

    private fun chunk(type: Int, payload: ByteArray): ByteArray =
        byteArrayOf(type.toByte()) + ByteArray(3) { (payload.size ushr (8 * it)).toByte() } + payload
}
