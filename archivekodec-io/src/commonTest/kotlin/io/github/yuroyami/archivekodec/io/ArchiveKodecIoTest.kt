package io.github.yuroyami.archivekodec.io

import io.github.yuroyami.archivekodec.ArchiveIOException
import io.github.yuroyami.archivekodec.archive.readFully
import io.github.yuroyami.archivekodec.codec.Codecs
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlinx.io.IOException
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ArchiveKodecIoTest {

    @Test
    fun sourceFailuresRetainTheirCauseUnderTheArchiveContract() {
        val original = IOException("unavailable source")
        val source = object : RawSource {
            override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = throw original
            override fun close() {}
        }
        val adapters = listOf<(RawSource) -> Unit>(
            { it.readAllBytes() }, { it.toRandomAccessSource() },
            { Codecs.GZIP.compress(it) }, { Codecs.GZIP.decompress(it) },
        )
        for (adapter in adapters) {
            val error = assertFailsWith<ArchiveIOException> { adapter(source) }
            assertSame(original, error.cause)
        }
    }

    @Test
    fun drainsRawSource() {
        for (size in listOf(0, 1, 65_535, 65_536, 65_537, 131_089)) {
            val data = sample(size)
            val source = ShortSource(data)
            try {
                assertContentEquals(data, source.readAllBytes(), "size=$size")
                assertFalse(source.closed)
            } finally {
                source.close()
            }
        }
    }

    @Test
    fun sourceBecomesSeekableAcrossChunkBoundaries() {
        val data = sample(131_089)
        val source = ShortSource(data)
        try {
            val random = source.toRandomAccessSource()
            try {
                assertEquals(data.size.toLong(), random.size)
                for ((offset, length) in listOf(65_529 to 23, 131_070 to 19, 0 to 37, 65_536 to 65_553)) {
                    assertContentEquals(data.copyOfRange(offset, offset + length), random.readFully(offset.toLong(), length))
                }
                assertContentEquals(data, random.readFully(0, data.size))
            } finally {
                random.close()
            }
            assertFalse(source.closed)
        } finally {
            source.close()
        }
    }

    @Test
    fun codecCompressOverSource() {
        val data = sample(131_089)
        for (codec in codecs()) {
            val source = ShortSource(data)
            try {
                val encoded = codec.compress(source)
                assertContentEquals(codec.compress(data), encoded, codec.id.toString())
                assertContentEquals(data, codec.decompress(encoded), codec.id.toString())
                assertFalse(source.closed)
            } finally {
                source.close()
            }
        }
    }

    @Test
    fun codecDecompressOverSource() {
        val data = sample(131_089)
        for (codec in codecs()) {
            val encoded = codec.compress(data)
            assertTrue(encoded.size > 65_536, "${codec.id} must cross the input chunk boundary")
            val source = ShortSource(encoded)
            try {
                assertContentEquals(data, codec.decompress(source), codec.id.toString())
                assertFalse(source.closed)
            } finally {
                source.close()
            }
            val capped = ShortSource(encoded)
            try {
                assertFailsWith<DecompressionBombException> {
                    codec.decompress(capped, ArchiveLimits { maxUncompressedBytes = data.size.toLong() - 1 })
                }
                assertFalse(capped.closed)
            } finally {
                capped.close()
            }
        }
    }

    private fun codecs() = listOf(Codecs.STORE, Codecs.DEFLATE, Codecs.GZIP, Codecs.ZLIB, Codecs.LZ4, Codecs.SNAPPY)

    private fun sample(size: Int): ByteArray {
        var state = 0x12345678
        return ByteArray(size) {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            state.toByte()
        }
    }

    private class ShortSource(data: ByteArray) : RawSource {
        private val buffer = Buffer().apply { write(data) }
        private val chunks = longArrayOf(1, 17, 8191, 65_536)
        private var reads = 0
        var closed = false
            private set

        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long =
            buffer.readAtMostTo(sink, minOf(byteCount, chunks[reads++ % chunks.size]))

        override fun close() {
            closed = true
            buffer.close()
        }
    }
}

class StreamingAdapterTest {
    @kotlin.test.Test
    fun decompressesIntoARawSinkInChunks() {
        val data = kotlin.random.Random(11).nextBytes(3_000_000)
        for (codec in listOf(io.github.yuroyami.archivekodec.codec.Codecs.GZIP, io.github.yuroyami.archivekodec.codec.Codecs.LZ4)) {
            val compressed = kotlinx.io.Buffer()
            val size = codec.compress(kotlinx.io.Buffer().apply { write(data) }, compressed)
            kotlin.test.assertEquals(compressed.size, size)
            var writes = 0
            val target = kotlinx.io.Buffer()
            val counting = object : kotlinx.io.RawSink {
                override fun write(source: kotlinx.io.Buffer, byteCount: Long) { writes++; target.write(source, byteCount) }
                override fun flush() {}
                override fun close() {}
            }
            kotlin.test.assertEquals(data.size.toLong(), codec.decompress(compressed, counting))
            kotlin.test.assertContentEquals(data, target.readByteArray())
            if (codec === io.github.yuroyami.archivekodec.codec.Codecs.GZIP) kotlin.test.assertTrue(writes >= 2, "gzip wrote $writes times")
        }
    }

    @kotlin.test.Test
    fun sinkFailuresKeepTheirCause() {
        val original = IOException("disk full")
        val failing = object : RawSource, kotlinx.io.RawSink {
            override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = -1
            override fun write(source: Buffer, byteCount: Long) = throw original
            override fun flush() {}
            override fun close() {}
        }
        val gz = Buffer().apply { write(Codecs.GZIP.compress("payload".encodeToByteArray())) }
        assertSame(original, assertFailsWith<ArchiveIOException> { Codecs.GZIP.decompress(gz, failing as kotlinx.io.RawSink) }.cause)
    }
}
