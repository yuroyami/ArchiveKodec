package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.codec.lzma.WRITER_MAX_DICTIONARY
import io.github.yuroyami.archivekodec.codec.lzma.lzma2DictionarySize
import io.github.yuroyami.archivekodec.codec.zstd.ZSTD_WRITER_MAX_WINDOW
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The window or dictionary an encoder declares does not grow with the input: zstd declares at
 * most 8 MiB (RFC 8878's recommended decoder minimum) and the LZMA writers at most 8 MiB (the
 * dictionary of `xz -6`). The input is one block of noise, zeros past the cap, then the same block
 * again, so its only long-distance redundancy lies beyond the window and must not be matched.
 */
class WindowBoundTest {
    private val cap = 8 shl 20
    private val block = 65_536

    private val input: ByteArray by lazy {
        val data = ByteArray(block + cap + block)
        var x = 0x2545F491
        for (i in 0 until block) {
            x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
            data[i] = x.toByte()
        }
        data.copyInto(data, block + cap, 0, block)
        data
    }

    private fun u32(bytes: ByteArray, at: Int): Long =
        (0 until 4).fold(0L) { value, i -> value or ((bytes[at + i].toLong() and 0xFF) shl (8 * i)) }

    /** Round-trip whole, then stream with maxDecoderMemory at the cap, and refuse one byte below it. */
    private fun checkDecodes(codec: Codec, stream: ByteArray) {
        assertContentEquals(input, codec.decompress(stream), "${codec.id} whole-array round trip")
        // Both copies of the noise block are coded: the second could only match at cap + 64 KiB back.
        assertTrue(stream.size > 2 * block, "${codec.id}: ${stream.size} bytes, so a match reached past the window")
        val atCap = ArchiveLimits { maxDecoderMemory = cap.toLong() }
        val out = ByteArray(input.size)
        var written = 0
        val count = codec.decompress(StreamParity.trickle(stream, 65_536), ByteSink { b, o, n ->
            b.copyInto(out, written, o, o + n); written += n
        }, atCap)
        assertEquals(input.size.toLong(), count)
        assertContentEquals(input, out, "${codec.id} streamed under maxDecoderMemory = cap")
        assertFailsWith<DecompressionBombException>("${codec.id} under maxDecoderMemory = cap - 1") {
            codec.decompress(StreamParity.trickle(stream, 65_536), ByteSink { _, _, _ -> }, atCap.copy { maxDecoderMemory = cap - 1L })
        }
    }

    @Test
    fun theCapsAreEightMebibytes() {
        assertEquals(cap, ZSTD_WRITER_MAX_WINDOW)
        assertEquals(cap, WRITER_MAX_DICTIONARY)
    }

    @Test
    fun zstdDeclaresAnEightMebibyteWindowAboveTheCap() {
        val stream = Codecs.ZSTD.compress(input)
        val descriptor = stream[4].toInt() and 0xFF
        assertEquals(0, descriptor and 0x20, "not single-segment")
        assertEquals(2, descriptor ushr 6, "a 4-byte content size")
        val windowDescriptor = stream[5].toInt() and 0xFF
        val base = 1L shl (10 + (windowDescriptor ushr 3))
        assertEquals(cap.toLong(), base + base / 8 * (windowDescriptor and 7))
        assertEquals(input.size.toLong(), u32(stream, 6), "Frame_Content_Size")
        checkDecodes(Codecs.ZSTD, stream)
    }

    @Test
    fun zstdAtTheCapStaysSingleSegment() {
        val stream = Codecs.ZSTD.compress(ByteArray(cap))
        assertEquals(0x20, stream[4].toInt() and 0x20)
        assertEquals(cap.toLong(), u32(stream, 5))
    }

    @Test
    fun lzmaDeclaresAnEightMebibyteDictionaryAboveTheCap() {
        val stream = Codecs.LZMA.compress(input)
        assertEquals(cap.toLong(), u32(stream, 1))
        checkDecodes(Codecs.LZMA, stream)
    }

    @Test
    fun xzDeclaresAnEightMebibyteDictionaryAboveTheCap() {
        val stream = Codecs.XZ.compress(input)
        // Stream header (12 bytes), then a block header: size, flags 0, filter 0x21 (LZMA2), one property byte.
        assertEquals(0x21, stream[14].toInt())
        assertEquals(cap.toLong(), lzma2DictionarySize(stream[16].toInt()))
        checkDecodes(Codecs.XZ, stream)
    }

    @Test
    fun lzipDeclaresAnEightMebibyteDictionaryAboveTheCap() {
        val stream = Codecs.LZIP.compress(input)
        assertEquals(23, stream[5].toInt(), "dictionary code: 2^23, no fraction")
        checkDecodes(Codecs.LZIP, stream)
    }

    @Test
    fun lzma2RoundTripsAboveTheCap() {
        val stream = Codecs.LZMA2.compress(input)
        assertContentEquals(input, Codecs.LZMA2.decompress(stream))
        assertTrue(stream.size > 2 * block)
    }
}
