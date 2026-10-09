package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.archive.zip.ZipMethod
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.util.readU32Le
import io.github.yuroyami.archivekodec.wrapper.Gzip
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

/** Deflate64, ZIP method 9 (#127). */
@OptIn(ExperimentalEncodingApi::class)
class Deflate64Test {
    /**
     * A hand-built Deflate64 stream, checked with the independent zipfile-deflate64 decoder:
     * a stored 50,000-byte block, then a fixed block whose matches use what DEFLATE lacks:
     * length code 285 with 16 extra bits (lengths 1000 and 65538) and distance codes 30 and
     * 31 (distances 33000 and 50001), then an ordinary length-3, distance-1 match.
     */
    private val stream = Gzip.decompress(Base64.decode(
        "H4sIAAAAAAAC/+3dZ1TOYRjH8ey9kySyCUn2yN5JskeSJHvPJCSZISTZe4+EZFOSJHtvSSrZI0mSc5/73K+ecx4vdDr1/H7ve/U5" +
        "Pfc5/+tc5/rq2YYettQzaWHvtuH045+lGvae4nP09ucitbuO8dofEZ+navuh87aHRKWVaz5w5vpTj5IMGvSavOrIrU+FzaxHL9l3" +
        "JS53lXZOHtuCX/4pa2nnuu7kwx8l6/ectDLg5sdCtbqMWrw3PDZX5bZD5m698CLVuNmAGWtPPEjUr9dj4orDNz4UrGk1ctGey29y" +
        "Vmrj6L7l/PPfZZr2d/ELuv+9RN3uE7z9r78vUKPziIW7w2JyVGw9eM7mc89SjJr0m77m+L1vxS1sxy8/dO1dftNOwxfsuvQ6e4VW" +
        "DrM3nX36q3TjvtN8A+9+LVan27hlByMT8lXvOGz+ztDobOVbDpq18cyTZMNGfaauPnbnS1Fzm7FLD1x9m7dah3/+gbPnjouvdBIo" +
        "nQSzIlCG/otlLqBM+BvMKKAs+0j9P5COv+LagHT8FdcGpOOvuDYgHX/FtQHp+CuuDUjHX3FtQDr+imsDgvlY0QSC+VjRBIL5WNEE" +
        "gvlY0QSC+VjRBIL5WNEEgvlY0QQCnMkpIMCZnAICnMkpIMCZnAICnMkpIMCZnAICnMkpIMCZnAICnMkpIMCZnAICnMkpIMCZnAIC" +
        "nMkpIMCZnAICnMkpIMCZnAICnMkpIMCZnAICnMkpIMCZnAICnMkpIMCZnAICnMkpIMCZnAICnMkpIMCZnAICnMkpIMh1cAkEuQ4u" +
        "gSDXwSUQ5Dq4BIJcB5dAkOvgEghyHVwCQa6DSyDIdXAJBLkOLoEg18ElEOQ6uASCXAeXQJDr4BIIch1cAkGug0sgyHVwCQS5Di6B" +
        "INfBJRDkOrgEglwHl0CQ6+ASCHIdXAJBroNLIMh1cAkEuQ4ugSDXwSUQ6CUiAQR6iUgAgV4iEkCgl4gEEOglIgEEeolIAIFeIhJA" +
        "oJeIBBDoJSIBBHqJSACBXiISQKCXiAQQ6CUiAQR6iUgAgV4iEkCgl4gEEOglIgEEeolIAIFeIhJAoJeIBBDoJSIBBHqJSACBXiIS" +
        "QKCXiAQQ6CUiAQR6iUgAgV4iEkCwEQxnT9gIRmQCbATDwhY2guEXBBvBSDWGjWA4ecBGMCLiYSMY5jawEQzfQNgIRooRbATD0R02" +
        "ghEeCxvBMLOGjWD4HIWNYCQbwkYwHGbDRjDCYmAjGDWtYCMYKwNgIxhJBrARDHs32AhGaDRsBMO0E2wEw9sfNoKRqA8bwbBzhY1g" +
        "hESxv87+Ovvr7K+zv87+Ovvr7K+zv87+Ovvr7K+zv87+Ovvr7K+zv87+Ovvr7K+zv87+Ovvr7K+zv87+Ovvr7K+zv87+Ovvr7K+z" +
        "v87+Ovvr7K+zv87+Ovvr7K+zv87+Ovvr7K+zv87+Ovvr7K+zv87+Ovvr7K+zv87+Ovvr7K+zv87+Ovvr7K+zv87+Ovvr7K+zv87+" +
        "Ovvr7K+zv54+/XUvQzMTl7jg+LS0hUbuenp/ATgCqQ9kwwAA"))

    private val expected: ByteArray = run {
        val out = ArrayList<Byte>()
        for (i in 0 until 50_000) out.add(((i * 31 + i / 251) and 0xFF).toByte())
        out.add('X'.code.toByte())
        fun copy(distance: Int, length: Int) = repeat(length) { out.add(out[out.size - distance]) }
        copy(33_000, 1_000); copy(50_001, 65_538); copy(1, 3)
        out.toByteArray()
    }

    @Test
    fun decodesTheDeflate64Extensions() {
        val output = Codecs.DEFLATE64.decompress(stream)
        assertEquals(116_542, output.size)
        assertEquals(0xDC84A295L, Crc32.of(output))
        assertContentEquals(expected, output)
        assertSame(Codecs.DEFLATE64, CodecRegistry.DEFAULT.byZipMethod(9))
        assertSame(Codecs.DEFLATE64, Codecs.byId(CodecId.DEFLATE64))
    }

    @Test
    fun plainDeflateCannotReproduceTheStream() {
        // Code 285 means length 258 with no extra bits in DEFLATE, so passing the stream through
        // the plain decoder misreads it: it fails or yields different bytes, never the expected ones.
        val plain = runCatching { Codecs.DEFLATE.decompress(stream) }.getOrNull()
        assertFalse(plain != null && plain.contentEquals(expected))
    }

    @Test
    fun compressedOutputRoundTripsAndAvoidsLengthCode285() {
        val data = ByteArray(200_000) { (it % 7).toByte() }
        val encoded = Codecs.DEFLATE64.compress(data)
        assertContentEquals(data, Codecs.DEFLATE64.decompress(encoded))
        // Without length 258 matches, plain DEFLATE decodes the same stream identically.
        assertContentEquals(data, Codecs.DEFLATE.decompress(encoded))
    }

    @Test
    fun ordinaryDeflateWithoutLength258DecodesUnchanged() {
        // DEFLATE streams decode identically unless they use length code 285, whose meaning differs.
        val data = "Deflate64 keeps every ordinary code ".repeat(3).encodeToByteArray()
        val noLongMatches = Codecs.DEFLATE.compress(data)
        assertContentEquals(data, Codecs.DEFLATE64.decompress(noLongMatches))
    }

    @Test
    fun outputLimitsApply() {
        assertFailsWith<DecompressionBombException> {
            Codecs.DEFLATE64.decompress(stream, ArchiveLimits { maxUncompressedBytes = 100_000 })
        }
    }


    @Test
    fun zipMethodNineEntriesRead() {
        // Store the stream, then relabel it as method 9 with the decoded size and CRC.
        val zip = ZipWriter.write(listOf(ZipWriter.FileSpec("data.bin", stream, ZipMethod.STORED)))
        val central = readU32Le(zip, zip.size - 6).toInt()
        fun put(at: Int, value: Long, width: Int) { for (i in 0 until width) zip[at + i] = (value shr (8 * i)).toByte() }
        for ((header, method, crc, size) in listOf(listOf(0, 8, 14, 22), listOf(central, 10, 16, 24))) {
            put(header + method, 9, 2)
            put(header + crc, Crc32.of(expected), 4)
            put(header + size, expected.size.toLong(), 4)
        }
        ZipReader(ByteArrayRandomAccessSource(zip)).use { reader ->
            val entry = reader.entries().single()
            assertEquals(9, entry.zipMethod)
            assertEquals(CodecId.DEFLATE64, entry.codec)
            assertContentEquals(expected, reader.read(entry))
        }
    }
}
