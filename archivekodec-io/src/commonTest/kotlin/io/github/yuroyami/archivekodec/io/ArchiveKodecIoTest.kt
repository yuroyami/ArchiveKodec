package io.github.yuroyami.archivekodec.io

import io.github.yuroyami.archivekodec.ArchiveIOException
import io.github.yuroyami.archivekodec.codec.Codecs
import kotlinx.io.IOException
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ArchiveKodecIoTest {

    @Test
    fun sourceFailuresRetainTheirCauseUnderTheArchiveContract() {
        val original = IOException("unavailable source")
        val source = object : RawSource {
            override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = throw original
            override fun close() {}
        }
        val error = assertFailsWith<ArchiveIOException> { source.readAllBytes() }
        assertSame(original, error.cause)
    }

    @Test
    fun drainsRawSource() {
        val data = "stream me through kotlinx-io".encodeToByteArray()
        val src: RawSource = Buffer().apply { write(data) }
        assertContentEquals(data, src.readAllBytes())
    }

    @Test
    fun codecDecompressOverSource() {
        val data = ("repeat me ").repeat(50).encodeToByteArray()
        val gz = Codecs.GZIP.compress(data)
        val src: RawSource = Buffer().apply { write(gz) }
        assertContentEquals(data, Codecs.GZIP.decompress(src))
    }
}
