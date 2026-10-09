package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveIOException
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** #24: decompressing to a [ByteSink] gives the same bytes as the buffered call, in bounded chunks. */
class StreamingDecompressTest {
    private class Collector : ByteSink {
        val chunks = ArrayList<Int>()
        private val parts = ArrayList<ByteArray>()
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            chunks += length
            parts += bytes.copyOfRange(offset, offset + length)
        }
        fun bytes(): ByteArray {
            val out = ByteArray(parts.sumOf { it.size })
            var at = 0
            for (part in parts) { part.copyInto(out, at); at += part.size }
            return out
        }
    }

    private val inputs: List<ByteArray> by lazy {
        val random = Random(24)
        val words = listOf("stream", "window", "sink", "chunk", "deflate", "\n").map { it.encodeToByteArray() }
        val text = ByteArray(3_000_000).also { var i = 0; while (i < it.size) { val w = words[random.nextInt(words.size)]; val n = minOf(w.size, it.size - i); w.copyInto(it, i, 0, n); i += n } }
        // A long-range repeat: matches reach back across the 1 MiB flushes.
        val repeat = Random(7).nextBytes(40_000).let { block -> ByteArray(2_500_000) { block[it % block.size] } }
        listOf(ByteArray(0), "small".encodeToByteArray(), text, repeat, Random(3).nextBytes(1_500_000), ByteArray(5_000_000))
    }

    @Test
    fun streamedOutputMatchesBufferedOutput() {
        for (codec in listOf(Codecs.DEFLATE, Codecs.ZLIB, Codecs.GZIP, Codecs.LZ4, Codecs.ZSTD)) {
            for (input in inputs) {
                val compressed = codec.compress(input)
                val sink = Collector()
                // zstd squeezes 5 MB of zeros past the default 10,000:1 ratio limit.
                val written = codec.decompress(compressed, sink, ArchiveLimits { maxCompressionRatio = Int.MAX_VALUE })
                assertEquals(input.size.toLong(), written, "${codec.id} ${input.size}")
                assertContentEquals(input, sink.bytes(), "${codec.id} ${input.size}")
                if (codec.id in setOf(CodecId.DEFLATE, CodecId.ZLIB, CodecId.GZIP)) {
                    // The DEFLATE family streams: no chunk is much over 1 MiB, and large output takes several.
                    assertTrue(sink.chunks.all { it <= (1 shl 20) + 65536 }, "${codec.id} chunks ${sink.chunks}")
                    if (input.size > 2_000_000) assertTrue(sink.chunks.size >= 2, "${codec.id} ${input.size} in ${sink.chunks.size} writes")
                }
            }
        }
    }

    @Test
    fun gzipMembersAndPresetFreeZlibStream() {
        val a = Random(1).nextBytes(1_200_000)
        val b = ByteArray(1_300_000) { (it % 7).toByte() }
        val members = Codecs.GZIP.compress(a) + Codecs.GZIP.compress(b)
        val sink = Collector()
        assertEquals((a.size + b.size).toLong(), Codecs.GZIP.decompress(members, sink, ArchiveLimits.DEFAULT))
        assertContentEquals(a + b, sink.bytes())
        // Gzip decoding also accepts one zlib stream.
        val zlibSink = Collector()
        Codecs.GZIP.decompress(Codecs.ZLIB.compress(b), zlibSink, ArchiveLimits.DEFAULT)
        assertContentEquals(b, zlibSink.bytes())
    }

    @Test
    fun limitsStopTheSinkAtTheLimit() {
        val bomb = Codecs.GZIP.compress(ByteArray(8_000_000))
        val limits = ArchiveLimits { maxUncompressedBytes = 3_000_000 }
        val sink = Collector()
        assertFailsWith<DecompressionBombException> { Codecs.GZIP.decompress(bomb, sink, limits) }
        assertTrue(sink.chunks.sum() <= 3_000_000, "the sink received ${sink.chunks.sum()} bytes")
        // The ratio limit applies to the streamed total too.
        assertFailsWith<DecompressionBombException> {
            Codecs.DEFLATE.decompress(Codecs.DEFLATE.compress(ByteArray(4_000_000)), Collector(), ArchiveLimits { maxCompressionRatio = 50 })
        }
    }

    @Test
    fun integrityFailuresAndSinkFailures() {
        val data = Random(5).nextBytes(2_500_000)
        val gzip = Codecs.GZIP.compress(data)
        gzip[gzip.size - 6] = (gzip[gzip.size - 6] + 1).toByte() // CRC-32
        assertFailsWith<ArchiveFormatException> { Codecs.GZIP.decompress(gzip, Collector(), ArchiveLimits.DEFAULT) }
        val zlib = Codecs.ZLIB.compress(data)
        zlib[zlib.size - 1] = (zlib[zlib.size - 1] + 1).toByte() // Adler-32
        assertFailsWith<ArchiveFormatException> { Codecs.ZLIB.decompress(zlib, Collector(), ArchiveLimits.DEFAULT) }
        val failure = ArchiveIOException("disk full", IllegalStateException())
        val error = assertFailsWith<ArchiveIOException> {
            Codecs.DEFLATE.decompress(Codecs.DEFLATE.compress(data), ByteSink { _, _, _ -> throw failure }, ArchiveLimits.DEFAULT)
        }
        assertSame(failure, error)
    }
}
