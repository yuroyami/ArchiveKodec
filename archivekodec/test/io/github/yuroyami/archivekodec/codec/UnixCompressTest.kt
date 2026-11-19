package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.ArchiveFormat
import io.github.yuroyami.archivekodec.archive.tar.TarWriter
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.wrapper.Gzip
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalEncodingApi::class)
class UnixCompressTest {
    // ncompress 5.0: printf 'hello hello hello hello\n' | compress
    private val hello = Base64.decode("H52QaMqwYfMGRMCBBQ8SNCiQoAI=")

    // ncompress 5.0 of 5000 random lowercase letters (CRC-32 8795eeb9): its codes cross from
    // 9 to 10 and 11 bits, each change padding the rest of an eight-code group. Gzipped here.
    private val letters = Gzip.decompress(Base64.decode(
        "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",
    ), ArchiveLimits.UNBOUNDED)

    @Test
    fun readsTheReferenceToolsOutput() {
        assertContentEquals("hello hello hello hello\n".encodeToByteArray(), Codecs.COMPRESS.decompress(hello))
        val text = Codecs.COMPRESS.decompress(letters)
        assertEquals(5000, text.size)
        assertEquals(0x8795eeb9L, Crc32.of(text))
        assertEquals(ArchiveFormat.COMPRESS, ArchiveKodec.sniff(hello))
        assertContentEquals(text, ArchiveKodec.decompress(letters))
    }

    @Test
    fun roundTripsThroughEveryWidthAndATableClear() {
        var x = 99
        fun next(): Int { x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5); return x }
        // Random bytes fill the 16-bit table; the runs after them then compress better, so the
        // encoder clears the table once the ratio stops improving.
        val data = ByteArray(400_000) { if (it < 250_000) next().toByte() else (it / 5000).toByte() }
        for (input in listOf(ByteArray(0), "a".encodeToByteArray(), "abababababab".encodeToByteArray(), data)) {
            assertContentEquals(input, Codecs.COMPRESS.decompress(Codecs.COMPRESS.compress(input), ArchiveLimits.UNBOUNDED))
        }
    }

    @Test
    fun damageWidthNineAndLimitsFail() {
        assertFailsWith<ArchiveFormatException> { Codecs.COMPRESS.decompress(byteArrayOf(0x1f, 0x9d.toByte())) }
        assertFailsWith<ArchiveFormatException> { Codecs.COMPRESS.decompress(byteArrayOf(0x1f, 0x9d.toByte(), 0x91.toByte(), 0xff.toByte(), 0xff.toByte())) }
        assertFailsWith<ArchiveUnsupportedException> { Codecs.COMPRESS.decompress(byteArrayOf(0x1f, 0x9d.toByte(), 0x89.toByte(), 0x61, 0)) }
        val zeros = Codecs.COMPRESS.compress(ByteArray(200_000))
        assertFailsWith<DecompressionBombException> { Codecs.COMPRESS.decompress(zeros, ArchiveLimits { maxUncompressedBytes = 100_000 }) }
    }

    @Test
    fun aTarZOpensInOneCall() {
        val tar = TarWriter.write(listOf(TarWriter.FileSpec("a.txt", "inside".encodeToByteArray())))
        ArchiveKodec.open(Codecs.COMPRESS.compress(tar)).use { reader ->
            assertEquals(ArchiveFormat.TAR, reader.format)
            assertContentEquals("inside".encodeToByteArray(), reader.read(reader.entries().single()))
        }
    }
}
