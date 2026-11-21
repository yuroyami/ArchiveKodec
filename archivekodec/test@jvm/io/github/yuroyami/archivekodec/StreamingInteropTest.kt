package io.github.yuroyami.archivekodec

import io.github.yuroyami.archivekodec.codec.ByteSink
import io.github.yuroyami.archivekodec.codec.ByteSource
import io.github.yuroyami.archivekodec.codec.Codecs
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.InflaterInputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

/** Streamed gzip and zlib against java.util.zip in both directions. */
class StreamingInteropTest {
    private val data = Random(11).let { r -> ByteArray(2_500_000) { if (it % 3000 < 2000) (it / 7 % 50).toByte() else r.nextInt().toByte() } }

    @Test
    fun theJdkReadsOurSessionsAndWeStreamTheJdksOutput() {
        val gz = ByteArrayOutputStream()
        Codecs.GZIP.encoder(ByteSink { b, o, n -> gz.write(b, o, n) }).apply { write(data); finish() }
        assertContentEquals(data, GZIPInputStream(ByteArrayInputStream(gz.toByteArray())).readAllBytes())
        val zl = ByteArrayOutputStream()
        Codecs.ZLIB.encoder(ByteSink { b, o, n -> zl.write(b, o, n) }).apply {
            write(data, 0, 1_000_000); flush(); write(data, 1_000_000, data.size - 1_000_000); finish()
        }
        assertContentEquals(data, InflaterInputStream(ByteArrayInputStream(zl.toByteArray())).readAllBytes())

        val jdkGzip = ByteArrayOutputStream().also { GZIPOutputStream(it).use { out -> out.write(data) } }.toByteArray()
        val jdkZlib = ByteArrayOutputStream().also { DeflaterOutputStream(it).use { out -> out.write(data) } }.toByteArray()
        for ((codec, stream) in listOf(Codecs.GZIP to jdkGzip, Codecs.ZLIB to jdkZlib)) {
            val input = ByteArrayInputStream(stream)
            val out = ByteArrayOutputStream()
            codec.decompress(ByteSource { b, o, n -> input.read(b, o, minOf(n, 333)) }, ByteSink { b, o, n -> out.write(b, o, n) }, ArchiveLimits.DEFAULT)
            assertContentEquals(data, out.toByteArray())
        }
    }
}
