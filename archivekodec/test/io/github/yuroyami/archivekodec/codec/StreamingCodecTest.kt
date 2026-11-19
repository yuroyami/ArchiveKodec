package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.codec.deflate.DeflateOptions
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder
import io.github.yuroyami.archivekodec.wrapper.Gzip
import io.github.yuroyami.archivekodec.wrapper.GzipHeader
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Streaming DEFLATE, zlib and gzip (#154, Stage 2): sessions out, sources in, any chunking. */
class StreamingCodecTest {
    private val codecs = listOf(Codecs.DEFLATE, Codecs.ZLIB, Codecs.GZIP, Codecs.BZIP2, Codecs.XZ)

    private class Collector : ByteSink {
        val out = ByteArrayBuilder(1024)
        override fun write(bytes: ByteArray, offset: Int, length: Int) = out.append(bytes, offset, length)
    }

    /** A source that hands out at most [step] bytes per read. */
    private fun trickle(bytes: ByteArray, step: Int): ByteSource {
        var position = 0
        return ByteSource { into, offset, length ->
            if (position == bytes.size) -1 else {
                val n = minOf(length, step, bytes.size - position)
                bytes.copyInto(into, offset, position, position + n)
                position += n
                n
            }
        }
    }

    private fun data(size: Int, seed: Int = 1): ByteArray {
        var x = seed
        fun next(): Int { x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5); return x }
        val words = listOf("stream ", "chunk ", "window ", "session ", "flush ")
        val out = ByteArrayBuilder(size)
        while (out.size < size) {
            val w = words[(next() ushr 1) % words.size].encodeToByteArray()
            out.append(w, 0, minOf(w.size, size - out.size))
        }
        return out.takeByteArray()
    }

    private fun encode(codec: Codec, input: ByteArray, piece: Int, options: CodecOptions? = null): ByteArray {
        val sink = Collector()
        val session = if (options == null) codec.encoder(sink) else codec.encoder(sink, options)
        var at = 0
        while (at < input.size) {
            val n = minOf(piece, input.size - at)
            session.write(input, at, n)
            at += n
        }
        session.finish()
        session.finish()
        return sink.out.takeByteArray()
    }

    private fun decode(codec: Codec, stream: ByteArray, step: Int, limits: ArchiveLimits = ArchiveLimits.DEFAULT): ByteArray {
        val sink = Collector()
        val count = codec.decompress(trickle(stream, step), sink, limits)
        val out = sink.out.takeByteArray()
        assertEquals(out.size.toLong(), count)
        return out
    }

    @Test
    fun sessionsAndSourcesRoundTripAtEveryChunking() {
        // Across the 1 MiB chunk edge, so matches must reach into the previous chunk's window.
        val big = data(3_000_000)
        for (codec in codecs) {
            for (input in listOf(ByteArray(0), "x".encodeToByteArray(), data(70_000), big)) {
                for (piece in listOf(1 shl 20, 100_003, 4096)) {
                    if (input.size < 100 && piece != 4096) continue
                    val stream = encode(codec, input, piece)
                    // The whole-array decoder reads the streamed encoding, and the streamed decoder
                    // reads it a few bytes at a time or a large window at a time.
                    assertContentEquals(input, codec.decompress(stream, ArchiveLimits.UNBOUNDED), "${codec.id} array")
                    for (step in listOf(7, 65_536)) {
                        if (input.size > 100_000 && step == 7 && piece != 4096) continue
                        assertContentEquals(input, decode(codec, stream, step, ArchiveLimits.UNBOUNDED), "${codec.id} step $step")
                    }
                }
            }
            // A one-byte trickle through a small stream exercises every refill path.
            val small = data(5000, 9)
            assertContentEquals(small, decode(codec, codec.compress(small), 1))
        }
    }

    @Test
    fun streamingCostsLittleAgainstAWholeArrayEncode() {
        // The second half repeats the first, past a chunk edge: the dictionary keeps it a match.
        val half = data(1_500_000, 4)
        val input = half + half
        val whole = Codecs.DEFLATE.compress(input).size
        val streamed = encode(Codecs.DEFLATE, input, 65_536).size
        assertTrue(streamed < whole * 1.05 + 1024, "streamed $streamed vs whole $whole")
        val level9 = encode(Codecs.GZIP, input, 65_536, DeflateOptions { level = 9 })
        assertContentEquals(input, decode(Codecs.GZIP, level9, 4096, ArchiveLimits.UNBOUNDED))
    }

    @Test
    fun gzipStreamsReadEveryMemberHeaderAndPadding() {
        val a = data(10_000, 2)
        val b = data(20_000, 3)
        val named = Gzip.compress(b, GzipHeader { name = "b.txt"; comment = "second member"; hasHeaderCrc = true; extra = ByteArray(300) { it.toByte() } })
        val stream = Gzip.compress(a) + ByteArray(5) + named + ByteArray(3)
        for (step in listOf(1, 13, 1 shl 16)) assertContentEquals(a + b, decode(Codecs.GZIP, stream, step))
    }

    @Test
    fun damageTruncationAndLimitsFail() {
        val input = data(200_000, 5)
        for (codec in codecs) {
            val stream = codec.compress(input)
            assertFailsWith<io.github.yuroyami.archivekodec.ArchiveException>("${codec.id} cut") { decode(codec, stream.copyOf(stream.size - 3), 1000) }
            assertFailsWith<DecompressionBombException>("${codec.id} size") { decode(codec, stream, 1000, ArchiveLimits { maxUncompressedBytes = 100_000 }) }
        }
        assertFailsWith<ArchiveFormatException> { decode(Codecs.ZLIB, Codecs.ZLIB.compress(input) + byteArrayOf(1), 1000) }
        assertFailsWith<ArchiveFormatException> { decode(Codecs.DEFLATE, Codecs.DEFLATE.compress(input) + byteArrayOf(1), 1000) }
        assertFailsWith<ArchiveFormatException> { decode(Codecs.GZIP, Codecs.GZIP.compress(input) + byteArrayOf(9, 9), 1000) }
        val crc = Codecs.GZIP.compress(input).also { it[it.size - 6] = (it[it.size - 6].toInt() xor 1).toByte() }
        assertFailsWith<ArchiveFormatException> { decode(Codecs.GZIP, crc, 1000) }
        // The ratio counts the compressed bytes read so far: zeros expand far past 50:1.
        val zeros = Codecs.GZIP.compress(ByteArray(2_000_000))
        assertFailsWith<DecompressionBombException> { decode(Codecs.GZIP, zeros, 512, ArchiveLimits { maxCompressionRatio = 50 }) }
    }

    @Test
    fun otherCodecsUseTheBufferingDefaults() {
        val input = data(50_000, 6)
        for (codec in listOf(Codecs.ZSTD, Codecs.LZ4, Codecs.LZIP)) {
            val stream = encode(codec, input, 7000)
            assertContentEquals(input, codec.decompress(stream))
            assertContentEquals(input, decode(codec, stream, 999))
        }
        assertFailsWith<IllegalArgumentException> { Codecs.XZ.encoder(Collector(), DeflateOptions.DEFAULT) }
        assertFailsWith<IllegalArgumentException> { Codecs.BZIP2.encoder(Collector(), DeflateOptions.DEFAULT) }
    }
}
