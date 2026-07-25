package io.github.yuroyami.kitearchive

import io.github.yuroyami.kitearchive.codec.deflate.Deflate
import io.github.yuroyami.kitearchive.wrapper.Gzip
import io.github.yuroyami.kitearchive.wrapper.Zlib
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * JVM-only interop gate: our encoder output must be decodable by the JDK's native
 * zlib (`java.util.zip`), which also validates the Adler-32 (zlib) and CRC-32 + ISIZE
 * (gzip) trailers — checks our own lenient [Deflate]/Inflate round-trips do not make.
 * This is what caught the signed-Int Adler-32 overflow.
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
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            bos.write(buf, 0, n)
        }
        inf.end()
        return bos.toByteArray()
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
}
