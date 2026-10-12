package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Zstandard (#12, #13). Fixtures come from libzstd 1.5 through python-zstandard; encoder output was checked with it. */
@OptIn(ExperimentalEncodingApi::class)
class ZstdTest {
    private val hello = "hello world, hello world".encodeToByteArray()
    private val checked = Base64.decode("KLUv/SQYnQAAaGhlbGxvIHdvcmxkLCABAGDMLz5y2qw=")      // level 3, content checksum
    private val level19 = Base64.decode("KLUv/SQYpQAAcGhlbGxvIHdvcmxkLCBoAQCwTSU+ctqs")      // level 19, content checksum
    private val unchecked = Base64.decode("KLUv/SAYnQAAaGhlbGxvIHdvcmxkLCABAGDMLw==")    // level 3, no checksum
    private val streamed = Base64.decode("KLUv/QRYvQAAaGhlbGxvIHdvcmxkLCACAJXs/ADE8wuXeDwz")     // streaming writer: no content size, a window descriptor

    // Level 19 of generated(1_100_000): nine blocks, so tables and repeat offsets carry across blocks.
    private val multiBlock = Base64.decode(
        "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")

    /** The fixture generator: an LCG choosing words, newlines and long runs of 'z'. */
    private fun generated(size: Int): ByteArray {
        val words = listOf("alpha", "beta", "gamma", "delta").map { it.encodeToByteArray() }
        var x = 1L
        val out = ArrayList<Byte>(size + 400)
        while (out.size < size) {
            x = (x * 1103515245 + 12345) and 0x7fffffff
            val r = (x shr 8) % 64
            if (r == 0L) repeat(((x shr 4) % 300).toInt()) { out.add('z'.code.toByte()) }
            for (b in words[if (r < 8) ((x shr 16) % 4).toInt() else 0]) out.add(b)
            out.add(if (r == 9L) 10 else 32)
        }
        return out.subList(0, size).toByteArray()
    }

    @Test
    fun decodesLibzstdFrames() {
        for (frame in listOf(checked, level19, unchecked)) assertContentEquals(hello, Codecs.ZSTD.decompress(frame))
        assertContentEquals(ByteArray(hello.size * 50) { hello[it % hello.size] }, Codecs.ZSTD.decompress(streamed))
        assertContentEquals(generated(1_100_000), Codecs.ZSTD.decompress(multiBlock))
    }

    @Test
    fun facadeAndRegistry() {
        assertContentEquals(hello, ArchiveKodec.decompress(checked))
        assertSame(Codecs.ZSTD, ArchiveKodec.zstd)
        assertSame(Codecs.ZSTD, CodecRegistry.DEFAULT.byZipMethod(93))
    }

    @Test
    fun skippableAndConcatenatedFrames() {
        val skippable = byteArrayOf(0x5A, 0x2A, 0x4D, 0x18, 3, 0, 0, 0, 1, 2, 3)
        assertContentEquals(hello + hello, Codecs.ZSTD.decompress(checked + skippable + level19))
        assertContentEquals(hello, Codecs.ZSTD.decompress(skippable + checked))
        // Skippable frames alone decode to nothing, as libzstd treats them.
        assertEquals(0, Codecs.ZSTD.decompress(skippable).size)
    }

    @Test
    fun corruptionIsDetected() {
        // Every single-bit change in the checked frames fails; none decodes to other bytes.
        for (frame in listOf(checked, level19)) {
            for (position in 4 until frame.size) {
                for (bit in 0 until 8) {
                    val damaged = frame.copyOf().also { it[position] = (it[position].toInt() xor (1 shl bit)).toByte() }
                    val result = runCatching { Codecs.ZSTD.decompress(damaged) }
                    assertTrue(result.isFailure || result.getOrNull()!!.contentEquals(hello), "byte $position bit $bit")
                }
            }
        }
        val damaged = multiBlock.copyOf().also { it[multiBlock.size / 2] = (it[multiBlock.size / 2].toInt() xor 0x20).toByte() }
        assertFailsWith<ArchiveFormatException> { Codecs.ZSTD.decompress(damaged) }
        for (bad in listOf(ByteArray(0), checked.copyOf(10), byteArrayOf(1, 2, 3, 4, 5))) {
            assertFailsWith<ArchiveFormatException> { Codecs.ZSTD.decompress(bad) }
        }
    }

    @Test
    fun dictionaryFramesAreRefused() {
        // Frame header descriptor with a one-byte dictionary id of 7.
        val frame = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte(), 0x21, 7, 0, 1, 0, 0)
        val error = assertFailsWith<ArchiveUnsupportedException> { Codecs.ZSTD.decompress(frame) }
        assertTrue(error.message!!.contains("dictionary"), error.message)
    }

    @Test
    fun encoderRoundTrips() {
        val random = ByteArray(200_000).also { var x = 3L; for (i in it.indices) { x = x * 6364136223846793005L + 1442695040888963407L; it[i] = (x ushr 56).toByte() } }
        for (data in listOf(ByteArray(0), hello, ByteArray(1_000_000), random, generated(1_100_000), random + generated(300_000))) {
            assertContentEquals(data, Codecs.ZSTD.decompress(Codecs.ZSTD.compress(data)), "${data.size} bytes")
        }
        assertTrue(Codecs.ZSTD.compress(generated(1_100_000)).size < 70_000)
    }

    @Test
    fun limitsApply() {
        // Frames carry their content size, so an oversized one is refused before decoding.
        assertFailsWith<DecompressionBombException> { Codecs.ZSTD.decompress(multiBlock, ArchiveLimits { maxUncompressedBytes = 1000 }) }
        assertFailsWith<DecompressionBombException> { Codecs.ZSTD.decompress(streamed, ArchiveLimits { maxUncompressedBytes = 100 }) }
        assertFailsWith<DecompressionBombException> { Codecs.ZSTD.decompress(Codecs.ZSTD.compress(ByteArray(1_000_000)), ArchiveLimits { maxCompressionRatio = 100 }) }
    }
}
