package io.github.yuroyami.archivekodec

import io.github.yuroyami.archivekodec.codec.deflate.Deflate
import io.github.yuroyami.archivekodec.wrapper.Gzip
import io.github.yuroyami.archivekodec.wrapper.Zlib
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JVM-only interop gate: our encoder output must be decodable by the JDK's native
 * zlib (`java.util.zip`), which also validates the Adler-32 (zlib) and CRC-32 + ISIZE
 * (gzip) trailers. The Inflater gate also requires a complete stream and consumes
 * the entire supplied input, so a decoded prefix cannot pass as a valid stream.
 */
class ZlibInteropTest {

    private fun sample(): ByteArray {
        val sb = StringBuilder()
        repeat(500) { sb.append("the quick brown fox jumps over the lazy dog ") }
        repeat(4000) { sb.append((it % 256).toChar()) } // widen alphabet → force dynamic block
        return sb.toString().encodeToByteArray()
    }

    private fun drain(inf: Inflater): ByteArray {
        val buf = ByteArray(8192)
        val bos = ByteArrayOutputStream()
        try {
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                assertTrue(n > 0 || inf.finished(), "Inflater stopped before completing the stream")
                bos.write(buf, 0, n)
            }
            assertEquals(0, inf.remaining, "Unexpected bytes after the stream")
            return bos.toByteArray()
        } finally {
            inf.end()
        }
    }

    @Test
    fun rawDeflateReadByJdk() {
        val data = sample()
        val inf = Inflater(true) // nowrap = raw DEFLATE
        inf.setInput(Deflate.encode(data))
        assertContentEquals(data, drain(inf))
    }

    @Test
    fun zlibReadByJdk() {
        val data = sample()
        val inf = Inflater(false) // expects zlib header + Adler-32 trailer (validated)
        inf.setInput(Zlib.compress(data))
        assertContentEquals(data, drain(inf))
    }

    @Test
    fun gzipReadByJdk() {
        val data = sample()
        val got = GZIPInputStream(ByteArrayInputStream(Gzip.compress(data))).readBytes()
        assertContentEquals(data, got) // GZIPInputStream validates CRC-32 + ISIZE
    }

    @Test
    fun rejectsMissingZlibTrailer() {
        val bytes = Zlib.compress(sample())
        val inf = Inflater(false).apply { setInput(bytes.copyOf(bytes.size - 4)) }
        assertFailsWith<AssertionError> { drain(inf) }
    }

    @Test
    fun rejectsTrailingZlibBytes() {
        val inf = Inflater(false).apply { setInput(Zlib.compress(sample()) + byteArrayOf(0)) }
        assertFailsWith<AssertionError> { drain(inf) }
    }

    @Test
    fun rejectsUnterminatedRawDeflate() {
        val bytes = Deflate.encode(sample())
        val notFinal = bytes.copyOf().also { it[0] = (it[0].toInt() and 0xFE).toByte() }
        for (input in listOf(notFinal, bytes.copyOf(bytes.size - 1))) {
            val inf = Inflater(true).apply { setInput(input) }
            assertFailsWith<AssertionError> { drain(inf) }
        }
    }
}
