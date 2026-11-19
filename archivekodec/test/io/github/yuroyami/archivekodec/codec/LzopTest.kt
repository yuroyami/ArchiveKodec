package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.archive.ArchiveFormat
import io.github.yuroyami.archivekodec.archive.tar.TarWriter
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class LzopTest {
    private val text = "hello hello hello hello lzop\n".encodeToByteArray()

    // lzop 1.04: `printf ... | lzop`, a stored block with Adler-32 (the data does not shrink).
    private val stored = Base64.decode(
        "iUxaTwANChoKEEAgoAlAAQUDAAANAAAAAGr++UMAAAAAADNnBBQAAAAdAAAAHaMkCqBoZWxsbyBoZWxsbyBoZWxsbyBoZWxsbyBsem9wCgAAAAA=",
    )

    // lzop 1.04: `lzop --crc32 -9`, an LZO1X-999 block with a CRC-32 header and CRC-32 checks.
    private val compressed = Base64.decode(
        "iUxaTwANChoKEEAgoBABAwkDABEMAAAAAGr++UMAAAAAAPg1P6oAAAAdAAAAExxARSwXaGVsbG8gMBQAAmx6b3AKEQAAAAAAAA==",
    )

    @Test
    fun readsTheReferenceToolsOutput() {
        assertContentEquals(text, Codecs.LZOP.decompress(stored))
        assertContentEquals(text, Codecs.LZOP.decompress(compressed))
        assertEquals(ArchiveFormat.LZOP, ArchiveKodec.sniff(compressed))
        assertContentEquals(text, ArchiveKodec.decompress(compressed))
    }

    @Test
    fun roundTripsAndCompresses() {
        var x = 7
        fun next(): Int { x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5); return x }
        val words = listOf("lzo ", "block ", "match ", "literal ", "window ")
        val prose = buildString { repeat(60_000) { append(words[(next() ushr 1) % words.size]) } }.encodeToByteArray()
        val runs = ByteArray(100_000) { (it / 7000).toByte() }
        val noise = ByteArray(3000) { next().toByte() }
        for (data in listOf(ByteArray(0), "a".encodeToByteArray(), text, prose, runs, noise, prose + noise + runs)) {
            assertContentEquals(data, Codecs.LZOP.decompress(Codecs.LZOP.compress(data), ArchiveLimits.UNBOUNDED))
        }
        assertTrue(Codecs.LZOP.compress(prose).size * 3 < prose.size)
        assertTrue(Codecs.LZOP.compress(runs).size < 2000)
    }

    @Test
    fun damageAndLimitsFail() {
        // The block's CRC-32 of the data, then the header checksum.
        val badBlock = compressed.copyOf().also { it[it.size - 9] = (it[it.size - 9].toInt() xor 1).toByte() }
        assertFailsWith<ArchiveFormatException> { Codecs.LZOP.decompress(badBlock) }
        val badHeader = compressed.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertFailsWith<ArchiveFormatException> { Codecs.LZOP.decompress(badHeader) }
        assertFailsWith<ArchiveFormatException> { Codecs.LZOP.decompress(compressed.copyOf(compressed.size - 2)) }
        assertFailsWith<ArchiveFormatException> { Codecs.LZOP.decompress(compressed + byteArrayOf(0)) }
        val zeros = Codecs.LZOP.compress(ByteArray(600_000))
        assertFailsWith<DecompressionBombException> { Codecs.LZOP.decompress(zeros, ArchiveLimits { maxUncompressedBytes = 300_000 }) }
    }

    @Test
    fun aTarLzoOpensInOneCall() {
        val tar = TarWriter.write(listOf(TarWriter.FileSpec("a.txt", "inside".encodeToByteArray())))
        ArchiveKodec.open(Codecs.LZOP.compress(tar)).use { reader ->
            assertEquals(ArchiveFormat.TAR, reader.format)
            assertContentEquals("inside".encodeToByteArray(), reader.read(reader.entries().single()))
        }
    }
}
